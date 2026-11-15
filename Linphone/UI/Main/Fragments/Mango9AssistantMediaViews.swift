// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import AVKit
import PhotosUI
import UniformTypeIdentifiers
import QuickLook

struct Mango9AssistantPicked: Identifiable { let id = UUID(); let url: URL }
struct Mango9AssistantShare: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) { }
}
struct Mango9AssistantQuickLook: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url) }
    func makeUIViewController(context: Context) -> QLPreviewController { let vc = QLPreviewController(); vc.dataSource = context.coordinator; return vc }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) { }
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(_ url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

/// Capture never shares the presentation state with upload work. Picker URLs are
/// copied before their temporary/security-scoped lifetime ends.
struct Mango9AssistantPicker: UIViewControllerRepresentable {
    enum Kind: String, Identifiable { case library, photo, video, file; var id: String { rawValue } }
    let kind: Kind
    let complete: (Result<(URL, String), Error>?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(complete) }
    func makeUIViewController(context: Context) -> UIViewController {
        if kind == .file {
            let vc = UIDocumentPickerViewController(forOpeningContentTypes: [.image, .movie, .audio, .pdf, .plainText, .commaSeparatedText], asCopy: true)
            vc.delegate = context.coordinator; return vc
        }
        if kind == .library {
            var configuration = PHPickerConfiguration(); configuration.selectionLimit = 1; configuration.filter = .any(of: [.images, .videos]); configuration.preferredAssetRepresentationMode = .current
            let vc = PHPickerViewController(configuration: configuration); vc.delegate = context.coordinator; return vc
        }
        let vc = UIImagePickerController(); vc.sourceType = .camera
        vc.mediaTypes = [kind == .video ? UTType.movie.identifier : UTType.image.identifier]
        vc.videoQuality = .typeHigh; vc.videoMaximumDuration = 600; vc.cameraCaptureMode = kind == .video ? .video : .photo
        vc.delegate = context.coordinator; return vc
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) { }
    final class Coordinator: NSObject, PHPickerViewControllerDelegate, UIDocumentPickerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let complete: (Result<(URL, String), Error>?) -> Void
        init(_ complete: @escaping (Result<(URL, String), Error>?) -> Void) { self.complete = complete }
        static func copy(_ source: URL) throws -> URL {
            let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
            let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0, size <= Mango9AssistantMediaContract.maximum else { throw Mango9AssistantMediaError(message: "Choose a file up to 500 MB.") }
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ephonix-supervisor-picked", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            let target = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(source.pathExtension)
            try FileManager.default.copyItem(at: source, to: target); return target
        }
        func picked(_ url: URL) {
            let callback = complete
            DispatchQueue.global(qos: .userInitiated).async {
                let result = Result { (try Self.copy(url), url.lastPathComponent) }
                DispatchQueue.main.async { callback(result) }
            }
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { complete(nil) }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { if let first = urls.first { picked(first) } else { complete(nil) } }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { complete(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let url = info[.mediaURL] as? URL { picked(url); return }
            if let image = info[.originalImage] as? UIImage, let bytes = image.jpegData(compressionQuality: 0.95) {
                do {
                    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ephonix-supervisor-picked", isDirectory: true)
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    let url = directory.appendingPathComponent(UUID().uuidString + ".jpg")
                    try bytes.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); complete(.success((url, "Photo.jpg")))
                } catch { complete(.failure(error)) }
            } else { complete(.failure(Mango9AssistantMediaError(message: "Could not open the selected photo."))) }
        }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else { complete(nil); return }
            let type = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) ? UTType.movie.identifier : UTType.image.identifier
            provider.loadFileRepresentation(forTypeIdentifier: type) { [complete] url, error in
                let result: Result<(URL, String), Error>
                if let url { result = Result { (try Self.copy(url), url.lastPathComponent) } }
                else { result = .failure(error ?? Mango9AssistantMediaError(message: "Download this file to your phone and try again.")) }
                DispatchQueue.main.async { complete(result) }
            }
        }
    }
}

@MainActor final class Mango9AssistantAudio: NSObject, ObservableObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    @Published var recording = false
    @Published var paused = false
    @Published var playing = false
    private var playback: AVAudioPlayer?
    @Published var seconds: TimeInterval = 0
    @Published var level: CGFloat = 0
    @Published var error: String?
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var url: URL?
    private var epoch = UUID()
    private var saved: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?
    private var interruption: NSObjectProtocol?
    override init() {
        super.init()
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            if note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                Task { @MainActor in self?.finishRecording() }
            }
        }
    }
    deinit { if let interruption { NotificationCenter.default.removeObserver(interruption) }; timer?.invalidate() }
    func start() async {
        guard recorder == nil, !(await Mango9SupervisorVoice.hasActiveCall()) else { error = "Finish your call before recording."; return }
        stopPlayback(); seconds = 0; level = 0; paused = false
        let stamp = epoch, session = AVAudioSession.sharedInstance()
        let permission = await withCheckedContinuation { continuation in session.requestRecordPermission { continuation.resume(returning: $0) } }
        guard stamp == epoch else { return }
        guard permission else { error = "Allow microphone access in Settings to record a message."; return }
        guard !(await Mango9SupervisorVoice.hasActiveCall()), stamp == epoch else { return }
        do {
            saved = (session.category, session.mode, session.categoryOptions)
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth]); try session.setActive(true)
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ephonix-supervisor-picked", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            let url = directory.appendingPathComponent("Voice recording-" + UUID().uuidString + ".wav")
            let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 48000, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false])
            recorder.delegate = self; recorder.isMeteringEnabled = true
            guard recorder.record(forDuration: 300) else { throw Mango9AssistantMediaError(message: "Recording could not start.") }
            self.url = url; self.recorder = recorder; recording = true; error = nil
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in Task { @MainActor in
                guard let self, let recorder = self.recorder else { return }
                self.seconds = recorder.currentTime; recorder.updateMeters(); self.level = CGFloat(pow(10, recorder.averagePower(forChannel: 0) / 30))
            } }
        } catch { self.error = error.localizedDescription; finishRecording() }
    }
    func togglePause() { guard let recorder else { return }; if paused { paused = !recorder.record() } else { recorder.pause(); paused = true } }
    func finishRecording() {
        epoch = UUID(); recorder?.stop(); recorder = nil; recording = false; timer?.invalidate(); timer = nil
        let old = saved; saved = nil
        Task {
            guard !(await Mango9SupervisorVoice.hasActiveCall()), let old else { return }
            let session = AVAudioSession.sharedInstance(); try? session.setActive(false, options: [.notifyOthersOnDeactivation]); try? session.setCategory(old.0, mode: old.1, options: old.2)
        }
    }
    func result() -> URL? { finishRecording(); return seconds > 0.2 ? url : nil }
    func stopPlayback() { playback?.stop(); playback = nil; playing = false }
    func play() async {
        guard !recording, let url, !(await Mango9SupervisorVoice.hasActiveCall()) else { return }
        if playing { stopPlayback(); return }
        do {
            let session = AVAudioSession.sharedInstance(); try session.setCategory(.playback); try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url); player.delegate = self; playback = player; playing = player.play()
        } catch { self.error = "Couldn’t play this recording. Please record again." }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in stopPlayback() } }
    func discard() { stopPlayback(); finishRecording(); if let url { try? FileManager.default.removeItem(at: url) }; url = nil; seconds = 0; level = 0 }
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) { Task { @MainActor in finishRecording() } }
}

struct Mango9AssistantRecordView: View {
    @StateObject private var recorder = Mango9AssistantAudio()
    @Environment(\.dismiss) private var dismiss
    @State private var inserted = false
    let insert: (URL) -> Void
    private var ready: Bool { !recorder.recording && recorder.seconds > 0.2 }
    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Text("Voice").font(.title3.weight(.semibold)); Spacer()
                Button { dismiss() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Discard recording")
            }
            Text("High quality · WAV · 48 kHz · Up to 5 minutes").font(.caption).foregroundColor(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 4) {
                ForEach(0..<32, id: \.self) { index in
                    Capsule().fill(recorder.recording ? Color.red : Color.mango9Primary)
                        .frame(width: 4, height: recorder.recording ? 5 + recorder.level * CGFloat(12 + (index * 17) % 42) : 5)
                }
            }.frame(height: 60).frame(maxWidth: .infinity).background(Color.mango9Surface).cornerRadius(18).accessibilityHidden(true)
            Text(String(format: "%d:%02d", Int(recorder.seconds) / 60, Int(recorder.seconds) % 60)).font(.system(size: 30, weight: .medium, design: .monospaced))
            Text(recorder.recording ? "Recording…" : recorder.playing ? "Playing preview" : ready ? "Ready to attach" : "Tap Record to start").font(.subheadline).foregroundColor(.secondary)
            if let error = recorder.error { Text(error).font(.caption).foregroundColor(.red) }
            HStack(spacing: 24) {
                if ready { Button { recorder.discard() } label: { Image(systemName: "arrow.counterclockwise").frame(width: 44, height: 44) }.accessibilityLabel("Record again") }
                Button {
                    if recorder.recording { recorder.finishRecording() }
                    else if ready { Task { await recorder.play() } }
                    else { Task { await recorder.start() } }
                } label: {
                    Image(systemName: recorder.recording || recorder.playing ? "stop.fill" : ready ? "play.fill" : "mic.fill")
                        .font(.system(size: 25)).foregroundColor(.white).frame(width: 64, height: 64)
                        .background(recorder.recording ? Color.red : Color.mango9Primary).clipShape(Circle())
                }.accessibilityLabel(recorder.recording ? "Stop recording" : recorder.playing ? "Stop playback" : ready ? "Play recording" : "Start recording")
                if ready { Button { recorder.stopPlayback(); if let url = recorder.result() { inserted = true; insert(url); dismiss() } } label: { Label("Insert", systemImage: "plus") }.buttonStyle(.borderedProminent).tint(.mango9Primary).accessibilityLabel("Insert recording") }
            }
            Text("Review here, then send from your message.").font(.caption).foregroundColor(.secondary)
        }.padding(20).frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.gray100).foregroundColor(.grayMain2c800).tint(.mango9Primary)
            .onDisappear { recorder.stopPlayback(); if !inserted { recorder.discard() } }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in recorder.stopPlayback(); recorder.finishRecording() }
    }
}

struct Mango9AssistantFileView: View {
    let file: Mango9AssistantAttachment
    @ObservedObject var transfer: Mango9AssistantTransfer
    @State private var resolved: Mango9AssistantAttachment?
    @State private var poster: UIImage?
    @State private var source: URL?
    @State private var preview: Mango9AssistantPicked?
    @State private var share: Mango9AssistantPicked?
    @State private var player: AVPlayer?
    @State private var busy = false
    @State private var error: String?
    @State private var revision = 0
    private var info: Mango9AssistantAttachment { resolved ?? file }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            if (info.mime ?? "").hasPrefix("image/") || (info.mime ?? "").hasPrefix("video/") {
                ZStack {
                    RoundedRectangle(cornerRadius: 16).fill(Color.mango9Surface)
                    if let poster { Image(uiImage: poster).resizable().scaledToFit() }
                    if let player { VideoPlayer(player: player) }
                    else { Button { Task { await open() } } label: {
                        Image(systemName: (info.mime ?? "").hasPrefix("video/") ? "play.circle.fill" : poster == nil ? "photo" : "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 36)).foregroundColor(.mango9Primary).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }.accessibilityLabel("Open attachment") }
                    if busy { ProgressView() }
                }.frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                HStack(spacing: 10) {
                    Button { Task { await open() } } label: { Image(systemName: (info.mime ?? "").hasPrefix("audio/") ? "play.circle.fill" : "doc.fill").font(.system(size: 34)).foregroundColor(.mango9Primary).frame(width: 44, height: 44) }.accessibilityLabel("Open attachment")
                    Text(info.name ?? "Attachment").font(.subheadline.weight(.medium)).lineLimit(2)
                    Spacer(minLength: 0)
                    if busy { ProgressView() }
                }
                if let player { VideoPlayer(player: player).frame(height: 68) }
            }
            HStack {
                Text(info.sizeLabel).font(.caption2).foregroundColor(.secondary)
                Spacer()
                Button { Task { await export() } } label: { Image(systemName: "square.and.arrow.up").frame(width: 36, height: 32) }.accessibilityLabel("Save or share attachment").disabled(busy)
                if error != nil { Button { error = nil; revision += 1 } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Retry attachment") }
            }
            if let error { Text(error).font(.caption).foregroundColor(.secondary) }
        }.frame(maxWidth: 300).padding(8).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 16))
            .task(id: "\(transfer.previewContext?.uuidString ?? ""):\(revision)") {
                guard transfer.previewContext != nil else { return }
                error = nil; await loadPreview()
            }
            .sheet(item: $preview) { Mango9AssistantQuickLook(url: $0.url) }
            .sheet(item: $share) { Mango9AssistantShare(url: $0.url) }
            .onDisappear { player?.pause(); player = nil; transfer.native.cancelLargeMediaDownload(file.id) }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in player?.pause(); player = nil }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { _ in player?.pause(); player = nil }
            .onReceive(NotificationCenter.default.publisher(for: .mango9AssistantPlayback)) { notification in if notification.object as? String != file.id { player?.pause(); player = nil } }
    }
    private func loadPreview() async {
        do {
            let resource = try await transfer.resource(file); try Task.checkCancellation(); resolved = resource.file; source = resource.url
            if (resource.file.mime ?? "").hasPrefix("image/"), (resource.file.size ?? 0) <= 8_000_000 {
                let local = try await transfer.download(resource.file)
                let raw = try await transfer.native.result { transfer.native.previewLargeMedia(local.absoluteString, mime: resource.file.mime!, identifier: file.id, resolver: $0, rejecter: $1) }
                try Task.checkCancellation(); if let path = raw as? String, let url = URL(string: path) { poster = UIImage(contentsOfFile: url.path) }
            } else if (resource.file.mime ?? "").hasPrefix("video/") {
                let asset = AVURLAsset(url: resource.url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
                let generator = AVAssetImageGenerator(asset: asset); generator.maximumSize = CGSize(width: 640, height: 640); generator.appliesPreferredTrackTransform = true
                let image: CGImage? = await withCheckedContinuation { continuation in generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: .zero)]) { _, image, _, _, _ in continuation.resume(returning: image) } }
                try Task.checkCancellation(); if let image { poster = UIImage(cgImage: image) }
            }
        } catch { if !Task.isCancelled { self.error = "Preview unavailable. Tap to retry." } }
    }
    private func open() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            if (info.mime ?? "").hasPrefix("audio/") || (info.mime ?? "").hasPrefix("video/") {
                guard !(await Mango9SupervisorVoice.hasActiveCall()) else { error = "Finish your call before playing this recording."; return }
                let resource = try await transfer.resource(file); try Task.checkCancellation()
                NotificationCenter.default.post(name: .mango9AssistantPlayback, object: file.id)
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default); try AVAudioSession.sharedInstance().setActive(true)
                let item = AVPlayerItem(url: resource.url); item.preferredForwardBufferDuration = 5
                player = AVPlayer(playerItem: item); player?.play()
            } else { preview = .init(url: try await transfer.download(file)) }
            error = nil
        } catch { self.error = "Couldn’t open this attachment. Try again." }
    }
    private func export() async {
        guard !busy else { return }; player?.pause(); busy = true; defer { busy = false }
        do { share = .init(url: try await transfer.download(file)); error = nil } catch { self.error = "Couldn’t prepare this file. Try again." }
    }
}
extension Notification.Name { static let mango9AssistantPlayback = Notification.Name("mango9AssistantPlayback") }

enum Mango9AssistantThinking {
    private static var previous = ""
    static func next() -> String {
        let labels = ["Thinking…", "Thinkerizing…", "Pondering…", "Mulling it over…", "Connecting the dots…", "Cogitating…", "Noodling…", "Percolating…", "Brewing ideas…", "Brainstorming…", "Puzzling it out…", "Letting ideas simmer…", "Stirring thoughts…", "Untangling thoughts…", "Turning the gears…", "Thought bubbling…", "Wondering away…", "Giving it a think…"]
        let chosen = labels.filter { $0 != previous }.randomElement() ?? "Thinking…"
        previous = chosen; return chosen
    }
}
struct Mango9AssistantWorking: View {
    let phase: String
    @State private var label = Mango9AssistantThinking.next()
    var body: some View {
        HStack(spacing: 10) { ProgressView().tint(.mango9Primary); Text(label).font(.footnote).foregroundColor(.secondary) }
            .padding(.vertical, 8).accessibilityElement(children: .combine)
    }
}
