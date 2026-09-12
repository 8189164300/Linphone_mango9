import XCTest
import SwiftUI
import Combine
@testable import CalendarView
@testable import LinphoneApp

@MainActor private final class CRMDisclosureFixtureState: ObservableObject {
	@Published var expanded = false
}

private struct CRMDisclosureFixture: View {
	@ObservedObject var state: CRMDisclosureFixtureState
	let transport: URLSession
	var body: some View {
		NavigationView {
			ScrollView {
				VStack {
					Text("CRM Settings").font(.headline)
					Mango9CRMSettings(transport: transport, embedded: true, isExpanded: state.expanded)
						.frame(height: state.expanded ? nil : 0, alignment: .top).clipped()
				}
			}
		}.navigationViewStyle(.stack)
	}
}

private final class CalendarMockURLProtocol: URLProtocol {
	static var handler: ((URLRequest) throws -> (Int, Data))?
	override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix(".invalid") == true }
	override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
	override func startLoading() {
		do {
			guard let handler = Self.handler else { throw URLError(.badServerResponse) }
			let (status, data) = try handler(request)
			let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
			client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
			client?.urlProtocol(self, didLoad: data)
			client?.urlProtocolDidFinishLoading(self)
		} catch { client?.urlProtocol(self, didFailWithError: error) }
	}
	override func stopLoading() {}
}

@available(iOS 18.0, *)
private struct CalendarTimelineTestProvider: CalendarsProvider {
	let values: [CalendarEvent]
	func getCalendars() async throws -> [ProviderCalendar] { [.init(id: "test", title: "Appointments", color: .blue)] }
	func getEvents(from startDate: Date, to endDate: Date, selectedCalendarIDs: [String]) async throws -> [CalendarEvent] { values }
	func getReminders(from startDate: Date, to endDate: Date, selectedCalendarIDs: [String]) async throws -> [CalendarReminder] { [] }
}

@MainActor private final class CalendarNavigationTestState: ObservableObject {
	@Published var date: Date
	@Published var revision = UUID()
	init(date: Date) { self.date = date }
}

@available(iOS 18.0, *)
private struct CalendarNavigationTestView: View {
	@ObservedObject var state: CalendarNavigationTestState
	let session: Mango9Session?
	let transport: URLSession
	let mode: CalendarDisplayMode
	var body: some View {
		Mango9ExyteCalendar(session: session, contactID: nil, date: $state.date, revision: state.revision,
			onSelect: { _ in }, onError: { if let error = $0 { XCTFail(error) } }, transport: transport, initialMode: mode)
			.tint(.mango9Primary)
	}
}

final class Mango9CalendarTests: XCTestCase {
	@MainActor func testCalendarProviderRejectsOldAccountWithoutPublishingStaleCallbacks() async throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Modern provider requires iOS 18") }
		let previous = Mango9SessionStore.activeIdentity
		let original = session(); let replacement = session(user: "99")
		try Mango9SessionStore.save(original, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: original.sipIdentity!)
			Mango9SessionStore.remove(for: replacement.sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previous)
			CalendarMockURLProtocol.handler = nil
		}
		CalendarMockURLProtocol.handler = { _ in
			(503, Data("{\"success\":false,\"message\":\"Temporarily unavailable\"}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		var errors = 0; var snapshots = 0
		let provider = Mango9CalendarProvider(session: original, contactID: nil, transport: transport,
			onError: { if $0 != nil { errors += 1 } }, onEvents: { _ in snapshots += 1 })
		let start = try event().startAt
		do {
			_ = try await provider.getEvents(from: start, to: start.addingTimeInterval(3600), selectedCalendarIDs: [])
			XCTFail("An active account's server failure must still reject the request")
		} catch { }
		XCTAssertEqual(errors, 1); XCTAssertEqual(snapshots, 1)
		try Mango9SessionStore.save(replacement, persist: false, makeActive: true)
		do {
			_ = try await provider.getEvents(from: start, to: start.addingTimeInterval(3600), selectedCalendarIDs: [])
			XCTFail("The previous account must still be rejected")
		} catch {
			XCTAssertEqual((error as? Mango9CalendarFailure)?.code, "account_changed")
		}
		XCTAssertEqual(errors, 1, "The old account must not publish an error into the new screen")
		XCTAssertEqual(snapshots, 1, "The old account must not clear the new account's events")
	}

	func testCalendarOffersOnlyDayWeekMonthAndUsesWeekStartPreference() throws {
		XCTAssertEqual(Mango9LegacyCalendarMode.allCases.map(\.title), ["Day", "Week", "Month"])
		var calendar = Calendar(identifier: .gregorian); calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		let date = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 12, day: 31, hour: 15)))
		calendar.firstWeekday = 2
		let days = Mango9LegacyCalendarMode.week.days(containing: date, calendar: calendar)
		XCTAssertEqual(days.map { calendar.component(.day, from: $0) }, [28, 29, 30, 31, 1, 2, 3])
		XCTAssertEqual(Mango9LegacyCalendarMode.week.heading(date, calendar: calendar), "Dec 28, 2026 – Jan 3, 2027")
		let next = Mango9LegacyCalendarMode.week.moved(date, direction: 1, calendar: calendar)
		XCTAssertEqual(calendar.component(.day, from: next), 4)
		XCTAssertEqual(Mango9LegacyCalendarMode.week.moved(next, direction: -1, calendar: calendar), days[0])
		calendar.firstWeekday = 1
		XCTAssertEqual(calendar.component(.day, from: Mango9LegacyCalendarMode.week.days(containing: date, calendar: calendar)[0]), 27)
	}

	@MainActor func testModernTimelineSegmentsOvernightAndExcludesMidnightEnd() async throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Exyte requires iOS 18; legacy overlap coverage runs separately") }
		let start = Calendar.current.startOfDay(for: try event().startAt)
		let next = Calendar.current.date(byAdding: .day, value: 1, to: start)!
		let overnight = CalendarEvent(id: "overnight", startDate: next.addingTimeInterval(-1800), endDate: next.addingTimeInterval(1800))
		let midnightEnd = CalendarEvent(id: "midnight", startDate: next.addingTimeInterval(-3600), endDate: next)
		let grouped = DayLayout<EmptyView>.Grouped.compute(events: [overnight, midnightEnd], reminders: [], anchorDate: start, daysCount: 7)
		XCTAssertEqual(grouped.nonAllDayEventsByDay[next]?.map(\.id), ["overnight"])
		XCTAssertEqual(grouped.nonAllDayEventsByDay[start]?.first?.endDate, next)
		XCTAssertEqual(grouped.nonAllDayEventsByDay[next]?.first?.startDate, next)
		let provider = CalendarTimelineTestProvider(values: [overnight, midnightEnd])
		let model = CalendarViewModel(providers: [provider]); await model.fetch(.init(start: start, end: next))
		XCTAssertEqual(model.getEvents(from: next, displayMode: .day, fullscreenDate: next).map(\.id), ["overnight"])
		let monthModel = MonthCellModel(id: 0); monthModel.events = [overnight, midnightEnd]
		let month = MonthLayout(date: start, viewModel: monthModel, monthDayBuilder: { _ in EmptyView() }, didSelectDay: { _ in })
		XCTAssertEqual(month.eventsFor(next).map(\.id), ["overnight"])
	}

	func testModernTimelineFramesInvalidateAfterRescheduleZoomAndKeepOverlapInBounds() throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Exyte layout requires iOS 18") }
		let start = try event().startAt
		let first = CalendarEvent(id: "1", startDate: start, endDate: start.addingTimeInterval(3600))
		let second = CalendarEvent(id: "2", startDate: start.addingTimeInterval(1800), endDate: start.addingTimeInterval(5400))
		var layout = EventsPlacement(events: [first, second], reminders: [], oneHourHeight: 60, horSpacing: 4, verSpacing: 2, trailingPadding: 4)
		let key = layout.frameKey(width: 180); let frames = layout.computeFrames(width: 180)
		XCTAssertEqual(frames.count, 2); XCTAssertLessThanOrEqual(frames[0].maxX, frames[1].minX)
		XCTAssertTrue(frames.allSatisfy { $0.minX >= 0 && $0.maxX <= 180 && $0.height > 0 })
		layout.events[0].startDate = start.addingTimeInterval(-3600)
		XCTAssertNotEqual(key, layout.frameKey(width: 180)); XCTAssertNotEqual(frames, layout.computeFrames(width: 180))
		let movedKey = layout.frameKey(width: 180); layout.oneHourHeight = 90
		XCTAssertNotEqual(movedKey, layout.frameKey(width: 180))
	}

	func testTimelineWallClockPlacementAcrossDST() throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Exyte range helper requires iOS 18") }
		let previous = NSTimeZone.default; NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		defer { NSTimeZone.default = previous }
		let spring = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-03-08T09:00:00-07:00"))
		let fall = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-01T09:00:00-08:00"))
		XCTAssertEqual(NSRange(spring, spring.addingTimeInterval(1800)).location, 540)
		XCTAssertEqual(NSRange(fall, fall.addingTimeInterval(1800)).location, 540)
		let repeatedStart = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-01T01:50:00-07:00"))
		let repeatedEnd = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-11-01T01:10:00-08:00"))
		XCTAssertEqual(NSRange(repeatedStart, repeatedEnd).length, 20)
	}

	func testCalendarFormatterCacheFollowsDeviceTimeZoneChanges() throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Vendored formatter requires iOS 18") }
		let previous = NSTimeZone.default; defer { NSTimeZone.default = previous }
		let instant = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-09-12T01:00:00Z"))
		NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		XCTAssertEqual(instant.formatted("yyyy-MM-dd HH:mm"), "2026-09-11 18:00")
		NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Asia/Yerevan"))
		XCTAssertEqual(instant.formatted("yyyy-MM-dd HH:mm"), "2026-09-12 05:00")
	}

	func testLegacyTimelineClipsOvernightAndPlacesOverlapsSideBySide() throws {
		let overnight = try event(eventJSON.replacingOccurrences(of: "2026-09-11T09:00:00-07:00", with: "2026-09-11T23:30:00-07:00")
			.replacingOccurrences(of: "2026-09-11T09:30:00-07:00", with: "2026-09-12T00:30:00-07:00"))
		var calendar = Calendar(identifier: .gregorian); calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		let next = calendar.date(from: DateComponents(year: 2026, month: 9, day: 12))!
		let frames = Mango9LegacyTimeline.placements([overnight], on: next, width: 100, hourHeight: 60, calendar: calendar)
		XCTAssertEqual(frames.count, 1); XCTAssertEqual(frames[0].frame.minY, 0); XCTAssertEqual(frames[0].frame.height, 28)
		let first = try event(); let second = try event(eventJSON.replacingOccurrences(of: "\"id\":91", with: "\"id\":92"))
		let overlap = Mango9LegacyTimeline.placements([first, second], on: first.startAt, width: 180, hourHeight: 60, calendar: calendar)
		XCTAssertEqual(overlap.count, 2); XCTAssertLessThanOrEqual(overlap[0].frame.maxX, overlap[1].frame.minX)
	}

	@MainActor func testModernDayWeekMonthRenderWithClearDatesAndPreserveVisibleDateOnRefresh() async throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Modern calendar requires iOS 18") }
		try await withRefreshFixture { store, date, window in
			for mode in [CalendarDisplayMode.day, .week, .month] {
				let state = CalendarNavigationTestState(date: date)
				window.rootViewController = UIHostingController(rootView: CalendarNavigationTestView(state: state,
					session: store.session, transport: store.transport, mode: mode))
				window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 2_000_000_000)
				if mode == .week { XCTAssertEqual(Calendar.current.component(.weekday, from: state.date), Calendar.current.firstWeekday) }
				self.capture(window, name: "Normalized modern \(mode.title) date headers and appointment layout")
				let shown = state.date; state.revision = UUID()
				try await Task.sleep(nanoseconds: 700_000_000); XCTAssertEqual(state.date, shown)
				let target = Calendar.current.date(byAdding: .day, value: mode == .week ? 7 : 1, to: shown)!
				state.date = target; try await Task.sleep(nanoseconds: 700_000_000)
				XCTAssertEqual(state.date, target)
			}
		}
	}

	@MainActor func testCalendarNavigationAndWeekRenderAtAccessibilitySize() async throws {
		try await withRefreshFixture { store, date, window in
			window.frame = CGRect(x: 0, y: 0, width: 320, height: 852)
			if #available(iOS 18.0, *) {
				let state = CalendarNavigationTestState(date: date)
				window.rootViewController = UIHostingController(rootView: CalendarNavigationTestView(state: state,
					session: store.session, transport: store.transport, mode: .week).dynamicTypeSize(.accessibility3))
			} else {
				window.rootViewController = UIHostingController(rootView: Mango9LegacyCalendar(session: store.session,
					contactID: nil, date: .constant(date), revision: UUID(), firstWeekday: 2, onSelect: { _ in }, onError: { _ in },
					transport: store.transport, initialMode: .week).dynamicTypeSize(.accessibility3))
			}
			window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 2_000_000_000)
			self.capture(window, name: "Readable Week at 320pt accessibility size with horizontal dates")
		}
	}

	func testAgendaRanksDueNowThenUpcomingAndPastWithStableDateOrdering() throws {
		let base = try event(); let now = base.startAt.addingTimeInterval(15 * 60)
		func fixture(_ id: Int, start: Date, status: String? = nil, priority: String = "medium") throws -> Mango9Appointment {
			var object = try JSONSerialization.jsonObject(with: Data(eventJSON.utf8)) as! [String: Any]
			object["id"] = id; object["start_at"] = Mango9CalendarAPI.timestamp(start)
			object["end_at"] = Mango9CalendarAPI.timestamp(start.addingTimeInterval(30 * 60))
			object["priority"] = priority
			if let status { object["status"] = ["id": 10, "name": status, "color": "#000000"] }
			return try Mango9CalendarAPI.decoder().decode(Mango9Appointment.self, from: JSONSerialization.data(withJSONObject: object))
		}
		let values = try [fixture(6, start: now.addingTimeInterval(-7200)),
			fixture(4, start: now.addingTimeInterval(7200), priority: "high"),
			fixture(3, start: now.addingTimeInterval(3600), priority: "low"),
			fixture(2, start: now.addingTimeInterval(3600)), fixture(1, start: base.startAt),
			fixture(5, start: now.addingTimeInterval(-3600)),
			fixture(7, start: now.addingTimeInterval(900), status: "Cancelled")]
		let groups = Mango9AppointmentAgenda.sections(values, now: now)
		XCTAssertEqual(groups.flatMap(\.events).map(\.id), [1, 2, 3, 4, 5, 6, 7])
		XCTAssertEqual(groups.first?.bucket, .dueNow)
		XCTAssertEqual(Mango9AppointmentAgenda.timing(values.last!, now: now), nil)
		let futureCustomStatus = try fixture(8, start: now.addingTimeInterval(900), status: "Awaiting client documents")
		XCTAssertEqual(Mango9AppointmentAgenda.bucket(futureCustomStatus, now: now), .upcoming)
		XCTAssertFalse(Mango9AppointmentAgenda.isClosed(futureCustomStatus))
	}

	func testAgendaCountdownAndBoundaryTransitionsAreClockBased() throws {
		let value = try event()
		XCTAssertEqual(Mango9AppointmentAgenda.timing(value, now: value.startAt.addingTimeInterval(-900)), "Upcoming in 15 minutes")
		XCTAssertEqual(Mango9AppointmentAgenda.timing(value, now: value.startAt.addingTimeInterval(-30)), "Starting in less than a minute")
		XCTAssertEqual(Mango9AppointmentAgenda.timing(value, now: value.startAt), "Due now · 30 minutes remaining")
		XCTAssertEqual(Mango9AppointmentAgenda.bucket(value, now: value.endAt), .past)
		XCTAssertEqual(Mango9AppointmentAgenda.duration(3600), "1 hour")
		XCTAssertEqual(Mango9AppointmentAgenda.duration(3660), "1 hour 1 min")
		XCTAssertEqual(Mango9AppointmentAgenda.duration(172800), "2 days")
	}

	func testAgendaUsesLocalDayAcrossDSTAndDoesNotDuplicateEvents() throws {
		let value = try event()
		var calendar = Calendar(identifier: .gregorian)
		calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Yerevan"))
		let sections = Mango9AppointmentAgenda.sections([value], now: value.startAt.addingTimeInterval(-900), calendar: calendar)
		XCTAssertEqual(sections.first?.day, calendar.startOfDay(for: value.startAt))
		XCTAssertEqual(sections.flatMap(\.events).map(\.id), [value.id])
	}

	@MainActor func testAppointmentCardsRenderWithStatusPriorityAndCountdownAtLargeText() async throws {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene); defer { window.isHidden = true }
		let value = try event()
		let noStatus = try event(eventJSON.replacingOccurrences(of: "\"status\":{\"id\":3,\"name\":\"Confirmed\",\"color\":\"#008080\"}", with: "\"status\":null")
			.replacingOccurrences(of: "\"priority\":\"medium\"", with: "\"priority\":\"high\""))
		for (width, size) in [(393.0, DynamicTypeSize.large), (320.0, .accessibility3)] {
			window.frame = CGRect(x: 0, y: 0, width: width, height: 1100)
			window.rootViewController = UIHostingController(rootView: ScrollView {
				VStack(alignment: .leading, spacing: 18) {
					Text("Appointments").font(.title2.bold())
					Mango9AppointmentRow(event: value, now: value.startAt.addingTimeInterval(-900))
						.padding(14).background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 16))
					Mango9AppointmentRow(event: noStatus, now: noStatus.startAt)
						.padding(14).background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 16))
				}.padding(16)
			}.background(Color(.systemGroupedBackground)).dynamicTypeSize(size))
			window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 500_000_000)
			capture(window, name: "Appointment agenda cards \(width) \(size)")
		}
	}
	private func requestBody(_ request: URLRequest) throws -> Data {
		if let body = request.httpBody { return body }
		let stream = try XCTUnwrap(request.httpBodyStream)
		stream.open(); defer { stream.close() }
		var data = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
		while true {
			let count = stream.read(&buffer, maxLength: buffer.count)
			if count < 0 { throw stream.streamError ?? URLError(.cannotDecodeContentData) }
			if count == 0 { return data }
			data.append(buffer, count: count)
		}
	}

	func testLegacyCalendarUsesLocalDaysAcrossDSTAndMonthBoundaries() throws {
		var calendar = Calendar(identifier: .gregorian)
		calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		let spring = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 3, day: 8)))
		let fall = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 1)))
		XCTAssertEqual(Mango9LegacyCalendarMode.day.range(containing: spring, calendar: calendar).duration, 23 * 3600)
		XCTAssertEqual(Mango9LegacyCalendarMode.day.range(containing: fall, calendar: calendar).duration, 25 * 3600)
		let september = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 30)))
		calendar.firstWeekday = 2
		let days = Mango9LegacyCalendarMode.week.days(containing: september, calendar: calendar)
		XCTAssertEqual(days.map { calendar.component(.day, from: $0) }, [28, 29, 30, 1, 2, 3, 4])
		XCTAssertEqual(calendar.component(.day, from: Mango9LegacyCalendarMode.week.range(containing: september, calendar: calendar).end), 5)
		XCTAssertEqual(Mango9LegacyCalendarMode.monthDays(containing: september, calendar: calendar).count, 30)
	}

	func testLegacyCalendarIncludesOvernightEventsButNotFabricatedRecurrences() throws {
		var calendar = Calendar(identifier: .gregorian)
		calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		let overnight = try event(eventJSON.replacingOccurrences(of: "2026-09-11T09:00:00-07:00", with: "2026-09-11T23:30:00-07:00")
			.replacingOccurrences(of: "2026-09-11T09:30:00-07:00", with: "2026-09-12T00:30:00-07:00"))
		let nextDay = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 12)))
		XCTAssertEqual(Mango9LegacyCalendarMode.events([overnight], on: nextDay, calendar: calendar).map(\.id), [91])
		let recurring = try event(eventJSON.replacingOccurrences(of: "\"frequency\":\"none\"", with: "\"frequency\":\"daily\""))
		XCTAssertTrue(Mango9LegacyCalendarMode.events([recurring], on: recurring.startAt, calendar: calendar).isEmpty)
	}

	@MainActor func testLegacyCalendarUsesSameAuthenticatedAPIAndRenders() async throws {
		let previousIdentity = Mango9SessionStore.activeIdentity
		let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
			CalendarMockURLProtocol.handler = nil
		}
		let json = eventJSON
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config)
		defer { transport.invalidateAndCancel() }
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true }
		for mode in [Mango9LegacyCalendarMode.month, .day, .week] {
			let loaded = expectation(description: "Legacy \(mode) calendar fetched scoped appointments")
			loaded.assertForOverFulfill = false
			CalendarMockURLProtocol.handler = { request in
				XCTAssertEqual(request.httpMethod, "GET")
				XCTAssertEqual(request.url?.host, "crm.example.invalid")
				XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "contact_id" }?.value, "101")
				XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token-not-a-credential")
				loaded.fulfill()
				return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":{\"events\":[\(json)],\"pagination\":{\"page\":1,\"limit\":100,\"total\":1,\"has_more\":false},\"snapshot\":\"fixture\"}}".utf8))
			}
			window.rootViewController = UIHostingController(rootView: Mango9LegacyCalendar(session: current, contactID: 101,
				date: .constant(try event().startAt), revision: UUID(), firstWeekday: 2, onSelect: { _ in },
				onError: { if let error = $0 { XCTFail(error) } }, transport: transport, initialMode: mode)
				.dynamicTypeSize(mode == .month ? .large : .accessibility3))
			window.makeKeyAndVisible()
			await fulfillment(of: [loaded], timeout: 10)
			try await Task.sleep(nanoseconds: 1_500_000_000)
			capture(window, name: "iOS 15-compatible \(mode) calendar\(mode == .month ? "" : " with large text")")
		}
	}

	func testMonthCellPreviewHandlesZeroNegativeAndNonfiniteHeights() throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Exyte is used only on iOS 18 and newer") }
		for height: CGFloat in [-100, 0, 1, 20, .nan, .infinity, -.infinity] {
			XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 3, availableHeight: height, rowHeight: 17), 0)
		}
		XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 3, availableHeight: 46, rowHeight: 17), 1)
		XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 3, availableHeight: 69, rowHeight: 17), 3)
		XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 3, availableHeight: .greatestFiniteMagnitude, rowHeight: 17), 3)
		XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 0, availableHeight: 100, rowHeight: 17), 0)
		XCTAssertEqual(Mango9MonthDay.visibleEventCount(total: 3, availableHeight: 100, rowHeight: 0), 0)
	}

	@MainActor func testMonthCellsRenderAtCompactAndAccessibilitySizes() async throws {
		guard #available(iOS 18.0, *) else { throw XCTSkip("Exyte is used only on iOS 18 and newer") }
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true }
		let events: [any CalendarEntity] = (0..<4).map {
			CalendarEvent(id: "fixture-\($0)", title: "Appointment \($0)", calendarColor: .teal, startDate: Date())
		}
		for size in [DynamicTypeSize.large, .accessibility5] {
			window.rootViewController = UIHostingController(rootView:
				HStack(alignment: .top) {
					ForEach([1, 32, 80, 150], id: \.self) { height in
						Mango9MonthDay(date: Date(), events: events).frame(width: 80, height: CGFloat(height))
					}
				}.dynamicTypeSize(size))
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 300_000_000)
			capture(window, name: "Safe compact month cells \(size)")
		}
	}

	private func session(host: String = "crm.example.invalid", user: String = "42") -> Mango9Session {
		Mango9Session(crmId: "crm", crmBaseUrl: "https://\(host)", crmApiBaseUrl: "https://\(host)/api/v2",
			userId: user, parentClientId: "1", role: "client", loginId: "calendar-test@example.invalid",
			displayName: "Calendar Test", accessToken: "test-token-not-a-credential", refreshToken: "test-refresh",
			smsChatApi: "", connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:\(user)@\(host)")
	}
	private var eventJSON: String {
		"""
		{"id":91,"owner_id":42,"title":"Client consultation","description":"Discuss next steps",
		"start_at":"2026-09-11T09:00:00-07:00","end_at":"2026-09-11T09:30:00-07:00",
		"timezone":"America/Los_Angeles","activity":"appointment","priority":"medium",
		"status":{"id":3,"name":"Confirmed","color":"#008080"},
		"contact":{"id":101,"name":"Taylor Reed","kind":"lead"},
		"recurrence":{"frequency":"none","weekdays":[]},"reminders":[],"origin":"appointment",
		"shared_by_me_user_ids":[43],"permissions":{"can_edit":true,"can_delete":true,"can_assign":true,
		"can_share":true,"can_change_contact":true,"read_only_reason":null},"revision":"abc123"}
		"""
	}
	private var metadataJSON: String {
		"""
		{"timezone":"America/Los_Angeles","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
		"activities":["appointment","call","meeting","task","follow_up"],"priorities":["low","medium","high"],
		"reminder_minutes":[0,15,30,60],"reminder_channels":["email","sms"],
		"share_recipients":[{"id":43,"name":"Jordan Lee"}],"assignees":[{"id":43,"name":"Jordan Lee"}],
		"capabilities":{"create":true,"assign":true,"push":false}}
		"""
	}
	private func event(_ json: String? = nil) throws -> Mango9Appointment {
		try Mango9CalendarAPI.decoder().decode(Mango9Appointment.self, from: Data((json ?? eventJSON).utf8))
	}
	private func draft(_ event: Mango9Appointment) -> Mango9AppointmentDraft {
		.init(title: event.title, notes: event.description, start: event.startAt, end: event.endAt,
			activity: event.activity, priority: event.priority, status: event.status?.id ?? 0,
			contact: event.contact, shares: Set(event.sharedByMeUserIds), assignee: 0, reminder: -1, channels: ["email"])
	}

	func testDecodesLiveContractDatesPermissionsAndRelations() throws {
		let value = try event()
		XCTAssertEqual(Mango9CalendarAPI.timestamp(value.startAt), "2026-09-11T16:00:00Z")
		XCTAssertEqual(value.contact?.id, 101)
		XCTAssertEqual(value.sharedByMeUserIds, [43])
		XCTAssertTrue(value.permissions.canEdit)
		XCTAssertFalse(value.isRecurring)
		let metadata = try Mango9CalendarAPI.decoder().decode(Mango9CalendarMetadata.self, from: Data(metadataJSON.utf8))
		XCTAssertFalse(metadata.capabilities.push)
		XCTAssertEqual(metadata.statuses.first?.name, "Confirmed")
	}

	func testRequestsUseSelectedCRMNotProxyAndScopeAccountKeys() throws {
		let first = session(); let second = session(host: "other.example.invalid")
		let request = try Mango9CalendarAPI.request(session: first, path: "events", query: [.init(name: "contact_id", value: "101")])
		XCTAssertEqual(request.url?.absoluteString, "https://crm.example.invalid/api/v2/mobile/calendar/events?contact_id=101")
		XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token-not-a-credential")
		XCTAssertNotEqual(Mango9CalendarAPI.accountKey(first), Mango9CalendarAPI.accountKey(second))
		XCTAssertNotEqual(Mango9CalendarAPI.accountKey(first), Mango9CalendarAPI.accountKey(session(user: "43")))
	}

	func testCreateUpdateDeleteWireMethodsAndRevision() throws {
		let value = try event()
		let body = draft(value).payload(event: nil, owner: true, timezone: "America/Los_Angeles")
		let create = try Mango9CalendarAPI.request(session: session(), path: "events", method: "POST", body: body)
		XCTAssertEqual(create.httpMethod, "POST")
		XCTAssertNil(create.value(forHTTPHeaderField: "If-Match"))
		XCTAssertNotNil(create.httpBody)
		XCTAssertEqual(body["contact_id"] as? Int, 101)
		let update = try Mango9CalendarAPI.request(session: session(), path: "events/91", method: "PATCH", body: ["status_id": 3], revision: value.revision)
		XCTAssertEqual(update.httpMethod, "PATCH")
		XCTAssertEqual(update.value(forHTTPHeaderField: "If-Match"), "\"abc123\"")
		let delete = try Mango9CalendarAPI.request(session: session(), path: "events/91", method: "DELETE", revision: value.revision)
		XCTAssertNil(delete.httpBody)
		XCTAssertEqual(delete.httpMethod, "DELETE")
		XCTAssertEqual(delete.value(forHTTPHeaderField: "If-Match"), "\"abc123\"")
	}

	func testStatusOnlyEditDoesNotRewriteScheduleContactOrReminders() throws {
		let value = try event()
		var edit = draft(value)
		XCTAssertTrue(edit.payload(event: value, owner: true, timezone: value.timezone).isEmpty)
		edit.status = 0
		let body = edit.payload(event: value, owner: true, timezone: value.timezone)
		XCTAssertEqual(Set(body.keys), ["status_id"])
		XCTAssertTrue(body["status_id"] is NSNull)
	}

	func testSharedRecipientCannotSendOwnerOnlyFields() throws {
		let json = eventJSON.replacingOccurrences(of: "\"can_delete\":true", with: "\"can_delete\":false")
			.replacingOccurrences(of: "\"can_assign\":true", with: "\"can_assign\":false")
			.replacingOccurrences(of: "\"can_change_contact\":true", with: "\"can_change_contact\":false")
		let value = try event(json)
		var edit = draft(value)
		edit.title = "Updated"; edit.contact = nil; edit.assignee = 90; edit.reminder = 15
		let body = edit.payload(event: value, owner: false, timezone: value.timezone)
		XCTAssertEqual(Set(body.keys), ["title"])
	}

	func testRevokingOwnSharesUsesEmptyArrayAndAssignmentIsExplicit() throws {
		let value = try event()
		var edit = draft(value); edit.shares = []; edit.assignee = 43
		let body = edit.payload(event: value, owner: true, timezone: value.timezone)
		XCTAssertEqual(body["share_user_ids"] as? [Int], [])
		XCTAssertEqual(body["assign_to_user_id"] as? Int, 43)
		XCTAssertNil(body["contact_id"])
	}

	func testReadOnlyEventsCannotProduceMutations() throws {
		let value = try event(eventJSON.replacingOccurrences(of: ":true", with: ":false"))
		var edit = draft(value); edit.title = "Ignored"; edit.shares = []; edit.assignee = 43; edit.contact = nil
		XCTAssertTrue(edit.payload(event: value, owner: false, timezone: value.timezone).isEmpty)
	}

	func testConflictIsExplicitAndNeverDecodedAsSuccess() throws {
		let json = "{\"success\":false,\"message\":\"Changed\",\"error\":{\"code\":\"event_changed\"}}"
		XCTAssertThrowsError(try Mango9CalendarAPI.decode(Mango9Appointment.self, data: Data(json.utf8), status: 409)) {
			XCTAssertEqual(($0 as? Mango9CalendarFailure)?.code, "event_changed")
			XCTAssertTrue($0.localizedDescription.contains("another device"))
		}
	}

	func testSnapshotsPageAtomicallyAndUseContactFilter() async throws {
		let value = try event()
		var calls = 0
		let result = try await Mango9CalendarAPI.collect(start: value.startAt, end: value.endAt, contactID: 101) { query in
			calls += 1
			XCTAssertEqual(query.first { $0.name == "contact_id" }?.value, "101")
			if calls == 2 { XCTAssertEqual(query.first { $0.name == "snapshot" }?.value, "s1") }
			return .init(events: calls == 1 ? [value] : [], pagination: .init(page: calls, limit: 100, total: 1, hasMore: calls == 1), snapshot: "s1")
		}
		XCTAssertEqual(calls, 2); XCTAssertEqual(result.map(\.id), [91])
	}

	func testSnapshotConflictRestartsAndDiscardsPartialResults() async throws {
		let value = try event()
		var calls = 0
		let result = try await Mango9CalendarAPI.collect(start: value.startAt, end: value.endAt, contactID: nil) { query in
			calls += 1
			if calls == 2 { throw Mango9CalendarFailure(status: 409, code: "snapshot_changed", message: "Changed") }
			return .init(events: calls == 1 ? [value] : [], pagination: .init(page: 1, limit: 100, total: 0, hasMore: calls == 1), snapshot: calls == 1 ? "old" : "new")
		}
		XCTAssertEqual(calls, 3); XCTAssertTrue(result.isEmpty)
	}

	func testExytePreloadIsSplitBelowAPIRangeLimitWithoutGaps() {
		let start = Date(timeIntervalSince1970: 0)
		let end = start.addingTimeInterval(101 * 86400)
		let windows = Mango9CalendarAPI.displayWindows(start: start, end: end)
		XCTAssertEqual(windows.count, 2)
		XCTAssertEqual(windows.first?.start, start)
		XCTAssertEqual(windows.last?.end, end)
		XCTAssertEqual(windows[0].end, windows[1].start)
		XCTAssertTrue(windows.allSatisfy { $0.duration <= 93 * 86400 })
	}

	@MainActor func testAuthenticatedTransportCRUDAndAccountSwitchRejection() async throws {
		let previousIdentity = Mango9SessionStore.activeIdentity
		let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
			CalendarMockURLProtocol.handler = nil
		}
		let json = eventJSON
		var methods: [String] = []
		CalendarMockURLProtocol.handler = { request in
			methods.append(request.httpMethod!)
			XCTAssertEqual(request.url?.host, "crm.example.invalid")
			XCTAssertNotNil(request.value(forHTTPHeaderField: "Authorization"))
			if request.httpMethod == "PATCH" || request.httpMethod == "DELETE" { XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "\"abc123\"") }
			let result = request.httpMethod == "DELETE" ? "{\"deleted\":true}" : json
			return (request.httpMethod == "POST" ? 201 : 200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(result)}".utf8))
		}
		let configuration = URLSessionConfiguration.ephemeral
		configuration.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: configuration)
		defer { transport.invalidateAndCancel() }
		let read = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: current, path: "events/91", transport: transport)
		let body = draft(read).payload(event: nil, owner: true, timezone: read.timezone)
		_ = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: current, path: "events", method: "POST", body: body, transport: transport)
		_ = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: current, path: "events/91", method: "PATCH", body: ["status_id": 3], revision: read.revision, transport: transport)
		_ = try await Mango9CalendarAPI.send(Mango9CalendarAPI.Empty.self, session: current, path: "events/91", method: "DELETE", revision: read.revision, transport: transport)
		XCTAssertEqual(methods, ["GET", "POST", "PATCH", "DELETE"])
		do {
			_ = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session(host: "other.example.invalid"), path: "events/91", transport: transport)
			XCTFail("Wrong account must not send a request")
		} catch { XCTAssertEqual((error as? Mango9CalendarFailure)?.code, "account_changed") }
		XCTAssertEqual(methods.count, 4)
	}

	@MainActor func testListAndCalendarRenderFromAuthenticatedAPIFixturesOnBothOSPaths() async throws {
		let previousIdentity = Mango9SessionStore.activeIdentity
		let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
			CalendarMockURLProtocol.handler = nil
		}
		let metadata = metadataJSON; let json = eventJSON
		CalendarMockURLProtocol.handler = { request in
			let result = request.url!.path.hasSuffix("metadata") ? metadata :
				"{\"events\":[\(json)],\"pagination\":{\"page\":1,\"limit\":100,\"total\":1,\"has_more\":false},\"snapshot\":\"fixture\"}"
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(result)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config)
		defer { transport.invalidateAndCancel() }
		let value = try event()
		let store = Mango9AppointmentsStore(transport: transport)
		await store.load(month: value.startAt)
		XCTAssertNil(store.error); XCTAssertEqual(store.events.count, 1)
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true }
		let list = NavigationView { Mango9AppointmentsFragment(store: store, date: value.startAt) }.navigationViewStyle(.stack)
		window.rootViewController = UIHostingController(rootView: list)
		window.makeKeyAndVisible()
		try await Task.sleep(nanoseconds: 1_000_000_000)
		capture(window, name: "Appointment list with lead link and status")
		window.rootViewController = UIHostingController(rootView:
			Mango9AppointmentsFragment(store: store, date: value.startAt, calendarMode: true))
		try await Task.sleep(nanoseconds: 2_000_000_000)
		XCTAssertNil(store.error); XCTAssertNil(store.calendarError)
		XCTAssertEqual(store.events.map(\.id), [91])
		capture(window, name: "OS-appropriate calendar with CRM appointment")
		store.reset(); XCTAssertTrue(store.events.isEmpty); XCTAssertNil(store.metadata)
	}

	@MainActor func testMissingMetadataKeepsCalendarVisibleAndClearsPermissionsOnBothOSPaths() async throws {
		let previousIdentity = Mango9SessionStore.activeIdentity
		let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
			CalendarMockURLProtocol.handler = nil
		}
		let eventRequest = expectation(description: "Calendar loads independently of metadata")
		eventRequest.assertForOverFulfill = false
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.url?.host, "crm.example.invalid")
			if request.url!.path.hasSuffix("events") { eventRequest.fulfill() }
			return (404, Data("{}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config)
		defer { transport.invalidateAndCancel() }
		let store = Mango9AppointmentsStore(transport: transport)
		store.metadata = try Mango9CalendarAPI.decoder().decode(Mango9CalendarMetadata.self, from: Data(metadataJSON.utf8))
		await store.load(month: Date())
		XCTAssertNil(store.metadata)
		XCTAssertTrue(store.events.isEmpty)
		XCTAssertTrue(store.error?.contains("crm.example.invalid") == true)
		let metadataError = store.error
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true }
		window.rootViewController = UIHostingController(rootView: Mango9AppointmentsFragment(store: store, date: Date(), calendarMode: true))
		window.makeKeyAndVisible()
		await fulfillment(of: [eventRequest], timeout: 10)
		try await Task.sleep(nanoseconds: 1_000_000_000)
		XCTAssertEqual(store.error, metadataError, "Provider errors must not overwrite metadata errors")
		capture(window, name: "Calendar remains visible when CRM endpoint is unavailable")
	}

	@MainActor func testSameAccountRefreshKeepsSheetMetadataAndInvalidatesCalendarOnce() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
			CalendarMockURLProtocol.handler = nil
		}
		let metadata = metadataJSON; let json = eventJSON
		CalendarMockURLProtocol.handler = { request in
			let result = request.url!.path.hasSuffix("metadata") ? metadata :
				"{\"events\":[\(json)],\"pagination\":{\"page\":1,\"limit\":100,\"total\":1,\"has_more\":false},\"snapshot\":\"fixture\"}"
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(result)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		let store = Mango9AppointmentsStore(transport: transport)
		let date = try event().startAt
		await store.load(month: date)
		XCTAssertNotNil(store.metadata)
		var removedSheetContent = false
		var invalidations = 0
		let metadataSubscription = store.$metadata.dropFirst().sink { if $0 == nil { removedSheetContent = true } }
		let calendarSubscription = store.$calendarRevision.dropFirst().sink { _ in invalidations += 1 }
		defer { metadataSubscription.cancel(); calendarSubscription.cancel() }
		await store.load(month: date)
		XCTAssertFalse(removedSheetContent, "A same-account refresh must not unmount the detail/editor sheet")
		XCTAssertEqual(invalidations, 1, "One refresh must not reset the calendar twice")
		XCTAssertEqual(store.events.map(\.id), [91])
		// Retained display metadata is not a permission bypass: failed reads clear it.
		CalendarMockURLProtocol.handler = { _ in (403, Data("{}".utf8)) }
		await store.load(month: date)
		XCTAssertNil(store.metadata); XCTAssertTrue(store.events.isEmpty); XCTAssertNotNil(store.error)
	}

	@MainActor private func capture(_ window: UIWindow, name: String) {
		window.layoutIfNeeded()
		let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
			XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
		}
		let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
	}

	@MainActor func testAppointmentEditorDraftAndScrollSurviveParentRefresh() async throws {
		try await withRefreshFixture { store, date, window in
			window.rootViewController = UIHostingController(rootView:
				Mango9AppointmentsFragment(store: store, date: date, creating: true))
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 1_500_000_000)
			let title = try XCTUnwrap(self.descendants(window).compactMap { $0 as? UITextField }.first {
				$0.accessibilityIdentifier == "appointment.title" || $0.placeholder == "Title"
			})
			title.text = "Unsaved appointment draft"
			title.sendActions(for: .editingChanged)
			let form = try XCTUnwrap(self.descendants(window).compactMap { $0 as? UIScrollView }.last {
				$0.bounds.height > 200 && $0.contentSize.height > $0.bounds.height
			})
			form.setContentOffset(CGPoint(x: 0, y: min(180, form.contentSize.height - form.bounds.height)), animated: false)
			try await Task.sleep(nanoseconds: 300_000_000)
			let offset = form.contentOffset.y
			for _ in 0..<3 {
				await store.load(month: date)
				try await Task.sleep(nanoseconds: 300_000_000)
				XCTAssertNotNil(title.window, "Refreshing must not recreate the editor")
				XCTAssertEqual(title.text, "Unsaved appointment draft")
				XCTAssertEqual(form.contentOffset.y, offset, accuracy: 1)
			}
			self.capture(window, name: "Appointment editor retains draft and position after refresh")
			window.rootViewController?.dismiss(animated: false)
		}
	}

	@MainActor func testCalendarScrollerSurvivesRepeatedRefreshesOnBothOSPaths() async throws {
		try await withRefreshFixture { store, date, window in
			window.rootViewController = UIHostingController(rootView:
				Mango9AppointmentsFragment(store: store, date: date, calendarMode: true))
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 2_000_000_000)
			let table: UIScrollView
			if #available(iOS 18.0, *) {
				table = try XCTUnwrap(self.descendants(window).compactMap { $0 as? UITableView }.first { $0.bounds.height > 200 })
			} else {
				table = try XCTUnwrap(self.descendants(window).compactMap { $0 as? UIScrollView }.first { $0.bounds.height > 200 })
			}
			let target = min(table.contentOffset.y + table.bounds.height * 0.15, max(0, table.contentSize.height - table.bounds.height))
			table.setContentOffset(CGPoint(x: 0, y: target), animated: false)
			try await Task.sleep(nanoseconds: 500_000_000)
			let offset = table.contentOffset.y
			let metadata = self.metadataJSON
			let changed = self.eventJSON.replacingOccurrences(of: "Client consultation", with: "Updated visit")
			CalendarMockURLProtocol.handler = { request in
				let result = request.url!.path.hasSuffix("metadata") ? metadata :
					"{\"events\":[\(changed)],\"pagination\":{\"page\":1,\"limit\":100,\"total\":1,\"has_more\":false},\"snapshot\":\"updated\"}"
				return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(result)}".utf8))
			}
			for _ in 0..<3 {
				await store.load(month: date)
				try await Task.sleep(nanoseconds: 500_000_000)
				XCTAssertTrue(self.descendants(window).contains { $0 === table }, "Calendar refresh must not replace its scroller")
				XCTAssertEqual(table.contentOffset.y, offset, accuracy: 1)
				XCTAssertEqual(store.events.first?.title, "Updated visit")
			}
			self.capture(window, name: "Calendar retains visible month position after refresh")
		}
	}

	@MainActor private func descendants(_ view: UIView) -> [UIView] {
		[view] + view.subviews.flatMap { descendants($0) }
	}

	@MainActor private func withRefreshFixture(_ action: (Mango9AppointmentsStore, Date, UIWindow) async throws -> Void) async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
			CalendarMockURLProtocol.handler = nil
		}
		let metadata = metadataJSON; let json = eventJSON
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.httpMethod, "GET", "Changing local options must not save automatically")
			let result = request.url!.path.hasSuffix("metadata") ? metadata :
				"{\"events\":[\(json)],\"pagination\":{\"page\":1,\"limit\":100,\"total\":1,\"has_more\":false},\"snapshot\":\"fixture\"}"
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(result)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		let store = Mango9AppointmentsStore(transport: transport); let date = try event().startAt
		await store.load(month: date)
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let previousWindow = scene.windows.first { $0.isKeyWindow }
		let window = UIWindow(windowScene: scene); window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true; previousWindow?.makeKey() }
		func detachFixture() async {
			// A hidden window can still own sheet subscriptions. Detach the view tree
			// before invalidating its injected URLSession or changing test accounts.
			window.rootViewController?.dismiss(animated: false)
			window.isHidden = true
			window.rootViewController = nil
			try? await Task.sleep(nanoseconds: 500_000_000)
		}
		do { try await action(store, date, window) }
		catch { await detachFixture(); throw error }
		await detachFixture()
	}

	private var settingsJSON: String {
		"""
		{"in_app_reminders":true,"reminder_minutes":15,"repeat_minutes":0,"snooze_minutes":10,
		"new_appointment_timezone":"Asia/Yerevan","first_weekday":2,"default_calendar_view":"appointments",
		"sms_push":false,"team_chat_push":true,"account_timezone":"America/Los_Angeles","revision":"settings-fixture"}
		"""
	}
	private func reminder(due: Date?, dismissed: Bool = false) -> Mango9PersonalReminder {
		.init(enabled: true, minutesBefore: 15, dueAt: due, dismissed: dismissed, needsReenable: false, revision: "reminder-fixture")
	}
	func testPushBackKeepsDurationAndOnlyChangesAbsoluteDates() throws {
		let value = try event(); let dates = Mango9AppointmentActions.pushedDates(event: value, minutes: 15)
		XCTAssertEqual(dates.duration, value.endAt.timeIntervalSince(value.startAt))
		XCTAssertEqual(dates.start.timeIntervalSince(value.startAt), 900)
		let body = Mango9AppointmentActions.pushBackBody(event: value, minutes: 15)
		XCTAssertEqual(Set(body.keys), ["start_at", "end_at"])
		XCTAssertEqual(body["start_at"] as? String, "2026-09-11T16:15:00Z")
	}
	func testSnoozeUsesSeparateEndpointBodyAndRevisionNotAppointmentTimes() throws {
		let due = Date(timeIntervalSince1970: 1800000000)
		let body = Mango9AppointmentActions.snoozeBody(until: due, reminder: reminder(due: due))
		XCTAssertEqual(Set(body.keys), ["action", "until", "reminder_revision"])
		let request = try Mango9CalendarAPI.request(session: session(), path: "events/91/reminder", method: "PATCH", body: body, revision: "event-revision")
		XCTAssertTrue(request.url!.path.hasSuffix("events/91/reminder"))
		XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "\"event-revision\"")
	}
	func testReminderCadenceIsBoundedDeduplicatedAndAccountScoped() throws {
		let event = try event(); let due = event.startAt.addingTimeInterval(-900)
		let item = Mango9ReminderFeed.Item(event: event, reminder: reminder(due: due))
		let key = Mango9ReminderCadence.key(session: session(), item: item)
		XCTAssertNotEqual(key, Mango9ReminderCadence.key(session: session(host: "second.example.invalid"), item: item))
		XCTAssertFalse(key.contains("91" + "|")); XCTAssertEqual(key.count, 64)
		var cadence = Mango9ReminderCadence()
		XCTAssertTrue(cadence.allows(key, now: due, repeatMinutes: 0))
		cadence.record(key, at: due)
		XCTAssertFalse(cadence.allows(key, now: due.addingTimeInterval(3600), repeatMinutes: 0))
		XCTAssertFalse(cadence.allows(key, now: due.addingTimeInterval(299), repeatMinutes: 5))
		XCTAssertTrue(cadence.allows(key, now: due.addingTimeInterval(300), repeatMinutes: 5))
		for index in 0..<300 { cadence.record("fixture-\(index)", at: due.addingTimeInterval(Double(index))) }
		XCTAssertEqual(cadence.lastShown.count, 256)
	}
	func testDueRemindersExcludeFutureEndedAndDismissedEvents() throws {
		let event = try event(); let now = event.startAt
		let due = Mango9ReminderFeed.Item(event: event, reminder: reminder(due: now))
		let future = Mango9ReminderFeed.Item(event: event, reminder: reminder(due: now.addingTimeInterval(300)))
		let dismissed = Mango9ReminderFeed.Item(event: event, reminder: reminder(due: now, dismissed: true))
		let feed = Mango9ReminderFeed(items: [due, future, dismissed], serverTime: now, settings: nil)
		XCTAssertEqual(feed.due(at: now).count, 1)
		XCTAssertTrue(feed.due(at: event.endAt).isEmpty)
	}
	func testCRMSettingsContractUsesSelectedAccountAndNeverIncludesIdentityOrRevisionInBody() throws {
		let settings = try Mango9CalendarAPI.decoder().decode(Mango9CRMPreferences.self, from: Data(settingsJSON.utf8))
		XCTAssertFalse(settings.smsPush); XCTAssertTrue(settings.teamChatPush)
		XCTAssertEqual(settings.appointmentTimezone.identifier, "Asia/Yerevan")
		XCTAssertEqual(settings.calendarFirstWeekday, 2)
		XCTAssertNil(settings.body()["user_id"]); XCTAssertNil(settings.body()["revision"])
		let request = try Mango9CalendarAPI.request(session: session(), path: "settings", method: "PATCH", body: settings.body(), revision: settings.revision, scope: .crm)
		XCTAssertEqual(request.url?.absoluteString, "https://crm.example.invalid/api/v2/mobile/crm/settings")
		XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "\"settings-fixture\"")
	}
	@MainActor func testReminderStoreClearsOnOfflineAndAccountRemoval() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		let suite = "calendar-reminder-tests-\(UUID().uuidString)"; let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
		defer {
			Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
			CalendarMockURLProtocol.handler = nil; defaults.removePersistentDomain(forName: suite)
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		let json = "{\"items\":[{\"event\":\(eventJSON),\"reminder\":{\"enabled\":true,\"minutes_before\":15,\"due_at\":\"2026-09-11T16:00:00Z\",\"dismissed\":false,\"needs_reenable\":false,\"revision\":\"r1\"}}],\"server_time\":\"2026-09-11T16:00:00Z\",\"settings\":\(settingsJSON)}"
		var requests = 0
		CalendarMockURLProtocol.handler = { request in requests += 1; XCTAssertTrue(request.url!.path.hasSuffix("/reminders")); return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(json)}".utf8)) }
		let store = Mango9InAppReminderStore(transport: transport, defaults: defaults)
		await store.refresh(); XCTAssertEqual(store.due.count, 1)
		CalendarMockURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
		await store.refresh(); XCTAssertTrue(store.due.isEmpty)
		Mango9SessionStore.remove(for: current.sipIdentity!)
		await store.refresh(); XCTAssertTrue(store.due.isEmpty); XCTAssertNil(store.session); XCTAssertEqual(requests, 1)
	}
	@MainActor func testCRMSettingsAndPushBackRender() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous); CalendarMockURLProtocol.handler = nil }
		let json = settingsJSON
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.httpMethod, "GET"); XCTAssertTrue(request.url!.path.hasSuffix("/crm/settings"))
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(json)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene); window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true }
		window.rootViewController = UIHostingController(rootView: NavigationView { Mango9CRMSettings(transport: transport) }.navigationViewStyle(.stack))
		window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 1_000_000_000)
		capture(window, name: "CRM settings and reminder frequency")
		window.rootViewController = UIHostingController(rootView: Mango9PushBackAppointment(event: try event(), session: current))
		try await Task.sleep(nanoseconds: 400_000_000)
		capture(window, name: "Push back preview with original and new times")
	}

	@MainActor func testReminderOptionMeasuresFullTextBeforeInteraction() async throws {
		guard #available(iOS 16.0, *) else { throw XCTSkip("Hosting size measurement requires iOS 16") }
		for width: CGFloat in [260, 345] {
			for size in [DynamicTypeSize.large, .accessibility3, .accessibility5] {
				let label = Mango9CRMOptionLabel(title: "In-app reminder", value: "120 minutes before", systemImage: "bell")
					.dynamicTypeSize(size)
				let host = UIHostingController(rootView: label)
				let proposal = CGSize(width: width, height: .greatestFiniteMagnitude)
				let first = host.sizeThatFits(in: proposal)
				XCTAssertGreaterThan(first.height, size == .large ? 45 : 85, "Title and selected value must have their own measured lines")
				XCTAssertLessThanOrEqual(first.width, width + 1)
				for _ in 0..<3 {
					host.view.setNeedsLayout(); host.view.layoutIfNeeded()
					XCTAssertEqual(host.sizeThatFits(in: proposal).height, first.height, accuracy: 1, "Row must not correct a clipped height after interaction")
				}
			}
		}
	}

	@MainActor func testSettingsCategoryIconsRenderWithExistingCollapsedAndExpandedContent() async throws {
		for symbol in ["slider.horizontal.3", "phone.fill", "bubble.left.and.bubble.right.fill", "person.crop.rectangle", "video.fill", "network"] {
			XCTAssertNotNil(UIImage(systemName: symbol), "Category icon must exist on this supported OS")
		}
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true; window.rootViewController = nil }
		for (width, size, expanded) in [(CGFloat(393), DynamicTypeSize.large, false), (320, .accessibility3, false), (393, .large, true)] {
			window.frame = CGRect(x: 0, y: 0, width: width, height: 852)
			window.rootViewController = UIHostingController(rootView:
				SettingsFragment(isShowSettingsFragment: .constant(true), networkIsOpen: expanded).dynamicTypeSize(size))
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 600_000_000)
			capture(window, name: "Settings category icons width \(width) \(size) network expanded \(expanded)")
		}
	}

	@MainActor func testInlineCRMSettingsLoadsOnExpansionAndKeepsUnsavedDraftWhenCollapsed() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous); CalendarMockURLProtocol.handler = nil }
		let json = settingsJSON; var reads = 0
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.httpMethod, "GET", "Expanding/collapsing settings must not save them")
			XCTAssertTrue(request.url!.path.hasSuffix("/crm/settings")); reads += 1
			Thread.sleep(forTimeInterval: 0.6) // Slow fixture: collapse/reopen before the initial read finishes.
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(json)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config)
		let state = CRMDisclosureFixtureState()
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene); window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		window.rootViewController = UIHostingController(rootView: CRMDisclosureFixture(state: state, transport: transport))
		window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 300_000_000)
		XCTAssertEqual(reads, 0)
		state.expanded = true; try await Task.sleep(nanoseconds: 150_000_000)
		state.expanded = false; try await Task.sleep(nanoseconds: 150_000_000)
		state.expanded = true; try await Task.sleep(nanoseconds: 800_000_000)
		XCTAssertEqual(reads, 1)
		XCTAssertEqual(descendants(window).compactMap { $0 as? UIScrollView }.filter { $0.bounds.height > 100 }.count, 1, "Inline CRM must use the parent scroll view, not a nested Form")
		let toggle = try XCTUnwrap(descendants(window).compactMap { $0 as? UISwitch }.first)
		XCTAssertTrue(toggle.isOn)
		toggle.setOn(false, animated: false); toggle.sendActions(for: .valueChanged)
		try await Task.sleep(nanoseconds: 100_000_000)
		state.expanded = false; try await Task.sleep(nanoseconds: 200_000_000)
		state.expanded = true; try await Task.sleep(nanoseconds: 500_000_000)
		XCTAssertFalse(toggle.isOn); XCTAssertNotNil(toggle.window)
		XCTAssertEqual(reads, 1, "Reopening must retain the unsaved draft, not overwrite it from server")
		capture(window, name: "Inline CRM settings with retained draft")
		window.isHidden = true; window.rootViewController = nil
		try await Task.sleep(nanoseconds: 300_000_000); transport.invalidateAndCancel()
	}

	@MainActor func testPersonalReminderDetailRendersInitialLargeTextWithoutSelection() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous); CalendarMockURLProtocol.handler = nil }
		let json = eventJSON.replacingOccurrences(of: "2026-09-11", with: "2032-09-11")
		let metadata = try Mango9CalendarAPI.decoder().decode(Mango9CalendarMetadata.self,
			from: Data(metadataJSON.replacingOccurrences(of: "\"push\":false", with: "\"push\":false,\"in_app_reminders\":true").utf8))
		let reminder = "{\"enabled\":true,\"minutes_before\":120,\"due_at\":\"2026-09-12T16:00:00Z\",\"dismissed\":false,\"needs_reenable\":false,\"revision\":\"r1\"}"
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.httpMethod, "GET")
			let data = request.url!.path.hasSuffix("/reminder") ? reminder : json
			return (200, Data("{\"success\":true,\"message\":\"success\",\"data\":\(data)}".utf8))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config)
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene); window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		for size in [DynamicTypeSize.large, .accessibility3] {
			window.rootViewController = UIHostingController(rootView: Mango9AppointmentDetail(event: try event(json), session: current, metadata: metadata, transport: transport).dynamicTypeSize(size))
			window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 700_000_000)
			// The full suite can still be completing the mocked GET and navigation
			// transition here. Wait for idle with a bound rather than treating a
			// fixed 700 ms scheduling budget as the application's loading contract.
			for _ in 0..<50 {
				let loading = descendants(window).compactMap { $0 as? UIActivityIndicatorView }.contains { $0.isAnimating }
				if !loading { break }
				try await Task.sleep(nanoseconds: 100_000_000)
			}
			XCTAssertTrue(descendants(window).compactMap { $0 as? UIActivityIndicatorView }.allSatisfy { !$0.isAnimating },
				"A loaded appointment must not retain an active toolbar spinner")
			capture(window, name: "Appointment compact Edit toolbar \(size)")
			let list = try XCTUnwrap(descendants(window).compactMap { $0 as? UIScrollView }.first { $0.contentSize.height > $0.bounds.height && $0.bounds.height > 200 })
			// Lazy List initially estimates offscreen row heights. Let it measure those
			// rows while scrolling; never select a reminder to force a relayout.
			for _ in 0..<4 {
				list.setContentOffset(CGPoint(x: 0, y: max(0, list.contentSize.height - list.bounds.height)), animated: false)
				try await Task.sleep(nanoseconds: 250_000_000)
			}
			capture(window, name: "Initial reminder controls without selection \(size)")
		}
		window.isHidden = true; window.rootViewController = nil
		try await Task.sleep(nanoseconds: 300_000_000); transport.invalidateAndCancel()
	}

	@MainActor func testSettingsAndPersonalReminderPatchReadBackThroughAuthenticatedTransport() async throws {
		let previous = Mango9SessionStore.activeIdentity; let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: current.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous); CalendarMockURLProtocol.handler = nil }
		var settings = try JSONSerialization.jsonObject(with: Data(settingsJSON.utf8)) as! [String: Any]
		var reminder: [String: Any] = ["enabled": true, "minutes_before": 15, "dismissed": false, "needs_reenable": false, "revision": "r1"]
		var writes = 0
		CalendarMockURLProtocol.handler = { request in
			XCTAssertEqual(request.url?.host, "crm.example.invalid")
			XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token-not-a-credential")
			let isSettings = request.url!.path.hasSuffix("/crm/settings")
			if request.httpMethod == "PATCH" {
				let input = try JSONSerialization.jsonObject(with: self.requestBody(request)) as! [String: Any]
				if isSettings {
					XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "\"settings-fixture\"")
					settings.merge(input) { _, new in new }; settings["revision"] = "settings-saved"
				} else {
					XCTAssertTrue(request.url!.path.hasSuffix("/calendar/events/91/reminder"))
					XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "\"abc123\"")
					XCTAssertEqual(input["reminder_revision"] as? String, "r1")
					XCTAssertEqual(input["action"] as? String, "configure")
					reminder["minutes_before"] = input["minutes_before"]; reminder["revision"] = "r2"
				}
				writes += 1
			}
			return (200, try JSONSerialization.data(withJSONObject: ["success": true, "message": "success", "data": isSettings ? settings : reminder]))
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CalendarMockURLProtocol.self]
		let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
		var before = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: current, path: "settings", transport: transport, scope: .crm)
		before.snoozeMinutes = 30; before.smsPush = true; before.teamChatPush = false
		let saved = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: current, path: "settings", method: "PATCH", body: before.body(), revision: before.revision, transport: transport, scope: .crm)
		let reread = try await Mango9CalendarAPI.send(Mango9CRMPreferences.self, session: current, path: "settings", transport: transport, scope: .crm)
		XCTAssertEqual(reread, saved); XCTAssertEqual(reread.snoozeMinutes, 30); XCTAssertTrue(reread.smsPush); XCTAssertFalse(reread.teamChatPush)
		_ = try await Mango9CalendarAPI.send(Mango9PersonalReminder.self, session: current, path: "events/91/reminder", method: "PATCH", body: ["action": "configure", "minutes_before": 120, "reminder_revision": "r1"], revision: "abc123", transport: transport)
		let rereadReminder = try await Mango9CalendarAPI.send(Mango9PersonalReminder.self, session: current, path: "events/91/reminder", transport: transport)
		XCTAssertEqual(rereadReminder.minutesBefore, 120); XCTAssertEqual(rereadReminder.revision, "r2"); XCTAssertEqual(writes, 2)
	}

	@MainActor func testEditorAndAppointmentRowRenderAtCompactAndLargeTextSizes() throws {
		let value = try event()
		let metadata = try Mango9CalendarAPI.decoder().decode(Mango9CalendarMetadata.self, from: Data(metadataJSON.utf8))
		for size in [ContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
			let view = Mango9AppointmentEditor(session: session(), metadata: metadata, event: value).environment(\.sizeCategory, size)
			let host = UIHostingController(rootView: view)
			let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
			let window = UIWindow(windowScene: scene)
			window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
			window.rootViewController = host; window.makeKeyAndVisible()
			host.view.layoutIfNeeded()
			RunLoop.main.run(until: Date().addingTimeInterval(0.3))
			let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
				XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
			}
			let attachment = XCTAttachment(image: image); attachment.name = "Appointment editor \(size)"; attachment.lifetime = .keepAlways; add(attachment)
			XCTAssertGreaterThan(host.view.bounds.height, 0)
			window.isHidden = true
		}
	}
}
