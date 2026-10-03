#if DEBUG && targetEnvironment(simulator)
import SwiftUI

/// Simulator-only, offline fixture for exercising real calendar gestures and
/// repeated sheet presentations. It cannot be enabled in device/release builds.
struct Mango9CalendarUITestFixture: View {
	static var isEnabled: Bool { ProcessInfo.processInfo.environment["UITEST_CALENDAR"] != nil }
	@State private var store: Mango9AppointmentsStore?
	var body: some View {
		Group {
			if let store {
				Mango9AppointmentsFragment(store: store, date: CalendarFixtureProtocol.day,
					calendarMode: ProcessInfo.processInfo.environment["UITEST_CALENDAR"] != "list",
					usesLegacyCalendar: ProcessInfo.processInfo.environment["UITEST_CALENDAR"] == "legacy")
			} else { ProgressView() }
		}.task {
			guard store == nil else { return }
			let host = "calendar-ui.example.invalid"
			let session = Mango9Session(crmId: "fixture", crmBaseUrl: "https://\(host)", crmApiBaseUrl: "https://\(host)/api/v2",
				userId: "42", parentClientId: "1", role: "client", loginId: "fixture@example.invalid",
				displayName: "Calendar fixture", accessToken: "fixture-not-a-credential", refreshToken: "fixture",
				smsChatApi: "", connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:42@\(host)")
			try? Mango9SessionStore.save(session, persist: false, makeActive: true)
			CalendarFixtureProtocol.reset()
			let config = URLSessionConfiguration.ephemeral
			config.protocolClasses = [CalendarFixtureProtocol.self]
			store = Mango9AppointmentsStore(transport: URLSession(configuration: config))
		}
	}
}

private final class CalendarFixtureProtocol: URLProtocol {
	static let day = Calendar.current.startOfDay(for: Date())
	private static var events: [[String: Any]] = []
	private static let lock = NSLock()
	static func reset() {
		lock.lock(); defer { lock.unlock() }
		events = [record(id: 91, title: "Tap", hour: 9), record(id: 92, title: "Next", hour: 10)]
		if ProcessInfo.processInfo.environment["UITEST_CALENDAR"] == "recurring" {
			events[0]["recurrence"] = ["frequency": "daily", "weekdays": []]
		}
	}
	private static func record(id: Int, title: String, hour: Int) -> [String: Any] {
		let start = Calendar.current.date(bySettingHour: hour, minute: 0, second: 0, of: day)!
		return ["id": id, "owner_id": 42, "title": title, "description": "Offline calendar fixture",
			"start_at": Mango9CalendarAPI.timestamp(start), "end_at": Mango9CalendarAPI.timestamp(start.addingTimeInterval(1800)),
			"timezone": TimeZone.current.identifier, "activity": "appointment", "priority": "medium",
			"status": ["id": 3, "name": "Confirmed", "color": "#008080"], "contact": NSNull(),
			"recurrence": ["frequency": "none", "weekdays": []], "reminders": [], "origin": "appointment",
			"shared_by_me_user_ids": [], "permissions": ["can_edit": true, "can_delete": true,
				"can_assign": false, "can_share": false, "can_change_contact": true], "revision": "fixture-\(id)"]
	}
	override class func canInit(with request: URLRequest) -> Bool { true }
	override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
	override func startLoading() {
		do {
			let (status, payload) = try response()
			let data = try JSONSerialization.data(withJSONObject: payload)
			let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
			client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
			client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
		} catch { client?.urlProtocol(self, didFailWithError: error) }
	}
	override func stopLoading() {}
	private func body() throws -> [String: Any] {
		var data = request.httpBody ?? Data()
		if let stream = request.httpBodyStream {
			stream.open(); defer { stream.close() }
			var bytes = [UInt8](repeating: 0, count: 4096)
			while stream.hasBytesAvailable { let count = stream.read(&bytes, maxLength: bytes.count); if count <= 0 { break }; data.append(contentsOf: bytes.prefix(count)) }
		}
		return try JSONSerialization.jsonObject(with: data) as! [String: Any]
	}
	private func response() throws -> (Int, [String: Any]) {
		Self.lock.lock(); defer { Self.lock.unlock() }
		guard request.url?.host == "calendar-ui.example.invalid" else { throw URLError(.unsupportedURL) }
		let path = request.url!.path
		let result: Any
		if path.hasSuffix("metadata") {
			result = ["timezone": TimeZone.current.identifier, "statuses": [["id": 3, "name": "Confirmed", "color": "#008080"]],
				"activities": ["appointment"], "priorities": ["low", "medium", "high"], "reminder_minutes": [0, 15],
				"reminder_channels": ["email", "sms"], "share_recipients": [], "assignees": [],
				"capabilities": ["create": true, "assign": false, "push": false, "in_app_reminders": false]] as [String: Any]
		} else if path.hasSuffix("events"), request.httpMethod == "POST" {
			let body = try body()
			if body["title"] as? String == "Conflict" {
				return (409, ["success": false, "message": "This time overlaps another appointment.", "error": ["code": "appointment_conflict"]])
			}
			var saved = Self.record(id: Self.events.count + 100, title: body["title"] as? String ?? "", hour: 12)
			for key in ["start_at", "end_at", "timezone"] { if let value = body[key] { saved[key] = value } }
			Self.events.append(saved); result = saved
		} else if path.hasSuffix("events") {
			result = ["events": Self.events, "pagination": ["page": 1, "limit": 100, "total": Self.events.count, "has_more": false], "snapshot": "fixture-\(Self.events.count)"] as [String: Any]
		} else if let id = Int(request.url!.lastPathComponent), let index = Self.events.firstIndex(where: { $0["id"] as? Int == id }) {
			if request.httpMethod == "PATCH" {
				let body = try body()
				for key in ["title", "start_at", "end_at"] { if let value = body[key] { Self.events[index][key] = value } }
				Self.events[index]["revision"] = UUID().uuidString
			}
			result = Self.events[index]
		} else { return (404, ["success": false, "message": "Fixture route unavailable"]) }
		return (200, ["success": true, "data": result, "message": "success"])
	}
}
#endif
