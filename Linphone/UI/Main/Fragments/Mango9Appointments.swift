import SwiftUI

extension Notification.Name {
	static let mango9AppointmentDidChange = Notification.Name("mango9AppointmentDidChange")
}

extension Mango9Appointment {
	// Source is deliberately represented only by color, not a campaign/shared badge.
	var tint: Color { origin == "campaign" ? .purple : .mango9Primary }
}

/// Presentation only. Dates remain authoritative and priority never overrides
/// chronological order. Unknown/custom CRM statuses retain their exact name.
enum Mango9AppointmentAgenda {
	enum Bucket: Int { case dueNow, upcoming, past, closed }
	struct Section: Identifiable {
		let bucket: Bucket
		let day: Date
		var events: [Mango9Appointment]
		var id: String { "\(bucket.rawValue)|\(day.timeIntervalSince1970)" }
	}
	static func isClosed(_ event: Mango9Appointment) -> Bool {
		["completed", "complete", "done", "cancelled", "canceled", "no show", "no-show"].contains(
			event.status?.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "")
	}
	static func bucket(_ event: Mango9Appointment, now: Date) -> Bucket {
		if isClosed(event) { return .closed }
		if event.endAt <= now { return .past }
		return event.startAt <= now ? .dueNow : .upcoming
	}
	static func sections(_ events: [Mango9Appointment], now: Date, calendar: Calendar = .current) -> [Section] {
		let sorted = events.sorted {
			let a = bucket($0, now: now); let b = bucket($1, now: now)
			if a != b { return a.rawValue < b.rawValue }
			if $0.startAt != $1.startAt {
				return a == .past || a == .closed ? $0.startAt > $1.startAt : $0.startAt < $1.startAt
			}
			return $0.id < $1.id
		}
		var result: [Section] = []
		for event in sorted {
			let day = calendar.startOfDay(for: event.startAt); let category = bucket(event, now: now)
			if let last = result.last, last.day == day, last.bucket == category {
				result[result.count - 1].events.append(event)
			} else { result.append(Section(bucket: category, day: day, events: [event])) }
		}
		return result
	}
	static func timing(_ event: Mango9Appointment, now: Date) -> String? {
		switch bucket(event, now: now) {
		case .closed: return nil
		case .past: return "Past appointment"
		case .dueNow: return "Due now · \(duration(event.endAt.timeIntervalSince(now))) remaining"
		case .upcoming:
			let seconds = event.startAt.timeIntervalSince(now)
			return seconds < 60 ? "Starting in less than a minute" : "Upcoming in \(duration(seconds))"
		}
	}
	static func duration(_ seconds: TimeInterval) -> String {
		let minutes = max(1, Int(ceil(seconds / 60)))
		if minutes < 60 { return "\(minutes) \(minutes == 1 ? "minute" : "minutes")" }
		let hours = minutes / 60; let remainder = minutes % 60
		if hours < 48 {
			return "\(hours) \(hours == 1 ? "hour" : "hours")" + (remainder > 0 ? " \(remainder) min" : "")
		}
		return "\(hours / 24) days"
	}
}

@MainActor final class Mango9AppointmentsStore: ObservableObject {
	@Published var events: [Mango9Appointment] = []
	@Published var metadata: Mango9CalendarMetadata?
	@Published var error: String?
	@Published var calendarError: String?
	@Published var loading = false
	@Published var calendarRevision = UUID()
	private(set) var session: Mango9Session?
	private var generation = UUID()
	let contact: Mango9AppointmentContact?
	let transport: URLSession

	init(contact: Mango9AppointmentContact? = nil, transport: URLSession = .shared) { self.contact = contact; self.transport = transport }

	func reset() {
		generation = UUID()
		session = nil
		events = []
		metadata = nil
		error = nil
		calendarError = nil
		loading = false
		calendarRevision = UUID()
	}

	func load(month: Date) async {
		guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(current) else {
			reset(); error = "Connect your CRM account to view appointments."; return
		}
		if session.map(Mango9CalendarAPI.accountKey) != Mango9CalendarAPI.accountKey(current) { reset() }
		session = current
		let requestID = UUID()
		generation = requestID
		loading = true
		error = nil
		// Keep the same-account sheet's context mounted during a background read.
		// Clearing it here destroys the detail/editor and loses local UI state.
		defer { if generation == requestID { loading = false } }
		do {
			let context = try await Mango9CalendarAPI.send(Mango9CalendarMetadata.self, session: current, path: "metadata", transport: transport)
			let range = Self.monthRange(month)
			let values = try await Mango9CalendarAPI.events(session: current, start: range.start, end: range.end, contactID: contact?.id, transport: transport)
			guard generation == requestID, Mango9SessionStore.isActive(current), !Task.isCancelled else { return }
			metadata = context
			events = values
			calendarRevision = UUID()
		} catch {
			guard generation == requestID, !Task.isCancelled else { return }
			// Do not leave revoked/shared appointments visible after a failed refresh.
			metadata = nil
			events = []
			self.error = error.localizedDescription
			calendarRevision = UUID()
		}
	}

	static func monthRange(_ date: Date) -> DateInterval {
		Calendar.current.dateInterval(of: .month, for: date)!
	}
}

struct Mango9AppointmentsFragment: View {
	@ObservedObject private var preferencesStore = Mango9CRMPreferencesStore.shared
	@Environment(\.presentationMode) private var presentationMode
	@Environment(\.scenePhase) private var scenePhase
	@StateObject private var store: Mango9AppointmentsStore
	@State private var date = Date()
	@State private var calendarMode = false
	@State private var visibleCalendarDate = Date()
	@State private var selected: Mango9Appointment?
	@State private var creating = false
	@State private var statusID = -1
	@State private var search = ""
	@State private var wasBackgrounded = false
	@State private var refreshID = UUID()

	init(contact: Mango9AppointmentContact? = nil, date: Date = Date()) {
		_store = StateObject(wrappedValue: Mango9AppointmentsStore(contact: contact))
		_date = State(initialValue: date)
		_visibleCalendarDate = State(initialValue: date)
	}

	init(store: Mango9AppointmentsStore, date: Date, calendarMode: Bool = false, creating: Bool = false) {
		_store = StateObject(wrappedValue: store)
		_date = State(initialValue: date)
		_visibleCalendarDate = State(initialValue: date)
		_calendarMode = State(initialValue: calendarMode)
		_creating = State(initialValue: creating)
	}

	private var filtered: [Mango9Appointment] {
		store.events.filter {
			(statusID == -1 || ($0.status?.id ?? 0) == statusID) &&
			(search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) ||
			 ($0.contact?.name?.localizedCaseInsensitiveContains(search) ?? false))
		}
	}

	var body: some View {
		VStack(spacing: 0) {
			header
			VStack(spacing: 12) {
				Picker("View", selection: $calendarMode) {
					Text("Appointments").tag(false)
					Text("Calendar").tag(true)
				}.pickerStyle(.segmented)
				if !calendarMode {
					HStack {
						Button { moveMonth(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 36) }
						Spacer()
						Text(date, format: .dateTime.month(.wide).year()).font(.headline)
						Spacer()
						Button { moveMonth(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 36) }
					}
					HStack {
						Image(systemName: "magnifyingglass").foregroundColor(.secondary)
						TextField("Search appointments or contacts", text: $search)
						Menu {
							Picker("Status", selection: $statusID) {
								Text("All statuses").tag(-1)
								Text("No status").tag(0)
								ForEach(store.metadata?.statuses ?? []) { Text($0.name).tag($0.id) }
							}
						} label: { Image(systemName: statusID == -1 ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill").padding(8) }
					}.padding(8).background(Color(.secondarySystemGroupedBackground)).cornerRadius(12)
				}
			}.padding(16)
			if let error = store.error ?? (calendarMode ? store.calendarError : nil) {
				HStack(alignment: .top, spacing: 8) {
					Image(systemName: "exclamationmark.triangle").foregroundColor(.orange)
					Text(error).font(.caption).foregroundColor(.secondary).frame(maxWidth: .infinity, alignment: .leading)
					Button("Retry") { Task { await store.load(month: date) } }
				}.padding().accessibilityIdentifier("appointments.error")
			}
			if calendarMode {
				if #available(iOS 18, *) {
					Mango9ExyteCalendar(session: store.session, contactID: store.contact?.id,
						date: $date, revision: store.calendarRevision, onSelect: { selected = $0 },
						onError: { store.calendarError = $0 }, onVisibleMonth: { visibleCalendarDate = $0 }, transport: store.transport)
						.id(store.session.map(Mango9CalendarAPI.accountKey) ?? "calendar-no-account")
						.accessibilityIdentifier("appointments.calendar")
				} else {
					Mango9LegacyCalendar(session: store.session, contactID: store.contact?.id,
						date: $date, revision: store.calendarRevision,
						firstWeekday: preferencesStore.value(for: store.session)?.calendarFirstWeekday ?? Calendar.current.firstWeekday,
						onSelect: { selected = $0 }, onError: { store.calendarError = $0 },
						onVisibleMonth: { visibleCalendarDate = $0 }, transport: store.transport)
						.id(store.session.map(Mango9CalendarAPI.accountKey) ?? "calendar-no-account")
				}
			} else {
				// Update countdown/order locally, without another API request, changing
				// the list identity, or interrupting the open detail/editor sheet.
				TimelineView(.periodic(from: .now, by: 30)) { context in appointmentList(now: context.date) }
			}
		}
		.background(Color(.systemGroupedBackground).ignoresSafeArea())
		.tint(.mango9Primary)
		.navigationBarHidden(true)
		.task(id: "\(Calendar.current.dateComponents([.year, .month], from: date))|\(refreshID)") { await store.load(month: date) }
		.task {
			if let session = Mango9SessionStore.load() {
				await preferencesStore.refresh(session: session)
				if preferencesStore.value(for: session)?.defaultCalendarView == "calendar" { calendarMode = true }
			}
		}
		.onChange(of: calendarMode) { if !$0 { date = visibleCalendarDate } else { visibleCalendarDate = date } }
		.onChange(of: scenePhase) { phase in
			// Native menus/permission prompts can briefly make a scene inactive.
			// Refresh only after an actual background -> foreground transition.
			if phase == .background { wasBackgrounded = true }
			else if phase == .active && wasBackgrounded {
				wasBackgrounded = false
				refreshID = UUID()
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AppointmentDidChange)) { _ in
			refreshID = UUID()
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in
			selected = nil; creating = false; statusID = -1; search = ""; store.reset()
			refreshID = UUID()
		}
		.sheet(item: $selected) { event in
			if let session = store.session, let metadata = store.metadata {
				Mango9AppointmentDetail(event: event, session: session, metadata: metadata)
			}
		}
		.sheet(isPresented: $creating) {
			if let session = store.session, let metadata = store.metadata {
				Mango9AppointmentEditor(session: session, metadata: metadata, contact: store.contact, date: date)
			}
		}
	}

	private var header: some View {
		HStack(spacing: 10) {
			Button { presentationMode.wrappedValue.dismiss() } label: {
				Image(systemName: "chevron.left").font(.title3).frame(width: 44, height: 44)
			}.accessibilityLabel("Back")
			VStack(alignment: .leading, spacing: 2) {
				Text("Appointments").font(.title2.bold()).foregroundColor(.primary)
				Text(store.contact?.displayName ?? "Your CRM calendar").font(.caption).foregroundColor(.secondary).lineLimit(1)
			}
			Spacer()
			Button { date = Date() } label: {
				Image(systemName: "calendar").opacity(store.loading ? 0 : 1)
					.overlay { if store.loading { ProgressView() } }.frame(width: 36, height: 44)
			}.disabled(store.loading).accessibilityLabel(store.loading ? "Loading appointments" : "Today")
			Button { creating = true } label: { Image(systemName: "plus").font(.title3.bold()).frame(width: 44, height: 44) }
				.disabled(store.metadata?.capabilities.create != true || store.loading)
				.accessibilityLabel("Create appointment").accessibilityIdentifier("appointments.create")
		}.padding(.horizontal, 8).padding(.vertical, 8).background(Color(.systemBackground))
	}

	private func appointmentList(now: Date) -> some View {
		let events = filtered
		let sections = Mango9AppointmentAgenda.sections(events, now: now)
		return List {
			if events.isEmpty && !store.loading && store.error == nil {
				VStack(spacing: 12) {
					Image(systemName: "calendar.badge.clock").font(.largeTitle).foregroundColor(.mango9Primary)
					Text(search.isEmpty && statusID == -1 ? "No appointments this month" : "No matching appointments").font(.headline)
					Text("Choose another month or create an appointment.").font(.subheadline).foregroundColor(.secondary)
				}.frame(maxWidth: .infinity).padding(.vertical, 24).listRowBackground(Color.clear)
			}
			ForEach(sections) { section in
				Section(header: VStack(alignment: .leading, spacing: 3) {
					if section.bucket == .dueNow { Text("Due now").foregroundColor(.orange) }
					else if section.bucket == .past { Text("Past appointments") }
					else if section.bucket == .closed { Text("Completed or closed") }
					Text(section.day, format: .dateTime.weekday(.wide).month(.abbreviated).day())
				}.textCase(nil)) {
					ForEach(section.events) { event in
						Button { selected = event } label: { Mango9AppointmentRow(event: event, now: now) }.buttonStyle(.plain)
					}
				}
			}
		}.listStyle(.insetGrouped).refreshable { await store.load(month: date) }
	}

	private func moveMonth(_ value: Int) {
		date = Calendar.current.date(byAdding: .month, value: value, to: date) ?? date
	}
}

struct Mango9AppointmentRow: View {
	let event: Mango9Appointment
	var now: Date = Date()
	@Environment(\.dynamicTypeSize) private var typeSize
	private var activityIcon: String {
		switch event.activity.lowercased() {
		case "call": return "phone.fill"
		case "meeting": return "person.2.fill"
		case "task": return "checklist"
		case "follow_up": return "arrow.turn.up.right"
		default: return "calendar"
		}
	}
	private var priorityColor: Color {
		switch event.priority.lowercased() {
		case "urgent", "high": return .red
		case "medium", "normal": return .orange
		case "low": return .green
		default: return .secondary
		}
	}
	private var dueNow: Bool { Mango9AppointmentAgenda.bucket(event, now: now) == .dueNow }
	private var statusText: String {
		let name = event.status?.name.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		return name.isEmpty ? "Status not set" : name
	}
	private var statusColor: Color {
		let hex = event.status?.color?.trimmingCharacters(in: CharacterSet(charactersIn: "#")) ?? ""
		guard hex.count == 6, let rgb = UInt32(hex, radix: 16) else { return .mango9Primary }
		return Color(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255)
	}
	private var indicators: some View {
		HStack(spacing: 6) {
			Image(systemName: activityIcon).foregroundColor(.purple)
				.accessibilityLabel(event.activity.replacingOccurrences(of: "_", with: " "))
			if !event.priority.isEmpty {
				Image(systemName: "flag.fill").foregroundColor(priorityColor)
					.accessibilityLabel("\(event.priority.capitalized) priority")
			}
			if !event.reminders.isEmpty {
				Image(systemName: "bell.fill").foregroundColor(.orange).accessibilityLabel("Appointment reminder set")
			}
		}.font(.caption.weight(.semibold)).padding(8)
			.background(Color.mango9Primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
	}
	var body: some View {
		HStack(alignment: .top, spacing: 12) {
			VStack(alignment: .leading, spacing: 9) {
				HStack(alignment: .top, spacing: 8) {
					Text(event.title).font(.headline).foregroundColor(.primary).frame(maxWidth: .infinity, alignment: .leading)
					if !typeSize.isAccessibilitySize { indicators }
				}
				if typeSize.isAccessibilitySize { HStack { Spacer(); indicators } }
				Label(statusText, systemImage: "tag")
					.font(.caption.weight(.semibold)).foregroundColor(.primary)
					.padding(.horizontal, 9).padding(.vertical, 5)
					.background(statusColor.opacity(0.13), in: RoundedRectangle(cornerRadius: 8))
					.accessibilityLabel("Status: \(statusText)")
				Text("\(event.startAt.formatted(date: .omitted, time: .shortened)) – \(event.endAt.formatted(date: .omitted, time: .shortened))")
					.font(.subheadline).foregroundColor(.secondary)
				if let contact = event.contact { Label(contact.displayName, systemImage: "person.crop.circle").font(.caption).foregroundColor(.secondary) }
				if let timing = Mango9AppointmentAgenda.timing(event, now: now) {
					Label(timing, systemImage: dueNow ? "clock.badge.exclamationmark" : "clock")
						.font(.caption.weight(.semibold)).foregroundColor(dueNow ? .orange : .mango9Primary)
				}
				if event.isRecurring { Label("Recurring · manage on web", systemImage: "repeat").font(.caption).foregroundColor(.secondary) }
			}
		}.padding(.leading, 14).padding(.vertical, 10)
			.overlay(alignment: .leading) { RoundedRectangle(cornerRadius: 3).fill(event.tint).frame(width: 4) }
			.fixedSize(horizontal: false, vertical: true)
			.accessibilityElement(children: .combine)
	}
}

struct Mango9AppointmentDetail: View {
	@ObservedObject private var preferencesStore = Mango9CRMPreferencesStore.shared
	@Environment(\.dismiss) private var dismiss
	@State var event: Mango9Appointment
	let session: Mango9Session
	let metadata: Mango9CalendarMetadata
	var transport: URLSession = .shared
	@State private var editing = false
	@State private var deleting = false
	@State private var pushingBack = false
	@State private var personalReminder: Mango9PersonalReminder?
	@State private var reminderError: String?
	@State private var busy = true
	@State private var error: String?
	@State private var detailRefreshID = UUID()

	var body: some View {
		NavigationView {
			List {
				Section {
					if let contact = event.contact {
						NavigationLink {
							Mango9LeadDetailFragment(leadId: contact.id, recordKind: contact.kind == "client" ? .client : .lead)
						} label: { Text(event.title).font(.title2.bold()).foregroundColor(event.tint) }
						.disabled(busy || error != nil)
						.accessibilityHint("Opens the linked \(contact.kind)")
					} else { Text(event.title).font(.title2.bold()) }
					Label(event.startAt.formatted(date: .abbreviated, time: .shortened), systemImage: "calendar")
					Label("Until " + event.endAt.formatted(date: .abbreviated, time: .shortened), systemImage: "clock")
					Text("Shown in \(TimeZone.current.identifier)").font(.caption).foregroundColor(.secondary)
				}
				Section("Details") {
					Menu {
						Button("No status") { Task { await changeStatus(nil) } }
						ForEach(metadata.statuses) { status in
							Button(status.name) { Task { await changeStatus(status.id) } }
						}
					} label: {
						HStack { Text("Status").foregroundColor(.primary); Spacer(); Text(event.status?.name ?? "No status"); if event.permissions.canEdit { Image(systemName: "chevron.up.chevron.down").font(.caption) } }
					}.disabled(busy || error != nil || !event.permissions.canEdit).accessibilityIdentifier("appointment.status")
					HStack { Text("Activity"); Spacer(); Text(event.activity.replacingOccurrences(of: "_", with: " ").capitalized) }
					HStack { Text("Priority"); Spacer(); Text(event.priority.capitalized) }
					if !event.description.isEmpty { Text(event.description) }
				}
				if event.permissions.canEdit {
					Section { Button { pushingBack = true } label: { Label("Push back appointment", systemImage: "clock.arrow.circlepath") }.disabled(busy || error != nil) }
				}
				if metadata.capabilities.inAppReminders == true && !event.isRecurring { reminderSection }
				if let contact = event.contact {
					Section("Linked " + (contact.kind == "client" ? "client" : "lead")) {
						NavigationLink(contact.displayName) {
							Mango9LeadDetailFragment(leadId: contact.id, recordKind: contact.kind == "client" ? .client : .lead)
						}
					}
				}
				if let reason = event.permissions.readOnlyReason, !reason.isEmpty {
					Section { Text("Manage this appointment in the web calendar.").font(.footnote).foregroundColor(.secondary) }
				}
				if let error { Section { Text(error).foregroundColor(.red); Button("Refresh appointment") { Task { await refresh() } }.disabled(busy) } }
				if event.permissions.canDelete {
					Section { Button("Delete appointment", role: .destructive) { deleting = true }.disabled(busy || error != nil) }
				}
			}.navigationTitle("Appointment").navigationBarTitleDisplayMode(.inline)
			.toolbar {
				ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
				ToolbarItem(placement: .primaryAction) {
					if event.permissions.canEdit || event.permissions.canShare {
						Button { editing = true } label: {
							// Keep one native toolbar button. An invisible spinner beside
							// the label adds empty space inside the system's button capsule.
							Text("Edit").opacity(busy ? 0 : 1)
								.overlay { if busy { ProgressView().accessibilityHidden(true) } }
						}
						.disabled(busy || error != nil)
						.accessibilityLabel("Edit appointment")
						.accessibilityValue(busy ? "Loading" : "")
						.accessibilityIdentifier("appointment.edit")
					} else if busy {
						ProgressView().accessibilityLabel("Loading appointment")
					}
				}
			}
		}.navigationViewStyle(.stack).tint(.mango9Primary)
		.task(id: detailRefreshID) { await refresh() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
		.sheet(isPresented: $editing, onDismiss: { detailRefreshID = UUID() }) {
			Mango9AppointmentEditor(session: session, metadata: metadata, event: event)
		}
		.sheet(isPresented: $pushingBack, onDismiss: { detailRefreshID = UUID() }) {
			Mango9PushBackAppointment(event: event, session: session)
		}
		.confirmationDialog("Delete this appointment?", isPresented: $deleting, titleVisibility: .visible) {
			Button("Delete appointment", role: .destructive) { Task { await remove() } }
		} message: { Text("This also removes it from the shared CRM calendar. This cannot be undone.") }
	}

	private func refresh() async {
		busy = true
		defer { busy = false }
		do {
			event = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session, path: "events/\(event.id)", transport: transport)
			error = nil
			reminderError = nil
			if metadata.capabilities.inAppReminders == true && !event.isRecurring {
				do { personalReminder = try await Mango9CalendarAPI.send(Mango9PersonalReminder.self, session: session, path: "events/\(event.id)/reminder", transport: transport) }
				catch { personalReminder = nil; reminderError = error.localizedDescription }
			} else { personalReminder = nil }
		} catch { self.error = error.localizedDescription }
	}
	private var reminderSection: some View {
		Section {
			if personalReminder?.enabled != true, let preferences = preferencesStore.value(for: session), preferences.inAppReminders {
				Button("Use suggested reminder · " + Mango9AppointmentActions.reminderLabel(preferences.reminderMinutes)) {
					Task { await reminderAction(["action": "configure", "minutes_before": preferences.reminderMinutes]) }
				}.disabled(busy || error != nil || personalReminder == nil || event.endAt <= Date())
			}
			if preferencesStore.value(for: session)?.inAppReminders == false { Text("In-app reminders are paused in CRM Settings.").font(.footnote).foregroundColor(.orange) }
			Menu {
				Button("Off") { Task { await reminderAction(["action": "configure", "minutes_before": -1]) } }
				ForEach(Mango9AppointmentActions.reminderChoices, id: \.self) { minutes in
					Button(Mango9AppointmentActions.reminderLabel(minutes)) { Task { await reminderAction(["action": "configure", "minutes_before": minutes]) } }
				}
			} label: {
				Mango9CRMOptionLabel(title: "In-app reminder", value: personalReminder?.minutesBefore.map(Mango9AppointmentActions.reminderLabel) ?? (busy ? "Loading…" : "Off"), systemImage: "bell")
			}.buttonStyle(.plain).disabled(busy || error != nil || personalReminder == nil || event.endAt <= Date())
			.accessibilityIdentifier("appointment.personalReminder")
			if personalReminder?.enabled == true {
				if let preferences = preferencesStore.value(for: session) {
					Button("Snooze \(preferences.snoozeMinutes) minutes") {
						Task { await reminderAction(["action": "snooze", "until": Mango9CalendarAPI.timestamp(Date().addingTimeInterval(Double(preferences.snoozeMinutes * 60)))]) }
					}.disabled(busy || error != nil || event.endAt <= Date())
				}
				if let due = personalReminder?.dueAt { Text("Next reminder: " + due.formatted(date: .abbreviated, time: .shortened)).font(.footnote).foregroundColor(.secondary) }
				else if personalReminder?.dismissed == true { Text("Dismissed for this appointment time.").font(.footnote).foregroundColor(.secondary) }
				Menu("Snooze reminder") {
					ForEach(Mango9AppointmentActions.minuteChoices, id: \.self) { minutes in
						Button("\(minutes) minutes") { Task { await reminderAction(["action": "snooze", "until": Mango9CalendarAPI.timestamp(Date().addingTimeInterval(Double(minutes * 60)))]) } }
					}
				}.disabled(busy || error != nil || event.endAt <= Date())
				Button("Dismiss reminder") { Task { await reminderAction(["action": "dismiss"]) } }.disabled(busy || error != nil || personalReminder?.dismissed == true)
			}
			if personalReminder?.needsReenable == true { Text("The appointment status or owner changed. Choose a reminder time to enable it again.").font(.footnote).foregroundColor(.orange) }
			if let reminderError { Text(reminderError).foregroundColor(.red); Button("Refresh reminder") { Task { await refresh() } }.disabled(busy) }
		} header: { Text("Your reminder") } footer: {
			Text("Only for you, while this CRM account is selected and the app is open. Snoozing does not move the appointment or send email/SMS. Manage defaults in CRM Settings.")
		}
	}
	private func changeStatus(_ status: Int?) async {
		guard !busy, event.permissions.canEdit, status != event.status?.id else { return }
		busy = true; error = nil
		defer { busy = false }
		do {
			event = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session, path: "events/\(event.id)", method: "PATCH",
				body: ["status_id": status as Any? ?? NSNull()], revision: event.revision, transport: transport)
			NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
			await refresh()
		} catch { self.error = error.localizedDescription + " Refresh before trying again." }
	}
	private func reminderAction(_ input: [String: Any]) async {
		guard !busy, let state = personalReminder else { return }
		busy = true; reminderError = nil
		defer { busy = false }
		var body = input; body["reminder_revision"] = state.revision
		do {
			personalReminder = try await Mango9CalendarAPI.send(Mango9PersonalReminder.self, session: session, path: "events/\(event.id)/reminder", method: "PATCH", body: body, revision: event.revision, transport: transport)
			NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
		} catch {
			personalReminder = nil // Uncertain writes must be re-read, not blindly repeated.
			reminderError = error.localizedDescription + " Refresh to check the saved reminder."
		}
	}
	private func remove() async {
		busy = true
		defer { busy = false }
		do {
			_ = try await Mango9CalendarAPI.send(Mango9CalendarAPI.Empty.self, session: session, path: "events/\(event.id)", method: "DELETE", revision: event.revision, transport: transport)
			NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
			dismiss()
		} catch { self.error = error.localizedDescription }
	}
}

struct Mango9AppointmentEditor: View {
	@ObservedObject private var preferencesStore = Mango9CRMPreferencesStore.shared
	@Environment(\.dismiss) private var dismiss
	let session: Mango9Session
	let metadata: Mango9CalendarMetadata
	let event: Mango9Appointment?
	@State private var title: String
	@State private var notes: String
	@State private var start: Date
	@State private var end: Date
	@State private var activity: String
	@State private var priority: String
	@State private var status: Int
	@State private var contact: Mango9AppointmentContact?
	@State private var shares: Set<Int>
	@State private var assignee = 0
	@State private var reminder: Int
	@State private var emailReminder: Bool
	@State private var smsReminder: Bool
	@State private var choosingContact = false
	@State private var busy = false
	@State private var blocked = false
	@State private var error: String?
	@State private var confirmAssignment = false

	init(session: Mango9Session, metadata: Mango9CalendarMetadata, event: Mango9Appointment? = nil,
		contact: Mango9AppointmentContact? = nil, date: Date = Date()) {
		self.session = session; self.metadata = metadata; self.event = event
		_title = State(initialValue: event?.title ?? "")
		_notes = State(initialValue: event?.description ?? "")
		let start = event?.startAt ?? date
		_start = State(initialValue: start)
		_end = State(initialValue: event?.endAt ?? start.addingTimeInterval(1800))
		_activity = State(initialValue: event?.activity ?? "appointment")
		_priority = State(initialValue: event?.priority ?? "medium")
		_status = State(initialValue: event?.status?.id ?? 0)
		_contact = State(initialValue: event?.contact ?? contact)
		_shares = State(initialValue: Set(event?.sharedByMeUserIds ?? []))
		_reminder = State(initialValue: event?.reminders.first?.minutesBefore ?? -1)
		_emailReminder = State(initialValue: event?.reminders.first?.channels.contains("email") ?? true)
		_smsReminder = State(initialValue: event?.reminders.first?.channels.contains("sms") ?? false)
	}

	private var canEdit: Bool { event?.permissions.canEdit ?? true }
	private var editingTimezone: TimeZone { event == nil ? (preferencesStore.value(for: session)?.appointmentTimezone ?? TimeZone(identifier: metadata.timezone) ?? .current) : .current }
	private var isOwner: Bool { event == nil || String(event!.ownerId) == session.userId }
	private var valid: Bool { !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && end > start && (reminder < 0 || emailReminder || smsReminder) }

	var body: some View {
		NavigationView {
			Form {
				Section("Appointment") {
					TextField("Title", text: $title).accessibilityIdentifier("appointment.title")
					DatePicker("Starts", selection: $start)
					DatePicker("Ends", selection: $end)
					Text("Times shown in \(editingTimezone.identifier)").font(.caption).foregroundColor(.secondary)
					Picker("Status", selection: $status) {
						Text("No status").tag(0)
						ForEach(metadata.statuses) { Text($0.name).tag($0.id) }
					}
					Picker("Activity", selection: $activity) { ForEach(metadata.activities, id: \.self) { Text($0.replacingOccurrences(of: "_", with: " ").capitalized).tag($0) } }
					Picker("Priority", selection: $priority) { ForEach(metadata.priorities, id: \.self) { Text($0.capitalized).tag($0) } }
				}.disabled(!canEdit)
				Section("Notes") { TextEditor(text: $notes).frame(minHeight: 90).disabled(!canEdit) }
				Section("Lead or client") {
					if let contact {
						Label(contact.displayName, systemImage: "person.crop.circle")
						if event?.permissions.canChangeContact ?? true { Button("Remove link", role: .destructive) { self.contact = nil } }
					}
					if event?.permissions.canChangeContact ?? true {
						Button(contact == nil ? "Link a lead or client" : "Change linked record") { choosingContact = true }
					}
				}
				if event?.permissions.canShare ?? true, !metadata.shareRecipients.isEmpty {
					Section(header: Text("Share with team"), footer: Text("Share this appointment without changing its owner or the linked CRM record.")) {
						ForEach(metadata.shareRecipients) { person in
							Toggle(person.name, isOn: Binding(get: { shares.contains(person.id) }, set: { if $0 { shares.insert(person.id) } else { shares.remove(person.id) } }))
						}
					}
				}
				if (event?.permissions.canAssign ?? metadata.capabilities.assign), !metadata.assignees.isEmpty {
					Section(header: Text("Assign appointment"), footer: Text("Transfers ownership of this appointment only. You may lose access unless it is shared back to you.")) {
						Picker("Owner", selection: $assignee) {
							Text("Keep current owner").tag(0)
							ForEach(metadata.assignees) { Text($0.name).tag($0.id) }
						}
					}
				}
				if isOwner && canEdit {
					Section("Reminder") {
						Picker("Remind before", selection: $reminder) {
							Text("None").tag(-1)
							ForEach(metadata.reminderMinutes, id: \.self) { Text($0 == 0 ? "At start" : "\($0) minutes").tag($0) }
						}
						if reminder >= 0 {
							if metadata.reminderChannels.contains("email") { Toggle("Email", isOn: $emailReminder) }
							if metadata.reminderChannels.contains("sms") { Toggle("SMS", isOn: $smsReminder) }
						}
					}
				}
				if let error { Section { Text(error).foregroundColor(.red) } }
				if end <= start { Text("End time must be after the start time.").foregroundColor(.red) }
			}.disabled(busy).environment(\.timeZone, editingTimezone)
			.navigationTitle(event == nil ? "New appointment" : "Edit appointment").navigationBarTitleDisplayMode(.inline)
			.toolbar {
				ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) }
				ToolbarItem(placement: .confirmationAction) {
					if busy { ProgressView() } else {
						Button("Save") { if assignee != 0 { confirmAssignment = true } else { Task { await save() } } }
							.disabled(!valid || blocked).accessibilityIdentifier("appointment.save")
					}
				}
			}
		}.navigationViewStyle(.stack).tint(.mango9Primary).interactiveDismissDisabled(busy)
		.sheet(isPresented: $choosingContact) { Mango9AppointmentContactPicker(session: session) { contact = $0 } }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
		.confirmationDialog("Transfer appointment ownership?", isPresented: $confirmAssignment, titleVisibility: .visible) {
			Button("Assign and save") { Task { await save() } }
		} message: { Text("This does not transfer the lead or client. You may no longer see the appointment after assigning it.") }
	}

	private func save() async {
		guard valid, !busy, !blocked else { return }
		busy = true; error = nil
		defer { busy = false }
		let draft = Mango9AppointmentDraft(title: title, notes: notes, start: start, end: end,
			activity: activity, priority: priority, status: status, contact: contact, shares: shares,
			assignee: assignee, reminder: reminder, channels: (emailReminder ? ["email"] : []) + (smsReminder ? ["sms"] : []))
		let body = draft.payload(event: event, owner: isOwner, timezone: editingTimezone.identifier)
		if body.isEmpty { dismiss(); return }
		do {
			_ = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session,
				path: event.map { "events/\($0.id)" } ?? "events", method: event == nil ? "POST" : "PATCH", body: body, revision: event?.revision)
			NotificationCenter.default.post(name: .mango9AppointmentDidChange, object: nil)
			dismiss()
		} catch {
			if let failure = error as? Mango9CalendarFailure {
				self.error = failure.localizedDescription
				blocked = failure.code == "event_changed" || failure.code == "account_changed"
			} else {
				// A timed-out POST may already have committed. Never offer a blind retry.
				blocked = true
				self.error = "We could not confirm whether the change was saved. Close this form and refresh Appointments before trying again."
			}
		}
	}
}

struct Mango9AppointmentContactPicker: View {
	@Environment(\.dismiss) private var dismiss
	let session: Mango9Session
	let select: (Mango9AppointmentContact) -> Void
	@State private var clients = false
	@State private var search = ""
	@State private var contacts: [Mango9AppointmentContact] = []
	@State private var page = 1
	@State private var more = false
	@State private var busy = false
	@State private var error: String?
	var body: some View {
		NavigationView {
			List {
				Picker("Record type", selection: $clients) { Text("Leads").tag(false); Text("Clients").tag(true) }.pickerStyle(.segmented)
				ForEach(contacts) { contact in Button(contact.displayName) { select(contact); dismiss() } }
				if busy { ProgressView() }
				if let error { Text(error).foregroundColor(.red) }
				if more && !busy { Button("Load more") { Task { await load(reset: false) } } }
				if !busy && contacts.isEmpty && error == nil { Text("No matching records").foregroundColor(.secondary) }
			}.searchable(text: $search).navigationTitle("Link CRM record").navigationBarTitleDisplayMode(.inline)
			.toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
		}.navigationViewStyle(.stack)
		.task(id: "\(clients)-\(search)") {
			contacts = []; more = false
			do { try await Task.sleep(nanoseconds: 250_000_000); try Task.checkCancellation(); await load(reset: true) } catch {}
		}
	}
	private func load(reset: Bool) async {
		guard Mango9SessionStore.load().map(Mango9CalendarAPI.accountKey) == Mango9CalendarAPI.accountKey(session) else { return }
		busy = true; error = nil
		let next = reset ? 1 : page + 1
		let wasClients = clients; let term = search
		do {
			let values: [Mango9AppointmentContact]
			let hasMore: Bool
			if wasClients {
				let result = try await Mango9CRMAPI.clients(session: Mango9SessionStore.load() ?? session, search: term, page: next)
				values = result.clients.map { .init(id: $0.id, name: $0.name, kind: "client") }; hasMore = next < result.pagination.pages
			} else {
				let result = try await Mango9CRMAPI.leads(session: Mango9SessionStore.load() ?? session, search: term, status: "", page: next)
				values = result.leads.map { .init(id: $0.id, name: $0.name, kind: "lead") }; hasMore = next < result.pagination.pages
			}
			guard !Task.isCancelled, term == search, wasClients == clients,
				Mango9SessionStore.load().map(Mango9CalendarAPI.accountKey) == Mango9CalendarAPI.accountKey(session) else { return }
			contacts = reset ? values : contacts + values; page = next; more = hasMore
		} catch { if !Task.isCancelled { self.error = "CRM records could not be loaded. Please try again." } }
		if !Task.isCancelled { busy = false }
	}
}

/// Existing lead and client pages enter the same filtered calendar, not a second UI.
struct Mango9LinkedAppointmentsSection: View {
	let contact: Mango9AppointmentContact
	@State private var count: Int?
	@State private var error = false
	var body: some View {
		NavigationLink(destination: Mango9AppointmentsFragment(contact: contact)) {
			HStack(spacing: 12) {
				Image(systemName: "calendar.badge.clock").font(.title2).foregroundColor(.mango9Primary)
				VStack(alignment: .leading, spacing: 4) {
					Text("Appointments").font(.headline)
					Text(count.map { "\($0) this month · View or schedule" } ?? (error ? "Open calendar to retry" : "Checking this month…"))
						.font(.caption).foregroundColor(.secondary)
				}
				Spacer(); Image(systemName: "chevron.right").foregroundColor(.secondary)
			}.padding(16).background(Color(.systemBackground)).cornerRadius(14)
		}.buttonStyle(.plain)
		.task { await load() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AppointmentDidChange)) { _ in Task { await load() } }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in count = nil }
	}
	private func load() async {
		guard let session = Mango9SessionStore.load() else { return }
		do {
			let range = Mango9AppointmentsStore.monthRange(Date())
			let events = try await Mango9CalendarAPI.events(session: session, start: range.start, end: range.end, contactID: contact.id)
			guard !Task.isCancelled else { return }
			count = events.count; error = false
		} catch { count = nil; self.error = true }
	}
}
