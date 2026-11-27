// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import AVKit

/// One owner per visible form; a stale screen cannot unlock a newer editor.
@MainActor final class Mango9CRMNavigationGuard: ObservableObject {
    struct Entry {
        let owner: UUID
        var busy: Bool
        var dirty: Bool
        var confirm: (@escaping () -> Void) -> Void
    }
    private var entries: [Entry] = []
    func update(owner: UUID, busy: Bool, dirty: Bool, confirm: @escaping (@escaping () -> Void) -> Void) {
        let entry = Entry(owner: owner, busy: busy, dirty: dirty, confirm: confirm)
        if let index = entries.firstIndex(where: { $0.owner == owner }) { entries[index] = entry }
        else { entries.append(entry) }
    }
    func remove(_ owner: UUID) { entries.removeAll { $0.owner == owner } }
    func request(_ leave: @escaping () -> Void, preservingDraft: Bool = false) {
        guard let entry = entries.last else { leave(); return }
        guard !entry.busy else { return }
        if entry.dirty && !preservingDraft { entry.confirm(leave) } else { leave() }
    }
}
private struct Mango9CRMNavigationGuardKey: EnvironmentKey {
    static let defaultValue: Mango9CRMNavigationGuard? = nil
}
extension EnvironmentValues {
    var mango9CRMNavigationGuard: Mango9CRMNavigationGuard? {
        get { self[Mango9CRMNavigationGuardKey.self] }
        set { self[Mango9CRMNavigationGuardKey.self] = newValue }
    }
}

/// Native pop gestures must not bypass the explicit Back/discard flow.
private struct Mango9CRMBackGestureLock: UIViewControllerRepresentable {
    let locked: Bool
    final class Controller: UIViewController {
        private final class Lock {
            weak var gesture: UIGestureRecognizer?
            let previous: Bool
            var owners: Set<UUID> = []
            init(_ gesture: UIGestureRecognizer) { self.gesture = gesture; previous = gesture.isEnabled }
        }
        private static var locks: [ObjectIdentifier: Lock] = [:]
        private let owner = UUID()
        private var key: ObjectIdentifier?
        var locked = false
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); apply() }
        func apply() {
            guard locked, let gesture = navigationController?.interactivePopGestureRecognizer else { restore(); return }
            let next = ObjectIdentifier(gesture)
            if key != next { restore(); key = next }
            let lock = Self.locks[next] ?? Lock(gesture)
            lock.owners.insert(owner); Self.locks[next] = lock
            gesture.isEnabled = false
        }
        func restore() {
            guard let key, let lock = Self.locks[key] else { key = nil; return }
            lock.owners.remove(owner)
            if lock.owners.isEmpty { lock.gesture?.isEnabled = lock.previous; Self.locks.removeValue(forKey: key) }
            self.key = nil
        }
    }
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.locked = locked
        controller.apply()
        DispatchQueue.main.async { [weak controller] in controller?.apply() }
    }
    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) { controller.restore() }
}
private struct Mango9CRMFormProtection: ViewModifier {
    @Environment(\.mango9CRMNavigationGuard) private var navigation
    let busy: Bool
    let dirty: Bool
    @State private var owner = UUID()
    @State private var pending: (() -> Void)?
    @State private var confirming = false
    @State private var visible = false
    func body(content: Content) -> some View {
        content
            .disabled(busy)
            .background(Mango9CRMBackGestureLock(locked: busy || dirty).frame(width: 0, height: 0))
            .interactiveDismissDisabled(busy || dirty)
            .onAppear { visible = true; register(busy: busy, dirty: dirty) }
            // onChange can capture the previous ViewModifier value. Register the
            // supplied new values together so Back never sees the previous draft state.
            .onChange(of: [busy, dirty]) { values in register(busy: values[0], dirty: values[1]) }
            .onDisappear { visible = false; navigation?.remove(owner); pending = nil; confirming = false }
            .alert("Discard unsaved changes?", isPresented: $confirming) {
                Button("Discard changes", role: .destructive) { let leave = pending; pending = nil; leave?() }
                Button("Keep editing", role: .cancel) { pending = nil }
            } message: { Text("Your changes have not been saved.") }
    }
    private func register(busy: Bool, dirty: Bool) {
        guard visible else { return }
        navigation?.update(owner: owner, busy: busy, dirty: dirty) { leave in pending = leave; confirming = true }
    }
}
extension View {
    func mango9CRMFormProtection(busy: Bool, dirty: Bool) -> some View {
        modifier(Mango9CRMFormProtection(busy: busy, dirty: dirty))
    }
}

struct Mango9CRMPageHeaderPreference: PreferenceKey {
    static var defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

/// Lives within the CRM navigation stack, leaving its actual footer and safe areas intact.
struct Mango9OpportunityPage<Content: View, Actions: View>: View {
    let title: String
    var backAction: (() -> Void)? = nil
    var backDisabled = false
    @ViewBuilder let actions: () -> Actions
    @ViewBuilder let content: () -> Content
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mango9CRMNavigationGuard) private var navigation
    private var isList: Bool { title == "Opportunities" }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 7) {
                Button {
                    let leave = { if let backAction { backAction() } else { dismiss() } }
                    if let navigation { navigation.request(leave) } else { leave() }
                } label: { Image(systemName: "chevron.left").font(.system(size: 23, weight: .medium)).frame(width: 40, height: 44) }
                    .disabled(backDisabled).accessibilityLabel("Back").accessibilityIdentifier("crm.opportunity.back")
                Text(title).font(.system(size: isList ? 18 : 22, weight: .bold)).lineLimit(1).minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                actions()
            }.foregroundColor(isList ? Mango9OpportunityStyle.blue : .white).padding(.horizontal, 11).frame(height: isList ? 58 : 62)
                .background(isList ? Color.white : Mango9OpportunityStyle.blue).zIndex(1)
            content().frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
        }.background(Mango9OpportunityStyle.background).navigationBarHidden(true)
            .preference(key: Mango9CRMPageHeaderPreference.self, value: !isList)
            .tint(Mango9OpportunityStyle.blue)
            .onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
    }
}

enum Mango9OpportunityStyle {
    static let blue = Color(red: 66/255, green: 83/255, blue: 199/255)
    static let ink = Color(red: 32/255, green: 40/255, blue: 55/255)
    static let muted = Color(red: 96/255, green: 112/255, blue: 135/255)
    static let line = Color(red: 227/255, green: 231/255, blue: 238/255)
    static let background = Color(red: 246/255, green: 247/255, blue: 251/255)
    static func money(_ value: String?, currency: String) -> String {
        guard let value, let decimal = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) else { return "Not entered" }
        let formatter = NumberFormatter(); formatter.locale = Locale(identifier: "en_US"); formatter.numberStyle = .currency
        formatter.currencyCode = currency; formatter.minimumFractionDigits = 0; formatter.maximumFractionDigits = 2
        return formatter.string(from: NSDecimalNumber(decimal: decimal)) ?? currency + " " + value
    }
    static func date(_ value: String?, time: Bool = false) -> String {
        guard let value, !value.isEmpty else { return "Not set" }
        guard let parsed = parseDate(value) else { return value }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US"); formatter.dateFormat = time ? "MMM d · h:mm a" : "MMM d, yyyy"
        return formatter.string(from: parsed)
    }
    static func parseDate(_ value: String?) -> Date? {
        guard let value else { return nil }
        let iso = ISO8601DateFormatter()
        if let date = iso.date(from: value) { return date }
        iso.formatOptions.insert(.withFractionalSeconds)
        if let date = iso.date(from: value) { return date }
        let formatter = DateFormatter(); formatter.calendar = Calendar(identifier: .gregorian); formatter.locale = Locale(identifier: "en_US_POSIX")
        for format in ["yyyy-MM-dd HH:mm:ssXXXXX", "yyyy-MM-dd HH:mm:ss.SSSSSSXXXXX", "yyyy-MM-dd HH:mm:ssX", "yyyy-MM-dd HH:mm:ss.SSSSSSX", "yyyy-MM-dd"] {
            formatter.dateFormat = format
            if let date = formatter.date(from: value) { return date }
        }
        return nil
    }
}

private struct OpportunitySurface: ViewModifier {
    var padding: CGFloat = 15
    func body(content: Content) -> some View {
        content.frame(maxWidth: .infinity, alignment: .leading).padding(padding).background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 15)).overlay(RoundedRectangle(cornerRadius: 15).stroke(Mango9OpportunityStyle.line, lineWidth: 1))
    }
}

extension Mango9Opportunity {
    var revision: String? { raw["revision"] as? String }
    var followupEventID: Int? {
        guard let info = raw["followUp"] as? [String: Any], info["mine"] as? Bool == true,
              let id = Mango9OpportunityAPI.integer(info["eventId"]), id > 0 else { return nil }; return id
    }
    var followupCalendarStatus: String {
        guard raw["onCalendar"] as? Bool == true else { return "Not on Calendar" }
        let info = raw["followUp"] as? [String: Any] ?? [:]
        if info["mine"] as? Bool == true { return "On your Calendar" }
        let name = info["holderName"] as? String ?? ""
        return name.isEmpty ? "On Calendar" : "On " + name + "’s Calendar"
    }
    var contact: [String: Any] { canOpenContact ? raw["contact"] as? [String: Any] ?? [:] : [:] }
    var canOpenContact: Bool { raw["canOpenContact"] as? Bool == true && raw["contact_state"] as? String == "available" }
    var contactID: Int? { canOpenContact ? Mango9OpportunityAPI.integer(raw["contact_id"]) : nil }
    var contactName: String { contact["name"] as? String ?? (raw["contact_state"] as? String == "missing" ? "No linked contact" : "Restricted contact") }
    var isClient: Bool { contact["is_client"] as? Bool == true || Mango9OpportunityAPI.integer(contact["is_client"]) == 1 }
    var canManage: Bool { raw["canManage"] as? Bool == true }
    var archived: Bool { raw["archived_at"] as? String != nil }
    var shares: [[String: Any]] { raw["shares"] as? [[String: Any]] ?? [] }
    var stageAge: Int { max(0, Mango9OpportunityAPI.integer(raw["stage_age_days"]) ?? 0) }
    var weightedValue: String? {
        guard let value, let decimal = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        return NSDecimalNumber(decimal: decimal * Decimal(probability) / 100).stringValue
    }
    var draft: Mango9OpportunityDraft {
        var draft = Mango9OpportunityDraft(); draft.title = title; draft.stage = stage; draft.value = value ?? ""; draft.currency = currency
        draft.ownerID = Mango9OpportunityAPI.integer(raw["owner_id"]) ?? 0; draft.probability = probability; draft.manualProbability = raw["probability_mode"] as? String == "manual"
        draft.hasCloseDate = closeDate != nil; draft.closeDate = Mango9OpportunityStyle.parseDate(closeDate) ?? Date()
        draft.source = source; draft.description = description; draft.nextAction = nextAction; draft.hasNextDue = nextDue != nil
        draft.nextDue = Mango9OpportunityStyle.parseDate(nextDue) ?? Date(); draft.actualValue = actualValue ?? ""
        draft.actualClose = Mango9OpportunityStyle.parseDate(actualClose) ?? Date(); draft.lossReason = lossReason
        return draft
    }
}

extension Notification.Name { static let mango9OpportunityDidChange = Notification.Name("mango9OpportunityDidChange") }

@MainActor final class Mango9OpportunityDetailModel: ObservableObject {
    let id: Int
    let session: Mango9Session?
    let transport: URLSession
    @Published var metadata: Mango9OpportunityMetadata?
    @Published var row: Mango9Opportunity?
    @Published var busy = false
    @Published var error: String?
    @Published var pending: [String: Any]?
    init(id: Int, session: Mango9Session?, metadata: Mango9OpportunityMetadata?, transport: URLSession = Mango9AuthTransport.session) { self.id = id; self.session = session; self.metadata = metadata; self.transport = transport }
    func load() async {
        guard !busy, let session else { return }
        busy = true; defer { busy = false }; error = nil
        do {
            metadata = try Mango9OpportunityMetadata(await Mango9OpportunityAPI.send(session, path: "metadata", transport: transport))
            pending = try Mango9OpportunityCommands.load(session, contact: id, operation: "mutate")
            let result = try Mango9Opportunity(await Mango9OpportunityAPI.send(session, path: String(id), transport: transport))
            guard result.id == id else { throw Mango9OpportunityFailure.invalid }; row = result
        } catch {
            if !Task.isCancelled { self.error = error.localizedDescription }
            row = nil
        }
    }
    func can(_ action: String) -> Bool {
        guard let row, !busy, pending == nil, row.revision != nil else { return false }
        let permission = ["share": "share", "assign": "assign", "archive": "remove", "restore": "remove"][action] ?? "edit"
        return metadata?.permissions[permission] == true && (!row.archived || action == "restore")
            && (!["share", "assign", "archive", "restore", "relink"].contains(action) || row.canManage)
    }
    /// Persist both action and body. A timeout never turns a retry into a second mutation.
    func mutate(_ action: String, values: [String: Any]) async -> Bool {
        guard !busy, let session, let row, let revision = row.revision, pending != nil || can(action) else { return false }
        busy = true; error = nil
        var success = false
        do {
            var values = values
            if pending == nil && action == "share" {
                let latest = try Mango9OpportunityMetadata(await Mango9OpportunityAPI.send(session, path: "metadata", transport: transport))
                metadata = latest
                guard latest.permissions["share"] == true else { throw Mango9OpportunityFailure(status: 403, message: "Sharing is no longer available. Reload this Opportunity.") }
                values["userIds"] = latest.visibleRecipients(values["userIds"] as? [Int] ?? [], ownerID: Mango9OpportunityAPI.integer(row.raw["owner_id"]) ?? 0)
            }
            let command = pending ?? ["action": action, "body": values.merging(["requestId": UUID().uuidString, "revision": revision]) { _, new in new }]
            guard let action = command["action"] as? String, let body = command["body"] as? [String: Any] else { throw Mango9OpportunityFailure.invalid }
            try Mango9OpportunityCommands.save(command, session: session, contact: id, operation: "mutate"); pending = command
            let receipt = try await Mango9OpportunityAPI.send(session, path: "\(id)/\(action)", body: body, transport: transport)
            guard Mango9OpportunityAPI.integer(receipt["id"]) == id, receipt["action"] as? String == action, receipt["saved"] as? Bool == true else { throw Mango9OpportunityFailure.invalid }
            try Mango9OpportunityCommands.save(nil, session: session, contact: id, operation: "mutate"); pending = nil; success = true
            NotificationCenter.default.post(name: .mango9OpportunityDidChange, object: nil)
        } catch {
            if Mango9OpportunityCommands.definiteRejection(error) {
                try? Mango9OpportunityCommands.save(nil, session: session, contact: id, operation: "mutate"); pending = nil
            }
            self.error = error.localizedDescription
        }
        busy = false
        if success { await load() }
        return success
    }
}

struct Mango9OpportunityDetail: View {
    @StateObject private var model: Mango9OpportunityDetailModel
    @State private var tab = "Details"
    @State private var archiveConfirmation = false
    @State private var closingStage: String?
    @State private var actionDestination: String?
    @State private var sharingSheet = false
    @State private var activitySheet = false
    init(id: Int, session: Mango9Session?, metadata: Mango9OpportunityMetadata?) { _model = StateObject(wrappedValue: Mango9OpportunityDetailModel(id: id, session: session, metadata: metadata)) }
    var body: some View {
        Mango9OpportunityPage(title: "Opportunity") {
            Menu {
                if model.can("update") { Button { actionDestination = "edit" } label: { Label("Edit", systemImage: "pencil") }.accessibilityIdentifier("crm.opportunity.menu.edit") }
                if model.can("update") { Button { actionDestination = "followup" } label: { Label("Schedule", systemImage: "calendar") }.accessibilityIdentifier("crm.opportunity.menu.followup") }
                if model.can("share") { Button { sharingSheet = true } label: { Label("Sharing", systemImage: "person.2") }.accessibilityIdentifier("crm.opportunity.menu.sharing") }
                if model.can("archive") || model.can("restore") { Button { archiveConfirmation = true } label: { Label(model.row?.archived == true ? "Restore" : "Archive", systemImage: "archivebox") } }
                Button { Task { await model.load() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            } label: { Image(systemName: "ellipsis").font(.system(size: 20, weight: .semibold)).foregroundColor(Mango9OpportunityStyle.blue).frame(width: 36, height: 36).background(Color.white).clipShape(Circle()).padding(4) }
                .accessibilityLabel("Opportunity actions").accessibilityIdentifier("crm.opportunity.actions")
        } content: {
            ScrollView {
                VStack(spacing: 12) {
                    if let error = model.error { OpportunityError(message: error) { Task { await model.load() } } }
                    if model.pending != nil {
                        VStack(alignment: .leading, spacing: 8) { Text("A change is awaiting confirmation."); Button("Retry original change") { Task { _ = await model.mutate("", values: [:]) } }.disabled(model.busy) }.modifier(OpportunitySurface())
                    }
                    if let row = model.row {
                        summary(row)
                        contact(row)
                        followup(row)
                        tabs
                        tabContent(row)
                    } else if model.busy { ProgressView("Loading Opportunity…").padding(30) }
                }.padding(14).foregroundColor(Mango9OpportunityStyle.ink)
            }.background(Mango9OpportunityStyle.background).refreshable { await model.load() }
        }.background {
            // Menu items disappear when selected; navigation must remain owned
            // by the detail page so the destination survives menu dismissal.
            ZStack {
                NavigationLink(tag: "edit", selection: $actionDestination) { Mango9OpportunityEditPage(model: model) } label: { EmptyView() }
                NavigationLink(tag: "followup", selection: $actionDestination) { Mango9OpportunityFollowupPage(model: model) } label: { EmptyView() }
                NavigationLink(tag: "sharing", selection: $actionDestination) { Mango9OpportunitySharingPage(model: model) } label: { EmptyView() }
            }.hidden().accessibilityHidden(true)
        }.task { await model.load(); if model.row != nil, let session = model.session { await Mango9CRMUnread.shared.readOpportunity(model.id, session: session) } }
             .sheet(isPresented: Binding(get: { closingStage != nil }, set: { if !$0 { closingStage = nil } })) {
                Mango9OpportunityOutcomeSheet(model: model, stage: closingStage ?? "")
            }
            .sheet(isPresented: $sharingSheet) { Mango9OpportunitySharingPage(model: model).modifier(Mango9CRMCompactSheet()) }
            .sheet(isPresented: $activitySheet) { Mango9OpportunityActivityPicker(model: model).modifier(Mango9CRMCompactSheet()) }
            .alert(model.row?.archived == true ? "Restore Opportunity?" : "Archive Opportunity?", isPresented: $archiveConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button(model.row?.archived == true ? "Restore" : "Archive") { Task { _ = await model.mutate(model.row?.archived == true ? "restore" : "archive", values: [:]) } }
            } message: { Text("The linked contact and activity history are preserved.") }
    }
    private func summary(_ row: Mango9Opportunity) -> some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(model.metadata?.stages ?? []) { stage in
                        Button {
                            guard stage.id != row.stage else { return }
                            if stage.classification == "open" { Task { _ = await model.mutate("stage", values: ["stage": stage.id]) } }
                            else { closingStage = stage.id }
                        } label: {
                            if stage.id == row.stage { Label(stage.name, systemImage: "checkmark") } else { Text(stage.name) }
                        }
                    }
                } label: { OpportunityStage(row: row, metadata: model.metadata, dropdown: model.can("update")) }
                    .disabled(!model.can("update")).accessibilityLabel("Change Opportunity status")
                    .accessibilityIdentifier("crm.opportunity.status")
                Spacer(minLength: 0)
                Text(row.archived ? "Archived" : "\(row.stageAge) \(row.stageAge == 1 ? "day" : "days") in status").font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
            }
            Text(row.title).font(Mango9CRMText.recordTitle).fixedSize(horizontal: false, vertical: true)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Mango9OpportunityStyle.money(row.value, currency: row.currency)).font(.system(size: 30, weight: .bold)).minimumScaleFactor(0.7).lineLimit(1)
                Text(row.currency).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
            }
            Rectangle().fill(Mango9OpportunityStyle.line).frame(height: 1).padding(.top, 2)
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], alignment: .leading, spacing: 13) {
                OpportunityFact("Expected close", Mango9OpportunityStyle.date(row.closeDate))
                OpportunityFact("Probability", "\(row.probability)%", subtitle: row.raw["probability_mode"] as? String == "manual" ? "manual" : "from status")
                OpportunityFact("Weighted value", Mango9OpportunityStyle.money(row.weightedValue, currency: row.currency))
                OpportunityFact("Created by", row.raw["created_by_name"] as? String ?? row.owner)
            }
            if let closed = row.actualClose { Text("Closed \(Mango9OpportunityStyle.date(closed))" + (row.actualValue.map { " · " + Mango9OpportunityStyle.money($0, currency: row.currency) } ?? "")).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted) }
            if !row.lossReason.isEmpty { Text(row.lossReason).font(Mango9CRMText.fieldValue) }
        }.modifier(OpportunitySurface())
    }
    @ViewBuilder private func contact(_ row: Mango9Opportunity) -> some View {
        if let id = row.contactID {
            NavigationLink { Mango9LeadDetailFragment(leadId: id, recordKind: row.isClient ? .client : .lead) } label: { contactLabel(row, linked: true) }.buttonStyle(.plain)
        } else { contactLabel(row, linked: false) }
    }
    private func contactLabel(_ row: Mango9Opportunity, linked: Bool) -> some View {
        HStack(spacing: 10) {
            Text(row.canOpenContact ? row.contactName.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined() : "—")
                .font(Mango9CRMText.emphasizedValue).foregroundColor(Mango9OpportunityStyle.blue).frame(width: 37, height: 37)
                .background(Mango9OpportunityStyle.blue.opacity(0.08)).cornerRadius(11)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.contactName).font(Mango9CRMText.recordTitle)
                if row.canOpenContact { Text([row.isClient ? "Client" : "Lead", row.contact["company"] as? String ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if linked { Image(systemName: "chevron.right").font(Mango9CRMText.fieldValue).foregroundColor(Mango9OpportunityStyle.muted) }
        }.modifier(OpportunitySurface(padding: 12)).accessibilityIdentifier("crm.opportunity.contact")
    }
    private func followup(_ row: Mango9Opportunity) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "calendar.badge.clock").foregroundColor(Mango9OpportunityStyle.blue).padding(.top, 3)
            VStack(alignment: .leading, spacing: 3) {
                Text("NEXT FOLLOW-UP · " + Mango9OpportunityStyle.date(row.nextDue, time: true)).font(Mango9CRMText.fieldLabel).foregroundColor(Mango9OpportunityStyle.blue)
                Text(row.nextAction.isEmpty ? "No next action" : row.nextAction).font(Mango9CRMText.emphasizedValue)
                Text(row.followupCalendarStatus).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
                if let date = Mango9OpportunityStyle.parseDate(row.nextDue), row.raw["onCalendar"] as? Bool == true,
                   (row.raw["followUp"] as? [String: Any])?["mine"] as? Bool == true {
                    NavigationLink { Mango9AppointmentsFragment(date: date, presentation: .calendar, initialEventID: row.followupEventID) } label: {
                        Label("Open in Calendar", systemImage: "calendar").font(Mango9CRMText.fieldValue).frame(minHeight: 44)
                    }.accessibilityIdentifier("crm.followup.calendar")
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if model.can("update") { NavigationLink { Mango9OpportunityFollowupPage(model: model) } label: { Image(systemName: "pencil").foregroundStyle(Mango9OpportunityStyle.blue).frame(width: 32, height: 32) }.accessibilityLabel("Edit follow-up") }
        }.padding(13).background(Mango9OpportunityStyle.blue.opacity(0.055)).cornerRadius(13)
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(Mango9OpportunityStyle.blue.opacity(0.12), lineWidth: 1))
    }
    private var tabs: some View {
        HStack(spacing: 2) {
            ForEach(["Details", "Notes", "Activity", "Follow-up"], id: \.self) { name in
                Button { tab = name } label: {
                    Text(name).font(.system(size: 14, weight: tab == name ? .semibold : .regular)).lineLimit(1).minimumScaleFactor(0.9)
                        .frame(maxWidth: .infinity, minHeight: 44).foregroundColor(tab == name ? Mango9OpportunityStyle.blue : Mango9OpportunityStyle.muted)
                        .background(tab == name ? Mango9OpportunityStyle.blue.opacity(0.08) : Color.clear)
                        .overlay(alignment: .bottom) { if tab == name { Mango9OpportunityStyle.blue.frame(height: 2) } }
                }.buttonStyle(.plain).accessibilityAddTraits(tab == name ? .isSelected : []).accessibilityIdentifier("crm.opportunity.tab." + name)
            }
        }.overlay(alignment: .bottom) { Mango9OpportunityStyle.line.frame(height: 0.5) }
    }
    @ViewBuilder private func tabContent(_ row: Mango9Opportunity) -> some View {
        if tab == "Details" {
            VStack(alignment: .leading, spacing: 15) {
                HStack(alignment: .top) { OpportunityFact("Source", row.source.isEmpty ? "Not set" : row.source).frame(maxWidth: .infinity, alignment: .leading); OpportunityFact("Contact group", row.raw["group_name"] as? String ?? "Not set").frame(maxWidth: .infinity, alignment: .leading) }
                OpportunityFact("Description", row.description.isEmpty ? "No description" : row.description)
                OpportunityFact("Owner", row.owner)
                if row.raw["fromSharedLead"] as? Bool == true { Label("Lead shared by " + (row.raw["sharedLeadBy"] as? String ?? "a team member"), systemImage: "person.crop.circle.badge.arrow.right").font(Mango9CRMText.metadata).foregroundStyle(Mango9OpportunityStyle.muted) }
                if let session = model.session { Mango9CRMDocumentLinks(documents: row.raw["documents"] as? [[String: Any]] ?? [], session: session) }
                Divider()
                HStack(alignment: .top) {
                    Image(systemName: "person.fill").foregroundColor(Color(red: 0.55, green: 0.40, blue: 0.02))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Shared with").font(Mango9CRMText.fieldLabel).foregroundColor(Mango9OpportunityStyle.muted)
                        Text(row.shares.compactMap { $0["name"] as? String }.joined(separator: ", ").isEmpty ? "Not shared" : row.shares.compactMap { $0["name"] as? String }.joined(separator: ", ")).font(.system(size: 14, weight: .semibold))
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if model.can("share") { Button { sharingSheet = true } label: { Image(systemName: "pencil").foregroundStyle(Mango9OpportunityStyle.blue).frame(width: 36, height: 36) }.accessibilityLabel("Edit sharing") }
                }
            }.modifier(OpportunitySurface())
        } else if tab == "Activity", model.metadata?.activityVersion == 1, let cid = row.contactID, let session = model.session {
            VStack(spacing: 14) {
                DisclosureGroup("Opportunity changes") { Mango9OpportunityHistoryView(model: model, notes: false, auditOnly: true) }.modifier(OpportunitySurface())
                Mango9CRMActivitySection(contactID: cid, session: session)
            }
        } else if tab == "Notes" || tab == "Activity" { Mango9OpportunityHistoryView(model: model, notes: tab == "Notes").id(tab) }
        else {
            VStack(alignment: .leading, spacing: 12) {
                OpportunityFact("Next action", row.nextAction.isEmpty ? "No next action" : row.nextAction)
                OpportunityFact("Due date", Mango9OpportunityStyle.date(row.nextDue, time: true))
                OpportunityFact("Calendar", row.followupCalendarStatus)
                if model.can("update") { NavigationLink("Edit follow-up") { Mango9OpportunityFollowupPage(model: model) }.font(Mango9CRMText.fieldValue) }
                Divider()
                OpportunityFact("Linked task or appointment", (row.raw["activity"] as? [String: Any])?["restricted"] as? Bool == true ? "Restricted activity" : (row.raw["activity"] as? [String: Any])?["title"] as? String ?? "None linked")
                if model.can("activity"), row.canOpenContact { Button { activitySheet = true } label: { Label("Link an existing activity", systemImage: "link") }.font(Mango9CRMText.fieldValue) }
            }.modifier(OpportunitySurface())
        }
    }
}

private struct OpportunityStage: View {
    let row: Mango9Opportunity
    let metadata: Mango9OpportunityMetadata?
    var dropdown = false
    var body: some View {
        let stage = metadata?.displayStages.first { $0.id == row.stage }
        Mango9CRMStatusBadge(title: stage?.name ?? row.stage, colorHex: stage?.colorHex, dropdown: dropdown)
    }
}
private struct OpportunityFact: View {
    let title: String, value: String
    var subtitle: String = ""
    init(_ title: String, _ value: String, subtitle: String = "") { self.title = title; self.value = value; self.subtitle = subtitle }
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(Mango9CRMText.fieldLabel).foregroundColor(Mango9OpportunityStyle.muted)
            (Text(value.isEmpty ? "Not set" : value).font(Mango9CRMText.fieldValue) + Text(subtitle.isEmpty ? "" : " " + subtitle).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
private struct OpportunityError: View {
    let message: String
    let retry: () -> Void
    var body: some View { VStack(alignment: .leading, spacing: 8) { Text(message).font(.system(size: 13)).foregroundColor(.red); Button("Retry", action: retry) }.modifier(OpportunitySurface()) }
}

/// One idempotent Opportunity update owns the follow-up. Calendar is maintained by
/// the server, including rescheduling, reassignment, archive and removal.
@MainActor final class Mango9OpportunityFollowupModel: ObservableObject {
    let opportunity: Mango9OpportunityDetailModel
    @Published var title = ""
    @Published var start = Date()
    @Published var scheduled = true
    private var baseline: String?
    private var draftKey: String { title + "|" + String(scheduled) + "|" + String(start.timeIntervalSince1970) }
    var dirty: Bool { loaded && baseline != draftKey }
    @Published var busy = false
    @Published var error: String?
    @Published var journal: [String: Any]?
    @Published var loaded = false
    init(_ opportunity: Mango9OpportunityDetailModel) { self.opportunity = opportunity }
    var writable: Bool {
        loaded && opportunity.row?.archived == false && opportunity.row?.revision != nil &&
            opportunity.metadata?.permissions["edit"] == true && journal == nil
    }
    func load() async {
        guard !busy, !loaded, let session = opportunity.session else { return }
        busy = true; defer { busy = false }; error = nil
        do {
            await opportunity.load()
            guard let row = opportunity.row else { throw failure(opportunity.error ?? "The Opportunity is unavailable.") }
            // Preserve uncertain writes from the old Calendar workflow for review;
            // never replay them or remove an existing calendar event automatically.
            journal = try Mango9OpportunityCommands.load(session, contact: row.id, operation: "followup")
            let pendingValues = (opportunity.pending?["body"] as? [String: Any])?["values"] as? [String: Any]
            title = pendingValues?["next_action"] as? String ?? row.nextAction
            let due = pendingValues == nil ? row.nextDue : pendingValues?["next_due_at"] as? String
            scheduled = pendingValues == nil ? true : due != nil
            start = Mango9OpportunityStyle.parseDate(due) ?? Date().addingTimeInterval(3600)
            baseline = draftKey
            loaded = true
            if journal != nil { error = "A previous Calendar save needs review. Check Calendar before starting a server-managed follow-up. Existing events will be kept." }
            else if !writable { error = "You do not have permission to edit this follow-up." }
        } catch { self.error = error.localizedDescription }
    }
    func save() async -> Bool {
        guard !busy, writable, let row = opportunity.row, let meta = opportunity.metadata else { return false }
        busy = true; error = nil; defer { busy = false }
        do {
            if let pending = opportunity.pending {
                guard pending["action"] as? String == "update" else { throw failure("Finish the previous Opportunity change first.") }
            }
            var draft = row.draft
            draft.nextAction = title.trimmingCharacters(in: .whitespacesAndNewlines)
            draft.hasNextDue = scheduled; draft.nextDue = start
            // The original request/revision is retained by mutate after a timeout.
            let values = opportunity.pending == nil ? try draft.values(metadata: meta, existingStage: row.stage) : [:]
            guard await opportunity.mutate("update", values: ["values": values]) else {
                if opportunity.pending == nil { loaded = false }
                throw failure(opportunity.error ?? "The follow-up save could not be confirmed. Retry to check the same request.")
            }
            NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func clearAfterReview() {
        do {
            guard let session = opportunity.session else { throw Mango9OpportunityFailure.accountChanged }
            guard opportunity.pending == nil else { throw failure("Finish the pending Opportunity change before clearing this older Calendar request.") }
            try Mango9OpportunityCommands.save(nil, session: session, contact: opportunity.id, operation: "followup")
            journal = nil; loaded = false; error = nil
        } catch { self.error = error.localizedDescription }
    }
    private func failure(_ message: String) -> Mango9OpportunityFailure { .init(status: 0, message: message) }
}

struct Mango9OpportunityFollowupPage: View {
    @StateObject private var scheduler: Mango9OpportunityFollowupModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false
    init(model: Mango9OpportunityDetailModel) { _scheduler = StateObject(wrappedValue: Mango9OpportunityFollowupModel(model)) }
    var body: some View {
        Mango9OpportunityPage(title: "Follow-up", backDisabled: scheduler.busy, actions: { EmptyView() }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("The Opportunity manages its Calendar follow-up. Open opportunities with a next action and due time get a 30-minute entry when the responsible user has Calendar access.").font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
                    if let error = scheduler.error { Text(error).font(Mango9CRMText.metadata).foregroundColor(.red) }
                    if !scheduler.loaded {
                        if scheduler.error == nil { ProgressView("Loading follow-up…") }
                        else { Button("Retry") { Task { await scheduler.load() } } }
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Next action").font(Mango9CRMText.fieldLabel)
                        TextField("Next action", text: $scheduler.title).accessibilityIdentifier("crm.followup.title")
                        Toggle("Schedule in Calendar", isOn: $scheduler.scheduled)
                        if scheduler.scheduled { DatePicker("Due", selection: $scheduler.start).accessibilityIdentifier("crm.followup.starts") }
                        Text("Times shown in \(TimeZone.current.identifier)").font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
                    }.modifier(OpportunitySurface()).disabled(scheduler.busy || !scheduler.writable || scheduler.opportunity.pending != nil)
                    if scheduler.journal != nil {
                        NavigationLink("Open Calendar") { Mango9AppointmentsFragment(date: scheduler.start, presentation: .calendar) }
                        Button("I checked Calendar — continue") { confirmReset = true }.font(Mango9CRMText.fieldValue)
                    }
                    Button(scheduler.busy ? "Saving…" : scheduler.opportunity.pending == nil ? "Save follow-up" : "Retry save") {
                        Task { if await scheduler.save() { dismiss() } }
                    }.buttonStyle(OpportunityPrimaryButton()).disabled(scheduler.busy || !scheduler.writable)
                        .accessibilityIdentifier("crm.followup.save")
                }.padding(14)
            }
        }.task { await scheduler.load() }
            .mango9CRMFormProtection(busy: scheduler.busy, dirty: scheduler.dirty)
            .alert("Keep the existing Calendar events?", isPresented: $confirmReset) {
                Button("Cancel", role: .cancel) {}
                Button("I reviewed the events") { scheduler.clearAfterReview(); Task { await scheduler.load() } }
            } message: { Text("This clears only the older pending request. Existing Calendar events are kept. Review any older appointment separately to avoid duplicate reminders.") }
    }
}

struct Mango9OpportunityEditPage: View {
    @ObservedObject var model: Mango9OpportunityDetailModel
    var followupOnly = false
    @State private var draft = Mango9OpportunityDraft()
    @State private var baseline: Mango9OpportunityDraft?
    @State private var loaded = false
    @State private var loadedSource: String?
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Mango9OpportunityPage(title: followupOnly ? "Follow-up" : "Edit Opportunity", backDisabled: model.busy, actions: { EmptyView() }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let error = error ?? model.error {
                        Text(error).font(.system(size: 14)).foregroundColor(.red)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                            .background(Color.red.opacity(0.05)).cornerRadius(12)
                    }
                    if !followupOnly {
                        section("Opportunity details") {
                            field("Title") { TextField("Opportunity title", text: $draft.title) }
                            choiceField("Status") {
                                Picker("Status", selection: $draft.stage) {
                                    ForEach(model.metadata?.displayStages.filter { stage in stage.id == model.row?.stage || model.metadata?.stages.contains { $0.id == stage.id } == true } ?? []) { Text($0.name).tag($0.id) }
                                }.pickerStyle(.menu)
                            }
                            field("Value") { TextField("Not entered", text: $draft.value).keyboardType(.decimalPad) }
                            choiceField("Currency") {
                                Picker("Currency", selection: $draft.currency) { ForEach(Mango9OpportunityDraft.currencies, id: \.self) { Text($0).tag($0) } }.pickerStyle(.menu)
                            }
                            Toggle("Expected close date", isOn: $draft.hasCloseDate).font(.system(size: 15))
                            if draft.hasCloseDate { DatePicker("Expected close", selection: $draft.closeDate, displayedComponents: .date).font(.system(size: 14)) }
                        }
                        section("Additional details") {
                            Toggle("Set probability manually", isOn: $draft.manualProbability).font(.system(size: 15))
                            if draft.manualProbability { Stepper("Probability: \(draft.probability)%", value: $draft.probability, in: 0...100).font(.system(size: 15)) }
                            else if let selected = model.metadata?.displayStages.first(where: { $0.id == draft.stage }) {
                                Text("\(selected.probability)% from status").font(.system(size: 13)).foregroundColor(Mango9OpportunityStyle.muted)
                            }
                            field("Source") { TextField("Source", text: $draft.source) }
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Description").font(.system(size: 14, weight: .medium)).foregroundColor(Mango9OpportunityStyle.muted)
                                TextEditor(text: $draft.description).font(.system(size: 16)).frame(minHeight: 100)
                                    .padding(6).background(Mango9OpportunityStyle.background).cornerRadius(10)
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mango9OpportunityStyle.line, lineWidth: 1))
                            }
                        }
                        if let selected = model.metadata?.displayStages.first(where: { $0.id == draft.stage }), selected.classification != "open" {
                            section("Closing details") {
                                Mango9CRMStatusBadge(title: selected.name, colorHex: selected.colorHex)
                                DatePicker("Actual close date", selection: $draft.actualClose, in: ...Date(), displayedComponents: .date).font(.system(size: 14))
                                field("Actual value") { TextField("Not entered", text: $draft.actualValue).keyboardType(.decimalPad) }
                                if selected.classification == "lost" { field("Loss reason") { TextField("Required", text: $draft.lossReason) } }
                            }
                        }
                    }
                    Label("Schedule or change the calendar event from Follow-up on the Opportunity.", systemImage: "calendar")
                        .font(.system(size: 13)).foregroundColor(Mango9OpportunityStyle.muted)
                        .padding(.horizontal, 4).fixedSize(horizontal: false, vertical: true)
                    Button(model.busy ? "Saving…" : "Save changes") { Task { await save() } }
                        .buttonStyle(OpportunityPrimaryButton()).disabled(!loaded || !model.can("update"))
                }.padding(14).foregroundColor(Mango9OpportunityStyle.ink)
            }
        }.task { guard !loaded, let row = model.row else { return }; draft = row.draft; baseline = draft; loaded = true }
        .mango9CRMFormProtection(busy: model.busy, dirty: loaded && baseline != draft)
    }
    private func section<V: View>(_ title: String, @ViewBuilder content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 17, weight: .semibold))
            Divider().overlay(Mango9OpportunityStyle.line)
            content()
        }.modifier(OpportunitySurface())
    }
    private func field<V: View>(_ title: String, @ViewBuilder content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundColor(Mango9OpportunityStyle.muted)
            content().font(.system(size: 16)).frame(maxWidth: .infinity, minHeight: 24, alignment: .leading)
                .padding(12).background(Mango9OpportunityStyle.background).cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mango9OpportunityStyle.line, lineWidth: 1))
        }
    }
    private func choiceField<V: View>(_ title: String, @ViewBuilder content: () -> V) -> some View {
        HStack(spacing: 12) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundColor(Mango9OpportunityStyle.muted)
                .lineLimit(1).fixedSize().frame(width: 88, alignment: .leading)
                .accessibilityIdentifier("crm.opportunity.label." + title)
            content().labelsHidden().font(.system(size: 15)).frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
                .accessibilityIdentifier("crm.opportunity.choice." + title)
                .padding(.horizontal, 11).background(Mango9OpportunityStyle.background).cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mango9OpportunityStyle.line, lineWidth: 1))
        }
    }
    private func save() async {
        guard let metadata = model.metadata else { return }
        error = nil
        do { let values = try draft.values(metadata: metadata, existingStage: model.row?.stage); if await model.mutate("update", values: ["values": values]) { dismiss() } }
        catch { self.error = error.localizedDescription }
    }
}

private struct OpportunityPrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label.font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 46).foregroundColor(.white).background(Mango9OpportunityStyle.blue.opacity(configuration.isPressed ? 0.75 : 1)).cornerRadius(12) }
}

struct Mango9OpportunitySharingPage: View {
    @ObservedObject var model: Mango9OpportunityDetailModel
    @State private var selected = Set<Int>()
    @State private var baseline = Set<Int>()
    @State private var loaded = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Mango9OpportunityPage(title: "Sharing", backDisabled: model.busy, actions: { EmptyView() }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let error = model.error { Text(error).foregroundColor(.red) }
                    Text("Shared by you").font(.headline)
                    if !loaded && model.error == nil { ProgressView("Loading people…") }
                    if !loaded && model.error != nil { Button("Retry") { Task { await loadChoices() } } }
                    if loaded && model.metadata?.owners.filter({ $0.id != Mango9OpportunityAPI.integer(model.row?.raw["owner_id"]) }).isEmpty == true { Text("No people are available to share with.").foregroundColor(Mango9OpportunityStyle.muted) }
                    ForEach(loaded ? (model.metadata?.owners.filter { $0.id != Mango9OpportunityAPI.integer(model.row?.raw["owner_id"]) } ?? []) : []) { owner in
                        Toggle(owner.name, isOn: Binding(get: { selected.contains(owner.id) }, set: { if $0 { selected.insert(owner.id) } else { selected.remove(owner.id) } })).padding(.vertical, 8)
                    }
                    Button("Save sharing") { Task { if await model.mutate("share", values: ["userIds": selected.sorted()]) { dismiss() } } }.buttonStyle(OpportunityPrimaryButton()).disabled(!loaded || !model.can("share"))
                }.padding(14)
            }
        }.task { await loadChoices() }
        .mango9CRMFormProtection(busy: model.busy, dirty: loaded && selected != baseline)
    }
    private func loadChoices() async {
        guard !loaded else { return }
        await model.load()
        guard let row = model.row, let meta = model.metadata else { return }
        let ids = row.shares.filter { Mango9OpportunityAPI.integer($0["shared_by"]) == meta.userID }.compactMap { Mango9OpportunityAPI.integer($0["id"]) }
        selected = Set(meta.visibleRecipients(ids, ownerID: Mango9OpportunityAPI.integer(row.raw["owner_id"]) ?? 0))
        baseline = selected
        loaded = true
    }
}

struct Mango9OpportunityHistoryView: View {
    @ObservedObject var model: Mango9OpportunityDetailModel
    let notes: Bool
    var auditOnly = false
    @State private var rows: [[String: Any]] = []
    @State private var source = "fast"
    @State private var sources = ["fast", "opportunity"]
    @State private var cursor: String?
    @State private var page = 0
    @State private var hasMore = false
    @State private var busy = false
    @State private var error: String?
    @State private var warnings: [String] = []
    @State private var text = ""
    @State private var generation = UUID()
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if !notes && !auditOnly { Picker("Show activity", selection: $source) { ForEach(sources, id: \.self) { Text(["fast": "Recent activity", "all": "All activity", "opportunity": "Opportunity", "note": "Contact notes", "sms": "SMS", "calls": "Calls"][$0] ?? $0).tag($0) } }.pickerStyle(.menu) }
            if let error { Text(error).foregroundColor(.red); Button("Retry") { Task { await load(reset: true) } } }
            ForEach(warnings, id: \.self) { Text($0).font(.system(size: 14)).foregroundColor(.secondary) }
            ForEach(rows.map(Mango9CRMActivityRow.init)) { wrapped in
                let row = wrapped.raw
                VStack(alignment: .leading, spacing: 5) {
                    Text(eventTitle(row)).font(Mango9CRMText.emphasizedValue)
                    Text(eventStamp(row)).font(Mango9CRMText.metadata).foregroundColor(Mango9OpportunityStyle.muted)
                    if !eventText(row).isEmpty { Text(eventText(row)).font(Mango9CRMText.fieldValue).textSelection(.enabled) }
                    Divider().padding(.top, 8)
                }
            }
            if busy { ProgressView() }
            if !busy && rows.isEmpty && error == nil { Text(notes ? "No notes yet." : "No activity yet.").font(Mango9CRMText.fieldValue).foregroundColor(Mango9OpportunityStyle.muted) }
            if hasMore { Button("Load more") { Task { await load(reset: false) } }.disabled(busy) }
            if notes && model.can("note") {
                Text("Add a note").font(.system(size: 14, weight: .medium))
                TextEditor(text: $text).font(Mango9CRMText.fieldValue).frame(minHeight: 85).overlay(RoundedRectangle(cornerRadius: 10).stroke(Mango9OpportunityStyle.line)).accessibilityLabel("Add a note")
                Button("Add note") { Task { if await model.mutate("note", values: ["text": text]) { text = ""; await load(reset: true) } } }.buttonStyle(OpportunityPrimaryButton()).disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || text.count > 5000 || model.busy)
            }
        }.modifier(OpportunitySurface()).task(id: source) { await load(reset: true) }
            .onDisappear { generation = UUID() }
    }
    private func load(reset: Bool) async {
        guard let session = model.session else { return }
        if busy && !reset { return }
        let request = UUID(); generation = request; busy = true; error = nil
        if reset { rows = []; cursor = nil; page = 0; hasMore = false }
        defer { if generation == request { busy = false } }
        do {
            var query: [URLQueryItem] = [.init(name: "kind", value: notes ? "notes" : "timeline")]
            if notes { query.append(.init(name: "page", value: String(page + 1))) }
            else { query.append(.init(name: "source", value: auditOnly ? "opportunity" : source)); if let cursor { query.append(.init(name: "cursor", value: cursor)) } }
            let data = try await Mango9OpportunityAPI.send(session, path: "\(model.id)/history", query: query)
            guard generation == request, !Task.isCancelled else { return }
            guard let incoming = data["items"] as? [[String: Any]] else { throw Mango9OpportunityFailure.invalid }
            rows.append(contentsOf: incoming); page += 1
            if notes { hasMore = (data["pagination"] as? [String: Any])?["hasMore"] as? Bool == true }
            else { cursor = data["next"] as? String; hasMore = cursor != nil; sources = ["fast"] + (data["sources"] as? [String] ?? ["opportunity"]).filter { $0 != "fast" }; warnings = data["warnings"] as? [String] ?? [] }
        } catch { if generation == request && !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func eventTitle(_ row: [String: Any]) -> String {
        let data = row["data"] as? [String: Any] ?? [:], details = row["details"] as? [String: Any] ?? [:]
        if let action = row["action"] as? String {
            if ["stage", "stage_changed", "stage_remapped"].contains(action) {
                let stage = details["to"] as? String ?? ""
                return "Status: " + (model.metadata?.displayStages.first { $0.id == stage }?.name ?? stage)
            }
            return ["created": "Opportunity created", "updated": "Opportunity updated", "assigned": "Owner updated", "shared": "Sharing updated", "removed": "Opportunity archived", "restored": "Opportunity restored", "note": "Note added", "relink": "Linked contact changed", "activity": "Follow-up link updated", "won": "Opportunity won", "lost": "Opportunity lost", "reopened": "Opportunity reopened"][action] ?? action.replacingOccurrences(of: "_", with: " ").capitalized
        }
        return data["title"] as? String ?? ["note": "Contact note", "sms": "SMS", "human_call": "Call", "ai_call": "AI call"][row["type"] as? String ?? ""] ?? "Activity"
    }
    private func eventText(_ row: [String: Any]) -> String { (row["details"] as? [String: Any])?["text"] as? String ?? (row["data"] as? [String: Any])?["text"] as? String ?? "" }
    private func eventStamp(_ row: [String: Any]) -> String {
        let actor = row["actor"] as? String ?? (row["data"] as? [String: Any])?["actor"] as? String ?? ""
        let value = row["created_at"] as? String ?? (row["at"] as? NSNumber).map { ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: $0.doubleValue)) }
        return [actor, Mango9OpportunityStyle.date(value, time: true)].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

struct Mango9OpportunityActivityPicker: View {
    @ObservedObject var model: Mango9OpportunityDetailModel
    @State private var rows: [[String: Any]] = []
    @State private var kind = "tasks"
    @State private var page = 0
    @State private var more = false
    @State private var error: String?
    @State private var busy = false
    @State private var generation = UUID()
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Mango9OpportunityPage(title: "Link activity", backDisabled: model.busy, actions: { EmptyView() }) {
            List {
                Picker("Activity", selection: $kind) { Text("Tasks").tag("tasks"); Text("Appointments").tag("appointments") }.pickerStyle(.segmented)
                if let error { Text(error).foregroundColor(.red); Button("Retry") { Task { await load(reset: true) } } }
                if let error = model.error { Text(error).foregroundColor(.red) }
                ForEach(rows.map(Mango9CRMActivityRow.init)) { wrapped in
                let row = wrapped.raw
                    Button { if let key = row["key"] as? String { Task { if await model.mutate("activity", values: ["activityKey": key]) { dismiss() } } } } label: {
                        VStack(alignment: .leading, spacing: 5) { Text(row["title"] as? String ?? "Activity"); Text(row["start_local"] as? String ?? "No date").font(.caption).foregroundColor(.secondary) }
                    }.disabled(model.busy)
                }
                if busy { ProgressView() }
                if rows.isEmpty && !busy && error == nil { Text("No matching activities for this contact.").foregroundColor(.secondary) }
                if more { Button("Load more") { Task { await load(reset: false) } }.disabled(busy) }
            }.listStyle(.plain)
        }.task(id: kind) { await load(reset: true) }
        .mango9CRMFormProtection(busy: model.busy, dirty: false)
    }
    private func load(reset: Bool) async {
        guard let session = model.session else { return }
        let request = UUID(); generation = request; busy = true; error = nil
        if reset { rows = []; page = 0; more = false }
        defer { if generation == request { busy = false } }
        do {
            let result = try await Mango9OpportunityAPI.send(session, path: "\(model.id)/activities", query: [.init(name: "kind", value: kind), .init(name: "page", value: String(page + 1))])
            guard generation == request, !Task.isCancelled else { return }
            guard let incoming = result["items"] as? [[String: Any]] else { throw Mango9OpportunityFailure.invalid }
            rows.append(contentsOf: incoming); page += 1; more = (result["pagination"] as? [String: Any])?["hasMore"] as? Bool == true
        } catch { if generation == request && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

struct Mango9OpportunityWorkspace: View {
    @ObservedObject private var unseen = Mango9CRMUnread.shared
    var contact: Mango9Lead? = nil
    private let preloaded: Mango9OpportunityOverview?
    @State private var initialLoad = true
    @State private var loadedQuery: String?
    @State private var session: Mango9Session?
    @State private var metadata: Mango9OpportunityMetadata?
    @State private var rows: [Mango9Opportunity] = []
    @State private var summary: [[String: Any]] = []
    @State private var search = ""
    @State private var stage = ""
    @State private var filters = Mango9OpportunityFilters()
    @State private var showFilters = false
    @State private var dashboardFilter: String
    @State private var showCreate = false
    @State private var page = 0
    @State private var more = false
    @State private var busy = false
    @State private var total = 0
    @State private var error: String?
    @State private var generation = UUID()
    init(contact: Mango9Lead? = nil, preloaded: Mango9OpportunityOverview? = nil, dashboardFilter: String = "") {
        _dashboardFilter = State(initialValue: dashboardFilter)
        self.contact = contact
        let current = Mango9SessionStore.load()
        let preference: Mango9OpportunityPreference = dashboardFilter.isEmpty ? (current.map { Mango9OpportunityPreference.load($0, contact: contact?.id) } ?? .init()) : .init()
        _stage = State(initialValue: preference.stage)
        _filters = State(initialValue: preference.filters)
        let page = dashboardFilter.isEmpty && contact == nil && preference == Mango9OpportunityPreference() && current.map({ preloaded?.matches($0) == true }) == true ? preloaded : nil
        self.preloaded = page
        _session = State(initialValue: current)
        _metadata = State(initialValue: page?.metadata)
        _rows = State(initialValue: page?.rows ?? [])
        _summary = State(initialValue: page?.summary ?? [])
        _total = State(initialValue: page?.total ?? 0)
        _page = State(initialValue: page == nil ? 0 : 1)
        _more = State(initialValue: page?.more ?? false)
        _loadedQuery = State(initialValue: page == nil ? nil : ["", "", Mango9OpportunityFilters().key, ""].joined(separator: "|"))
    }
    private var queryKey: String { [search, stage, filters.key, dashboardFilter].joined(separator: "|") }
    var body: some View {
        Mango9OpportunityPage(title: "Opportunities") {
            if metadata?.permissions["create"] == true {
                Button { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil); showCreate = true } label: { Image(systemName: "plus").font(.system(size: 23)).frame(width: 40, height: 44) }.accessibilityLabel("Add Opportunity").accessibilityIdentifier("crm.opportunity.add")
            }
        } content: {
            ScrollView {
                LazyVStack(spacing: 12) {
                    if let contact { Text(contact.name).font(.headline).frame(maxWidth: .infinity, alignment: .leading) }
                    HStack { Image(systemName: "magnifyingglass").foregroundColor(Mango9OpportunityStyle.muted); TextField("Search Opportunities or contacts", text: $search).accessibilityIdentifier("crm.opportunity.search") }.padding(12).background(Color.white).cornerRadius(12)
                    HStack(spacing: 8) {
                        Menu {
                            Picker("Status", selection: $stage) { Text("All statuses").tag(""); ForEach(metadata?.displayStages ?? []) { Text($0.name).tag($0.id) } }
                        } label: {
                            filterLabel(metadata?.displayStages.first(where: { $0.id == stage })?.name ?? "All statuses")
                        }.accessibilityIdentifier("crm.opportunity.filter.stage")
                        Menu {
                            Picker("Created by", selection: $filters.createdBy) { Text("Anyone").tag(""); ForEach(metadata?.owners ?? []) { Text($0.name).tag(String($0.id)) } }
                        } label: { filterLabel(metadata?.owners.first(where: { String($0.id) == filters.createdBy })?.name ?? "Created by") }
                            .accessibilityIdentifier("crm.opportunity.filter.createdBy")
                        Button { showFilters = true } label: {
                            Image(systemName: "slider.horizontal.3").frame(width: 44, height: 44).background(Mango9OpportunityStyle.blue.opacity(0.06)).cornerRadius(10)
                                .overlay(alignment: .topTrailing) { if filters.activeCount > 0 { Text(String(filters.activeCount)).font(.system(size: 10, weight: .bold)).foregroundColor(.white).padding(3).background(Mango9OpportunityStyle.blue).clipShape(Circle()) } }
                        }.accessibilityLabel("More filters").accessibilityIdentifier("crm.opportunity.filter.more")
                    }.buttonStyle(.plain).foregroundColor(Mango9OpportunityStyle.blue)
                    if !dashboardFilter.isEmpty {
                        HStack(spacing: 8) {
                            Text("Showing: " + (dashboardFilter == "won" ? "Won this month" : dashboardFilter == "due" ? "Follow-ups due" : "Open opportunities"))
                                .font(.system(size: 12)).foregroundColor(Mango9OpportunityStyle.muted)
                            Spacer(minLength: 4)
                            Button("Show all") { dashboardFilter = "" }
                                .font(.system(size: 13, weight: .semibold)).foregroundColor(Mango9OpportunityStyle.blue)
                                .frame(minHeight: 44).buttonStyle(.plain).accessibilityIdentifier("crm.opportunity.dashboard.clear")
                        }
                    }
                    HStack { Text(page == 0 ? "Opportunities" : "\(total) Opportunities").accessibilityIdentifier("crm.opportunity.total"); Spacer(); Text("Latest updated") }.font(.system(size: 12)).foregroundColor(Mango9OpportunityStyle.muted)
                    if let error { OpportunityError(message: error) { Task { await load(reset: true) } } }
                    ForEach(rows) { row in
                        NavigationLink { Mango9OpportunityDetail(id: row.id, session: session, metadata: metadata) } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack { OpportunityStage(row: row, metadata: metadata); Spacer(); Text(Mango9OpportunityStyle.money(row.value, currency: row.currency) + " " + row.currency).font(.system(size: 14, weight: .semibold)) }
                                HStack { Text(row.title).font(.system(size: 16, weight: .semibold)).fixedSize(horizontal: false, vertical: true); if unseen.opportunityReceipts[row.id] != nil { Circle().fill(Color.mango9Primary).frame(width: 7, height: 7).accessibilityLabel("New Opportunity") } }
                                Text(row.contactName).font(.system(size: 13)).foregroundColor(Mango9OpportunityStyle.muted)
                                HStack { Text(row.owner); Spacer(); Text(Mango9OpportunityStyle.date(row.closeDate)) }.font(.system(size: 11)).foregroundColor(Mango9OpportunityStyle.muted)
                            }.modifier(OpportunitySurface(padding: 13))
                        }.buttonStyle(.plain).accessibilityIdentifier("crm.opportunity.row.\(row.id)")
                    }
                    if busy { ProgressView("Loading Opportunities…") }
                    if !busy && rows.isEmpty && metadata != nil && error == nil { Text("No Opportunities match these filters.").foregroundColor(Mango9OpportunityStyle.muted).padding(20) }
                    if more { Button("Load more") { Task { await load(reset: false) } }.disabled(busy) }
                }.padding(14).foregroundColor(Mango9OpportunityStyle.ink)
            }.refreshable { await load(reset: true, refreshMetadata: true) }
        }.background { NavigationLink(isActive: $showCreate) { Mango9OpportunityCreationPage(record: contact, close: { showCreate = false }) } label: { EmptyView() }.hidden() }
        .sheet(isPresented: $showFilters) {
            Mango9OpportunityFilterSheet(value: filters, metadata: metadata) { filters = $0 }
        }.onChange(of: stage) { _ in saveFilters() }
            .onChange(of: filters) { _ in saveFilters() }
            .task(id: queryKey) {
            session = Mango9SessionStore.load()
            if initialLoad {
                initialLoad = false
                if let session, preloaded?.matches(session) == true { return }
            }
            do {
                // Debounce typing, not normal navigation or the first load.
                if !search.isEmpty { try await Task.sleep(nanoseconds: 250_000_000) }
                try Task.checkCancellation()
                await load(reset: true)
            } catch {}
        }
            .onReceive(NotificationCenter.default.publisher(for: .mango9OpportunityDidChange)) { _ in Task { await load(reset: true) } }
    }
    private func saveFilters() {
        guard dashboardFilter.isEmpty, let session, Mango9SessionStore.isActive(session) else { return }
        Mango9OpportunityPreference(stage: stage, filters: filters).save(session, contact: contact?.id)
    }
    private func filterLabel(_ title: String) -> some View {
        HStack(spacing: 6) {
            Text(title).font(.system(size: 12, weight: .medium)).lineLimit(1).minimumScaleFactor(0.85)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
        }.padding(.horizontal, 10).frame(maxWidth: .infinity, minHeight: 44, maxHeight: 44)
            .background(Mango9OpportunityStyle.blue.opacity(0.06)).cornerRadius(10)
    }
    private func load(reset: Bool, refreshMetadata: Bool = false) async {
        guard let session else { return }; if busy && !reset { return }
        let request = UUID(); generation = request; busy = true; error = nil
        let requestedQuery = queryKey
        defer { if generation == request { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            var query = try filters.query()
            query.merge(["q": search, "stage": stage, "sort": "updated_at", "direction": "desc", "page": String(next), "limit": "25"]) { _, new in new }
            if !dashboardFilter.isEmpty { query["dashboard"] = dashboardFilter }
            if let contact { query["contactId"] = String(contact.id) }
            let meta: Mango9OpportunityMetadata
            if let metadata, !refreshMetadata && !reset { meta = metadata }
            else { meta = try Mango9OpportunityMetadata(await Mango9OpportunityAPI.send(session, path: "metadata")) }
            guard request == generation, requestedQuery == queryKey, !Task.isCancelled else { return }
            if !stage.isEmpty && !meta.displayStages.contains(where: { $0.id == stage }) { metadata = meta; stage = ""; return }
            let owner = meta.visibleOwnerFilter(filters.owner), creator = meta.visibleOwnerFilter(filters.createdBy)
            if owner != filters.owner || creator != filters.createdBy {
                metadata = meta; filters.owner = owner; filters.createdBy = creator
                return // Changed visibility starts one new first-page request.
            }
            let data = try await Mango9OpportunityAPI.send(session, query: query.filter { !$0.value.isEmpty }.map { .init(name: $0.key, value: $0.value) })
            guard request == generation, requestedQuery == queryKey, !Task.isCancelled else { return }
            guard let raw = data["items"] as? [[String: Any]], let summary = data["summary"] as? [[String: Any]], let pagination = data["pagination"] as? [String: Any], let total = Mango9OpportunityAPI.integer(pagination["total"]), let hasMore = pagination["hasMore"] as? Bool, let returned = Mango9OpportunityAPI.integer(pagination["page"]), returned == next else { throw Mango9OpportunityFailure.invalid }
            let incoming = try raw.map(Mango9Opportunity.init)
            rows = reset ? incoming : rows + incoming.filter { r in !rows.contains { $0.id == r.id } }
            self.summary = summary; metadata = meta; self.total = total; more = hasMore; page = returned
            loadedQuery = requestedQuery
        } catch {
            if request == generation && requestedQuery == queryKey && !Task.isCancelled {
                self.error = error.localizedDescription
                // A failed refresh can keep this list, but a failed new filter
                // must not present the previous filter's results as a match.
                if loadedQuery != requestedQuery { rows = []; summary = []; total = 0; more = false; page = 0 }
                if let failure = error as? Mango9OpportunityFailure, [401, 403].contains(failure.status) {
                    rows = []; summary = []; total = 0; more = false; page = 0; metadata = nil
                }
                if let failure = error as? Mango9CRMAPIError, failure.isSignInRequired {
                    rows = []; summary = []; total = 0; more = false; page = 0; metadata = nil
                }
            }
        }
    }
}

struct Mango9OpportunityContactPicker: View {
    let close: () -> Void
    let select: (Mango9Lead) -> Void
    let create: (Mango9CRMRecordKind) -> Void
    @State private var types: [Int: String] = [:]
    @State private var companies: [Int: String] = [:]
    @State private var search = ""
    @State private var kind = "all"
    @State private var rows: [Mango9Lead] = []
    @State private var session: Mango9Session?
    @State private var page = 0
    @State private var more = false
    @State private var busy = false
    @State private var error: String?
    @State private var generation = UUID()
    var body: some View {
        Mango9OpportunityPage(title: "Lead or Client", backAction: close, actions: { EmptyView() }) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundColor(Mango9OpportunityStyle.muted)
                        TextField("Search name, company, email, or phone", text: $search).autocorrectionDisabled()
                            .accessibilityIdentifier("crm.opportunity.recordSearch")
                    }.padding(12).background(Color.white).cornerRadius(12)
                    HStack {
                        Menu {
                            Picker("Record type", selection: $kind) {
                                Text("All").tag("all"); Text("Leads").tag("leads"); Text("Clients").tag("clients")
                            }
                        } label: {
                            HStack { Image(systemName: "person.2"); Text(kind == "leads" ? "Leads" : kind == "clients" ? "Clients" : "All"); Image(systemName: "chevron.down").font(.caption) }
                                .font(.system(size: 14, weight: .semibold)).padding(.horizontal, 12).frame(minHeight: 44)
                                .background(Mango9OpportunityStyle.blue.opacity(0.06)).cornerRadius(10)
                        }.accessibilityIdentifier("crm.opportunity.recordType")
                        Spacer()
                        Menu {
                            Button("Add Lead") { create(.lead) }
                            Button("Add Client") { create(.client) }
                        } label: { Label("Add new", systemImage: "plus").font(.system(size: 14, weight: .semibold)).frame(minHeight: 44) }
                            .accessibilityIdentifier("crm.opportunity.newRecord")
                    }.foregroundColor(Mango9OpportunityStyle.blue)
                    Text("Choose the Lead or Client for this Opportunity.").font(.system(size: 13)).foregroundColor(Mango9OpportunityStyle.muted)
                    if let error { Text(error).foregroundColor(.red); Button("Retry") { Task { await load(reset: true) } } }
                    ForEach(rows) { row in
                        Button { select(row) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack { Text(row.name).font(.system(size: 15, weight: .semibold)); Spacer(); Text(types[row.id] ?? "Lead").font(.system(size: 12)).foregroundColor(Mango9OpportunityStyle.muted) }
                                Text([companies[row.id] ?? "", row.email, row.phone].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.system(size: 13)).foregroundColor(Mango9OpportunityStyle.muted)
                            }.modifier(OpportunitySurface())
                        }.buttonStyle(.plain).accessibilityIdentifier("crm.opportunity.record.\(row.id)")
                    }
                    if busy { ProgressView().frame(maxWidth: .infinity) }
                    if !busy && error == nil && rows.isEmpty { Text("No matching leads or clients. Use Add new to create one.").foregroundColor(Mango9OpportunityStyle.muted) }
                    if more { Button("Load more") { Task { await load(reset: false) } }.disabled(busy) }
                }.padding(14).foregroundColor(Mango9OpportunityStyle.ink)
            }
        }.task(id: search + "|" + kind) {
            session = Mango9SessionStore.load()
            do { if !search.isEmpty { try await Task.sleep(nanoseconds: 250_000_000) }; try Task.checkCancellation(); await load(reset: true) } catch {}
        }
    }
    private func load(reset: Bool) async {
        guard let session else { return }; if busy && !reset { return }
        let request = UUID(); generation = request; busy = true; error = nil
        if reset { rows = []; types = [:]; companies = [:]; page = 0; more = false }
        defer { if generation == request { busy = false } }
        do {
            let result = try await Mango9OpportunityAPI.send(session, path: "contacts", query: [.init(name: "q", value: search), .init(name: "type", value: kind), .init(name: "page", value: String(page + 1))])
            guard generation == request, !Task.isCancelled else { return }
            guard let contacts = result["items"] as? [[String: Any]] else { throw Mango9OpportunityFailure.invalid }
            for c in contacts { if let id = Mango9OpportunityAPI.integer(c["id"]) { companies[id] = c["company"] as? String ?? ""; types[id] = (c["is_client"] as? Bool == true || Mango9OpportunityAPI.integer(c["is_client"]) == 1) ? "Client" : "Lead" } }
            let incoming = contacts.compactMap { c -> Mango9Lead? in guard let id = Mango9OpportunityAPI.integer(c["id"]), let name = c["name"] as? String else { return nil }; return Mango9Lead(id: id, ownerUserId: Mango9OpportunityAPI.integer(c["owner_id"]) ?? 0, ownerName: "", name: name, phone: c["phone"] as? String ?? "", email: c["email"] as? String ?? "", status: "", source: c["source"] as? String ?? "", createdAt: "") }
            rows += incoming.filter { next in !rows.contains { $0.id == next.id } }
            page += 1; more = (result["pagination"] as? [String: Any])?["hasMore"] as? Bool == true
        } catch { if generation == request && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

/// Stable identities preserve the visible activity when a page is appended or refreshed.
struct Mango9CRMActivityRow: Identifiable {
    let raw: [String: Any]
    var id: String { raw["key"] as? String ?? "audit:" + String(Mango9OpportunityAPI.integer(raw["id"]) ?? 0) }
    init(_ raw: [String: Any]) { self.raw = raw }
}
struct Mango9CRMActivityTarget: Identifiable {
    let screen: String, recordID: String, contactID: String, key: String, phone: String
    var id: String { [screen, recordID, contactID, key].joined(separator: ":") }
    init?(_ raw: [String: Any]) {
        guard let screen = raw["screen"] as? String else { return nil }
        func value(_ key: String) -> String { raw[key] as? String ?? Mango9OpportunityAPI.integer(raw[key]).map(String.init) ?? "" }
        self.screen = screen; recordID = value(screen == "sms" ? "conversationId" : "id"); contactID = value("contactId"); key = value("key"); phone = value("phone")
        switch screen {
        case "quote", "invoice", "opportunity": guard Int(recordID).map({ $0 > 0 }) == true else { return nil }
        case "activity": guard Int(contactID).map({ $0 > 0 }) == true, key.count <= 180, key.range(of: #"^(note|email|event|event-history|pbx|ai-call|ai-usage|ai-sms-usage):[A-Za-z0-9_-]+$"#, options: .regularExpression) != nil else { return nil }
        case "sms": guard phone.range(of: #"^\+?[\d ()-]{7,35}$"#, options: .regularExpression) != nil, recordID.range(of: #"^[a-f0-9]{64}$"#, options: .regularExpression) != nil else { return nil }
        case "social", "live": guard UUID(uuidString: recordID) != nil else { return nil }
        default: return nil
        }
    }
    var title: String { ["quote": "Quote", "invoice": "Invoice", "opportunity": "Opportunity", "sms": "SMS", "social": "Social", "live": "Live Chat"][screen] ?? "Activity" }
    var mobilePath: String? {
        switch screen {
        case "quote", "invoice": return "documents/\(screen == "quote" ? "quotes" : "invoices")/\(recordID)"
        case "activity": return "contacts/\(contactID)/activity/item"
        case "sms": return "sms/conversations/\(recordID)/messages"
        default: return nil
        }
    }
}
struct Mango9CRMActivitySection: View {
    let contactID: Int
    let session: Mango9Session
    var collapsed = false
    @State private var expanded = false
    @State private var rows: [Mango9CRMActivityRow] = []
    @State private var source = "fast"
    @State private var sources = ["fast"]
    @State private var cursor: String?
    @State private var busy = false
    @State private var loaded = false
    @State private var loadedSource: String?
    @State private var error: String?
    @State private var warnings: [String] = []
    @State private var generation = UUID()
    static let labels = ["fast": "Recent activity", "all": "All activity", "note": "Notes", "email": "Email", "sms": "SMS", "calls": "Calls", "document": "Quotes & invoices", "activity": "Tasks & appointments", "opportunity": "Opportunities", "social": "Social DMs", "live": "Live Chat"]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if collapsed {
                Button { expanded.toggle() } label: { HStack { Label("Activity", systemImage: "clock.arrow.circlepath"); Spacer(); Image(systemName: expanded ? "chevron.up" : "chevron.down") }.font(Mango9CRMText.emphasizedValue).frame(minHeight: 40) }.buttonStyle(.plain)
            }
            if !collapsed || expanded {
                HStack { Text("Show").font(Mango9CRMText.fieldLabel); Spacer(); Picker("Show activity", selection: $source) { ForEach(sources, id: \.self) { Text(Self.labels[$0] ?? $0).tag($0) } }.pickerStyle(.menu).labelsHidden().font(Mango9CRMText.fieldValue).fixedSize(horizontal: true, vertical: false).accessibilityIdentifier("crm.activity.source") }
                if let error { Text(error).font(Mango9CRMText.fieldValue).foregroundStyle(.red); Button("Retry") { Task { await load(reset: true) } } }
                ForEach(warnings, id: \.self) { Text($0).font(Mango9CRMText.metadata).foregroundStyle(Mango9OpportunityStyle.muted) }
                ForEach(rows) { row in
                    if let raw = row.raw["open"] as? [String: Any], let target = Mango9CRMActivityTarget(raw) {
                        if target.screen == "sms" {
                            Button { if Mango9SessionStore.isActive(session) { Mango9SMSRouting.open(.init(phone: target.phone, name: target.phone)) } } label: { activityLabel(row, linked: true) }.buttonStyle(.plain)
                        } else {
                            NavigationLink { Mango9CRMActivityDestination(target: target, session: session) } label: { activityLabel(row, linked: true) }.buttonStyle(.plain)
                        }
                    } else { activityLabel(row, linked: false) }
                }
                if busy { ProgressView().frame(maxWidth: .infinity) }
                if loaded && !busy && error == nil && rows.isEmpty { Text("No activity to show.").font(Mango9CRMText.fieldValue).foregroundStyle(Mango9OpportunityStyle.muted) }
                if cursor != nil && !busy { Button("Load more") { Task { await load(reset: false) } } }
                Text("Calls load separately when selected.").font(Mango9CRMText.metadata).foregroundStyle(Mango9OpportunityStyle.muted)
            }
        }.modifier(OpportunitySurface())
            .task(id: "\(contactID):\(source):\(expanded)") { if !collapsed || expanded { await load(reset: true) } }
            .onDisappear { generation = UUID() }
    }
    private func activityLabel(_ row: Mango9CRMActivityRow, linked: Bool) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: ["sms": "message", "human_call": "phone", "ai_call": "phone", "document": "doc.text", "note": "note.text", "email": "envelope", "activity": "calendar", "opportunity": "dollarsign.circle", "social": "bubble.left.and.bubble.right", "live": "globe"][row.raw["type"] as? String ?? ""] ?? "clock").foregroundStyle(Mango9OpportunityStyle.blue).frame(width: 23)
            VStack(alignment: .leading, spacing: 4) {
                Text(row.raw["title"] as? String ?? "Activity").font(Mango9CRMText.emphasizedValue)
                if let summary = row.raw["summary"] as? String, !summary.isEmpty { Text(summary).font(Mango9CRMText.fieldValue).lineLimit(2) }
                let at = (row.raw["at"] as? NSNumber)?.doubleValue ?? 0
                Text([row.raw["actor"] as? String ?? "", at > 0 ? Mango9OpportunityStyle.date(ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: at)), time: true) : ""].filter { !$0.isEmpty }.joined(separator: " · ")).font(Mango9CRMText.metadata).foregroundStyle(Mango9OpportunityStyle.muted)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if linked { Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(Mango9OpportunityStyle.muted) }
        }.padding(.vertical, 8).overlay(alignment: .bottom) { Mango9OpportunityStyle.line.frame(height: 0.5) }
    }
    private func load(reset: Bool) async {
        guard reset || !busy else { return }
        let token = UUID(); generation = token; busy = true; error = nil
        if loadedSource != source { rows = []; cursor = nil; warnings = []; loaded = false }
        defer { if generation == token { busy = false } }
        do {
            var query = [URLQueryItem(name: "source", value: source)]
            if !reset, let cursor { query.append(.init(name: "cursor", value: cursor)) }
            let data = try await Mango9OpportunityAPI.send(session, query: query, mobilePath: "contacts/\(contactID)/activity")
            guard generation == token, !Task.isCancelled else { return }
            guard Mango9OpportunityAPI.integer(data["version"]) == 1, let incoming = data["items"] as? [[String: Any]], incoming.allSatisfy({ $0["key"] is String }) else { throw Mango9OpportunityFailure.invalid }
            let fresh = incoming.map(Mango9CRMActivityRow.init)
            rows = reset ? fresh : rows + fresh.filter { next in !rows.contains { $0.id == next.id } }
            cursor = data["next"] as? String; sources = (data["sources"] as? [String] ?? ["fast"]).filter { Self.labels[$0] != nil }
            warnings = data["warnings"] as? [String] ?? []; loaded = true; loadedSource = source
        } catch { if generation == token && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
struct Mango9CRMDocumentLinks: View {
    let documents: [[String: Any]]
    let session: Mango9Session
    var body: some View {
        if !documents.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Recent quotes & invoices").font(Mango9CRMText.emphasizedValue)
                ForEach(documents.indices, id: \.self) { index in
                    let doc = documents[index]
                    if let target = Mango9CRMActivityTarget(["screen": doc["kind"] ?? "", "id": doc["id"] ?? ""]) {
                        NavigationLink { Mango9CRMActivityDestination(target: target, session: session) } label: {
                            HStack { Image(systemName: "doc.text"); VStack(alignment: .leading, spacing: 3) { Text(doc["number"] as? String ?? target.title).font(Mango9CRMText.emphasizedValue); Text(doc["status"] as? String ?? "").font(Mango9CRMText.metadata).foregroundStyle(Mango9OpportunityStyle.muted) }; Spacer(); Text(Mango9OpportunityStyle.money(doc["total"] as? String, currency: doc["currency"] as? String ?? "USD")).font(Mango9CRMText.fieldValue); Image(systemName: "chevron.right").font(.caption) }.padding(.vertical, 7)
                        }.buttonStyle(.plain)
                    }
                }
            }
        }
    }
}
struct Mango9CRMActivityDestination: View {
    let target: Mango9CRMActivityTarget
    let session: Mango9Session
    @State private var data: [String: Any]?
    @State private var error: String?
    @State private var busy = false
    @State private var player: AVPlayer?
    @State private var recordingURL: URL?
    @State private var recordingOwner = UUID()
    @State private var socialThread: Mango9SocialThread?
    @State private var liveThread: Mango9LiveConversation?
    var body: some View {
        if target.screen == "opportunity", let id = Int(target.recordID) { Mango9OpportunityDetail(id: id, session: session, metadata: nil) }
        else if let socialThread { Mango9SocialConversation(thread: socialThread, session: session) }
        else if let liveThread { Mango9LiveChatConversation(conversation: liveThread, session: session) }
        else {
            Mango9OpportunityPage(title: target.title) {
                Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise").frame(width: 40, height: 44) }.accessibilityLabel("Refresh").disabled(busy)
            } content: {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if let error { Text(error).foregroundStyle(.red); Button("Retry") { Task { await load() } } }
                        if let data {
                            if target.screen == "quote" || target.screen == "invoice" { document(data) }
                            else { activity(data) }
                        }
                        if busy { ProgressView().frame(maxWidth: .infinity) }
                    }.font(Mango9CRMText.fieldValue).padding(16)
                }
            }.task { await load() }.modifier(Mango9VoicePreviewLifecycle(owner: recordingOwner, url: $recordingURL))
            .onChange(of: recordingURL) { if $0 == nil { player?.pause(); player = nil } }
            .onDisappear { player?.pause(); player = nil; recordingURL = nil; Mango9VoicePlaybackSession.shared.release(recordingOwner) }
        }
    }
    @ViewBuilder private func document(_ result: [String: Any]) -> some View {
        let d = result["document"] as? [String: Any] ?? [:]
        let currency = d["currency"] as? String ?? "USD"
        VStack(alignment: .leading, spacing: 12) {
            Text(d[target.screen == "quote" ? "quote_number" : "invoice_number"] as? String ?? target.title).font(Mango9CRMText.recordTitle)
            OpportunityFact("Status", d["status"] as? String ?? "")
            OpportunityFact("Customer", d["bill_to_name"] as? String ?? "")
            OpportunityFact("Issued", Mango9OpportunityStyle.date(d["issue_date"] as? String))
            OpportunityFact(target.screen == "quote" ? "Expires" : "Due", Mango9OpportunityStyle.date(d[target.screen == "quote" ? "expiry_date" : "due_date"] as? String))
            OpportunityFact("Subtotal", Mango9OpportunityStyle.money(d["subtotal"] as? String, currency: currency))
            OpportunityFact("Tax", Mango9OpportunityStyle.money(d["tax_total"] as? String, currency: currency))
            OpportunityFact("Discount", Mango9OpportunityStyle.money(d["discount_total"] as? String, currency: currency))
            OpportunityFact("Total", Mango9OpportunityStyle.money(d["grand_total"] as? String, currency: currency))
            if target.screen == "invoice" { OpportunityFact("Balance due", Mango9OpportunityStyle.money(d["balance_due"] as? String, currency: currency)) }
        }.modifier(OpportunitySurface())
        let items = result["items"] as? [[String: Any]] ?? []
        ForEach(items.indices, id: \.self) { index in
            let item = items[index]
            VStack(alignment: .leading, spacing: 6) {
                Text(item["name"] as? String ?? "Item").font(Mango9CRMText.emphasizedValue)
                if let description = item["description"] as? String, !description.isEmpty { Text(description).textSelection(.enabled) }
                HStack { Text("Quantity: " + String(describing: item["quantity"] ?? "")); Spacer(); Text(Mango9OpportunityStyle.money(item["line_total"] as? String, currency: currency)) }
            }.modifier(OpportunitySurface())
        }
        let payments = result["payments"] as? [[String: Any]] ?? []
        ForEach(payments.indices, id: \.self) { index in
            let payment = payments[index]
            VStack(alignment: .leading, spacing: 8) {
                Text("Payment · " + Mango9OpportunityStyle.money(payment["amount"] as? String, currency: currency)).font(Mango9CRMText.emphasizedValue)
                OpportunityFact("Paid", Mango9OpportunityStyle.date(payment["paid_on"] as? String))
                OpportunityFact("Method", payment["method"] as? String ?? "")
                OpportunityFact("Reference", payment["reference"] as? String ?? "")
            }.modifier(OpportunitySurface())
        }
        ForEach(["notes", "terms"], id: \.self) { key in if let value = d[key] as? String, !value.isEmpty { VStack(alignment: .leading, spacing: 6) { Text(key.capitalized).font(Mango9CRMText.emphasizedValue); Text(value).textSelection(.enabled) }.modifier(OpportunitySurface()) } }
    }
    @ViewBuilder private func activity(_ result: [String: Any]) -> some View {
        let d = result["data"] as? [String: Any] ?? [:]
        VStack(alignment: .leading, spacing: 12) {
            Text(d["title"] as? String ?? "Activity").font(Mango9CRMText.recordTitle)
            if let text = d["text"] as? String, !text.isEmpty { Text(text).textSelection(.enabled) }
            ForEach(["actor", "status", "duration", "cause", "start", "end", "outcome", "recipient", "note"], id: \.self) { key in
                if let v = d[key], !(v is NSNull), !String(describing: v).isEmpty { OpportunityFact(key == "duration" ? "Duration (seconds)" : key.capitalized, String(describing: v)) }
            }
            if let player { VideoPlayer(player: player).frame(height: 75) }
            else if let value = d["recording"] as? String, let url = URL(string: value), url.scheme == "https" { Button { do { guard Mango9SessionStore.isActive(session) else { throw Mango9OpportunityFailure.accountChanged }; try Mango9VoicePlaybackSession.shared.acquire(recordingOwner) { player?.pause(); player = nil; recordingURL = nil }; recordingURL = url; let playback = AVPlayer(url: url); player = playback; playback.play() } catch { self.error = error.localizedDescription } } label: { Label("Play recording", systemImage: "play.circle") } }
        }.modifier(OpportunitySurface())
    }
    private func load() async {
        guard !busy else { return }
        busy = true; error = nil; defer { busy = false }
        do {
            if (target.screen == "social" && !Mango9ChatTabPreferences.shared.social) || (target.screen == "live" && !Mango9ChatTabPreferences.shared.live) { throw Mango9OpportunityFailure(status: 403, message: "This chat tab is disabled in your app settings.") }
            if target.screen == "social" {
                let thread = try await Mango9MessagingAPI.shared.request(Mango9SocialThread.self, session: session, social: true, path: ["inbox-detail"], query: [.init(name: "id", value: target.recordID)])
                guard !Task.isCancelled, Mango9SessionStore.isActive(session), thread.id == target.recordID else { throw Mango9OpportunityFailure.accountChanged }
                socialThread = thread; return
            }
            if target.screen == "live" {
                let result = try await Mango9LiveChatAPI.shared.request("messages", session: session, query: [.init(name: "id", value: target.recordID)])
                guard !Task.isCancelled, Mango9SessionStore.isActive(session), let raw = result["conversation"] as? [String: Any], let thread = Mango9LiveConversation(raw), thread.id == target.recordID else { throw Mango9OpportunityFailure.accountChanged }
                liveThread = thread; return
            }
            guard let path = target.mobilePath else { throw Mango9OpportunityFailure.invalid }
            let query: [URLQueryItem] = target.screen == "activity" ? [.init(name: "key", value: target.key)] : []
            let response = try await Mango9OpportunityAPI.send(session, query: query, mobilePath: path)
            try Task.checkCancellation()
            if target.screen == "quote" || target.screen == "invoice" { guard let doc = response["document"] as? [String: Any], String(Mango9OpportunityAPI.integer(doc["id"]) ?? 0) == target.recordID else { throw Mango9OpportunityFailure.invalid } }
            if target.screen == "activity" { guard response["key"] as? String == target.key else { throw Mango9OpportunityFailure.invalid } }
            data = response
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

private struct Mango9CRMCompactSheet: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 16.0, *) { content.presentationDetents([.medium, .large]).presentationDragIndicator(.visible) }
        else { content }
    }
}

private struct Mango9OpportunityFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: Mango9OpportunityFilters
    let metadata: Mango9OpportunityMetadata?
    let apply: (Mango9OpportunityFilters) -> Void
    init(value: Mango9OpportunityFilters, metadata: Mango9OpportunityMetadata?, apply: @escaping (Mango9OpportunityFilters) -> Void) {
        _draft = State(initialValue: value); self.metadata = metadata; self.apply = apply
    }
    private var validation: String? { do { _ = try draft.query(); return nil } catch { return error.localizedDescription } }
    var body: some View {
        NavigationView {
            Form {
                Section("People") {
                    Picker("Created by", selection: $draft.createdBy) { Text("Anyone").tag(""); people }
                    Picker("Owner", selection: $draft.owner) { Text("All owners").tag(""); people }
                    Picker("Sharing", selection: $draft.shared) { Text("All sharing").tag(""); Text("Shared with me").tag("with_me"); Text("Shared by me").tag("by_me") }
                }
                Section("Due dates") {
                    Picker("Follow-up", selection: $draft.followup) { Text("All follow-ups").tag(""); Text("Overdue").tag("overdue"); Text("Next step needed").tag("missing"); Text("No activity for 14+ days").tag("stale") }
                    Text("Expected close date").font(.footnote).foregroundColor(.secondary)
                    day("From", value: $draft.closeFrom, id: "closeFrom")
                    day("To", value: $draft.closeTo, id: "closeTo")
                }
                Section {
                    HStack { Text("Minimum"); TextField("No minimum", text: $draft.minValue).keyboardType(.decimalPad).multilineTextAlignment(.trailing).accessibilityIdentifier("crm.opportunity.filter.minValue") }
                    HStack { Text("Maximum"); TextField("No maximum", text: $draft.maxValue).keyboardType(.decimalPad).multilineTextAlignment(.trailing).accessibilityIdentifier("crm.opportunity.filter.maxValue") }
                    Picker("Currency", selection: $draft.currency) { Text("All currencies").tag(""); ForEach(Mango9OpportunityDraft.currencies, id: \.self) { Text($0).tag($0) } }
                } header: { Text("Amount") } footer: { Text("Amounts use each Opportunity’s currency.") }
                Section("Created date") { day("From", value: $draft.createdFrom, id: "createdFrom"); day("To", value: $draft.createdTo, id: "createdTo") }
                Section("More") {
                    Picker("Source", selection: $draft.source) { Text("All sources").tag(""); ForEach(metadata?.sources ?? [], id: \.self) { Text($0).tag($0) } }
                    Toggle("Archived", isOn: $draft.archived)
                }
                if let validation { Text(validation).foregroundColor(.red).font(.footnote) }
                Button("Reset filters") { draft = Mango9OpportunityFilters() }.accessibilityIdentifier("crm.opportunity.filter.reset")
            }.font(.system(size: 15)).navigationTitle("Filters").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Apply") { apply(draft); dismiss() }.disabled(validation != nil).accessibilityIdentifier("crm.opportunity.filter.apply") }
                }
        }.navigationViewStyle(.stack).tint(Mango9OpportunityStyle.blue).preferredColorScheme(.light)
    }
    private var people: some View { ForEach(metadata?.owners ?? []) { Text($0.name).tag(String($0.id)) } }
    private func day(_ label: String, value: Binding<String>, id: String) -> some View {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
        return HStack {
            Text(label); Spacer()
            if value.wrappedValue.isEmpty {
                Button("Any date") { value.wrappedValue = formatter.string(from: Date()) }.accessibilityIdentifier("crm.opportunity.filter." + id)
            } else {
                DatePicker(label, selection: Binding(get: { formatter.date(from: value.wrappedValue) ?? Date() }, set: { value.wrappedValue = formatter.string(from: $0) }), displayedComponents: .date).labelsHidden().datePickerStyle(.compact)
                Button { value.wrappedValue = "" } label: { Image(systemName: "xmark.circle") }.accessibilityLabel("Clear " + id)
            }
        }.frame(minHeight: 38)
    }
}

/// Keep the configured Opportunity colors; use consistent defaults where contact APIs return only a label.
enum Mango9CRMStatusStyle {
    static func hex(_ supplied: String?, fallback title: String) -> String {
        if let supplied, supplied.range(of: "^#[0-9a-fA-F]{6}$", options: .regularExpression) != nil { return supplied }
        switch title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "no status": return "#64748B"
        case "new", "new lead", "new client": return "#718096"
        case "qualified": return "#2563EB"
        case "proposal": return "#7C3AED"
        case "negotiation", "pending": return "#B45309"
        case "won", "active", "client": return "#15803D"
        case "lost", "inactive", "rejected": return "#B91C1C"
        default:
            let palette = ["#2563EB", "#7C3AED", "#0F766E", "#B45309", "#BE185D"]
            return palette[title.lowercased().utf8.reduce(0) { ($0 * 31 + Int($1)) % palette.count }]
        }
    }
}

struct Mango9CRMStatusBadge: View {
    let title: String
    var colorHex: String? = nil
    var dropdown = false
    var selected = false
    private var base: UIColor { UIColor(Color(hex: Mango9CRMStatusStyle.hex(colorHex, fallback: title))) }
    private var foreground: Color {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        base.getRed(&r, green: &g, blue: &b, alpha: &a)
        let maxComponent = max(r, g, b)
        let scale = maxComponent > 0.65 ? 0.65 / maxComponent : 1
        return Color(red: Double(r * scale), green: Double(g * scale), blue: Double(b * scale))
    }
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(selected ? Color.white : Color(base)).frame(width: 6, height: 6)
            Text(title).font(.system(size: 14, weight: .semibold)).lineLimit(1).truncationMode(.tail)
            if dropdown { Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold)) }
        }.foregroundColor(selected ? Color.white : foreground).padding(.horizontal, 10).padding(.vertical, 7)
            .background(Color(base).opacity(selected ? 1 : 0.11)).clipShape(Capsule())
            .accessibilityElement(children: .ignore).accessibilityLabel(title)
    }
}

struct Mango9OpportunityDashboard: View {
    @ObservedObject var cards: Mango9OpportunityCards
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Opportunities").font(.system(size: 18, weight: .bold))
                Spacer()
                NavigationLink { Mango9OpportunityWorkspace(preloaded: cards.overview) } label: {
                    Label("View all", systemImage: "chevron.right").font(.system(size: 13, weight: .semibold))
                }
            }
            if let data = cards.dashboard {
                LazyVGrid(columns: [GridItem(.flexible(), alignment: .top), GridItem(.flexible(), alignment: .top)], spacing: 10) {
                    metric("Open opportunities", filter: "open", icon: "dollarsign.circle", color: Mango9OpportunityStyle.blue) {
                        value(String(data.open)); caption("Active opportunities")
                    }
                    metric("Open value", filter: "open", icon: "chart.bar", color: Mango9OpportunityStyle.blue) {
                        amounts(data.openAmounts, count: data.open)
                        if data.unknownOpen > 0 { caption("\(data.unknownOpen) without a value") }
                    }
                    metric("Won this month", filter: "won", icon: "checkmark.circle", color: Color(hex: "#15803D")) {
                        amounts(data.wonAmounts, count: data.won)
                        caption("\(data.won) won")
                        if data.estimatedWon > 0 { caption("\(data.estimatedWon) using estimated value") }
                        if data.unknownWon > 0 { caption("\(data.unknownWon) without a value") }
                    }
                    metric("Follow-ups due", filter: "due", icon: "calendar", color: Color(hex: "#B45309")) {
                        value(String(data.due)); caption("\(data.overdue) overdue · \(data.today) today")
                    }
                }
            } else if let error = cards.dashboardError {
                Button { Task { await cards.refresh(ids: [], includeOverview: true) } } label: {
                    Label(error, systemImage: "arrow.clockwise").font(.system(size: 14)).frame(maxWidth: .infinity, alignment: .leading)
                }.accessibilityIdentifier("crm.opportunity.dashboard.retry")
            } else { ProgressView("Loading totals…").font(.system(size: 14)).frame(maxWidth: .infinity).padding(12) }
        }.foregroundColor(Mango9OpportunityStyle.ink).modifier(OpportunitySurface())
            .accessibilityIdentifier("crm.opportunity.dashboard")
    }
    private func metric<C: View>(_ title: String, filter: String, icon: String, color: Color, @ViewBuilder content: () -> C) -> some View {
        NavigationLink { Mango9OpportunityWorkspace(dashboardFilter: filter) } label: {
            VStack(alignment: .leading, spacing: 7) {
                Image(systemName: icon).font(.system(size: 18)).foregroundColor(color)
                Text(title).font(.system(size: 13, weight: .medium)).foregroundColor(Mango9OpportunityStyle.muted).fixedSize(horizontal: false, vertical: true)
                content()
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading).padding(12)
                .background(Mango9OpportunityStyle.background).cornerRadius(12)
        }.buttonStyle(.plain).accessibilityIdentifier("crm.opportunity.dashboard." + title)
    }
    private func value(_ text: String) -> some View { Text(text).font(.system(size: 22, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7) }
    private func caption(_ text: String) -> some View { Text(text).font(.system(size: 12)).foregroundColor(Mango9OpportunityStyle.muted).fixedSize(horizontal: false, vertical: true) }
    @ViewBuilder private func amounts(_ amounts: [Mango9OpportunityDashboardData.Amount], count: Int) -> some View {
        if amounts.isEmpty { value(count == 0 ? "0" : "Not set") }
        ForEach(amounts.indices, id: \.self) { index in
            let amount = amounts[index]
            VStack(alignment: .leading, spacing: 2) {
                value(amount.value == nil ? "Not set" : Mango9OpportunityStyle.money(amount.value, currency: amount.currency))
                caption(amount.currency)
            }
        }
    }
}

private struct Mango9OpportunityOutcomeSheet: View {
    @ObservedObject var model: Mango9OpportunityDetailModel
    let stage: String
    @Environment(\.dismiss) private var dismiss
    @State private var draft = Mango9OpportunityDraft()
    @State private var loaded = false
    @State private var error: String?
    private var selected: Mango9OpportunityMetadata.Stage? { model.metadata?.stages.first { $0.id == stage } }
    var body: some View {
        NavigationView {
            Form {
                Section {
                    if let selected { Mango9CRMStatusBadge(title: selected.name, colorHex: selected.colorHex) }
                    DatePicker("Actual close date", selection: $draft.actualClose, in: ...Date(), displayedComponents: .date)
                    HStack {
                        Text("Actual value (\(draft.currency))")
                        TextField("Not entered", text: $draft.actualValue).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    }
                    if selected?.classification == "lost" { TextField("Loss reason (required)", text: $draft.lossReason) }
                }
                if let error = error ?? model.error { Section { Text(error).foregroundColor(.red) } }
                if model.pending != nil {
                    Section { Text("The previous save is not confirmed. Close this sheet and retry that change from the Opportunity.").font(.footnote) }
                }
            }.navigationTitle("Update status").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(model.busy) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(model.busy ? "Saving…" : "Save") { Task { await save() } }
                            .disabled(!loaded || !model.can("update"))
                    }
                }
        }.navigationViewStyle(.stack).tint(Mango9OpportunityStyle.blue).interactiveDismissDisabled(model.busy)
            .modifier(Mango9CRMCompactSheet())
            .task {
                guard !loaded, let row = model.row else { return }
                draft = row.draft; draft.stage = stage
                if row.raw["outcome"] as? String == "open" { draft.actualClose = Date() }
                loaded = true
            }
    }
    private func save() async {
        guard let metadata = model.metadata else { return }
        error = nil
        do {
            let values = try draft.values(metadata: metadata, existingStage: model.row?.stage)
            if await model.mutate("update", values: ["values": values]) { dismiss() }
        } catch { self.error = error.localizedDescription }
    }
}
