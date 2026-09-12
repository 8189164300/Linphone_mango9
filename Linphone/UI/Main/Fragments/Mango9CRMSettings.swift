import SwiftUI

/// Two-line native menu label: the selection never steals width from the title.
/// Explicit vertical sizing lets Form/List measure wrapped Dynamic Type text on
/// its first layout, instead of correcting a clipped row after interaction.
struct Mango9CRMOptionLabel: View {
	let title: String
	let value: String
	var systemImage: String?
	var body: some View {
		HStack(alignment: .top, spacing: 12) {
			if let systemImage { Image(systemName: systemImage).frame(width: 26).accessibilityHidden(true) }
			VStack(alignment: .leading, spacing: 5) {
				Text(title).foregroundColor(.primary).fixedSize(horizontal: false, vertical: true)
				Text(value).font(.subheadline).fixedSize(horizontal: false, vertical: true)
			}
			.frame(maxWidth: .infinity, alignment: .leading)
			Image(systemName: "chevron.up.chevron.down").font(.caption).accessibilityHidden(true)
		}
		.foregroundColor(.mango9Primary).padding(.vertical, 4)
		.fixedSize(horizontal: false, vertical: true).contentShape(Rectangle())
		.accessibilityElement(children: .combine)
	}
}

private struct Mango9CRMOptionPicker<Selection: Hashable, Content: View>: View {
	let title: String
	let value: String
	@Binding var selection: Selection
	let content: Content
	init(_ title: String, value: String, selection: Binding<Selection>, @ViewBuilder content: () -> Content) {
		self.title = title; self.value = value; _selection = selection; self.content = content()
	}
	var body: some View {
		Menu { Picker(title, selection: $selection) { content } } label: {
			Mango9CRMOptionLabel(title: title, value: value)
		}.buttonStyle(.plain)
	}
}

extension Notification.Name {
	static let mango9CRMSettingsDidChange = Notification.Name("mango9CRMSettingsDidChange")
}

struct Mango9CRMPreferences: Decodable, Equatable {
	var inAppReminders = true
	var reminderMinutes = 15
	var repeatMinutes = 0
	var snoozeMinutes = 15
	var newAppointmentTimezone = "account"
	var firstWeekday = 0
	var defaultCalendarView = "appointments"
	var smsPush = true
	var teamChatPush = true
	var accountTimezone = "UTC"
	var revision = ""

	func body() -> [String: Any] {
		["in_app_reminders": inAppReminders, "reminder_minutes": reminderMinutes, "repeat_minutes": repeatMinutes,
		 "snooze_minutes": snoozeMinutes, "new_appointment_timezone": newAppointmentTimezone, "first_weekday": firstWeekday,
		 "default_calendar_view": defaultCalendarView, "sms_push": smsPush, "team_chat_push": teamChatPush]
	}
	var appointmentTimezone: TimeZone {
		if newAppointmentTimezone == "device" { return .current }
		return TimeZone(identifier: newAppointmentTimezone == "account" ? accountTimezone : newAppointmentTimezone) ?? .current
	}
	var calendarFirstWeekday: Int { firstWeekday == 0 ? Calendar.current.firstWeekday : firstWeekday }
}

/// Cached display defaults only. No credentials, login state or SIP configuration is changed.
@MainActor final class Mango9CRMPreferencesStore: ObservableObject {
	static let shared = Mango9CRMPreferencesStore()
	@Published private(set) var preferences: Mango9CRMPreferences?
	private var accountKey: String?
	func value(for session: Mango9Session?) -> Mango9CRMPreferences? {
		guard let session, accountKey == Mango9CalendarAPI.accountKey(session) else { return nil }
		return preferences
	}
	func accept(_ value: Mango9CRMPreferences, for session: Mango9Session) {
		guard Mango9SessionStore.isActive(session) else { return }
		accountKey = Mango9CalendarAPI.accountKey(session); preferences = value
	}
	func refresh(session: Mango9Session) async {
		do {
			let result = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: session, path: "settings", scope: .crm)
			accept(result, for: session)
		} catch { if accountKey == Mango9CalendarAPI.accountKey(session) { preferences = nil } }
	}
}

struct Mango9CRMSettings: View {
	var transport: URLSession = .shared
	var embedded = false
	var isExpanded = true
	@Environment(\.dismiss) private var dismiss
	@State private var session: Mango9Session?
	@State private var draft = Mango9CRMPreferences()
	@State private var loaded = false
	@State private var busy = false
	@State private var blocked = false
	@State private var error: String?
	@State private var saved = false
	@State private var baseline: Mango9CRMPreferences?
	@State private var reloadID = UUID()
	@State private var requestID = UUID()

	var body: some View {
		Group {
			if embedded {
				VStack(alignment: .leading, spacing: 16) {
					settingsContent.disabled(busy)
					HStack { Spacer(); saveControls }.buttonStyle(.borderedProminent).padding(.bottom, 16)
				}
			} else {
				Form { settingsContent }.disabled(busy)
					.navigationTitle("CRM Settings").navigationBarTitleDisplayMode(.inline).navigationBarHidden(false)
					.toolbar { ToolbarItem(placement: .confirmationAction) { saveControls } }
			}
		}
		.tint(.mango9Primary)
		.task(id: reloadID) {
			// Keep an unsaved draft when this inline section is collapsed/reopened.
			if isExpanded && !busy && (!loaded || draft == baseline) { await load() }
		}
		.onChange(of: isExpanded) { expanded in
			// Collapsing only hides this retained view: an in-flight read can finish.
			// Reopening during that read must not cancel it or leave an empty section.
			if expanded && !busy { reloadID = UUID() }
		}
		.onChange(of: draft) { if $0 != baseline { saved = false } }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in
			requestID = UUID(); busy = false; blocked = false; error = nil
			loaded = false; session = nil; baseline = nil; draft = Mango9CRMPreferences(); saved = false
			if embedded { reloadID = UUID() } else { dismiss() }
		}
	}

	@ViewBuilder private var settingsContent: some View {
			settingsSection {
				Text(session?.displayName ?? "Connect a CRM account").font(.headline)
				Text(session?.crmBaseUrl ?? "Sign in to manage settings.").font(.caption).foregroundColor(.secondary)
				Text("These preferences apply to this CRM account. Message-push choices apply to all of its Mango9 devices; calls and other accounts are not changed.").font(.footnote).foregroundColor(.secondary)
				if embedded { Text("Tap Save CRM settings below to apply your changes.").font(.footnote).foregroundColor(.secondary) }
			}
			if loaded {
				settingsSection("Appointments") {
					Toggle("In-app reminders", isOn: $draft.inAppReminders).accessibilityIdentifier("crm.inAppReminders")
					Mango9CRMOptionPicker("Suggested reminder", value: Mango9AppointmentActions.reminderLabel(draft.reminderMinutes), selection: $draft.reminderMinutes) {
						ForEach(Mango9AppointmentActions.reminderChoices, id: \.self) { Text(Mango9AppointmentActions.reminderLabel($0)).tag($0) }
					}.disabled(!draft.inAppReminders)
					Mango9CRMOptionPicker("Show reminder again", value: draft.repeatMinutes == 0 ? "Once on this device" : "Every \(draft.repeatMinutes) minutes", selection: $draft.repeatMinutes) {
						Text("Once on this device").tag(0)
						ForEach(Mango9AppointmentActions.minuteChoices, id: \.self) { Text("Every \($0) minutes").tag($0) }
					}.disabled(!draft.inAppReminders)
					Mango9CRMOptionPicker("Default snooze", value: "\(draft.snoozeMinutes) minutes", selection: $draft.snoozeMinutes) {
						ForEach(Mango9AppointmentActions.minuteChoices, id: \.self) { Text("\($0) minutes").tag($0) }
					}.disabled(!draft.inAppReminders)
					Text("Enable a reminder on each appointment. Banners appear while this account is selected and the app is open, and pause during calls. Snooze is saved to the CRM; it never changes the appointment time. Email/SMS appointment reminders are managed on the appointment itself.").font(.footnote).foregroundColor(.secondary)
				}
				settingsSection("Calendar") {
					Mango9CRMOptionPicker("Open appointments as", value: draft.defaultCalendarView == "calendar" ? "Calendar" : "Appointment list", selection: $draft.defaultCalendarView) {
						Text("Appointment list").tag("appointments")
						Text("Calendar").tag("calendar")
					}
					Mango9CRMOptionPicker("Week starts on", value: draft.firstWeekday == 0 ? "Device default" : draft.firstWeekday == 1 ? "Sunday" : "Monday", selection: $draft.firstWeekday) {
						Text("Device default").tag(0); Text("Sunday").tag(1); Text("Monday").tag(2)
					}
					NavigationLink {
						Mango9AppointmentTimezonePicker(selection: $draft.newAppointmentTimezone, accountTimezone: draft.accountTimezone)
					} label: {
						VStack(alignment: .leading, spacing: 4) {
							Text("New appointment time zone")
							Text(timezoneLabel).font(.subheadline).foregroundColor(.secondary)
						}
					}
					Text("The calendar grid and appointment list use your phone's time zone (\(TimeZone.current.identifier)). The selection above applies only when creating appointments; it does not move existing events.").font(.footnote).foregroundColor(.secondary)
				}
				settingsSection("Message notifications") {
					Toggle("SMS / MMS push", isOn: $draft.smsPush)
					Toggle("Team chat push", isOn: $draft.teamChatPush)
					Text("Turning a switch off stops new pushes from the server. Messages still arrive in your conversations. Pushes already handed to Apple cannot be recalled. iOS notification permission is also required.").font(.footnote).foregroundColor(.secondary)
					Button("Open iOS notification settings") {
						if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
					}
				}
			}
			if let error { settingsSection { Text(error).foregroundColor(.red); Button("Reload saved settings") { Task { await load() } } } }
	}

	@ViewBuilder private func settingsSection<Content: View>(_ title: String? = nil, @ViewBuilder content: () -> Content) -> some View {
		if embedded {
			VStack(alignment: .leading, spacing: 18) {
				if let title { Text(title).font(.headline).foregroundColor(.primary) }
				content()
			}.frame(maxWidth: .infinity, alignment: .leading).padding(20)
				.background(Color(.systemBackground)).cornerRadius(15)
		} else {
			Section { content() } header: { if let title { Text(title) } }
		}
	}

	private var saveControls: some View {
				HStack(spacing: 8) {
					ZStack {
						ProgressView().opacity(busy ? 1 : 0)
						Image(systemName: "checkmark.circle.fill").foregroundColor(.green).opacity(saved && !busy ? 1 : 0)
					}.frame(width: 22, height: 22).accessibilityElement(children: .ignore)
					.accessibilityLabel(busy ? "Saving or loading settings" : "Settings saved").accessibilityHidden(!busy && !saved)
					Button(embedded ? "Save CRM settings" : "Save") { Task { await save() } }.disabled(!loaded || busy || blocked || draft == baseline)
				}
	}
	private var timezoneLabel: String { draft.newAppointmentTimezone == "account" ? "CRM default · \(draft.accountTimezone)" : draft.newAppointmentTimezone == "device" ? "Device · \(TimeZone.current.identifier)" : draft.newAppointmentTimezone }
	@MainActor private func load() async {
		guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(current) else { session = nil; loaded = false; return }
		let operation = UUID(); requestID = operation
		if session.map(Mango9CalendarAPI.accountKey) != Mango9CalendarAPI.accountKey(current) { loaded = false }
		session = current; busy = true; error = nil; saved = false; blocked = false
		defer { if requestID == operation { busy = false } }
		do {
			let result = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: current, path: "settings", transport: transport, scope: .crm)
			guard requestID == operation, !Task.isCancelled, Mango9SessionStore.isActive(current) else { return }
			draft = result
			Mango9CRMPreferencesStore.shared.accept(draft, for: current); loaded = true; baseline = draft
		} catch {
			guard requestID == operation, !Task.isCancelled, Mango9SessionStore.isActive(current) else { return }
			blocked = true; self.error = error.localizedDescription
		}
	}
	@MainActor private func save() async {
		guard let session, loaded, !busy, !blocked else { return }
		let operation = UUID(); requestID = operation
		busy = true; error = nil
		defer { if requestID == operation { busy = false } }
		do {
			let result = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: session, path: "settings", method: "PATCH", body: draft.body(), revision: draft.revision, transport: transport, scope: .crm)
			guard requestID == operation, Mango9SessionStore.isActive(session) else { return }
			draft = result
			Mango9CRMPreferencesStore.shared.accept(draft, for: session)
			NotificationCenter.default.post(name: .mango9CRMSettingsDidChange, object: nil)
			saved = true; baseline = draft
		} catch {
			guard requestID == operation, Mango9SessionStore.isActive(session) else { return }
			blocked = true; self.error = error.localizedDescription + " Reload saved settings before trying again."
		}
	}
}

private struct Mango9AppointmentTimezonePicker: View {
	@Binding var selection: String
	let accountTimezone: String
	@State private var search = ""
	var body: some View {
		List {
			Button { selection = "account" } label: { row("CRM default · \(accountTimezone)", id: "account") }
			Button { selection = "device" } label: { row("Device · \(TimeZone.current.identifier)", id: "device") }
			ForEach(TimeZone.knownTimeZoneIdentifiers.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) }, id: \.self) { zone in
				Button { selection = zone } label: { row(zone.replacingOccurrences(of: "_", with: " "), id: zone) }
			}
		}.navigationTitle("Time zone").navigationBarTitleDisplayMode(.inline)
			.navigationBarHidden(false).searchable(text: $search)
	}
	private func row(_ title: String, id: String) -> some View { HStack { Text(title).foregroundColor(.primary); Spacer(); if selection == id { Image(systemName: "checkmark").foregroundColor(.mango9Primary) } } }
}
