// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** Native bounded file operations for multipart uploads. No Android/UI/SIP
 * dependencies; callers own private-file validation, lifecycle and scope. */
internal object Mango9AssistantMediaFile {
  const val maximum = 500_000_000L // 500 decimal MB, matching the server.
  const val partSize = 8_388_608
  const val bufferSize = 65_536

  data class Manifest(val size: Long, val sha256: String, val partMD5: List<String>)

  class InsufficientStorage : Exception("Not enough free space to prepare this file. Free some space, then try again.")

  fun requireSpace(bytes: Long, available: Long) {
    require(bytes in 1..maximum)
    if (available <= bytes + 134_217_728L)throw InsufficientStorage()
  }

  /** Only call before this process creates work. Retain immutable resumable files. */
  fun removeInterruptedWork(root: File, prefix: String) {
    require(prefix in setOf("prepare-", "download-", "preview-"))
    root.listFiles()?.filter{
        it.name.startsWith(prefix) && it.extension == "tmp" && it.isFile && it.canonicalFile == File(root.canonicalFile, it.name)
    }?.forEach{check(it.delete())}
  }

  private fun valid(file: File): Long {
    require(file.isFile && file.length() in 1..maximum) { "Invalid video file" }
    return file.length()
  }

  fun inspect(file: File, copyTo: OutputStream? = null, progress: (Long, Long) -> Unit = {_, _ -> }): Manifest {
    val size = valid(file); val modified = file.lastModified()
    val full = MessageDigest.getInstance("SHA-256"); var part = MessageDigest.getInstance("MD5")
    val hashes = ArrayList<String>(); val buffer = ByteArray(bufferSize); var total = 0L; var partBytes = 0
    FileInputStream(file).use { input ->
      while (true){
        val n = input.read(buffer, 0, minOf(bufferSize, partSize - partBytes)); if (n < 0)break; if (n == 0)continue
        total += n; require(total <= size) { "Video changed" }
        copyTo?.write(buffer, 0, n); full.update(buffer, 0, n); part.update(buffer, 0, n); partBytes += n
        if (partBytes == partSize){
            hashes.add(Base64.getEncoder().encodeToString(part.digest())); part = MessageDigest.getInstance("MD5"); partBytes = 0; progress(total, size)
        }
      }
    }
    require(total == size && file.length() == size && file.lastModified() == modified) { "Video changed" }
    if (partBytes > 0)hashes.add(Base64.getEncoder().encodeToString(part.digest()))
    copyTo?.flush(); progress(size, size)
    return Manifest(size, full.digest().joinToString(""){"%02x".format(it)}, hashes)
  }

  /** Streams one exact range, usable by an OkHttp RequestBody. Content-MD5 on
   * the presigned PUT makes Wasabi reject changed bytes even during streaming. */
  fun copyPart(file: File, out: OutputStream, size: Long, number: Int, md5: String): Long {
    require(valid(file) == size && number >= 1 && number <= (size + partSize - 1) / partSize) { "Invalid video part" }
    val modified = file.lastModified(); val offset = (number - 1).toLong() * partSize
    val length = minOf(partSize.toLong(), size - offset); var remaining = length
    val digest = MessageDigest.getInstance("MD5"); val buffer = ByteArray(bufferSize)
    FileInputStream(file).use { input ->
      input.channel.position(offset)
      while (remaining > 0){
        val n = input.read(buffer, 0, minOf(bufferSize.toLong(), remaining).toInt()); require(n > 0) { "Video changed" }
        remaining -= n; digest.update(buffer, 0, n); out.write(buffer, 0, n)
      }
    }
    require(file.length() == size && file.lastModified() == modified) { "Video changed" }
    require(Base64.getEncoder().encodeToString(digest.digest()) == md5) { "Video part changed" }
    out.flush(); return length
  }
}
