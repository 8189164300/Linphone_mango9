// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
typealias Mango9MediaResolve = (Any?) -> Void
typealias Mango9MediaReject = (String, String?, Error?) -> Void
import ImageIO
import UIKit
import AVFoundation

/// Private-file multipart transport. No SIP/audio-session interaction and no file bytes in JS.
final class Mango9AssistantMediaNative: NSObject, URLSessionTaskDelegate, URLSessionDownloadDelegate {
  static func requiresMainQueueSetup() -> Bool { false }
  private let queue: OperationQueue = { let q = OperationQueue(); q.maxConcurrentOperationCount = 2; return q }()
  private let lock = NSLock()
  private let maintenanceLock = NSLock()
  private var cleanedInterruptedWork = false
  private var epochs: [String: Int] = [:]
  private var tasks: [Int: (String, URLSessionTask)] = [:]
  private var downloads: [Int: (String, Int64, URLSessionTask)] = [:]
  private var downloadEpochs: [String:Int] = [:]
  private lazy var wire: URLSession = {
    let c = URLSessionConfiguration.ephemeral
    c.httpCookieStorage = nil; c.urlCache = nil; c.urlCredentialStorage = nil
    c.timeoutIntervalForRequest = 120; c.timeoutIntervalForResource = 600
    c.httpMaximumConnectionsPerHost = 2
    return URLSession(configuration: c, delegate: self, delegateQueue: nil)
  }()
  private let types = ["jpg":"image/jpeg", "jpeg":"image/jpeg", "png":"image/png", "webp":"image/webp",
    "heic":"image/heic", "heif":"image/heif", "mp4":"video/mp4", "mov":"video/quicktime",
    "pdf":"application/pdf", "txt":"text/plain", "csv":"text/csv", "wav":"audio/wav", "mp3":"audio/mpeg"]
  private func root() throws -> URL {
    let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("SupervisorMedia", isDirectory: true)
    try FileManager.default.createDirectory(at: u, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    var marked = u; var values = URLResourceValues(); values.isExcludedFromBackup = true; try marked.setResourceValues(values)
    maintenanceLock.lock();defer{maintenanceLock.unlock()}
    if !cleanedInterruptedWork {try Mango9AssistantMediaFile.removeInterruptedWork(in:u);cleanedInterruptedWork=true}
    return u.resolvingSymlinksInPath()
  }
  private func snapshot(_ uri: String, sha: String? = nil) throws -> URL {
    try Mango9AssistantMediaFile.snapshot(uri,root:root(),sha:sha)
  }
  private func failure(_ reject: Mango9MediaReject) { reject("supervisor_media", "File transfer interrupted. Retry to resume, or remove the file.", nil) }
  private func previewRoot() throws -> URL {
    let dir=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("SupervisorMediaPreviews",isDirectory:true)
    try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication])
    let files=(try? FileManager.default.contentsOfDirectory(at:dir,includingPropertiesForKeys:[.contentModificationDateKey])) ?? []
    let owned=files.filter{$0.pathExtension=="jpg" && UUID(uuidString:$0.deletingPathExtension().lastPathComponent) != nil}
      .sorted{((try? $0.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast)>((try? $1.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast)}
    for (index,file) in owned.enumerated() {
      let date=(try? file.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      if index>=100 || date.timeIntervalSinceNow < -30*86400 {try? FileManager.default.removeItem(at:file)}
    }
    return dir
  }
  private func exportRoot() throws -> URL {
    let dir=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("SupervisorMediaExports",isDirectory:true)
    try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication])
    return dir.resolvingSymlinksInPath()
  }
  func downloadLargeMedia(_ raw:String,identifier:String,mime:String,size:NSNumber,resolver resolve:@escaping Mango9MediaResolve,rejecter reject:@escaping Mango9MediaReject){
    lock.lock();let version=downloadEpochs[identifier,default:0];lock.unlock()
    queue.addOperation {
      do {
        self.lock.lock();let valid=self.downloadEpochs[identifier,default:0]==version;self.lock.unlock()
        guard valid else{throw Mango9AssistantMediaFile.Failure.invalidFile}
        guard UUID(uuidString:identifier) != nil,let ext=self.types.first(where:{$0.value==mime})?.key,
          size.doubleValue==Double(size.int64Value),size.int64Value>0,size.int64Value<=500_000_000,
          let url=URL(string:raw),url.scheme=="https",url.user==nil,url.password==nil,url.port==nil,url.fragment==nil,
          ["s3.us-central-1.wasabisys.com","newdev-chatmedia.s3.us-central-1.wasabisys.com"].contains(url.host ?? ""),
          url.path.range(of:"^(/newdev-chatmedia)?/ephonix-supervisor/v1/(2|us)\\.mango9\\.com/[1-9][0-9]*/[1-9][0-9]*/"+identifier+"$",options:.regularExpression) != nil else {throw Mango9AssistantMediaFile.Failure.invalidFile}
        let root=try self.exportRoot(),target=root.appendingPathComponent(identifier+"."+ext)
        let items=(try? FileManager.default.contentsOfDirectory(at:root,includingPropertiesForKeys:[.contentModificationDateKey])) ?? []
        let sorted=items.sorted{((try? $0.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast)>((try? $1.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast)}
        for (index,file) in sorted.enumerated() where file != target && UUID(uuidString:file.deletingPathExtension().lastPathComponent) != nil {
          let date=(try? file.resourceValues(forKeys:[.contentModificationDateKey]).contentModificationDate) ?? .distantPast
          if index>=2 || date.timeIntervalSinceNow < -86400 {try? FileManager.default.removeItem(at:file)}
        }
        if (try? target.resourceValues(forKeys:[.fileSizeKey]).fileSize)==size.intValue {resolve(target.absoluteString);return}
        let disk=try FileManager.default.attributesOfFileSystem(forPath:root.path)
        try Mango9AssistantMediaFile.requireSpace(bytes:size.int64Value,available:(disk[.systemFreeSize] as? NSNumber)?.int64Value ?? 0)
        var request=URLRequest(url:url);request.httpShouldHandleCookies=false
        var taskID=0
        let task=self.wire.downloadTask(with:request){temp,response,error in
          self.lock.lock();self.downloads.removeValue(forKey:taskID);let valid=self.downloadEpochs[identifier,default:0]==version;self.lock.unlock()
          do {
            guard valid,error==nil,let temp=temp,(response as? HTTPURLResponse)?.statusCode==200,
              (try temp.resourceValues(forKeys:[.fileSizeKey]).fileSize)==size.intValue else{throw Mango9AssistantMediaFile.Failure.invalidFile}
            if FileManager.default.fileExists(atPath:target.path){try FileManager.default.removeItem(at:target)}
            try FileManager.default.moveItem(at:temp,to:target)
            try FileManager.default.setAttributes([.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication],ofItemAtPath:target.path)
            resolve(target.absoluteString)
          }catch{reject("media_download","This attachment could not be downloaded. Check your connection and free space, then retry.",nil)}
        }
        taskID=task.taskIdentifier
        self.lock.lock();self.downloads[taskID]=(identifier,size.int64Value,task);let current=self.downloadEpochs[identifier,default:0]==version;self.lock.unlock()
        if !current{task.cancel()};task.resume()
      }catch{reject("media_download","This attachment could not be downloaded. Check your connection and free space, then retry.",nil)}
    }
  }
  func cancelLargeMediaDownload(_ identifier:String){lock.lock();downloadEpochs[identifier,default:0]+=1;let active=downloads.values.filter{$0.0==identifier}.map{$0.2};lock.unlock();active.forEach{$0.cancel()}}
  func playableLargeMedia(_ uri:String,mime:String,resolver resolve:@escaping Mango9MediaResolve,rejecter reject:@escaping Mango9MediaReject){
    queue.addOperation {
      do {
        guard mime.hasPrefix("image/") || mime.hasPrefix("video/") || mime.hasPrefix("audio/"),
          let ext=self.types.keys.sorted().first(where:{self.types[$0]==mime}) else {throw Mango9AssistantMediaFile.Failure.invalidFile}
        let source=try self.snapshot(uri)
        if self.types[source.pathExtension]==mime {resolve(source.absoluteString);return}
        guard source.pathExtension=="media" else {throw Mango9AssistantMediaFile.Failure.invalidFile}
        // A hard link gives older resumable snapshots a playable extension
        // without copying 500 MB, changing their bytes or invalidating resume.
        let typed=source.deletingPathExtension().appendingPathExtension(ext)
        if !FileManager.default.fileExists(atPath:typed.path){try FileManager.default.linkItem(at:source,to:typed)}
        resolve(typed.absoluteString)
      }catch{
        self.logPreviewFailure(error,uri:uri,operation:"playback")
        self.failure(reject)
      }
    }
  }
  func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64){
    lock.lock();let expected=downloads[downloadTask.taskIdentifier]?.1;lock.unlock()
    if let expected=expected,totalBytesWritten>expected || totalBytesExpectedToWrite>expected{downloadTask.cancel()}
  }
  func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL){}
  func previewLargeMedia(_ uri:String,mime:String,identifier:String,resolver resolve:@escaping Mango9MediaResolve,rejecter reject:@escaping Mango9MediaReject){
    queue.addOperation {
      do {
        guard UUID(uuidString:identifier) != nil, mime.hasPrefix("image/") || mime.hasPrefix("video/") else {resolve(nil);return}
        let target=try self.previewRoot().appendingPathComponent(identifier+".jpg")
        if FileManager.default.fileExists(atPath:target.path){resolve(target.absoluteString);return}
        guard !uri.isEmpty else {resolve(nil);return}
        let candidate=URL(string:uri)?.resolvingSymlinksInPath()
        let source:URL
        if let candidate=candidate,candidate.isFileURL,candidate.deletingLastPathComponent()==(try self.exportRoot()),UUID(uuidString:candidate.deletingPathExtension().lastPathComponent) != nil {source=candidate}
        else {source=try self.snapshot(uri)}
        let cg:CGImage
        if mime.hasPrefix("video/") {
          // Older resumable snapshots used .media. Tell AVFoundation the
          // verified container type instead of relying on that extension.
          var options:[String:Any]=[:]
          if #available(iOS 17.0, *) {options[AVURLAssetOverrideMIMETypeKey]=mime}
          let asset=AVURLAsset(url:source,options:options),generator=AVAssetImageGenerator(asset:asset)
          generator.appliesPreferredTrackTransform=true;generator.maximumSize=CGSize(width:320,height:320)
          generator.requestedTimeToleranceBefore = .zero;generator.requestedTimeToleranceAfter = .zero
          // Avoid a blank lead-in frame; the local decoder stays off the UI thread.
          let duration=CMTimeGetSeconds(asset.duration)
          let seconds=duration.isFinite && duration>0 ? min(1,duration/2) : 0
          cg=try generator.copyCGImage(at:CMTime(seconds:seconds,preferredTimescale:600),actualTime:nil)
        }else{
          guard let src=CGImageSourceCreateWithURL(source as CFURL,[kCGImageSourceShouldCache:false] as CFDictionary),
            let thumb=CGImageSourceCreateThumbnailAtIndex(src,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:320] as CFDictionary) else {resolve(nil);return}
          cg=thumb
        }
        guard let bytes=UIImage(cgImage:cg).jpegData(compressionQuality:0.7),bytes.count<=160000 else {resolve(nil);return}
        try bytes.write(to:target,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication]);resolve(target.absoluteString)
      }catch{
        self.logPreviewFailure(error,uri:uri,operation:"poster")
        resolve(nil)
      } // Preview is optional, and must not break sending.
    }
  }
  // Log only a failure classification, never media names, contents or URLs.
  private func logPreviewFailure(_ error:Error,uri:String,operation:String){
    let source=URL(string:uri)?.resolvingSymlinksInPath(),e=error as NSError
    let exists=source?.isFileURL == true && FileManager.default.fileExists(atPath:source!.path)
    let local=source?.deletingLastPathComponent() == (try? root())
    NSLog("[Mango9AssistantMedia] %@ failed domain=%@ code=%ld fileExists=%d currentContainer=%d",operation,e.domain,e.code,exists,local)
  }
  func prepareLargeVideo(_ uri: String, name: String, resolver resolve: @escaping Mango9MediaResolve, rejecter reject: @escaping Mango9MediaReject) {
    queue.addOperation {
      var temporary: URL?
      do {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.utf8.count <= 240,
          name.range(of: "[\\x00-\\x1f\\x7f/\\\\\\u202a-\\u202e\\u2066-\\u2069]", options: .regularExpression) == nil,
          let mime = self.types[(name as NSString).pathExtension.lowercased()], let url = URL(string: uri), url.isFileURL else { throw Mango9AssistantMediaFile.Failure.invalidFile }
        let inputURL = url.resolvingSymlinksInPath()
        let home = URL(fileURLWithPath: NSHomeDirectory()).resolvingSymlinksInPath().path + "/"
        guard inputURL.path.hasPrefix(home) else { throw Mango9AssistantMediaFile.Failure.invalidFile }
        let directory = try self.root(), values = try inputURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= 500_000_000 else { throw Mango9AssistantMediaFile.Failure.invalidFile }
        let disk = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        try Mango9AssistantMediaFile.requireSpace(bytes:Int64(size),available:(disk[.systemFreeSize] as? NSNumber)?.int64Value ?? 0)
        let copy = directory.appendingPathComponent(UUID().uuidString + ".tmp"); temporary = copy
        guard FileManager.default.createFile(atPath: copy.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw Mango9AssistantMediaFile.Failure.invalidFile }
        let input = try FileHandle(forReadingFrom: inputURL), output = try FileHandle(forWritingTo: copy)
        defer { try? input.close(); try? output.close() }
        let info = try Mango9AssistantMediaFile.inspect(input, copyTo: output)
        // Native video playback identifies local containers by extension.
        // Retain the allowlisted extension, not the original user filename.
        let saved = directory.appendingPathComponent(info.sha256 + "-" + UUID().uuidString + "." + (name as NSString).pathExtension.lowercased())
        try FileManager.default.moveItem(at: copy, to: saved); temporary = nil
        try FileManager.default.setAttributes([.posixPermissions: 0o400], ofItemAtPath: saved.path)
        if inputURL.deletingLastPathComponent().lastPathComponent == "ephonix-supervisor-picked" { try? FileManager.default.removeItem(at: inputURL) }
        resolve(["uri": saved.absoluteString, "name": name, "mime": mime, "size": info.size, "sha256": info.sha256, "partMD5": info.partMD5])
      } catch { if let u = temporary { try? FileManager.default.removeItem(at: u) }
        if case Mango9AssistantMediaFile.Failure.insufficientStorage = error {reject("media_storage","Not enough free space to prepare this file. Free some space, then try again.",nil)} else {self.failure(reject)}
      }
    }
  }
  func uploadLargeVideoPart(_ uri: String, size: NSNumber, sha256: String, ticket: NSDictionary, operationId: String,
    resolver resolve: @escaping Mango9MediaResolve, rejecter reject: @escaping Mango9MediaReject) {
    lock.lock(); let epoch = epochs[operationId, default: 0]; lock.unlock()
    queue.addOperation {
      var part: URL?
      do {
        guard UUID(uuidString: operationId) != nil, sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          let raw = ticket["url"] as? String, let url = URL(string: raw), url.scheme == "https", url.user == nil, url.password == nil,
          url.port == nil, url.fragment == nil, ["s3.us-central-1.wasabisys.com", "newdev-chatmedia.s3.us-central-1.wasabisys.com"].contains(url.host ?? ""),
          url.path.range(of: "^(/newdev-chatmedia)?/ephonix-supervisor/v1/(2|us)\\.mango9\\.com/[1-9][0-9]*/[1-9][0-9]*/" + operationId + "$", options: .regularExpression) != nil,
          let number = ticket["number"] as? Int, let length = ticket["size"] as? Int, let md5 = ticket["md5"] as? String else { throw Mango9AssistantMediaFile.Failure.invalidPart }
        let source = try self.snapshot(uri, sha: sha256), temp = try self.root().appendingPathComponent(UUID().uuidString + ".part"); part = temp
        guard FileManager.default.createFile(atPath: temp.path, contents: nil) else { throw Mango9AssistantMediaFile.Failure.invalidFile }
        let input = try FileHandle(forReadingFrom: source), output = try FileHandle(forWritingTo: temp)
        defer { try? input.close(); try? output.close() }
        guard try Mango9AssistantMediaFile.copyPart(input, to: output, size: size.int64Value, number: number, md5: md5) == length else { throw Mango9AssistantMediaFile.Failure.invalidPart }
        var request = URLRequest(url: url); request.httpMethod = "PUT"
        request.setValue(md5, forHTTPHeaderField: "Content-MD5"); request.setValue(String(length), forHTTPHeaderField: "Content-Length")
        self.lock.lock(); let current = self.epochs[operationId, default: 0]; self.lock.unlock()
        guard current == epoch else { throw Mango9AssistantMediaFile.Failure.invalidPart }
        let task = self.wire.uploadTask(with: request, fromFile: temp) { _, response, error in
          try? FileManager.default.removeItem(at: temp)
          self.lock.lock(); let valid = self.epochs[operationId, default: 0] == epoch
          self.tasks = self.tasks.filter { $0.value.1.state != .completed }; self.lock.unlock()
          if error == nil, valid, let http = response as? HTTPURLResponse { resolve(["status": http.statusCode]) } else { self.failure(reject) }
        }
        self.lock.lock(); self.tasks[task.taskIdentifier] = (operationId, task)
        let cancelled = self.epochs[operationId, default: 0] != epoch; self.lock.unlock()
        if cancelled { task.cancel() }; task.resume(); part = nil
      } catch { if let u = part { try? FileManager.default.removeItem(at: u) }; self.failure(reject) }
    }
  }
  func cancelLargeVideoUpload(_ id: String, resolver resolve: Mango9MediaResolve, rejecter reject: Mango9MediaReject) {
    lock.lock(); epochs[id, default: 0] += 1; let active = tasks.values.filter { $0.0 == id }.map { $0.1 }; lock.unlock()
    active.forEach { $0.cancel() }; resolve(nil)
  }
  func discardLargeMedia(_ uri: String, resolver resolve: @escaping Mango9MediaResolve, rejecter reject: @escaping Mango9MediaReject) {
    queue.addOperation { do {
      let source=try self.snapshot(uri)
      try FileManager.default.removeItem(at:source)
      if source.pathExtension=="media" {for ext in self.types.keys {try? FileManager.default.removeItem(at:source.deletingPathExtension().appendingPathExtension(ext))}}
      resolve(nil)
    } catch { self.failure(reject) } }
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
