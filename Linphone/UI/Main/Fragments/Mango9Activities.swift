import SwiftUI

/// Matches the CRM activity service, including legacy follow-up labels.
enum Mango9ActivityKind: String, CaseIterable, Identifiable {
	case appointments, events, tasks
	var id: String { rawValue }
	var title: String { rawValue.capitalized }
	static func classify(_ activity: String) -> Self {
		switch activity.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
		case "", "appointment": return .appointments
		case "task", "follow up call", "follow_up": return .tasks
		default: return .events
		}
	}
	func defaultActivity(in choices: [String]) -> String? { choices.first { Self.classify($0) == self } }
}

enum Mango9ActivityPresentation {
	case combined, calendar, activities
}

struct Mango9ManagedTask: Decodable, Identifiable {
	struct Person: Decodable, Identifiable { let id: Int; let name: String }
	struct History: Decodable, Identifiable {
		let id: Int
		let action: String
		let actorName: String?
		let createdAt: String
	}
	let id: Int
	let eventId: Int?
	let title: String
	let description: String
	let type: String
	let execution: String
	let priority: String
	let status: String
	let dueAt: String?
	let timezone: String
	let assignees: [Person]
	let contactName: String?
	let aiAgentName: String?
	let revision: String
	let history: [History]?
	var dueDate: Date? { dueAt.flatMap(Self.date) }
	var statusLabel: String { status.replacingOccurrences(of: "_", with: " ").capitalized }
	static func date(_ value: String) -> Date? {
		// Tasks return PostgreSQL timestamptz, unlike the calendar's ISO dates.
		if let date = ISO8601DateFormatter().date(from: value) { return date }
		let fractional = ISO8601DateFormatter(); fractional.formatOptions.insert(.withFractionalSeconds)
		if let date = fractional.date(from: value) { return date }
		let parser = DateFormatter(); parser.locale = Locale(identifier: "en_US_POSIX")
		parser.timeZone = TimeZone(secondsFromGMT: 0)
		for format in ["yyyy-MM-dd HH:mm:ss.SSSSSSXXXXX", "yyyy-MM-dd HH:mm:ssXXXXX", "yyyy-MM-dd HH:mm:ss.SSSSSSX", "yyyy-MM-dd HH:mm:ssX"] {
			parser.dateFormat = format
			if let date = parser.date(from: value) { return date }
		}
		return nil
	}
}

struct Mango9TaskPage: Decodable {
	let items: [Mango9ManagedTask]
	let pagination: Mango9AppointmentPage.Pagination
}

@MainActor final class Mango9TasksStore: ObservableObject {
	@Published private(set) var items: [Mango9ManagedTask] = []
	@Published private(set) var loading = false
	@Published private(set) var hasMore = false
	@Published private(set) var error: String?
	private var generation = UUID()
	private var page = 0
	private var identity: String?
	private var search = ""
	let transport: URLSession
	init(transport: URLSession = .shared) { self.transport = transport }
	var representedEventIDs: Set<Int> { Set(items.compactMap(\.eventId)) }
	func reset() {
		generation = UUID(); items = []; page = 0; hasMore = false
		loading = false; error = nil; identity = nil
	}
	func load(session: Mango9Session?, search: String, more: Bool = false) async {
		guard let session, Mango9SessionStore.isActive(session) else { reset(); return }
		let key = Mango9CalendarAPI.accountKey(session)
		let query = String(search.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
		if more {
			guard !loading, hasMore, identity == key, self.search == query else { return }
		} else {
			reset(); identity = key; self.search = query
		}
		let token = UUID(); generation = token; loading = true; error = nil
		defer { if generation == token { loading = false } }
		do {
			let next = page + 1
			let response = try await Mango9CalendarAPI.send(Mango9TaskPage.self, session: session, path: "",
				query: [.init(name: "q", value: query), .init(name: "page", value: String(next)), .init(name: "limit", value: "50")],
				transport: transport, scope: .tasks)
			guard generation == token, Mango9SessionStore.isActive(session), !Task.isCancelled else { return }
			guard response.pagination.page == next, response.items.count <= 50,
				!response.pagination.hasMore || !response.items.isEmpty else { throw Mango9CRMAPIError.server }
			// A task can move between pages while another staff member edits it.
			// Replace by stable ID; never append duplicate cards.
			for item in response.items {
				if let index = items.firstIndex(where: { $0.id == item.id }) { items[index] = item }
				else { items.append(item) }
			}
			page = next; hasMore = response.pagination.hasMore
		} catch {
			guard generation == token, !Task.isCancelled else { return }
			if let failure = error as? Mango9CalendarFailure, [401, 403, 404].contains(failure.status) {
				items = []; hasMore = false
				self.error = failure.status == 403 ? "Tasks are not available for this account’s permissions." :
					failure.status == 404 ? "The Tasks API is unavailable on this server." : failure.localizedDescription
			} else { self.error = "Tasks could not be loaded. Please retry." }
		}
	}
}

/// Scheduled tasks use the calendar's existing editor. Managed assignments keep
/// their own revision/history boundary; never write them through calendar PATCH.
struct Mango9TasksList: View {
	let events: [Mango9Appointment]
	let session: Mango9Session?
	let search: String
	let refreshID: UUID
	let onSelect: (Mango9Appointment) -> Void
	let onRefresh: () async -> Void
	@StateObject private var store = Mango9TasksStore()
	@State private var selected: Mango9ManagedTask?
	private var scopedEvents: [Mango9Appointment] { events.filter { !store.representedEventIDs.contains($0.id) } }
	var body: some View {
		List {
			Section("Scheduled tasks · selected month") {
				ForEach(scopedEvents, id: \.displayID) { event in
					Button { onSelect(event) } label: {
						Mango9AppointmentRow(event: event).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
					}.buttonStyle(.plain)
				}
				if scopedEvents.isEmpty { Text("No scheduled tasks this month").foregroundColor(.secondary) }
			}
			Section("Assigned tasks · all dates") {
				ForEach(store.items) { task in
					Button { selected = task } label: {
						VStack(alignment: .leading, spacing: 6) {
							Text(task.title).font(.headline).foregroundColor(.primary)
							HStack {
								Label(task.statusLabel, systemImage: task.status == "completed" ? "checkmark.circle" : "checklist")
								if task.execution == "ai_call" { Label("AI call", systemImage: "phone.badge.waveform") }
							}.font(.caption).foregroundColor(.mango9Primary)
							if let date = task.dueDate { Text(date, format: .dateTime.month(.abbreviated).day().hour().minute()).font(.subheadline).foregroundColor(.secondary) }
							else { Text(task.dueAt ?? "No due date").font(.subheadline).foregroundColor(.secondary) }
							Text(task.assignees.map(\.name).joined(separator: ", ")).font(.caption).foregroundColor(.secondary)
						}.padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
					}.buttonStyle(.plain).accessibilityIdentifier("activities.task.\(task.id)")
				}
				if let error = store.error {
					Text(error).foregroundColor(.secondary)
					Button("Retry") { Task { await reload() } }
				} else if store.items.isEmpty && !store.loading { Text("No assigned tasks").foregroundColor(.secondary) }
				if store.loading { ProgressView().frame(maxWidth: .infinity) }
				if store.hasMore && !store.loading {
					Button("Load more tasks") { Task { await store.load(session: session, search: search, more: true) } }
				}
			}
		}.listStyle(.insetGrouped)
			.task(id: "\(session.map(Mango9CalendarAPI.accountKey) ?? "")|\(search)|\(refreshID)") {
				// Clear old results immediately; debounce only the search request.
				store.reset()
				if !search.isEmpty { do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return } }
				await reload()
			}
			.refreshable { await onRefresh(); await reload() }
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in selected = nil; store.reset() }
			.sheet(item: $selected) { task in
				if let session, Mango9SessionStore.isActive(session) { Mango9ManagedTaskDetail(taskID: task.id, session: session) }
			}
	}
	private func reload() async { await store.load(session: session, search: search) }
}

struct Mango9ManagedTaskDetail: View {
	let taskID: Int
	let session: Mango9Session
	@Environment(\.dismiss) private var dismiss
	@Environment(\.scenePhase) private var scenePhase
	@State private var task: Mango9ManagedTask?
	@State private var error: String?
	@State private var loading = false
	var body: some View {
		NavigationView {
			List {
				if let task {
					Section {
						Text(task.title).font(.title2.bold())
						Label(task.statusLabel, systemImage: "checklist")
						if !task.description.isEmpty { Text(task.description) }
					}
					Section("Assignment") {
						LabeledContentCompat("Type", value: task.type.replacingOccurrences(of: "_", with: " ").capitalized)
						LabeledContentCompat("Priority", value: task.priority.capitalized)
						LabeledContentCompat("Assigned to", value: task.assignees.map(\.name).joined(separator: ", "))
						if let contact = task.contactName { LabeledContentCompat("Contact", value: contact) }
						if let agent = task.aiAgentName { LabeledContentCompat("AI agent", value: agent) }
					}
					Section("Schedule") {
						if let date = task.dueDate {
							Text(date, format: .dateTime.month(.abbreviated).day().year().hour().minute())
							Text("Shown in \(TimeZone.current.identifier)").font(.caption).foregroundColor(.secondary)
							if task.eventId != nil {
								NavigationLink { Mango9AppointmentsFragment(date: date, presentation: .calendar) } label: { Label("See in Calendar", systemImage: "calendar") }
							}
						} else { Text(task.dueAt ?? "No due date") }
					}
					if let url = Self.webURL(session: session, taskID: task.id) {
						Section(footer: Text("Assignment, automation and task history are managed by the enhanced CRM task workflow. This opens your CRM website; sign in there if requested.")) {
							Link(destination: url) { Label("Manage task in CRM", systemImage: "arrow.up.right.square") }
						}
					}
					if let history = task.history, !history.isEmpty {
						Section("History") {
							ForEach(history) { entry in
								VStack(alignment: .leading, spacing: 4) {
									Text(entry.action.replacingOccurrences(of: "_", with: " ").capitalized)
									Text(entry.actorName ?? "Team member").font(.caption).foregroundColor(.secondary)
									if let date = Mango9ManagedTask.date(entry.createdAt) { Text(date, format: .dateTime.month().day().hour().minute()).font(.caption).foregroundColor(.secondary) }
								}
							}
						}
					}
				}
				if loading { ProgressView() }
				if let error { Text(error).foregroundColor(.secondary); Button("Retry") { Task { await reload() } } }
			}.navigationTitle("Task").navigationBarTitleDisplayMode(.inline).navigationBarHidden(false)
				.toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
				.task { await reload() }.refreshable { await reload() }
		}.navigationViewStyle(.stack).tint(.mango9Primary)
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in task = nil; dismiss() }
			.onChange(of: scenePhase) { phase in
				if phase == .active && !loading { Task { await reload() } }
			}
	}
	private func reload() async {
		loading = true; error = nil
		defer { loading = false }
		do { task = try await Mango9CalendarAPI.send(Mango9ManagedTask.self, session: session, path: String(taskID), scope: .tasks) }
		catch { task = nil; self.error = "This task could not be loaded. Check your connection and account access." }
	}
	static func webURL(session: Mango9Session, taskID: Int) -> URL? {
		guard taskID > 0, var url = URLComponents(string: session.crmApiBaseUrl), url.scheme == "https",
			url.host != nil, url.user == nil, url.password == nil else { return nil }
		let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
		guard path.hasSuffix("api/v2") else { return nil }
		url.path = "/" + path.dropLast("api/v2".count) + "activity/tasks/task-\(taskID)"
		url.query = nil; url.fragment = nil
		return url.url
	}
}

private struct LabeledContentCompat: View {
	let title: String
	let value: String
	init(_ title: String, value: String) { self.title = title; self.value = value }
	var body: some View {
		HStack(alignment: .top) { Text(title); Spacer(minLength: 16); Text(value).foregroundColor(.secondary).multilineTextAlignment(.trailing) }
	}
}
