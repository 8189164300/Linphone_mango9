// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SwiftUI

struct Mango9AssistantAttachment: Codable, Equatable, Identifiable {
    let id: String
    var name: String?
    var mime: String?
    var size: Int64?
    init?(raw: [String: Any]) {
        guard raw["type"] as? String == "attachment", let id = raw["id"] as? String, UUID(uuidString: id) != nil else { return nil }
        self.id = id.lowercased(); name = (raw["name"] as? String).map { String($0.prefix(240)) }
        mime = raw["mime"] as? String; size = (raw["size"] as? NSNumber)?.int64Value
    }
    var sizeLabel: String { Self.sizeLabel(size ?? 0) }
    static func sizeLabel(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "" }
        if bytes < 1000 { return "\(bytes) B" }
        if bytes < 1_000_000 { return String(format: "%.1f KB", Double(bytes) / 1000) }
        return String(format: "%.1f MB", Double(bytes) / 1_000_000)
    }
}
struct Mango9AssistantFile: Codable, Equatable {
    let uri: String
    let name: String
    let size: Int64
    let mime: String
    let sha256: String
    let partMD5: [String]
}
struct Mango9AssistantUpload: Codable {
    let id: String
    let account: String
    let origin: String
    let conversationId: String
    let file: Mango9AssistantFile
    var caption: String?
}
struct Mango9AssistantMediaError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
enum Mango9AssistantMediaContract {
    static let maximum: Int64 = 500_000_000
    static let partSize = 8_388_608
    static let types: Set<String> = ["video/mp4", "video/quicktime", "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf", "text/plain", "text/csv", "audio/wav", "audio/mpeg"]
    static func invalid() -> Mango9AssistantMediaError { .init(message: "The file transfer could not be verified. Your draft is kept; try Resume.") }
    static func decode<T: Decodable>(_ value: Any, as type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value))
    }
    static func validate(_ d: Mango9AssistantUpload) throws {
        let f = d.file
        guard UUID(uuidString: d.id) != nil, UUID(uuidString: d.conversationId) != nil,
              ["https://2.mango9.com", "https://us.mango9.com"].contains(d.origin), !d.account.isEmpty,
              f.size > 0, f.size <= maximum, types.contains(f.mime), URL(string: f.uri)?.isFileURL == true,
              !f.name.isEmpty, f.name.utf8.count <= 240,
              f.name.range(of: "[\\x00-\\x1f\\x7f/\\\\\\u202a-\\u202e\\u2066-\\u2069]", options: .regularExpression) == nil,
              f.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              f.partMD5.count == Int((f.size + Int64(partSize) - 1) / Int64(partSize)),
              f.partMD5.allSatisfy({ Data(base64Encoded: $0)?.count == 16 }), (d.caption?.utf8.count ?? 0) <= 8000 else { throw invalid() }
    }
    static func length(_ file: Mango9AssistantFile, _ part: Int) throws -> Int64 {
        guard part > 0, part <= file.partMD5.count else { throw invalid() }
        return min(Int64(partSize), file.size - Int64(part - 1) * Int64(partSize))
    }
    static func status(_ raw: [String: Any], _ d: Mango9AssistantUpload) throws -> (state: String, uploaded: Set<Int>) {
        guard raw["version"] as? Int == 1, raw["id"] as? String == d.id, raw["conversationId"] as? String == d.conversationId,
              raw["size"] as? Int64 == d.file.size, raw["sha256"] as? String == d.file.sha256,
              raw["partSize"] as? Int == partSize, raw["partCount"] as? Int == d.file.partMD5.count,
              let state = raw["state"] as? String, ["creating", "uploading", "completing", "validating", "ready", "aborting", "aborted", "rejected"].contains(state),
              let parts = raw["uploaded"] as? [[String: Any]], parts.count <= d.file.partMD5.count else { throw invalid() }
        var seen = Set<Int>()
        for p in parts {
            guard let n = p["number"] as? Int, seen.insert(n).inserted,
                  p["size"] as? Int64 == (try length(d.file, n)) else { throw invalid() }
        }
        if state == "ready" {
            guard let a = raw["attachment"] as? [String: Any], a["id"] as? String == d.id,
                  a["size"] as? Int64 == d.file.size, a["sha256"] as? String == d.file.sha256,
                  a["mime"] as? String == d.file.mime, a["name"] as? String == d.file.name else { throw invalid() }
        }
        return (state, seen)
    }
    static func objectURL(_ raw: String, id: String, origin: String) -> URL? {
        guard UUID(uuidString: id) != nil, let c = URLComponents(string: raw), let url = c.url, raw.count <= 12000,
              c.scheme == "https", c.user == nil, c.password == nil, c.port == nil, c.fragment == nil,
              ["s3.us-central-1.wasabisys.com", "newdev-chatmedia.s3.us-central-1.wasabisys.com"].contains(c.host ?? ""),
              let realm = URL(string: origin)?.host, ["2.mango9.com", "us.mango9.com"].contains(realm) else { return nil }
        let prefix = c.host == "s3.us-central-1.wasabisys.com" ? "/newdev-chatmedia" : ""
        let pattern = "^" + prefix + "/ephonix-supervisor/v1/" + NSRegularExpression.escapedPattern(for: realm) + "/[1-9][0-9]*/[1-9][0-9]*/" + id + "$"
        guard url.path.range(of: pattern, options: .regularExpression) != nil else { return nil }
        return url
    }
    static func ticket(_ raw: [String: Any], _ d: Mango9AssistantUpload, part: Int) throws -> [String: Any] {
        guard raw["version"] as? Int == 1, raw["id"] as? String == d.id,
              let parts = raw["parts"] as? [[String: Any]], parts.count == 1, let p = parts.first,
              p["number"] as? Int == part, p["size"] as? Int64 == (try length(d.file, part)),
              p["md5"] as? String == d.file.partMD5[part - 1], let headers = p["headers"] as? [String: String],
              Set(headers.keys) == ["Content-Length", "Content-MD5"], headers["Content-Length"] == String(try length(d.file, part)),
              headers["Content-MD5"] == d.file.partMD5[part - 1], let rawURL = p["url"] as? String,
              let url = objectURL(rawURL, id: d.id, origin: d.origin), let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { throw invalid() }
        let keys = ["partNumber", "uploadId", "X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature"]
        guard keys.allSatisfy({ key in query.filter { $0.name == key }.count == 1 }) else { throw invalid() }
        func q(_ key: String) -> String { query.first { $0.name == key }?.value ?? "" }
        guard q("partNumber") == String(part), !q("uploadId").isEmpty, q("X-Amz-Algorithm") == "AWS4-HMAC-SHA256",
              !q("X-Amz-Credential").isEmpty, (1...600).contains(Int(q("X-Amz-Expires")) ?? 0),
              q("X-Amz-Date").range(of: "^\\d{8}T\\d{6}Z$", options: .regularExpression) != nil,
              q("X-Amz-Signature").range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              Set(q("X-Amz-SignedHeaders").split(separator: ";").map(String.init)).isSuperset(of: ["host", "content-length", "content-md5"]) else { throw invalid() }
        return p
    }
}

extension Mango9AssistantMediaNative {
    func result(_ action: (@escaping Mango9MediaResolve, @escaping Mango9MediaReject) -> Void) async throws -> Any? {
        try await withCheckedThrowingContinuation { continuation in
            action({ continuation.resume(returning: $0) }, { _, message, _ in continuation.resume(throwing: Mango9AssistantMediaError(message: message ?? "File unavailable. Try again.")) })
        }
    }
    func prepare(_ url: URL, name: String) async throws -> Mango9AssistantFile {
        guard let raw = try await result({ prepareLargeVideo(url.absoluteString, name: name, resolver: $0, rejecter: $1) }) else { throw Mango9AssistantMediaContract.invalid() }
        return try Mango9AssistantMediaContract.decode(raw, as: Mango9AssistantFile.self)
    }
    func discard(_ uri: String) async { _ = try? await result { discardLargeMedia(uri, resolver: $0, rejecter: $1) } }
    func pause(_ id: String) { cancelLargeVideoUpload(id, resolver: {_ in}, rejecter: {_,_,_ in}) }
}

struct Mango9AssistantUploadBatch: Codable {
    let version: Int
    var drafts: [Mango9AssistantUpload]
    var sealed: Bool
    static func restore(_ data: Data, account: String, origin: String, conversation: String) throws -> Self {
        let decoder = JSONDecoder()
        let batch = try? decoder.decode(Self.self, from: data)
        let values = try batch?.drafts ?? [decoder.decode(Mango9AssistantUpload.self, from: data)]
        guard batch == nil || batch?.version == 2, !values.isEmpty, values.count <= 3,
              Set(values.map(\.id)).count == values.count else { throw Mango9AssistantMediaContract.invalid() }
        let frozen = batch?.sealed ?? (values.first?.caption != nil)
        for value in values {
            try Mango9AssistantMediaContract.validate(value)
            guard value.account == account, value.origin == origin, value.conversationId == conversation,
                  !frozen || value.caption == values[0].caption else { throw Mango9AssistantMediaContract.invalid() }
        }
        return Self(version: 2, drafts: values, sealed: frozen)
    }
}

enum Mango9AssistantDraftMigration {
    static func recover(_ sourceURL: URL, source: String, account: String, origin: String) throws -> String {
        let marker = sourceURL.appendingPathExtension("recovery")
        let destination: String
        if FileManager.default.fileExists(atPath: marker.path) {
            guard let saved = String(data: try Data(contentsOf: marker), encoding: .utf8) else { throw Mango9AssistantMediaContract.invalid() }
            guard UUID(uuidString: saved) != nil, saved != source else { throw Mango9AssistantMediaContract.invalid() }
            destination = saved
        } else {
            destination = UUID().uuidString.lowercased()
            try Data(destination.utf8).write(to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        if FileManager.default.fileExists(atPath: sourceURL.path) {
            let data = try Data(contentsOf: sourceURL)
            let batch = try Mango9AssistantUploadBatch.restore(data, account: account, origin: origin, conversation: source)
            let target = sourceURL.deletingLastPathComponent().appendingPathComponent(account + "." + destination + ".json")
            if FileManager.default.fileExists(atPath: target.path) {
                let restored = try Mango9AssistantUploadBatch.restore(Data(contentsOf: target), account: account, origin: origin, conversation: destination)
                guard restored.drafts.map(\.file) == batch.drafts.map(\.file) else { throw Mango9AssistantMediaContract.invalid() }
            } else {
                let moved = batch.drafts.map { Mango9AssistantUpload(id: UUID().uuidString.lowercased(), account: account,
                    origin: origin, conversationId: destination, file: $0.file) }
                try JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: moved, sealed: false)).write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            try FileManager.default.removeItem(at: sourceURL)
        }
        return destination
    }
}

@MainActor final class Mango9AssistantTransfer: ObservableObject {
    @Published private(set) var draft: Mango9AssistantUpload?
    @Published private(set) var drafts: [Mango9AssistantUpload] = []
    @Published private(set) var sealed = false
    @Published private(set) var maximum = 1
    var canAdd: Bool { enabled && !busy && !sealed && drafts.count < maximum }
    var total: Int64 { drafts.reduce(0) { $0 + $1.file.size } }
    @Published private(set) var phase = "idle"
    @Published private(set) var uploaded: Int64 = 0
    @Published private(set) var previewContext: UUID?
    @Published private(set) var enabled = false
    @Published private(set) var allowedTypes = Set<String>()
    @Published var error: String?
    let native = Mango9AssistantMediaNative()
    private let api: any Mango9SupervisorClient
    private let account: String
    private var conversation = ""
    private var epoch = UUID()
    private var busy = false
    private var captions = false
    var active: Bool { ["preparing", "uploading", "verifying"].contains(phase) }
    var label: String {
        switch phase {
        case "preparing": return "Preparing file…"
        case "uploading": return "Uploading \(total > 0 ? Int(Double(uploaded) / Double(total) * 100) : 0)%"
        case "verifying": return "Checking file…"
        case "paused": return "Upload paused"
        case "received": return "File received"
        default: return draft == nil ? "" : "Ready to send"
        }
    }
    init(api: any Mango9SupervisorClient) { self.api = api; account = Mango9SupervisorPersistence.key(api.original) }
    private var origin: String { return "https://" + (URL(string: api.original.crmApiBaseUrl)?.host ?? "") }
    private func check(_ stamp: UUID) throws {
        guard stamp == epoch, api.active, !Task.isCancelled else { throw CancellationError() }
    }
    private func location(_ id: String) throws -> URL {
        guard UUID(uuidString: id) != nil else { throw Mango9AssistantMediaContract.invalid() }
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("AssistantUploadDrafts", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var excluded = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try excluded.setResourceValues(values)
        return root.appendingPathComponent(account + "." + id + ".json")
    }
    private func save(_ values: [Mango9AssistantUpload], id: String, sealed: Bool) throws {
        let path = try location(id)
        guard values.count <= 3, Set(values.map(\.id)).count == values.count else { throw Mango9AssistantMediaContract.invalid() }
        for value in values { try Mango9AssistantMediaContract.validate(value) }
        if !values.isEmpty { try JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: values, sealed: sealed)).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
        else if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
    }
    func recoverDraft(from source: String) throws -> String {
        guard api.active else { throw Mango9AssistantMediaContract.invalid() }
        pause()
        return try Mango9AssistantDraftMigration.recover(location(source), source: source, account: account, origin: origin)
    }
    func configure(_ id: String) async {
        previewContext = nil
        pause(); conversation = id; draft = nil; drafts = []; sealed = false; maximum = 1; uploaded = 0; phase = "idle"; error = nil; enabled = false
        let stamp = epoch
        // Cached messages can appear before startup restores the chat's transfer scope.
        // Publish readiness so their previews retry only against the correct chat.
        defer { if stamp == epoch && api.active { previewContext = stamp } }
        do {
            if let data = try? Data(contentsOf: location(id)) {
                let batch = try Mango9AssistantUploadBatch.restore(data, account: account, origin: origin, conversation: id)
                drafts = batch.drafts; draft = batch.drafts.first; sealed = batch.sealed; phase = "paused"
            }
            let cap = try await request("capabilities", stamp: stamp); try check(stamp)
            enabled = cap["version"] as? Int == 1 && cap["enabled"] as? Bool == true && cap["mode"] as? String == "transport" && cap["maxBytes"] as? Int64 == Mango9AssistantMediaContract.maximum && cap["partSize"] as? Int == Mango9AssistantMediaContract.partSize
            allowedTypes = Set(cap["types"] as? [String] ?? []).intersection(Mango9AssistantMediaContract.types)
            captions = cap["textWithAttachment"] as? Bool == true
            maximum = cap["multipleAttachments"] as? Bool == true && cap["maxFiles"] as? Int == 3 ? 3 : 1
        } catch { if stamp == epoch && api.active { self.error = "File transfers are temporarily unavailable. You can still use text chat." } }
    }
    private func request(_ action: String, id: String? = nil, body: [String: Any] = [:], stamp: UUID) async throws -> [String: Any] {
        for attempt in 0...90 {
            try check(stamp)
            var input = body; input["action"] = action; input["conversationId"] = conversation
            if let id { input["id"] = id }
            do { let result = try await api.request("transport", body: input); try check(stamp); return result }
            catch let failure as Mango9SupervisorFailure where failure.status == 409 && failure.code == "media_upload_busy" && attempt < 90 {
                try await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
        throw Mango9AssistantMediaContract.invalid()
    }
    func select(_ url: URL, name: String) async { await selectMany([(url, name)]) }
    func selectMany(_ files: [(URL, String)]) async {
        guard canAdd, api.active, api.hasAIConsent else { return }
        guard !files.isEmpty, files.count + drafts.count <= maximum else { error = "Choose up to \(maximum) files. Nothing was added."; return }
        busy = true; phase = "preparing"; error = nil; let stamp = epoch
        defer { if stamp == epoch { busy = false } }
        var prepared: [Mango9AssistantFile] = []
        do {
            var added: [Mango9AssistantUpload] = []
            for (url, name) in files {
                let file = try await native.prepare(url, name: name); prepared.append(file); try check(stamp)
                guard allowedTypes.contains(file.mime) else { throw Mango9AssistantMediaError(message: "This file format is not supported by your server.") }
                guard !(drafts + added).contains(where: { $0.file.sha256 == file.sha256 && $0.file.size == file.size }) else { throw Mango9AssistantMediaError(message: "This file is already attached. Choose different files.") }
                added.append(Mango9AssistantUpload(id: UUID().uuidString.lowercased(), account: account, origin: origin, conversationId: conversation, file: file))
            }
            let all = drafts + added; try save(all, id: conversation, sealed: false)
            drafts = all; draft = all.first; phase = "selected"; uploaded = 0
        } catch {
            for file in prepared where !drafts.contains(where: { $0.file.uri == file.uri }) { await native.discard(file.uri) }
            if stamp == epoch { self.error = error.localizedDescription; phase = "error" }
        }
    }
    func pause() { epoch = UUID(); busy = false; if let draft { native.pause(draft.id); phase = "paused" } else { phase = "idle" } }
    func remove(id: String? = nil) async {
        guard !drafts.isEmpty, !busy, api.active else { return }
        let stamp = epoch; error = nil; busy = true
        defer { if stamp == epoch { busy = false } }
        let removing = sealed || id == nil ? drafts : drafts.filter { $0.id == id }
        do {
            for item in removing {
                do { _ = try await request("abort", id: item.id, stamp: stamp) }
                catch let e as Mango9SupervisorFailure where e.status == 404 {}
                try check(stamp)
            }
            let kept = drafts.filter { d in !removing.contains(where: { $0.id == d.id }) }
            try save(kept, id: conversation, sealed: false); drafts = kept; draft = kept.first; sealed = false; phase = kept.isEmpty ? "idle" : "selected"; uploaded = 0
            for item in removing { await native.discard(item.file.uri) }
        } catch { if stamp == epoch { self.error = "Removal could not be confirmed. Your files have been kept." } }
    }
    func send(caption: String) async -> (id: String, caption: String)? {
        guard !drafts.isEmpty, enabled, !busy, api.active, api.hasAIConsent else { return nil }
        let stamp = epoch; busy = true; error = nil
        defer { if stamp == epoch { busy = false } }
        do {
            guard drafts.count <= maximum else { throw Mango9AssistantMediaError(message: "Multiple attachments are unavailable on this server. Your files have been kept.") }
            let text = drafts[0].caption ?? caption.trimmingCharacters(in: .whitespacesAndNewlines)
            guard text.utf8.count <= 8000, captions || text.isEmpty else { throw Mango9AssistantMediaError(message: "Send a shorter caption, or send these files without text.") }
            let all = drafts.map { value -> Mango9AssistantUpload in var value = value; value.caption = text; return value }
            try save(all, id: conversation, sealed: true); drafts = all; sealed = true; try check(stamp)
            var completed: Int64 = 0
            for d in all {
            draft = d
            let f = d.file
            let start: [String: Any] = ["name": f.name, "size": f.size, "mime": f.mime, "sha256": f.sha256, "partMD5": f.partMD5]
            _ = try Mango9AssistantMediaContract.status(await request("start", id: d.id, body: start, stamp: stamp), d)
            var status = try Mango9AssistantMediaContract.status(await request("status", id: d.id, stamp: stamp), d)
            if status.state == "uploading" {
                uploaded = try status.uploaded.reduce(completed) { try $0 + Mango9AssistantMediaContract.length(f, $1) }; phase = "uploading"
                // One bounded native part at a time; memory stays independent of the video size.
                for part in 1...f.partMD5.count where !status.uploaded.contains(part) {
                    try check(stamp)
                    let ticket = try Mango9AssistantMediaContract.ticket(await request("parts", id: d.id, body: ["parts": [part]], stamp: stamp), d, part: part)
                    let result = try await native.result { native.uploadLargeVideoPart(f.uri, size: NSNumber(value: f.size), sha256: f.sha256, ticket: ticket as NSDictionary, operationId: d.id, resolver: $0, rejecter: $1) }
                    try check(stamp)
                    guard let result = result as? [String: Any], let code = result["status"] as? Int, (200..<300).contains(code) else { throw Mango9AssistantMediaContract.invalid() }
                    uploaded += try Mango9AssistantMediaContract.length(f, part)
                }
                phase = "verifying"
                status = try Mango9AssistantMediaContract.status(await request("complete", id: d.id, stamp: stamp), d)
            }
            for _ in 0..<90 where ["validating", "completing"].contains(status.state) {
                phase = "verifying"; try await Task.sleep(nanoseconds: 2_000_000_000); try check(stamp)
                status = try Mango9AssistantMediaContract.status(await request("status", id: d.id, stamp: stamp), d)
            }
            guard status.state == "ready" else { throw Mango9AssistantMediaError(message: status.state == "rejected" ? "This file could not be verified. Remove it and choose another." : "Verification is still running. Tap Resume to check its status.") }
            completed += f.size; uploaded = completed
            }
            let first = all[0], ids = all.map(\.id)
            var body: [String: Any] = text.isEmpty ? [:] : ["text": text]
            if ids.count > 1 { body["ids"] = ids }
            let result = try await request("attach", id: first.id, body: body, stamp: stamp)
            guard result["received"] as? Bool == true, result["id"] as? String == first.id, result["conversationId"] as? String == conversation,
                  ids.count == 1 || result["ids"] as? [String] == ids else { throw Mango9AssistantMediaContract.invalid() }
            try save([], id: conversation, sealed: false); drafts = []; draft = nil; sealed = false; phase = "received"
            Task { for item in all { await native.discard(item.file.uri) } }
            return (first.id, text)
        } catch { if stamp == epoch { phase = "error"; self.error = error.localizedDescription }; return nil }
    }
    func resource(_ file: Mango9AssistantAttachment) async throws -> (url: URL, file: Mango9AssistantAttachment) {
        let stamp = epoch
        let raw = try await request("download", id: file.id, stamp: stamp)
        guard raw["id"] as? String == file.id, let value = raw["url"] as? String,
              let expires = raw["expiresAt"] as? Double, expires > Date().timeIntervalSince1970 * 1000,
              expires <= Date().timeIntervalSince1970 * 1000 + 610000,
              let url = Mango9AssistantMediaContract.objectURL(value, id: file.id, origin: origin) else { throw Mango9AssistantMediaContract.invalid() }
        var metadata = raw["file"] as? [String: Any] ?? [:]; metadata["type"] = "attachment"; metadata["id"] = file.id
        let resolved = Mango9AssistantAttachment(raw: metadata) ?? file
        guard let mime = resolved.mime ?? file.mime, Mango9AssistantMediaContract.types.contains(mime), let size = resolved.size ?? file.size, size > 0, size <= Mango9AssistantMediaContract.maximum else { throw Mango9AssistantMediaContract.invalid() }
        return (url, .init(raw: ["type": "attachment", "id": file.id, "name": resolved.name ?? file.name ?? "Attachment", "mime": mime, "size": size])!)
    }
    func download(_ file: Mango9AssistantAttachment) async throws -> URL {
        let stamp = epoch, value = try await resource(file)
        let raw = try await native.result { native.downloadLargeMedia(value.url.absoluteString, identifier: file.id, mime: value.file.mime!, size: NSNumber(value: value.file.size!), resolver: $0, rejecter: $1) }
        try check(stamp)
        guard let string = raw as? String, let url = URL(string: string), url.isFileURL else { throw Mango9AssistantMediaContract.invalid() }
        return url
    }
}
