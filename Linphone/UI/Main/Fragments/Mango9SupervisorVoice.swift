// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import CallKit
import SwiftUI
import linphonesw

@MainActor final class Mango9SupervisorVoice: ObservableObject {
    @Published private(set) var connecting = false
    @Published private(set) var connected = false
    @Published private(set) var muted = false
    @Published private(set) var speaking = false
    @Published private(set) var level: Double = 0
    @Published var error: String?
    var onHistoryChanged: (() -> Void)?
    var running: Bool { connecting || connected }
    var status: String { connecting ? "Connecting…" : !connected ? (error == nil ? "Ready to connect" : "Voice disconnected") : muted ? "Microphone muted" : speaking ? "Speaking…" : "Listening…" }

    private var socket: URLSessionWebSocketTask?
    private var network: URLSession?
    private var receiver: Task<Void, Never>?
    private var sender: Task<Void, Never>?
    private var watchdog: Task<Void, Never>?
    private var accountCheck: Task<Void, Never>?
    private let captureGate = Mango9SupervisorCaptureGate()
    private static let callObserver = CXCallObserver()
    private var generation = UUID()
    private var playbackGeneration = UUID()
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var tapInstalled = false
    private var queuedFrames = 0
    private var chunks: [Data] = []
    private var queuedBytes = 0
    private var observers: [NSObjectProtocol] = []
    private var savedAudio: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions, Double, TimeInterval)?
    private var ownsAudio = false
    private var engineObserver: NSObjectProtocol?
    private var audioRestartAttempts = 0
    private var consentSession: Mango9Session?

    init() {
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor in self?.interruptAudio(deactivate: false) }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            Task { @MainActor in self?.interruptAudio(deactivate: true) }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.interruptAudio(deactivate: false) }
        })
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    func start(api: any Mango9SupervisorClient, conversationID: String) async {
        guard !running else { return }
        stop(); let stamp = generation; connecting = true; error = nil
        do {
            try api.requireAIConsent()
            consentSession = api.original
            let hasCall = await Self.hasActiveCall()
            guard !hasCall else { throw Mango9SupervisorFailure(status: 0, code: "call_active") }
            try Task.checkCancellation(); guard stamp == generation else { return }
            try api.requireAIConsent()
            let granted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
            guard granted else { throw Mango9SupervisorFailure(status: 0, code: "voice_permission") }
            try Task.checkCancellation(); guard stamp == generation else { return }
            guard api.active else { throw Mango9SupervisorFailure(status: 0, code: "scope_changed") }
            let result = try await api.ticket(conversationID: conversationID)
            guard stamp == generation else { return }
            try Task.checkCancellation()
            guard api.active else { throw Mango9SupervisorFailure(status: 0, code: "scope_changed") }
            try api.requireAIConsent()
            guard let ticket = result.value["ticket"] as? String, ticket.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
                  let path = result.value["websocketPath"] as? String,
                  let input = result.value["inputAudio"] as? [String: Any], input["sampleRate"] as? Int == 16000,
                  input["encoding"] as? String == "pcm_s16le", input["channels"] as? Int == 1,
                  let output = result.value["outputAudio"] as? [String: Any], output["sampleRate"] as? Int == 24000,
                  output["encoding"] as? String == "pcm_s16le", output["channels"] as? Int == 1 else {
                throw Mango9SupervisorFailure(status: 0, code: "voice_audio_format")
            }
            var request = URLRequest(url: try Mango9SupervisorAPI.voiceURL(session: result.session, path: path))
            request.httpShouldHandleCookies = false
            request.setValue("Bearer " + result.session.accessToken, forHTTPHeaderField: "Authorization")
            let config = URLSessionConfiguration.ephemeral
            config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
            let transport = URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
            network = transport
            let ws = transport.webSocketTask(with: request); ws.maximumMessageSize = 524_288; socket = ws; ws.resume()
            try await ws.send(.string(Self.json(["type": "authenticate", "ticket": ticket])))
            guard stamp == generation else { return }
            receiver = Task { [weak self] in
                do {
                    while !Task.isCancelled {
                        let message = try await ws.receive()
                        guard let self, self.generation == stamp else { return }
                        guard api.active else { self.fail("scope_changed"); return }
                        guard api.hasAIConsent else { self.fail("ai_consent_required"); return }
                        try await self.receive(message, stamp: stamp, api: api)
                    }
                } catch { if self?.generation == stamp { self?.fail((error as? Mango9SupervisorFailure)?.code ?? "voice_connection_lost") } }
            }
            watchdog = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                guard !Task.isCancelled, self?.generation == stamp, self?.connecting == true else { return }
                self?.fail("voice_connection_failed")
            }
            accountCheck = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    guard !Task.isCancelled, let self, self.generation == stamp else { return }
                    if !api.active { self.fail("scope_changed"); return }
                    if !api.hasAIConsent { self.fail("ai_consent_required"); return }
                    let inCall = await Self.hasActiveCall()
                    guard self.generation == stamp else { return }
                    if inCall { self.interruptAudio(deactivate: false); return }
                }
            }
        } catch {
            guard stamp == generation else { return }
            stop(); self.error = error is CancellationError ? nil : error.localizedDescription
        }
    }

    private func receive(_ message: URLSessionWebSocketTask.Message, stamp: UUID, api: any Mango9SupervisorClient) async throws {
        let data: Data
        switch message { case .string(let value): data = Data(value.utf8); case .data(let value): data = value; @unknown default: return }
        guard let event = try JSONSerialization.jsonObject(with: data) as? [String: Any], let type = event["type"] as? String else { throw Mango9SupervisorFailure(status: 0, code: "voice_connection_failed") }
        switch type {
        case "ready":
            guard connecting else { return }
            let inCall = await Self.hasActiveCall()
            guard stamp == generation, connecting, !Task.isCancelled else { return }
            guard api.active else { throw Mango9SupervisorFailure(status: 0, code: "scope_changed") }
            try api.requireAIConsent()
            guard !inCall else { throw Mango9SupervisorFailure(status: 0, code: "call_active") }
            try startAudio(stamp: stamp); connecting = false; connected = true; watchdog?.cancel()
        case "audio":
            guard connected, event["sampleRate"] as? Int == 24000, let base64 = event["pcm"] as? String,
                  let pcm = Data(base64Encoded: base64), !pcm.isEmpty, pcm.count % 2 == 0, pcm.count <= 262_144 else { return }
            try play(pcm, stamp: stamp)
        case "interrupted": clearPlayback()
        case "history_saved", "task_settled", "supervisor_result": onHistoryChanged?()
        case "notice": if let code = event["code"] as? String { error = Mango9SupervisorFailure(status: 0, code: code).localizedDescription }
        case "closed":
            let code = event["code"] as? String ?? "voice_connection_lost"
            stop(); if code != "voice_session_complete" { error = Mango9SupervisorFailure(status: 0, code: code).localizedDescription }; onHistoryChanged?()
        default: break
        }
    }

    private func startAudio(stamp: UUID) throws {
        audioRestartAttempts = 0
        let audio = AVAudioSession.sharedInstance()
        savedAudio = (audio.category, audio.mode, audio.categoryOptions, audio.preferredSampleRate, audio.preferredIOBufferDuration)
        ownsAudio = true
        try audio.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try audio.setPreferredSampleRate(48000); try audio.setPreferredIOBufferDuration(0.02); try audio.setActive(true)
        let engine = AVAudioEngine(); self.engine = engine
        // Keep capture and AI playback in this engine: VoiceProcessingIO uses
        // the output reference for acoustic echo cancellation and double-talk.
        try engine.inputNode.setVoiceProcessingEnabled(true)
        guard engine.inputNode.isVoiceProcessingEnabled, engine.outputNode.isVoiceProcessingEnabled else {
            throw Mango9SupervisorFailure(status: 0, code: "voice_audio_format")
        }
        engine.inputNode.isVoiceProcessingBypassed = false
        let inputFormat = engine.inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inputFormat, to: target),
              let playback = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false) else {
            throw Mango9SupervisorFailure(status: 0, code: "voice_audio_format")
        }
        let player = AVAudioPlayerNode(); self.player = player
        engine.attach(player); engine.connect(player, to: engine.mainMixerNode, format: playback)
        engine.inputNode.isVoiceProcessingInputMuted = muted
        let gate = captureGate; gate.reset(enabled: !muted)
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] input, _ in
            let ticket: Mango9SupervisorCaptureGate.Ticket
            switch gate.claim() {
            case .paused: return
            case .overflow(let overflow):
                Task { @MainActor [weak self] in
                    guard let self, self.generation == stamp, gate.didOverflow(overflow) else { return }
                    self.fail("voice_connection_lost")
                }
                return
            case .buffer(let claimed): ticket = claimed
            }
            var handedOff = false
            defer { if !handedOff { gate.finish(ticket) } }
            let capacity = AVAudioFrameCount(ceil(Double(input.frameLength) * 16000 / inputFormat.sampleRate) + 32)
            guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var supplied = false; var failure: NSError?
            converter.convert(to: output, error: &failure) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return input
            }
            guard failure == nil, output.frameLength > 0, let bytes = output.int16ChannelData?[0] else { return }
            let count = Int(output.frameLength)
            let data = Data(bytes: bytes, count: count * 2)
            var sum = 0.0
            for i in 0..<count { let value = Double(bytes[i]) / 32768; sum += value * value }
            let meter = min(1, sqrt(sum / Double(count)) * 5)
            handedOff = true
            Task { @MainActor [weak self] in
                defer { gate.finish(ticket) }
                guard gate.permits(ticket) else { return }
                self?.enqueue(data, meter: meter, stamp: stamp)
            }
        }
        tapInstalled = true; engine.prepare(); try engine.start(); player.play()
        engineObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == stamp, let engine = self.engine else { return }
                let current = engine.inputNode.outputFormat(forBus: 0)
#if DEBUG
                self.audioEventsForTesting.append("engine notification: running=\(engine.isRunning), input=\(current.sampleRate)/\(current.channelCount), expected=\(inputFormat.sampleRate)/\(inputFormat.channelCount)")
#endif
                // Re-negotiate the I/O unit without ending the WebSocket session.
                // The existing converter/tap can be reused only for the same format.
                guard current.sampleRate == inputFormat.sampleRate, current.channelCount == inputFormat.channelCount else {
                    self.interruptAudio(deactivate: true); return
                }
                guard !engine.isRunning else { return }
                let inCall = await Self.hasActiveCall()
                guard self.generation == stamp, self.ownsAudio else { return }
                if inCall { self.interruptAudio(deactivate: false); return }
                // A real session interruption invalidates the generation. Never
                // reactivate a session here or loop indefinitely on a broken route.
                guard self.audioRestartAttempts < 2 else { self.interruptAudio(deactivate: true); return }
                self.audioRestartAttempts += 1
                do {
                    self.captureGate.reset(enabled: !self.muted)
                    engine.prepare(); try engine.start()
                    engine.inputNode.isVoiceProcessingInputMuted = self.muted
                    self.chunks.removeAll(); self.queuedBytes = 0
                    self.clearPlayback()
#if DEBUG
                    self.audioEventsForTesting.append("restarted unchanged audio format: running=\(engine.isRunning)")
#endif
                } catch { self.interruptAudio(deactivate: true) }
            }
        }
    }

    static func hasActiveCall() async -> Bool {
        if callObserver.calls.contains(where: { !$0.hasEnded }) { return true }
        let context = CoreContext.shared
        let sipCall: Bool = await withCheckedContinuation { continuation in
            // A state read must also complete while SIP is stopped or starting.
            // doOnCoreQueue otherwise defers it until a later Core.On event.
            coreQueue.async { continuation.resume(returning: (context.mCore?.callsNb ?? 0) > 0) }
        }
        return sipCall || callObserver.calls.contains(where: { !$0.hasEnded })
    }

    private func enqueue(_ data: Data, meter: Double, stamp: UUID) {
        guard stamp == generation, connected, !muted else { return }
        guard let consentSession, Mango9SupervisorConsent.accepted(consentSession) else { fail("ai_consent_required"); return }
        guard Mango9SessionStore.isActive(consentSession) else { fail("scope_changed"); return }
        level = level * 0.65 + meter * 0.35
        guard queuedBytes + data.count <= 32_768 else { fail("voice_connection_lost"); return }
        for offset in stride(from: 0, to: data.count, by: 8192) { chunks.append(data.subdata(in: offset..<min(offset + 8192, data.count))) }
        queuedBytes += data.count
        guard sender == nil, let ws = socket else { return }
        sender = Task { [weak self] in
            defer { if self?.generation == stamp { self?.sender = nil } }
            do {
                while !Task.isCancelled, let self, self.generation == stamp, self.connected, !self.muted, !self.chunks.isEmpty {
                    guard let session = self.consentSession, Mango9SupervisorConsent.accepted(session) else { self.fail("ai_consent_required"); return }
                    guard Mango9SessionStore.isActive(session) else { self.fail("scope_changed"); return }
                    let chunk = self.chunks.removeFirst(); self.queuedBytes -= chunk.count
                    try await ws.send(.data(chunk))
                }
            } catch { if self?.generation == stamp { self?.fail("voice_connection_lost") } }
        }
    }
    private func play(_ data: Data, stamp: UUID) throws {
        guard let player, let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(data.count / 2)),
              let samples = buffer.floatChannelData?[0] else { return }
        let frames = data.count / 2
        guard queuedFrames + frames <= 480_000 else { throw Mango9SupervisorFailure(status: 0, code: "voice_connection_failed") }
        buffer.frameLength = AVAudioFrameCount(frames)
        data.withUnsafeBytes { raw in
            let bytes = raw.bindMemory(to: UInt8.self)
            for i in 0..<frames { samples[i] = Float(Int16(bitPattern: UInt16(bytes[2*i]) | (UInt16(bytes[2*i+1]) << 8))) / 32768 }
        }
        queuedFrames += frames; speaking = true
        let playbackStamp = playbackGeneration
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == stamp, self.playbackGeneration == playbackStamp else { return }
                self.queuedFrames = max(0, self.queuedFrames - frames); self.speaking = self.queuedFrames > 0
            }
        }
        if !player.isPlaying { player.play() }
    }
    func setMuted(_ value: Bool) {
        guard connected else { return }
        captureGate.reset(enabled: !value)
        muted = value; level = 0; chunks.removeAll(); queuedBytes = 0
        engine?.inputNode.isVoiceProcessingInputMuted = value
        if value, let ws = socket { Task { try? await ws.send(.string(Self.json(["type": "flush"]))) } }
    }
    private func clearPlayback() {
        playbackGeneration = UUID(); player?.stop(); queuedFrames = 0; speaking = false
        if connected { player?.play() }
    }
    private func interruptAudio(deactivate: Bool) { guard running else { return }; stop(deactivate: deactivate); error = Mango9SupervisorFailure(status: 0, code: "voice_interrupted").localizedDescription }
    private func fail(_ code: String) { stop(); error = Mango9SupervisorFailure(status: 0, code: code).localizedDescription; onHistoryChanged?() }
    func stop(deactivate: Bool = true) {
        captureGate.reset(enabled: false)
        generation = UUID(); connecting = false; connected = false; speaking = false; level = 0
        consentSession = nil
        receiver?.cancel(); receiver = nil; sender?.cancel(); sender = nil; watchdog?.cancel(); watchdog = nil; accountCheck?.cancel(); accountCheck = nil
        chunks.removeAll(); queuedBytes = 0; queuedFrames = 0
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver); self.engineObserver = nil }
        if tapInstalled { engine?.inputNode.removeTap(onBus: 0); tapInstalled = false }
        engine?.stop(); player?.stop(); engine = nil; player = nil
        if ownsAudio && deactivate && !Self.callObserver.calls.contains(where: { !$0.hasEnded }) {
            let audio = AVAudioSession.sharedInstance()
            try? audio.setActive(false, options: .notifyOthersOnDeactivation)
            if let savedAudio {
                try? audio.setCategory(savedAudio.0, mode: savedAudio.1, options: savedAudio.2)
                if savedAudio.3 > 0 { try? audio.setPreferredSampleRate(savedAudio.3) }
                if savedAudio.4 > 0 { try? audio.setPreferredIOBufferDuration(savedAudio.4) }
            }
        }
        ownsAudio = false; savedAudio = nil
        let ws = socket; let transport = network; socket = nil; network = nil
        if let ws {
            ws.send(.string(Self.json(["type": "stop"]))) { _ in ws.cancel(with: .normalClosure, reason: nil); transport?.finishTasksAndInvalidate() }
            // Stop remains bounded even when the peer cannot acknowledge the final frame.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { ws.cancel(with: .normalClosure, reason: nil); transport?.invalidateAndCancel() }
        } else { transport?.invalidateAndCancel() }
    }
    private static func json(_ value: [String: String]) -> String { String(data: try! JSONSerialization.data(withJSONObject: value), encoding: .utf8)! }
#if DEBUG
    private(set) var audioEventsForTesting: [String] = []
    /// Physical-device verification only: no ticket, network or microphone samples are sent.
    func startMutedAudioForTesting() throws {
        stop(); muted = true; connecting = true; error = nil
        try startAudio(stamp: generation); connecting = false; connected = true
    }
    var audioStateForTesting: (running: Bool, processing: Bool, muted: Bool) {
        (engine?.isRunning == true, engine?.inputNode.isVoiceProcessingEnabled == true && engine?.outputNode.isVoiceProcessingEnabled == true,
         engine?.inputNode.isVoiceProcessingInputMuted == true)
    }
#endif
}
