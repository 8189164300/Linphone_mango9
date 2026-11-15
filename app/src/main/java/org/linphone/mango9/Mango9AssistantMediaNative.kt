// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.*
import okio.BufferedSink
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever

/** Supervisor transport only. Neither credentials nor media bytes cross the JS bridge. */
internal class Mango9AssistantMediaNative(private val context: Context) {
  private val queue = Executors.newFixedThreadPool(2)
  private val root by lazy {
      File(context.filesDir, "supervisor-media").apply {
          mkdirs(); Mango9AssistantMediaFile.removeInterruptedWork(this, "prepare-")
      }
  }
  private val epochs = ConcurrentHashMap<String, AtomicInteger>()
  private val calls = ConcurrentHashMap<Call, String>()
  private val downloads = ConcurrentHashMap<Call, String>()
  private val downloadEpochs = ConcurrentHashMap<String, AtomicInteger>()
  private val exports by lazy {
      File(context.cacheDir, "supervisor-media-exports").apply{
          mkdirs(); Mango9AssistantMediaFile.removeInterruptedWork(this, "download-")
      }
  }
  private val previews by lazy {
      File(context.cacheDir, "supervisor-media-previews").apply{
          mkdirs(); Mango9AssistantMediaFile.removeInterruptedWork(this, "preview-")
      }
  }
  private val wire = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).callTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
  private val types = mapOf(
      "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    "heic" to "image/heic", "heif" to "image/heif", "mp4" to "video/mp4", "mov" to "video/quicktime",
    "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv", "wav" to "audio/wav", "mp3" to "audio/mpeg"
  )

  private fun snapshot(uri: String, sha: String? = null): File {
    val u = URI(uri); require(u.scheme == "file" && u.authority == null)
    val f = File(u).canonicalFile
    require(f.parentFile == root.canonicalFile && f.isFile && f.length() in 1..500_000_000L)
    if (sha != null) require(Regex("[a-f0-9]{64}").matches(sha) && f.name.startsWith(sha + "-"))
    return f
  }

  private fun fail(p: Mango9MediaCallback, e: Exception) = p.reject("supervisor_media", "File transfer interrupted. Retry to resume, or remove the file.")

  fun downloadLargeMedia(raw: String, identifier: String, mime: String, size: Double, promise: Mango9MediaCallback) {
    val epoch = downloadEpochs.getOrPut(identifier){AtomicInteger()}; val version = epoch.get()
    queue.execute{
    var call: Call? = null; var temp: File? = null
    try {
      check(epoch.get() == version)
      require(Mango9SupervisorPresentation.uuid(identifier) && size == size.toLong().toDouble() && size.toLong() in 1..500_000_000L)
      val ext = types.entries.firstOrNull{it.value == mime}?.key ?: error("Unsupported type")
      val url = URI(raw)
      require(
          url.scheme == "https" && url.rawUserInfo == null && url.port == -1 && url.fragment == null && url.host in setOf("s3.us-central-1.wasabisys.com", "newdev-chatmedia.s3.us-central-1.wasabisys.com") &&
        Regex("^(/newdev-chatmedia)?/ephonix-supervisor/v1/(2|us)\\.mango9\\.com/[1-9][0-9]*/[1-9][0-9]*/" + identifier + "$").matches(url.path)
      )
      val directory = exports; val target = File(directory, "$identifier.$ext")
      directory.listFiles()?.filter{
          Mango9SupervisorPresentation.uuid(it.nameWithoutExtension)
      }?.sortedByDescending{it.lastModified()}?.forEachIndexed{index, file ->
        if (file != target && (index >= 2 || file.lastModified() < System.currentTimeMillis() - 86400000L))file.delete()
      }
      if (target.isFile && target.length() == size.toLong()){
          promise.resolve(android.net.Uri.fromFile(target).toString()); return@execute
      }
      Mango9AssistantMediaFile.requireSpace(size.toLong(), directory.usableSpace)
      temp = File.createTempFile("download-", ".tmp", directory)
      call = wire.newBuilder().callTimeout(600, java.util.concurrent.TimeUnit.SECONDS).build().newCall(Request.Builder().url(url.toASCIIString()).build())
      downloads[call] = identifier
      if (epoch.get() != version)call.cancel()
      call.execute().use{response ->
        check(response.code == 200); val body = response.body ?: error("No file")
        require(body.contentLength() <= size.toLong())
        var count = 0L
        body.byteStream().use{input ->
            temp!!.outputStream().use{output ->
                val buffer = ByteArray(65536)
          while (true){
              val read = input.read(buffer); if (read < 0)break; count += read; require(count <= size.toLong()); output.write(buffer, 0, read)
          }
        }
        }
        require(count == size.toLong())
      }
      check(epoch.get() == version)
      check(temp.renameTo(target)); temp = null
      promise.resolve(android.net.Uri.fromFile(target).toString())
    }catch (_: Exception){
        promise.reject("media_download", "This attachment could not be downloaded. Check your connection and free space, then retry.")
    } finally {temp?.delete(); if (call != null)downloads.remove(call)}
  }
  }

  fun cancelLargeMediaDownload(identifier: String) {
      downloadEpochs.getOrPut(identifier){
          AtomicInteger()
      }.incrementAndGet(); downloads.forEach{(call, id) -> if (id == identifier)call.cancel()}
  }

  fun previewLargeMedia(uri: String, mime: String, identifier: String, promise: Mango9MediaCallback) {
      queue.execute {
    var bitmap: Bitmap? = null
    try {
      require(Mango9SupervisorPresentation.uuid(identifier))
      if (!mime.startsWith("image/") && !mime.startsWith("video/")) { promise.resolve(null); return@execute }
      val directory = previews
      directory.listFiles()?.filter { it.extension == "jpg" && Mango9SupervisorPresentation.uuid(it.nameWithoutExtension) }
        ?.sortedByDescending{it.lastModified()}?.forEachIndexed { index, file ->
          if (index >= 100 || file.lastModified() < System.currentTimeMillis() - 30L * 86400000L) file.delete()
        }
      val target = File(directory, "$identifier.jpg")
      if (target.isFile){promise.resolve(android.net.Uri.fromFile(target).toString()); return@execute}
      if (uri.isBlank()){promise.resolve(null); return@execute}
      val candidate = File(URI(uri)).canonicalFile
      val input = if (candidate.parentFile == exports.canonicalFile && Mango9SupervisorPresentation.uuid(candidate.nameWithoutExtension) && candidate.isFile)candidate else snapshot(uri)
      if (mime.startsWith("video/")) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(input.path)
          val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
          val timeUs = if (duration > 0)minOf(1000L, duration / 2) * 1000 else 0L
          bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
        }finally {retriever.release()}
      }else {
        val bounds = BitmapFactory.Options().apply{inJustDecodeBounds = true}; BitmapFactory.decodeFile(input.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        bitmap = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply{inSampleSize = sample})
        bitmap?.let { original ->
          val scale = minOf(1f, 320f / maxOf(original.width, original.height))
          val orient = try {ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)}catch (_: Exception){1}
          val matrix = Matrix().apply{
            postScale(scale, scale)
            when (orient){2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f); 5 -> {
                postRotate(90f); postScale(-1f, 1f)
            }; 6 -> postRotate(90f); 7 -> {postRotate(-90f); postScale(-1f, 1f)}; 8 -> postRotate(-90f)}
          }
          bitmap = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
          if (bitmap !== original)original.recycle()
        }
      }
      val image = bitmap ?: error("Preview unavailable")
      val bytes = java.io.ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.JPEG, 70, out); out.toByteArray() }
      require(bytes.size <= 160000)
      val temp = File.createTempFile("preview-", ".tmp", directory)
      try {temp.writeBytes(bytes); check(temp.renameTo(target))}finally {temp.delete()}
      promise.resolve(android.net.Uri.fromFile(target).toString())
    }catch (_: Exception){promise.resolve(null)}finally {bitmap?.recycle()}
  }
  }

  fun prepareLargeVideo(uri: String, name: String, promise: Mango9MediaCallback) {
      queue.execute {
    var temp: File? = null
    try {
      require(name.isNotBlank() && name.toByteArray().size <= 240 && !Regex("[\\x00-\\x1f\\x7f/\\\\\\u202a-\\u202e\\u2066-\\u2069]").containsMatchIn(name))
      val mime = types[name.substringAfterLast('.', "").lowercase()] ?: error("Unsupported type")
      val u = URI(uri); require(u.scheme == "file" && u.authority == null)
      val input = File(u).canonicalFile
      require(input.path.startsWith(context.cacheDir.canonicalPath + "/") || input.path.startsWith(context.filesDir.canonicalPath + "/"))
      require(input.isFile && input.length() in 1..500_000_000L)
      Mango9AssistantMediaFile.requireSpace(input.length(), root.usableSpace)
      temp = File.createTempFile("prepare-", ".tmp", root)
      val info = temp.outputStream().use { Mango9AssistantMediaFile.inspect(input, it) }
      val saved = File(root, info.sha256 + "-" + UUID.randomUUID() + ".media")
      check(temp.renameTo(saved)); temp = null; check(saved.setReadOnly())
      if (input.parentFile?.name == "ephonix-supervisor-picked") input.delete()
      promise.resolve(
          JSONObject().apply {
              put("uri", android.net.Uri.fromFile(saved).toString()); put("name", name)
        put("mime", mime); put("size", info.size.toDouble()); put("sha256", info.sha256)
        put("partMD5", JSONArray(info.partMD5))
          }
      )
    } catch (e: Exception) {
        temp?.delete(); if (e is Mango9AssistantMediaFile.InsufficientStorage)promise.reject("media_storage", e.message) else fail(promise, e)
    }
  }
  }

  fun uploadLargeVideoPart(uri: String, size: Double, sha: String, ticket: JSONObject, operation: String, promise: Mango9MediaCallback) {
    val epoch = epochs.getOrPut(operation) { AtomicInteger() }; val version = epoch.get()
    queue.execute {
      var call: Call? = null
      try {
        require(Mango9SupervisorPresentation.uuid(operation))
        val input = snapshot(uri, sha); require(size == input.length().toDouble())
        val number = ticket.getInt("number"); val length = ticket.getInt("size"); val md5 = ticket.getString("md5")!!
        val url = URI(ticket.getString("url")!!)
        require(
            url.scheme == "https" && url.rawUserInfo == null && url.port == -1 && url.fragment == null &&
          url.host in setOf("s3.us-central-1.wasabisys.com", "newdev-chatmedia.s3.us-central-1.wasabisys.com") &&
          Regex("^(/newdev-chatmedia)?/ephonix-supervisor/v1/(2|us)\\.mango9\\.com/[1-9][0-9]*/[1-9][0-9]*/" + operation + "$").matches(url.path)
        )
        require(number > 0 && length == minOf(8_388_608L, input.length() - (number - 1L) * 8_388_608L).toInt() && length > 0)
        require(Regex("[A-Za-z0-9+/]{22}==").matches(md5) && epoch.get() == version)
        val body = object : RequestBody() {
          override fun contentType(): MediaType? = null

          override fun contentLength() = length.toLong()

          override fun isOneShot() = true

          override fun writeTo(sink: BufferedSink) {
            check(epoch.get() == version)
            Mango9AssistantMediaFile.copyPart(input, sink.outputStream(), input.length(), number, md5)
            check(epoch.get() == version)
          }
        }
        call = wire.newCall(Request.Builder().url(url.toASCIIString()).header("Content-MD5", md5).put(body).build())
        calls[call] = operation
        if (epoch.get() != version) call.cancel()
        call.execute().use { response ->
          check(epoch.get() == version)
          promise.resolve(JSONObject().apply { put("status", response.code) })
        }
      } catch (e: Exception) { fail(promise, e) } finally { if (call != null) calls.remove(call) }
    }
  }

  fun cancelLargeVideoUpload(operation: String, promise: Mango9MediaCallback) {
    epochs.getOrPut(operation) { AtomicInteger() }.incrementAndGet()
    calls.forEach { (call, id) -> if (id == operation) call.cancel() }; promise.resolve(null)
  }

  fun discardLargeMedia(uri: String, promise: Mango9MediaCallback) {
      queue.execute {
    try { val f = snapshot(uri); check(f.delete()); promise.resolve(null) } catch (e: Exception) { fail(promise, e) }
  }
  }

  fun invalidate() { calls.keys.forEach { it.cancel() }; downloads.keys.forEach{it.cancel()}; queue.shutdownNow() }
}

internal interface Mango9MediaCallback { fun resolve(value: Any?)

fun reject(code: String, message: String?) }
