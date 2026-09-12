import SwiftUI
import CryptoKit

struct Mango9PersonalReminder: Decodable {
	let enabled: Bool
	let minutesBefore: Int?
	let dueAt: Date?
	let dismissed: Bool
	let needsReenable: Bool
	let revision: String
}

struct Mango9ReminderFeed: Decodable {
	struct Item: Decodable, Identifiable {
		let event: Mango9Appointment
		let reminder: Mango9PersonalReminder
		var id: Int { event.id }
	}
	let items: [Item]
	let serverTime: Date
	let settings: Mango9CRMPreferences?

	func due(at now: Date) -> [Item] {
		items.filter { item in
			guard item.reminder.enabled, !item.reminder.dismissed, let due = item.reminder.dueAt else { return false }
			return due <= now && item.event.endAt > now && !item.event.isRecurring
		}.sorted { $0.reminder.dueAt == $1.reminder.dueAt ? $0.id < $1.id : $0.reminder.dueAt! < $1.reminder.dueAt! }
	}
}

struct Mango9ReminderCadence {
	var lastShown: [String: Double] = [:]
	static func key(session: Mango9Session, item: Mango9ReminderFeed.Item) -> String {
		let value = Mango9CalendarAPI.accountKey(session) + "|\(item.id)|" + item.reminder.revision
		return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
	}
	func allows(_ key: String, now: Date, repeatMinutes: Int) -> Bool {
		guard let previous = lastShown[key] else { return true }
		return repeatMinutes > 0 && now.timeIntervalSince1970 - previous >= Double(repeatMinutes * 60)
	}
	mutating func record(_ key: String, at now: Date) {
		lastShown[key] = now.timeIntervalSince1970
		// Retain only bounded hashes/timestamps, never appointment/customer content.
		if lastShown.count > 256 { lastShown = Dictionary(uniqueKeysWithValues: lastShown.sorted { $0.value > $1.value }.prefix(256).map { ($0.key, $0.value) }) }
	}
}

enum Mango9AppointmentActions {
	static let minuteChoices = [5, 10, 15, 30, 60]
	static let reminderChoices = [0, 5, 10, 15, 30, 60, 120, 1440]
	static func pushedDates(event: Mango9Appointment, minutes: Int) -> DateInterval {
		DateInterval(start: event.startAt.addingTimeInterval(Double(minutes * 60)), duration: event.endAt.timeIntervalSince(event.startAt))
	}
	static func pushBackBody(event: Mango9Appointment, minutes: Int) -> [String: Any] {
		let dates = pushedDates(event: event, minutes: minutes)
		return ["start_at": Mango9CalendarAPI.timestamp(dates.start), "end_at": Mango9CalendarAPI.timestamp(dates.end)]
	}
	static func snoozeBody(until: Date, reminder: Mango9PersonalReminder) -> [String: Any] {
		["action": "snooze", "until": Mango9CalendarAPI.timestamp(until), "reminder_revision": reminder.revision]
	}
	static func reminderLabel(_ minutes: Int) -> String {
		minutes == 0 ? "At start time" : minutes == 1440 ? "1 day before" : "\(minutes) minutes before"
	}
}

/// Foreground-only and active-CRM scoped. Never activates a SIP account or schedules APNs/local notifications.
@MainActor final class Mango9InAppReminderStore: ObservableObject {
	@Published private(set) var due: [Mango9ReminderFeed.Item] = []
	private(set) var session: Mango9Session?
	private var generation = UUID()
	private var visibleUntil = Date.distantPast
	private var cadence: Mango9ReminderCadence
	private let defaults: UserDefaults
	private let cadenceKey = "mango9_crm_reminder_presentations_v1"
	let transport: URLSession
	init(transport: URLSession = .shared, defaults: UserDefaults = .standard) {
		self.transport = transport; self.defaults = defaults
		cadence = Mango9ReminderCadence(lastShown: defaults.dictionary(forKey: cadenceKey) as? [String: Double] ?? [:])
	}
	func reset() { generation = UUID(); due = []; session = nil }
	func refresh() async {
		guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(current) else { reset(); return }
		if session.map(Mango9CalendarAPI.accountKey) != Mango9CalendarAPI.accountKey(current) { reset() }
		session = current
		let requestID = UUID(); generation = requestID
		do {
			let feed = try await Mango9CalendarAPI.send(Mango9ReminderFeed.self, session: current, path: "reminders", transport: transport)
			guard generation == requestID, !Task.isCancelled, Mango9SessionStore.isActive(current) else { return }
			// Server clock is authoritative; don't fire stale cached reminders when offline.
			let preferences = feed.settings ?? Mango9CRMPreferences()
			Mango9CRMPreferencesStore.shared.accept(preferences, for: current)
			let candidates = preferences.inAppReminders ? feed.due(at: feed.serverTime) : []
			if let visible = due.first, visibleUntil > Date(), let item = candidates.first(where: { $0.id == visible.id && $0.reminder.revision == visible.reminder.revision }) { due = [item]; return }
			due = []
			if let item = candidates.first(where: { cadence.allows(Mango9ReminderCadence.key(session: current, item: $0), now: feed.serverTime, repeatMinutes: preferences.repeatMinutes) }) {
				due = [item]; visibleUntil = Date().addingTimeInterval(20)
				cadence.record(Mango9ReminderCadence.key(session: current, item: item), at: feed.serverTime)
				defaults.set(cadence.lastShown, forKey: cadenceKey)
			}
		} catch {
			guard generation == requestID else { return }
			due = []
		}
	}
}

struct Mango9InAppReminderHost: View {
	@Environment(\.scenePhase) private var scenePhase
	@ObservedObject private var telecom = TelecomManager.shared
	@StateObject private var store = Mango9InAppReminderStore()
	@State private var refreshID = UUID()
	@State private var selection: Mango9ReminderFeed.Item?
	@State private var metadata: Mango9CalendarMetadata?
	@State private var opening = false
	@State private var error: String?
	private var canPresent: Bool { scenePhase == .active && !telecom.callInProgress && !telecom.callDisplayed }

	var body: some View {
		Group {
			if canPresent, let item = store.due.first {
				HStack(spacing: 10) {
					Image(systemName: "bell.badge.fill").foregroundColor(.orange)
					VStack(alignment: .leading, spacing: 2) {
						Text(item.event.title).font(.subheadline.bold()).lineLimit(1)
						Text(error ?? "\(item.event.startAt.formatted(date: .omitted, time: .shortened)) · Appointment reminder")
							.font(.caption).foregroundColor(.secondary).lineLimit(2)
					}
					Spacer(minLength: 0)
					Button(opening ? "Opening…" : "View") { Task { await open(item) } }.disabled(opening)
				}.padding(12).background(Color(.secondarySystemBackground)).accessibilityIdentifier("appointment.reminderBanner")
			}
		}
		.task(id: "\(canPresent)-\(refreshID)") {
			guard canPresent else { store.reset(); return }
			while !Task.isCancelled {
				await store.refresh()
				do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in
			selection = nil; metadata = nil; error = nil; store.reset(); refreshID = UUID()
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AppointmentDidChange)) { _ in refreshID = UUID() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9CRMSettingsDidChange)) { _ in store.reset(); refreshID = UUID() }
		.onChange(of: canPresent) { if !$0 { selection = nil; metadata = nil; store.reset() } }
		.sheet(item: $selection, onDismiss: { refreshID = UUID() }) { item in
			if let session = store.session, let metadata {
				Mango9AppointmentDetail(event: item.event, session: session, metadata: metadata)
			}
		}
	}
	@MainActor private func open(_ item: Mango9ReminderFeed.Item) async {
		guard let session = store.session else { return }
		opening = true; error = nil
		defer { opening = false }
		do {
			let context = try await Mango9CalendarAPI.send(Mango9CalendarMetadata.self, session: session, path: "metadata")
			guard canPresent, Mango9SessionStore.isActive(session) else { return }
			metadata = context; selection = item
		} catch { self.error = "Unable to open. Check your connection and try again." }
	}
}

struct Mango9PushBackAppointment: View {
	@Environment(\.dismiss) private var dismiss
	let event: Mango9Appointment
	let session: Mango9Session
	@State private var minutes = 15
	@State private var busy = false
	@State private var blocked = false
	@State private var error: String?
	private var dates: DateInterval { Mango9AppointmentActions.pushedDates(event: event, minutes: minutes) }
	var body: some View {
		NavigationView {
			Form {
				Section("Move the appointment later") {
					Picker("Push back", selection: $minutes) {
						ForEach(Mango9AppointmentActions.minuteChoices, id: \.self) { Text("\($0) minutes").tag($0) }
					}.disabled(busy || blocked)
					Text(event.title).font(.headline)
					LabeledDate(label: "Current start", date: event.startAt)
					LabeledDate(label: "New start", date: dates.start)
					LabeledDate(label: "New end", date: dates.end)
				}
				Section {
					Text("This changes the shared CRM appointment, keeping its duration and status. The server checks overlaps and working hours before saving. Existing CRM update notifications still apply.")
					Text("Times shown in \(TimeZone.current.identifier)")
				}.font(.footnote).foregroundColor(.secondary)
				if dates.start <= Date() { Text("The new start is still in the past. Use Edit to choose a future time.").foregroundColor(.orange) }
				if let error { Text(error).foregroundColor(.red) }
				if busy { ProgressView() }
			}.navigationTitle("Push back").navigationBarTitleDisplayMode(.inline)
			.toolbar {
				ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) }
				ToolbarItem(placement: .confirmationAction) {
					Button("Save") { Task { await save() } }.disabled(busy || blocked || dates.start <= Date() || !event.permissions.canEdit)
				}
			}
		}.navigationViewStyle(.stack).tint(.mango9Primary).interactiveDismissDisabled(busy)
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
	}
	private struct LabeledDate: View {
		let label: String; let date: Date
		var body: some View { VStack(alignment: .leading, spacing: 4) { Text(label).foregroundColor(.secondary); Text(date.formatted(date: .abbreviated, time: .shortened)) } }
	}
	@MainActor private func save() async {
		busy = true; error = nil
		defer { busy = false }
		do {
			_ = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session, path: "events/\(event.id)", method: "PATCH",
				body: Mango9AppointmentActions.pushBackBody(event: event, minutes: minutes), revision: event.revision)
			NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
			dismiss()
		} catch {
			if let failure = error as? Mango9CalendarFailure, ["overlap", "out_of_calendar"].contains(failure.code) {
				self.error = failure.localizedDescription
			} else {
				blocked = true
				self.error = error.localizedDescription + " Close this screen and refresh the appointment before trying again."
			}
		}
	}
}
