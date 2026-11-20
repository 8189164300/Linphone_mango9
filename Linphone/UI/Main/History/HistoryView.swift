/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of Linphone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import SwiftUI
import linphonesw

/// The history is constructed only when requested, never for a keypad keystroke.
struct HistoryView: View {
    @EnvironmentObject var historyListViewModel: HistoryListViewModel
    @Binding var isShowStartCallFragment: Bool
    @Binding var isShowEditContactFragment: Bool
    @Binding var text: String
    @Binding var isShowEditContactFragmentAddress: String

    var body: some View {
        NavigationView {
            HistoryFragment(isShowEditContactFragment: $isShowEditContactFragment,
                text: $text, isShowEditContactFragmentAddress: $isShowEditContactFragmentAddress)
        }.navigationViewStyle(.stack)
    }
}

/// Mirrors the EPhonix keypad geometry. Short windows scroll instead of clipping controls.
struct Mango9KeypadLayout {
    let key: CGFloat
    let scale: CGFloat
    let columnGap: CGFloat = 24
    let rowGap: CGFloat
    let bottom: CGFloat
    let actionGap: CGFloat

    init(width: CGFloat, height: CGFloat, landscape: Bool = false) {
        if landscape {
            bottom = 12; actionGap = 12; rowGap = 6
            let base = min(88, max(44, (width - 48) / 3))
            key = min(base, max(44, floor((height - 24 - 3 * rowGap) / 4)))
            scale = key / base
            return
        }
        let room = max(0, min(1, (height - 440) / 215))
        bottom = (8 + 48 * room).rounded()
        actionGap = (8 + 12 * room).rounded()
        rowGap = (6 + 14 * room).rounded()
        let base = min(88, max(44, (width - 96) / 3))
        key = min(base, max(44, floor((height - 86 - bottom - actionGap - 3 * rowGap) / 5)))
        scale = key / base
    }
    var minimumHeight: CGFloat { 86 + key * 5 + rowGap * 3 + actionGap + bottom }
}

enum Mango9PhonePolicy {
    static func localKeyTone(_ value: String) -> CChar? {
        guard value.utf8.count == 1, let byte = value.utf8.first,
              "0123456789*#".utf8.contains(byte) else { return nil }
        return CChar(byte)
    }
    static func mailboxNumber(_ raw: String, domain: String) -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return "*97" }
        var number = value
        if value.lowercased().hasPrefix("sip:") || value.lowercased().hasPrefix("sips:") {
            let parts = value.dropFirst(value.lowercased().hasPrefix("sips:") ? 5 : 4).split(separator: "@", omittingEmptySubsequences: false)
            guard parts.count == 2, parts[1].lowercased() == domain.lowercased() else { return nil }
            number = String(parts[0])
        }
        guard (1...12).contains(number.count), number.allSatisfy({ "0123456789*#".contains($0) }) else { return nil }
        return number
    }
    static func voicemailStatus(waiting: Bool, voiceCount: Int?) -> (waiting: Bool, count: Int) {
        guard waiting else { return (false, 0) }
        if let voiceCount { return (voiceCount > 0, max(0, voiceCount)) }
        return (waiting, 0)
    }
    static func recallDestination(username: String?, uri: String?, domain: String?, ownerDomain: String?) -> String? {
        // A SIP contact on another domain must retain that domain when recalled.
        domain?.lowercased() == ownerDomain?.lowercased() ? (username ?? uri) : uri
    }
    static func recallNumber(_ remote: String?, local: String?, owner: String, outgoing: Bool) -> String? {
        guard outgoing, local == owner, let remote, !remote.isEmpty else { return nil }
        return remote
    }
}

/// UI state stays on the main queue. SDK/history work is asynchronous and never tied to digits.
final class Mango9PhoneModel: ObservableObject {
    @Published var number = ""
    @Published var lastNumber = ""
    @Published var error = ""
    @Published var starting = false
    let account: Account
    weak var numberField: UITextField?
    private var delegate: CoreDelegate?
    private var owner = ""
    private var lastCompletedCall = ""

    init(account: Account) { self.account = account }

    func start() {
        CoreContext.shared.doOnCoreQueue { [weak self] core in
            guard let self else { return }
            if self.delegate == nil {
                let delegate = CoreDelegateStub(onCallStateChanged: { [weak self] (_, call, state, _) in
                    guard let self, call.callLog?.localAddress?.asStringUriOnly() == self.owner,
                          state == .End || state == .Error else { return }
                    let completed = call.callLog?.callId ?? String(ObjectIdentifier(call).hashValue)
                    guard self.lastCompletedCall != completed else { return }
                    self.lastCompletedCall = completed
                    self.loadRecall()
                    DispatchQueue.main.async { self.number = ""; self.starting = false }
                })
                self.delegate = delegate
                core.addDelegate(delegate: delegate)
            }
            self.owner = self.account.params?.identityAddress?.asStringUriOnly() ?? ""
            self.loadRecall()
        }
    }
    private func loadRecall() {
        let owner = self.owner
        let value = account.callLogs.lazy.compactMap { log in
            Mango9PhonePolicy.recallNumber(Mango9PhonePolicy.recallDestination(username: log.remoteAddress?.username,
                uri: log.remoteAddress?.asStringUriOnly(), domain: log.remoteAddress?.domain,
                ownerDomain: self.account.params?.identityAddress?.domain),
                local: log.localAddress?.asStringUriOnly(), owner: owner, outgoing: log.dir == .Outgoing)
        }.first ?? ""
        DispatchQueue.main.async { [weak self] in self?.lastNumber = value }
    }
    deinit {
        if let delegate { CoreContext.shared.doOnCoreQueue { $0.removeDelegate(delegate: delegate) } }
    }
    func digit(_ value: String) {
        guard number.count < 128 else { return }
        error = ""
        if let field = numberField, field.isFirstResponder { field.insertText(value); number = field.text ?? "" }
        else { number += value }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        if let tone = Mango9PhonePolicy.localKeyTone(value) {
            // Local feedback only. Predial keys must never transmit DTMF to a call.
            CoreContext.shared.doOnCoreQueue { core in core.playDtmf(dtmf: tone, durationMs: 120) }
        }
    }
    func erase() {
        if let field = numberField, field.isFirstResponder { field.deleteBackward(); number = field.text ?? "" }
        else { number = String(number.dropLast()) }
    }
    func recall() { if !lastNumber.isEmpty { number = lastNumber; error = "" } }
    func call(video: Bool = false, mailbox: Bool = false) {
        if number.isEmpty && !mailbox { recall(); return }
        guard !starting else { return }
        starting = true; error = ""
        let entered = number
        CoreContext.shared.doOnCoreQueue { [weak self] core in
            guard let self else { return }
            guard core.defaultAccount.map({ $0 == self.account }) == true else { self.fail("Your calling account changed. Open Calls again."); return }
            guard self.account.state == .Ok || self.account.state == .Refreshing else {
                self.fail("Your phone line is reconnecting. Try again when connected."); return
            }
            let target: Address?
            if mailbox, let identity = self.account.params?.identityAddress,
               let value = Mango9PhonePolicy.mailboxNumber(self.account.params?.voicemailAddress?.asStringUriOnly() ?? "", domain: identity.domain ?? ""),
               let address = identity.clone() {
                try? address.setUsername(newValue: value)
                try? address.setDisplayname(newValue: "")
                target = address
            } else if mailbox { target = nil }
            else { target = core.interpretUrl(url: entered, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) }
            guard let target else { self.fail("Enter a valid phone number or extension."); return }
            TelecomManager.shared.doCallOrJoinConf(address: target, isVideo: video)
            DispatchQueue.main.async {
                if !mailbox { self.lastNumber = entered }
                self.numberField?.resignFirstResponder()
                self.number = ""
                // The call UI takes ownership; a failed attempt must not leave the keypad disabled.
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.starting = false }
            }
        }
    }
    private func fail(_ message: String) { DispatchQueue.main.async { self.error = message; self.starting = false } }
}

struct Mango9PhoneView: View {
    @ObservedObject private var core = CoreContext.shared
    @State private var accountRevision = 0
    var contacts: () -> Void
    var body: some View {
        Group {
            if let account = core.accounts.first(where: { $0.isDefaultAccount }) {
                Mango9AccountKeypad(account: account, contacts: contacts).id(ObjectIdentifier(account)).id(accountRevision)
            } else {
                Text("Connect a phone line to make calls.").foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.white)
            }
        }.onReceive(NotificationCenter.default.publisher(for: Notification.Name("DefaultAccountChanged"))) { _ in accountRevision += 1 }
    }
}

private struct Mango9AccountKeypad: View {
    @ObservedObject var account: AccountModel
    @ObservedObject private var telecom = TelecomManager.shared
    @StateObject private var model: Mango9PhoneModel
    let contacts: () -> Void
    @State private var heldZero = false
    @State private var heldCall = false
    @State private var heldDelete = false
    private let keys = [("1", ""), ("2", "ABC"), ("3", "DEF"), ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
                        ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"), ("*", ""), ("0", "+"), ("#", "")]
    init(account: AccountModel, contacts: @escaping () -> Void) {
        self.account = account; self.contacts = contacts
        _model = StateObject(wrappedValue: Mango9PhoneModel(account: account.account))
    }
    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height && geometry.size.height < 400
            let layout = Mango9KeypadLayout(width: landscape ? geometry.size.width / 2 - 24 : geometry.size.width,
                                           height: geometry.size.height, landscape: landscape)
            ScrollView(.vertical, showsIndicators: false) {
                if landscape {
                    // Keep every digit and call control visible above the footer on short screens.
                    HStack(spacing: 16) {
                        keypad(layout).frame(maxWidth: .infinity)
                        VStack(spacing: 12) {
                            display
                            actions(layout)
                        }.frame(maxWidth: .infinity)
                    }.padding(.horizontal, 12)
                        .frame(minHeight: max(geometry.size.height, layout.key * 4 + layout.rowGap * 3 + 24))
                } else {
                    VStack(spacing: 0) {
                        display.frame(maxWidth: .infinity, maxHeight: .infinity)
                        keypad(layout)
                        actions(layout)
                    }.frame(minHeight: max(geometry.size.height, layout.minimumHeight))
                        .frame(maxWidth: .infinity)
                }
            }
        }.background(Color.white).foregroundColor(Color.orangeMain500).buttonStyle(.plain)
            .accessibilityIdentifier("phone.keypad")
            .onAppear { model.start() }
    }
    private func keypad(_ layout: Mango9KeypadLayout) -> some View {
                    VStack(spacing: layout.rowGap) {
                        ForEach(0..<4, id: \.self) { row in
                            HStack(spacing: layout.columnGap) {
                                ForEach(0..<3, id: \.self) { column in
                                    key(keys[row * 3 + column], diameter: layout.key, scale: layout.scale)
                                }
                            }
                        }
                    }
    }
    private func actions(_ layout: Mango9KeypadLayout) -> some View {
                    HStack(spacing: 32) {
                        Button { model.call(video: true) } label: {
                            Image(systemName: "video.fill").font(.system(size: 26)).frame(width: 54, height: 54).contentShape(Rectangle())
                        }.disabled(model.number.isEmpty || model.starting || telecom.callInProgress)
                            .accessibilityLabel("Start video call").accessibilityIdentifier("dial.video")
                        Button {
                            if !heldCall { model.call() }; heldCall = false
                        } label: {
                            Image(systemName: "phone.fill").font(.system(size: 30)).foregroundColor(.white)
                                .frame(width: layout.key, height: layout.key).background(Color(red: 0.188, green: 0.82, blue: 0.345), in: Circle())
                        }.disabled(model.starting || telecom.callInProgress || (model.number.isEmpty && model.lastNumber.isEmpty))
                            .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in heldCall = true; model.recall() })
                            .accessibilityLabel("Call").accessibilityHint("Tap with an empty keypad or hold to recall the last dialed number. Tap again to call.")
                            .accessibilityIdentifier("dial.call")
                        Button { model.call(mailbox: true) } label: {
                            ZStack(alignment: .topTrailing) {
                                Image(systemName: "recordingtape").font(.system(size: 27)).frame(width: 54, height: 54).contentShape(Rectangle())
                                if account.showMwi {
                                    Text(account.voicemailCount > 0 ? (account.voicemailCount > 99 ? "99+" : String(account.voicemailCount)) : "•")
                                        .font(.system(size: 11, weight: .bold)).foregroundColor(.white).padding(4)
                                        .background(Color.red, in: Capsule()).offset(x: 5, y: 0)
                                }
                            }
                        }.disabled(model.starting || telecom.callInProgress)
                            .accessibilityLabel(account.showMwi ? "Voicemail, new messages" : "Voicemail")
                            .accessibilityValue(String(account.voicemailCount)).accessibilityIdentifier("dial.voicemail")
                    }.padding(.top, layout.actionGap).padding(.bottom, layout.bottom)
    }
    private var display: some View {
        VStack(spacing: 4) {
            HStack {
                Button(action: contacts) { Image(systemName: "person.crop.circle.badge.plus").font(.system(size: 23)).frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .accessibilityLabel("Search contacts").accessibilityIdentifier("dial.contacts")
                Mango9NumberField(model: model).frame(height: 48)
                Button { if !heldDelete { model.erase() }; heldDelete = false } label: {
                    Image(systemName: "delete.left").font(.system(size: 24)).frame(width: 44, height: 44).contentShape(Rectangle())
                }.simultaneousGesture(LongPressGesture().onEnded { _ in heldDelete = true; model.number = "" })
                    .accessibilityLabel("Delete last digit").accessibilityIdentifier("dial.delete")
            }
            if !model.error.isEmpty { Text(model.error).font(.footnote).foregroundColor(.red).multilineTextAlignment(.center) }
        }.padding(.horizontal, 16).padding(.vertical, 8)
    }
    private func key(_ item: (String, String), diameter: CGFloat, scale: CGFloat) -> some View {
        Button {
            if item.0 != "0" || !heldZero { model.digit(item.0) }; heldZero = false
        } label: {
            VStack(spacing: 0) {
                Text(item.0).font(.system(size: 40 * scale, weight: .medium)).monospacedDigit()
                if !item.1.isEmpty || item.0 == "1" {
                    Text(item.1.isEmpty ? " " : item.1).font(.system(size: max(9, 11 * scale), weight: .semibold)).tracking(1.4)
                }
            }.foregroundColor(.black).frame(width: diameter, height: diameter)
        }.buttonStyle(Mango9KeypadButtonStyle()).simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            if item.0 == "0" { heldZero = true; model.digit("+") }
        }).accessibilityLabel(item.0).accessibilityIdentifier("dial.key." + item.0)
    }
}

private struct Mango9NumberField: UIViewRepresentable {
    @ObservedObject var model: Mango9PhoneModel
    func makeCoordinator() -> Coordinator { Coordinator(model) }
    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        field.font = .systemFont(ofSize: 27); field.textColor = .black; field.textAlignment = .center
        field.adjustsFontSizeToFitWidth = true; field.minimumFontSize = 15
        field.inputView = UIView(frame: .zero) // Custom keypad stays visible; Paste/selection still work.
        field.autocorrectionType = .no; field.autocapitalizationType = .none
        field.accessibilityLabel = "Phone number or extension"; field.accessibilityIdentifier = "dial.number"
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        model.numberField = field
        return field
    }
    func updateUIView(_ field: UITextField, context: Context) { if field.text != model.number { field.text = model.number } }
    final class Coordinator: NSObject {
        let model: Mango9PhoneModel
        init(_ model: Mango9PhoneModel) { self.model = model }
        @objc func changed(_ field: UITextField) { model.number = String((field.text ?? "").prefix(128)) }
    }
}

struct Mango9VoicemailSettings: View {
    @ObservedObject var account: AccountModel
    @State private var number = ""
    @State private var error = ""
    @State private var saving = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Voicemail number").font(.system(size: 15, weight: .semibold))
                Spacer()
                TextField("*97", text: $number).keyboardType(.numbersAndPunctuation).multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().frame(maxWidth: 130)
                    .accessibilityIdentifier("settings.voicemail.number")
            }
            Text("Used for voicemail on this phone line. Leave empty to use *97.").font(.footnote).foregroundColor(.secondary)
            if !error.isEmpty { Text(error).font(.footnote).foregroundColor(.red) }
            Button(saving ? "Saving…" : "Save voicemail number") {
                saving = true
                account.saveVoicemail(number) { result in
                    saving = false
                    switch result { case .success(let value): number = value; error = ""
                    case .failure(let failure): error = failure.localizedDescription }
                }
            }.foregroundColor(Color.orangeMain500).disabled(saving).accessibilityIdentifier("settings.voicemail.save")
        }.foregroundColor(.primary).tint(Color.orangeMain500)
            .onAppear { account.loadVoicemail { number = $0 } }
    }
}

private struct Mango9KeypadButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color(red: 236 / 255, green: 238 / 255, blue: 243 / 255) : Color(white: 253 / 255), in: Circle())
            .shadow(color: Color(red: 80 / 255, green: 85 / 255, blue: 105 / 255).opacity(0.09), radius: 16, y: 6)
    }
}
