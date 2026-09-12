import Foundation

struct Mango9AppointmentStatus: Decodable, Identifiable, Equatable {
	let id: Int
	let name: String
	let color: String?
}

struct Mango9AppointmentContact: Decodable, Identifiable, Equatable {
	let id: Int
	let name: String?
	let kind: String
	var displayName: String { name?.isEmpty == false ? name! : "CRM contact" }
}

struct Mango9CalendarMetadata: Decodable {
	struct Person: Decodable, Identifiable { let id: Int; let name: String }
	struct Capabilities: Decodable { let create: Bool; let assign: Bool; let push: Bool; let inAppReminders: Bool? }
	let timezone: String
	let statuses: [Mango9AppointmentStatus]
	let activities: [String]
	let priorities: [String]
	let reminderMinutes: [Int]
	let reminderChannels: [String]
	let shareRecipients: [Person]
	let assignees: [Person]
	let capabilities: Capabilities
}

struct Mango9Appointment: Decodable, Identifiable {
	struct Permissions: Decodable {
		let canEdit: Bool
		let canDelete: Bool
		let canAssign: Bool
		let canShare: Bool
		let canChangeContact: Bool
		let readOnlyReason: String?
	}
	struct Recurrence: Decodable { let frequency: String; let weekdays: [Int] }
	struct Reminder: Decodable { let minutesBefore: Int?; let channels: [String] }
	let id: Int
	let ownerId: Int
	let title: String
	let description: String
	let startAt: Date
	let endAt: Date
	let timezone: String
	let activity: String
	let priority: String
	let status: Mango9AppointmentStatus?
	let contact: Mango9AppointmentContact?
	let recurrence: Recurrence
	let reminders: [Reminder]
	let origin: String
	let sharedByMeUserIds: [Int]
	let permissions: Permissions
	let revision: String
	var isRecurring: Bool { recurrence.frequency != "none" }
}

struct Mango9AppointmentPage: Decodable {
	struct Pagination: Decodable { let page: Int; let limit: Int; let total: Int; let hasMore: Bool }
	let events: [Mango9Appointment]
	let pagination: Pagination
	let snapshot: String
}

struct Mango9AppointmentDraft {
	var title: String
	var notes: String
	var start: Date
	var end: Date
	var activity: String
	var priority: String
	var status: Int
	var contact: Mango9AppointmentContact?
	var shares: Set<Int>
	var assignee: Int
	var reminder: Int
	var channels: [String]

	func payload(event: Mango9Appointment?, owner: Bool, timezone: String) -> [String: Any] {
		var body: [String: Any] = [:]
		if event?.permissions.canEdit ?? true {
			let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
			if event?.title != cleanTitle { body["title"] = cleanTitle }
			if event?.description != notes { body["description"] = notes }
			if event?.startAt != start { body["start_at"] = Mango9CalendarAPI.timestamp(start) }
			if event?.endAt != end { body["end_at"] = Mango9CalendarAPI.timestamp(end) }
			if event?.activity != activity { body["activity"] = activity }
			if event?.priority != priority { body["priority"] = priority }
			if event == nil || (event?.status?.id ?? 0) != status { body["status_id"] = status == 0 ? NSNull() : status as Any }
		}
		if (event?.permissions.canChangeContact ?? true), event == nil || event?.contact?.id != contact?.id {
			body["contact_id"] = contact?.id as Any? ?? NSNull()
		}
		if owner && (event?.permissions.canEdit ?? true) {
			if event == nil { body["timezone"] = timezone }
			let oldReminder = event?.reminders.first
			if event == nil || (oldReminder?.minutesBefore ?? -1) != reminder || (reminder >= 0 && Set(oldReminder?.channels ?? []) != Set(channels)) {
				body["reminders"] = reminder < 0 ? [] : [["minutes_before": reminder, "channels": channels]]
			}
		}
		if (event?.permissions.canShare ?? true), event == nil || Set(event?.sharedByMeUserIds ?? []) != shares {
			body["share_user_ids"] = shares.sorted()
		}
		if assignee != 0, event?.permissions.canAssign ?? owner { body["assign_to_user_id"] = assignee }
		return body
	}
}

struct Mango9CalendarFailure: LocalizedError {
	let status: Int
	let code: String
	let message: String
	var errorDescription: String? {
		switch code {
		case "calendar_endpoint_unavailable": return message
		case "event_changed": return "This appointment changed on another device. Close and reopen it to review the latest details before saving."
		case "account_changed": return "The active account changed. Reopen Appointments for the selected account."
		case "overlap": return "That time overlaps another appointment. Nothing was changed. Choose a different time."
		case "out_of_calendar": return "That time is outside the appointment owner's working hours. Nothing was changed."
		case "reminder_changed": return "Your reminder changed on another device. Refresh before trying again."
		default:
			if status == 401 { return "Your session expired. Please sign in again." }
			if status == 404 { return "The requested appointment data could not be found. You can still browse the calendar." }
			return message
		}
	}
	static let accountChanged = Self(status: 0, code: "account_changed", message: "")
}

/// Uses the provisioned CRM identity, never the SIP proxy or a fixed tenant hostname.
enum Mango9CalendarAPI {
	enum Scope: String { case calendar, crm }
	struct Envelope<T: Decodable>: Decodable { let success: Bool; let message: String; let data: T? }
	struct ErrorBody: Decodable {
		struct Detail: Decodable { let code: String }
		let message: String
		let error: Detail?
	}
	struct Empty: Decodable {}

	static func accountKey(_ session: Mango9Session) -> String {
		[session.crmApiBaseUrl, session.crmId, session.userId, session.sipIdentity ?? ""].joined(separator: "|")
	}

	static func decoder() -> JSONDecoder {
		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		decoder.dateDecodingStrategy = .custom { decoder in
			let value = try decoder.singleValueContainer().decode(String.self)
			let formatter = ISO8601DateFormatter()
			if let date = formatter.date(from: value) { return date }
			formatter.formatOptions.insert(.withFractionalSeconds)
			guard let date = formatter.date(from: value) else {
				throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid appointment date"))
			}
			return date
		}
		return decoder
	}

	static func timestamp(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }

	static func request(session: Mango9Session, path: String, method: String = "GET",
		query: [URLQueryItem] = [], body: [String: Any]? = nil, revision: String? = nil, scope: Scope = .calendar) throws -> URLRequest {
		guard let base = URL(string: session.crmApiBaseUrl), base.scheme == "https", base.host != nil,
			var components = URLComponents(url: base.appendingPathComponent("mobile/" + scope.rawValue + "/" + path), resolvingAgainstBaseURL: false)
		else { throw Mango9CRMAPIError.invalidConfiguration }
		components.queryItems = query.isEmpty ? nil : query
		guard let url = components.url else { throw Mango9CRMAPIError.invalidConfiguration }
		var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
		request.httpMethod = method
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
		if let body {
			request.httpBody = try JSONSerialization.data(withJSONObject: body)
			request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		}
		if let revision { request.setValue("\"\(revision)\"", forHTTPHeaderField: "If-Match") }
		return request
	}

	static func decode<T: Decodable>(_ type: T.Type, data: Data, status: Int) throws -> T {
		guard (200..<300).contains(status) else {
			let error = try? decoder().decode(ErrorBody.self, from: data)
			throw Mango9CalendarFailure(status: status, code: error?.error?.code ?? "request_failed",
				message: error?.message ?? "Appointments could not be loaded. Please try again.")
		}
		let envelope = try decoder().decode(Envelope<T>.self, from: data)
		guard envelope.success, let result = envelope.data else {
			throw Mango9CalendarFailure(status: status, code: "invalid_response", message: envelope.message)
		}
		return result
	}

	@MainActor static func send<T: Decodable>(_ type: T.Type, session: Mango9Session, path: String,
		method: String = "GET", query: [URLQueryItem] = [], body: [String: Any]? = nil,
		revision: String? = nil, transport: URLSession = .shared, scope: Scope = .calendar) async throws -> T {
		func active() throws -> Mango9Session {
			guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(current),
				accountKey(current) == accountKey(session) else { throw Mango9CalendarFailure.accountChanged }
			return current
		}
		var current = try active()
		for attempt in 0...1 {
			try Task.checkCancellation()
			let request = try request(session: current, path: path, method: method, query: query, body: body, revision: revision, scope: scope)
			let (data, response) = try await transport.data(for: request)
			_ = try active()
			guard let http = response as? HTTPURLResponse else { throw Mango9CRMAPIError.server }
			if http.statusCode == 404, path == "metadata" {
				throw Mango9CalendarFailure(status: 404, code: "calendar_endpoint_unavailable",
					message: "Appointment sync is unavailable on \(request.url?.host ?? "your CRM server"). You can still browse the calendar.")
			}
			if http.statusCode == 401 && attempt == 0 {
				// Retry only an explicit authentication rejection, never an uncertain write.
				let latest = try active()
				if latest.accessToken != current.accessToken { current = latest; continue }
				let refreshed = try await Mango9CRMAPI.refresh(session: current)
				_ = try active()
				try Mango9SessionStore.save(refreshed)
				current = refreshed
				continue
			}
			return try decode(type, data: data, status: http.statusCode)
		}
		throw Mango9CalendarFailure(status: 401, code: "unauthorized", message: "")
	}

	/// Replace a range only after every page agrees on one snapshot. No partial lists.
	static func collect(start: Date, end: Date, contactID: Int?,
		fetch: ([URLQueryItem]) async throws -> Mango9AppointmentPage) async throws -> [Mango9Appointment] {
		for restart in 0...1 {
			var events: [Mango9Appointment] = []
			var snapshot: String?
			var page = 1
			do {
				while page <= 100 {
					try Task.checkCancellation()
					var query = [URLQueryItem(name: "start_at", value: timestamp(start)),
						URLQueryItem(name: "end_at", value: timestamp(end)),
						URLQueryItem(name: "page", value: String(page)), URLQueryItem(name: "limit", value: "100")]
					if let contactID { query.append(.init(name: "contact_id", value: String(contactID))) }
					if let snapshot { query.append(.init(name: "snapshot", value: snapshot)) }
					let result = try await fetch(query)
					if let snapshot, snapshot != result.snapshot {
						throw Mango9CalendarFailure(status: 409, code: "snapshot_changed", message: "The calendar changed. Refresh to load the latest appointments.")
					}
					snapshot = result.snapshot
					events.append(contentsOf: result.events)
					if !result.pagination.hasMore { return events }
					page += 1
				}
				throw Mango9CalendarFailure(status: 0, code: "too_many_events", message: "Choose a smaller date range to view appointments.")
			} catch let error as Mango9CalendarFailure where error.code == "snapshot_changed" && restart == 0 {
				continue
			}
		}
		throw Mango9CRMAPIError.server
	}

	@MainActor static func events(session: Mango9Session, start: Date, end: Date, contactID: Int? = nil, transport: URLSession = .shared) async throws -> [Mango9Appointment] {
		try await collect(start: start, end: end, contactID: contactID) { query in
			try await send(Mango9AppointmentPage.self, session: session, path: "events", query: query, transport: transport)
		}
	}

	/// Exyte preloads three months plus padding. Keep every API request within
	/// its 93-day limit; collect all windows before replacing the displayed data.
	static func displayWindows(start: Date, end: Date) -> [DateInterval] {
		guard end > start, end.timeIntervalSince(start) <= 366 * 86400 else { return [] }
		var cursor = start
		var result: [DateInterval] = []
		while cursor < end {
			let next = min(cursor.addingTimeInterval(90 * 86400), end)
			result.append(DateInterval(start: cursor, end: next)); cursor = next
		}
		return result
	}

	@MainActor static func displayEvents(session: Mango9Session, start: Date, end: Date, contactID: Int?, transport: URLSession = .shared) async throws -> [Mango9Appointment] {
		let windows = displayWindows(start: start, end: end)
		guard !windows.isEmpty else { throw Mango9CalendarFailure(status: 422, code: "invalid_range", message: "Choose a smaller calendar range.") }
		var result: [Int: Mango9Appointment] = [:]
		for window in windows {
			let values = try await events(session: session, start: window.start, end: window.end, contactID: contactID, transport: transport)
			for value in values { result[value.id] = value }
		}
		return result.values.sorted { $0.startAt < $1.startAt }
	}
}
