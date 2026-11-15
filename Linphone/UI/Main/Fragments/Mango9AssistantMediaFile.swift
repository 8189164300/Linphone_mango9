// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import CryptoKit
import Darwin

/// Bounded, off-main-thread file preparation for the new multipart path.
/// File selection/tenant authorization and media validation belong to the caller.
/// Never accepts a remote URL or returns file bytes to the JavaScript bridge.
enum Mango9AssistantMediaFile {
  static let maximum: Int64 = 500_000_000 // 500 decimal MB, matching the server.
  static let partSize = 8_388_608
  static let bufferSize = 65_536
  enum Failure: Error { case invalidFile, changedFile, invalidPart, wrongDigest, insufficientStorage }
  struct Manifest { let size: Int64; let sha256: String; let partMD5: [String] }
  static func requireSpace(bytes:Int64, available:Int64) throws {
    guard bytes>0,bytes<=maximum else {throw Failure.invalidFile}
    guard available>bytes+134_217_728 else {throw Failure.insufficientStorage}
  }
  /// Called once before this process creates any work files. Never remove a
  /// resumable snapshot, exported media, user file, directory or symlink.
  static func removeInterruptedWork(in root:URL) throws {
    let fm=FileManager.default
    for url in try fm.contentsOfDirectory(at:root,includingPropertiesForKeys:[.isRegularFileKey,.isSymbolicLinkKey]) {
      guard ["part","tmp"].contains(url.pathExtension),UUID(uuidString:url.deletingPathExtension().lastPathComponent) != nil else {continue}
      let values=try url.resourceValues(forKeys:[.isRegularFileKey,.isSymbolicLinkKey])
      guard values.isRegularFile==true,values.isSymbolicLink != true else {continue}
      try fm.removeItem(at:url)
    }
  }
  /// iOS may relocate the data container during an app update. Resolve a saved
  /// snapshot by its private generated basename, never by opening an old or
  /// arbitrary filesystem path. Only a previous iOS app-container URL qualifies.
  static func snapshot(_ uri:String,root:URL,sha:String?=nil) throws -> URL {
    guard let url=URL(string:uri),url.isFileURL,url.host == nil || url.host == "",
      url.query == nil,url.fragment == nil else {throw Failure.invalidFile}
    let directory=root.resolvingSymlinksInPath(),name=url.lastPathComponent
    let stem=url.deletingPathExtension().lastPathComponent
    guard stem.count==101,stem[stem.index(stem.startIndex,offsetBy:64)]=="-",
      stem.prefix(64).allSatisfy({"0123456789abcdef".contains($0)}),
      UUID(uuidString:String(stem.suffix(36))) != nil,
      ["media","jpg","jpeg","png","webp","heic","heif","mp4","mov","pdf","txt","csv","wav","mp3"].contains(url.pathExtension),
      sha == nil || String(stem.prefix(64))==sha else {throw Failure.invalidFile}
    if url.resolvingSymlinksInPath().deletingLastPathComponent() != directory {
      var parts=url.pathComponents
      if parts.count>1 && parts[1]=="private" {parts.remove(at:1)}
      guard parts.count==11,Array(parts.prefix(6))==["/","var","mobile","Containers","Data","Application"],
        UUID(uuidString:parts[6]) != nil,
        Array(parts[7...10])==["Library","Application Support","SupervisorMedia",name] else {throw Failure.invalidFile}
    }
    let candidate=directory.appendingPathComponent(name),resolved=candidate.resolvingSymlinksInPath()
    guard resolved==candidate,(try? resolved.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile)==true else {throw Failure.invalidFile}
    return resolved
  }
  private static func metadata(_ handle: FileHandle) throws -> stat {
    var value = stat()
    guard fstat(handle.fileDescriptor, &value) == 0,
      value.st_mode & S_IFMT == S_IFREG, value.st_size > 0,
      value.st_size <= maximum else { throw Failure.invalidFile }
    return value
  }
  private static func unchanged(_ before: stat, _ after: stat) -> Bool {
    before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size &&
      before.st_mtimespec.tv_sec == after.st_mtimespec.tv_sec && before.st_mtimespec.tv_nsec == after.st_mtimespec.tv_nsec &&
      before.st_ctimespec.tv_sec == after.st_ctimespec.tv_sec && before.st_ctimespec.tv_nsec == after.st_ctimespec.tv_nsec
  }
  /// Hash and optionally copy an opened local file. The destination must be a
  /// newly created private snapshot supplied by the caller, never the original.
  static func inspect(_ input: FileHandle, copyTo output: FileHandle? = nil,
                      progress: (Int64, Int64) -> Void = {_,_ in}) throws -> Manifest {
    let before = try metadata(input)
    try input.seek(toOffset: 0)
    var full = SHA256(), part = Insecure.MD5(), total: Int64 = 0, partBytes = 0, digests: [String] = []
    while true {
      // Foundation's FileHandle returns autoreleased backing objects on Apple
      // platforms. Bound their lifetime too, not just the requested read size.
      let count: Int = try autoreleasepool {
        guard let block = try input.read(upToCount: min(bufferSize, partSize - partBytes)), !block.isEmpty else { return 0 }
        total += Int64(block.count)
        guard total <= before.st_size else { throw Failure.changedFile }
        try output?.write(contentsOf: block)
        full.update(data: block); part.update(data: block); partBytes += block.count
        if partBytes == partSize {
          digests.append(Data(part.finalize()).base64EncodedString()); part = Insecure.MD5(); partBytes = 0
          progress(total, before.st_size)
        }
        return block.count
      }
      if count == 0 { break }
    }
    guard total == before.st_size, unchanged(before, try metadata(input)) else { throw Failure.changedFile }
    if partBytes > 0 { digests.append(Data(part.finalize()).base64EncodedString()) }
    try output?.synchronize()
    progress(total, total)
    return Manifest(size: total, sha256: full.finalize().map { String(format:"%02x", $0) }.joined(), partMD5: digests)
  }
  /// URLSession uploads a bounded part file, not an in-memory Data body. This
  /// verifies that the exact bytes still match the signed Content-MD5 digest.
  static func copyPart(_ input: FileHandle, to output: FileHandle, size: Int64,
                       number: Int, md5: String) throws -> Int {
    let before = try metadata(input)
    guard size == before.st_size, number >= 1, number <= Int((size + Int64(partSize) - 1) / Int64(partSize)) else { throw Failure.invalidPart }
    let offset = Int64(number - 1) * Int64(partSize), length = Int(min(Int64(partSize), size - offset))
    try input.seek(toOffset: UInt64(offset))
    var remaining = length, digest = Insecure.MD5()
    while remaining > 0 {
      try autoreleasepool {
        guard let block = try input.read(upToCount: min(bufferSize, remaining)), !block.isEmpty else { throw Failure.changedFile }
        remaining -= block.count; digest.update(data: block); try output.write(contentsOf: block)
      }
    }
    guard unchanged(before, try metadata(input)) else { throw Failure.changedFile }
    guard Data(digest.finalize()).base64EncodedString() == md5 else { throw Failure.wrongDigest }
    try output.synchronize()
    return length
  }
}
