// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

/// Native counterpart of the centered Supervisor voice mockup. iOS 15+.
/// Present after the voice session is ready. The session owns `isMuted`;
/// tapping the button requests a change, rather than claiming the mic changed.
/// Pass a smoothed, normalized PCM level (0...1) from the existing audio pipeline.
/// This view does not request microphone access, record, or contact the server.
/// Animation APIs: https://developer.apple.com/documentation/swiftui/timelineview
/// Drawing API: https://developer.apple.com/documentation/swiftui/canvas
@available(iOS 15.0, *)
struct Mango9SupervisorVoiceControl: View {
    var isMuted: Bool = false
    var isEnabled: Bool = true
    var status: String = "Listening…"
    var level: Double = 0
    var tint: Color = .mango9Primary
    var diameter: CGFloat = 276
    var showsActions = true
    var canConnect = false
    var onConnect: (() -> Void)?
    /// Requests the new input-mute state. Wire to the real audio capture pipeline.
    /// Muting input must not stop playback of the assistant's voice.
    let onMuteChange: (Bool) -> Void
    /// Stop capture/playback and close the voice transport, then return to chat.
    let onEndVoice: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false
    @State private var startedAt = Date()

    private var shouldAnimate: Bool {
        isEnabled && !isMuted && !reduceMotion && isVisible && scenePhase == .active
    }

    private var normalizedLevel: Double {
        level.isFinite ? min(1, max(0, level)) : 0
    }

    private var ringTint: Color {
        tint
    }

    var body: some View {
        VStack(spacing: 5) {
            GeometryReader { geometry in
                let side = min(geometry.size.width, geometry.size.height)
                ZStack {
                    TimelineView(.animation(minimumInterval: 1 / 30, paused: !shouldAnimate)) { timeline in
                        let time = shouldAnimate ? timeline.date.timeIntervalSince(startedAt) : 0
                        Canvas { context, size in
                            drawRings(context: &context, size: size, time: time)
                        }
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)

                    Button { if canConnect { onConnect?() } else { onMuteChange(!isMuted) } } label: {
                        Image(systemName: isMuted && isEnabled ? "mic.slash" : "mic")
                            .font(.system(size: max(24, side * 30 / 276), weight: .regular))
                            .foregroundColor(isMuted && isEnabled ? .redDanger700 : ringTint)
                            .frame(width: side * 94 / 276, height: side * 94 / 276)
                            .background(Circle().fill(Color.white))
                            .overlay(Circle().strokeBorder(ringTint.opacity(0.85), lineWidth: 1.5))
                            .background(Circle().fill(ringTint.opacity(0.10)).padding(-7))
                            .background(Circle().stroke(ringTint.opacity(0.38), lineWidth: 1.5).padding(-8))
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!isEnabled && !canConnect)
                    .accessibilityIdentifier(canConnect ? "supervisor.voice.connect" : "supervisor.voice.microphone")
                    .accessibilityLabel(canConnect ? "Connect voice" : isMuted ? "Unmute microphone" : "Mute microphone")
                    .accessibilityValue(status)
                    .accessibilityHint(canConnect ? "Starts a voice session using your microphone" : "Double tap to change microphone state")
                }
                .frame(width: side, height: side)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(width: diameter, height: diameter)

            Text(status)
                .font(.subheadline.weight(.medium))
                .foregroundColor(.primary)
                .multilineTextAlignment(.center).padding(.horizontal, 20)
                .padding(.top, 8)

            Text(canConnect ? "Tap the microphone to connect" : isEnabled ? (isMuted ? "Tap to unmute" : "Tap to mute") : " ")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 20)

            if showsActions {
                Mango9SupervisorVoiceActions(isMuted: isMuted, isEnabled: isEnabled, onMuteChange: onMuteChange, onEndVoice: onEndVoice)
                    .padding(.top, 15).padding(.horizontal, 20)
            }
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            startedAt = Date()
            isVisible = true
        }
        .onDisappear { isVisible = false }
    }

    private func drawRings(context: inout GraphicsContext, size: CGSize, time: Double) {
        let side = min(size.width, size.height)
        let scale = side / 276
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        // Reduce Motion disables changes driven by both time and audio level.
        let energy = shouldAnimate ? normalizedLevel : 0
        let active = isEnabled && !isMuted

        let halo = Path(ellipseIn: CGRect(
            x: center.x - 136 * scale, y: center.y - 136 * scale,
            width: 272 * scale, height: 272 * scale
        ))
        let gradient = Gradient(stops: [
            .init(color: .clear, location: 0),
            .init(color: ringTint.opacity(active ? 0.06 : 0.025), location: 0.62),
            .init(color: .clear, location: 1)
        ])
        context.fill(halo, with: .radialGradient(
            gradient, center: center, startRadius: 40 * scale, endRadius: 136 * scale
        ))

        for ring in 0..<3 {
            var path = Path()
            for point in 0...180 {
                let angle = Double(point) / 180 * .pi * 2
                let waveA = sin(angle * 3 - time * 1.45 + Double(ring) * 0.8) * 0.65
                let waveB = sin(angle * 5 + time * 0.9 + Double(ring)) * 0.35
                let amplitude = shouldAnimate ? (2 + energy * 5) * (1 - Double(ring) * 0.12) : 0
                let radius = 112 + Double(ring) * 7 + (waveA + waveB) * amplitude
                let location = CGPoint(
                    x: center.x + CGFloat(cos(angle) * radius) * scale,
                    y: center.y + CGFloat(sin(angle) * radius) * scale
                )
                if point == 0 { path.move(to: location) } else { path.addLine(to: location) }
            }
            path.closeSubpath()
            let opacity = active ? [0.48, 0.24, 0.10][ring] : [0.20, 0.10, 0.05][ring]
            context.stroke(path, with: .color(ringTint.opacity(opacity)),
                           lineWidth: (ring == 0 ? 1.7 : 1) * scale)
        }

        for tick in 0..<48 {
            let angle = Double(tick) / 48 * .pi * 2
            let wave = (sin(angle * 4 - time * 2.1) + sin(angle * 7 + time * 1.3) + 2) / 4
            let length = shouldAnimate ? 4 + wave * (4 + energy * 10) : 4
            var path = Path()
            path.move(to: CGPoint(
                x: center.x + CGFloat(cos(angle) * 87) * scale,
                y: center.y + CGFloat(sin(angle) * 87) * scale
            ))
            path.addLine(to: CGPoint(
                x: center.x + CGFloat(cos(angle) * (87 + length)) * scale,
                y: center.y + CGFloat(sin(angle) * (87 + length)) * scale
            ))
            context.stroke(path, with: .color(ringTint.opacity(active ? 0.3 + wave * 0.4 : 0.18)),
                           style: StrokeStyle(lineWidth: 3 * scale, lineCap: .round))
        }
    }
}

struct Mango9SupervisorVoiceActions: View {
    let isMuted: Bool
    let isEnabled: Bool
    let onMuteChange: (Bool) -> Void
    let onEndVoice: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize { VStack(spacing: 10) { mute; end } }
            else { HStack(spacing: 12) { mute; end } }
        }.frame(maxWidth: 520).frame(maxWidth: .infinity)
    }
    private var mute: some View {
        Button { onMuteChange(!isMuted) } label: {
            Label(isMuted ? "Unmute" : "Mute", systemImage: isMuted ? "mic" : "mic.slash")
                .font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.85)
                .padding(.horizontal, 12).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 48)
                .foregroundColor(.mango9Primary).background(Color.mango9Surface).clipShape(Capsule())
        }.buttonStyle(.plain).disabled(!isEnabled)
            .accessibilityIdentifier("supervisor.voice.mute")
            .accessibilityLabel(isMuted ? "Unmute microphone" : "Mute microphone")
            .accessibilityValue(isMuted ? "Muted" : "Not muted")
    }
    private var end: some View {
        Button(action: onEndVoice) {
            Label("End voice", systemImage: "stop.fill")
                .font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.85)
                .padding(.horizontal, 12).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 48)
                .foregroundColor(.redDanger700).background(Color.redDanger200.opacity(0.35)).clipShape(Capsule())
        }.buttonStyle(.plain).accessibilityIdentifier("supervisor.voice.end")
            .accessibilityLabel("End voice").accessibilityHint("Ends the voice session and returns to chat")
    }
}
