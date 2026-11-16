// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import AVKit

struct Mango9AssistantDraftView: View {
    @ObservedObject var transfer: Mango9AssistantTransfer
    var body: some View {
        VStack(spacing: 4) {
            if !transfer.drafts.isEmpty {
                if transfer.drafts.count == 1, let draft = transfer.drafts.first {
                    Mango9AssistantDraftItem(transfer: transfer, draft: draft)
                } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(transfer.drafts, id: \.id) { draft in
                            Mango9AssistantDraftItem(transfer: transfer, draft: draft).frame(width: 280)
                        }
                    }
                }
                }
                HStack {
                    Text(transfer.label + (transfer.drafts.count > 1 ? " · \(transfer.drafts.count) files" : "")).font(.caption).foregroundColor(.secondary)
                    Spacer()
                    if transfer.sealed && !transfer.active {
                        Button { Task { await transfer.remove() } } label: { Image(systemName: "xmark.circle.fill").frame(width: 44, height: 32) }.accessibilityLabel("Remove upload")
                    }
                }
                if transfer.active { ProgressView(value: Double(transfer.uploaded), total: Double(max(1, transfer.total))).tint(.mango9Primary) }
            } else if transfer.active { HStack { ProgressView(); Text(transfer.label).font(.caption); Spacer() }.padding(8) }
        }.padding(8).background(Color.mango9Surface.opacity(0.7)).cornerRadius(14)
    }
}
private struct Mango9AssistantDraftItem: View {
    @ObservedObject var transfer: Mango9AssistantTransfer
    let draft: Mango9AssistantUpload
    @State private var poster: UIImage?
    @State private var preview: Mango9AssistantPicked?
    @State private var opening = false
    var body: some View {
        HStack(spacing: 10) {
            Button { Task { await open() } } label: {
                if let poster { Image(uiImage: poster).resizable().scaledToFill().frame(width: 48, height: 48).clipped().cornerRadius(10) }
                else { Image(systemName: draft.file.mime.hasPrefix("audio/") ? "play.circle.fill" : "doc.fill").font(.system(size: 30)).frame(width: 48, height: 48) }
            }.accessibilityLabel("Preview " + draft.file.name).disabled(transfer.active || opening)
            VStack(alignment: .leading, spacing: 4) {
                Text(draft.file.name).font(.subheadline.weight(.medium)).lineLimit(2)
                Text(Mango9AssistantAttachment.sizeLabel(draft.file.size)).font(.caption).foregroundColor(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if !transfer.active && !transfer.sealed {
                Button { Task { await transfer.remove(id: draft.id) } } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 22)).foregroundColor(.gray).frame(width: 44, height: 44) }.accessibilityLabel("Remove " + draft.file.name)
            }
        }.frame(minHeight: 56)
        .sheet(item: $preview) { Mango9AssistantQuickLook(url: $0.url) }
        .task(id: draft.id) {
            poster = nil
            guard draft.file.mime.hasPrefix("image/") || draft.file.mime.hasPrefix("video/") else { return }
            let raw = try? await transfer.native.result { transfer.native.previewLargeMedia(draft.file.uri, mime: draft.file.mime, identifier: draft.id, resolver: $0, rejecter: $1) }
            guard !Task.isCancelled, transfer.drafts.contains(where: { $0.id == draft.id }) else { return }
            if let path = raw as? String, let url = URL(string: path) { poster = UIImage(contentsOfFile: url.path) }
        }
    }
    private func open() async {
        guard !opening, !(await Mango9SupervisorVoice.hasActiveCall()) else { return }
        opening = true; defer { opening = false }
        do {
            let raw = try await transfer.native.result { transfer.native.playableLargeMedia(draft.file.uri, mime: draft.file.mime, resolver: $0, rejecter: $1) }
            guard transfer.drafts.contains(where: { $0.id == draft.id }), let path = raw as? String, let url = URL(string: path) else { return }
            if draft.file.mime.hasPrefix("audio/") || draft.file.mime.hasPrefix("video/") { try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default); try AVAudioSession.sharedInstance().setActive(true) }
            preview = .init(url: url)
        } catch { transfer.error = "Couldn’t preview the file. You can still send it." }
    }
}

struct Mango9AssistantReportView: View {
    @ObservedObject var model: Mango9SupervisorModel
    let message: Mango9SupervisorMessage
    @Environment(\.dismiss) private var dismiss
    @State private var reason = "incorrect"
    @State private var details = ""
    @State private var busy = false
    @State private var error: String?
    @State private var reportID = UUID().uuidString.lowercased()
    var body: some View {
        NavigationView {
            Form {
                Picker("Reason", selection: $reason) {
                    Text("Incorrect information").tag("incorrect"); Text("Unsafe or harmful").tag("unsafe")
                    Text("Inappropriate content").tag("inappropriate"); Text("Privacy concern").tag("privacy"); Text("Other").tag("other")
                }
                Section("Details (optional)") { TextEditor(text: $details).frame(minHeight: 90); Text("\(details.utf8.count) / 2000").font(.caption).foregroundColor(.secondary) }
                if let error { Text(error).foregroundColor(.red).font(.footnote) }
                Button {
                    busy = true; error = nil
                    Task { do { try await model.report(message, reason: reason, details: details, reportID: reportID); dismiss() } catch { self.error = "Couldn’t submit the report. Please try again." }; busy = false }
                } label: { HStack { Text("Send report"); Spacer(); if busy { ProgressView() } else { Image(systemName: "paperplane") } } }.disabled(busy || details.utf8.count > 2000)
            }.navigationTitle("Report reply").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Image(systemName: "chevron.left") }.disabled(busy).accessibilityLabel("Back") } }
                .onChange(of: reason) { _ in reportID = UUID().uuidString.lowercased() }
                .onChange(of: details) { _ in reportID = UUID().uuidString.lowercased() }
        }.navigationViewStyle(.stack).tint(.mango9Primary)
    }
}

/// Keep the same top-to-bottom options on iOS and Android, independent of menu placement.
struct Mango9AssistantMenuOrder: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 16.0, *) { content.menuOrder(.fixed) }
        else { content }
    }
}
