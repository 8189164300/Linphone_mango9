import SwiftUI
import CalendarView

/// Exyte supplies layout only. No AppleCalendarsProvider / LocalCalendarsProvider,
/// EventKit permissions, or library-local create/edit flows are used.
@available(iOS 18.0, *)
struct Mango9CalendarProvider: CalendarsProvider {
	let session: Mango9Session?
	let contactID: Int?
	let transport: URLSession
	let onError: @MainActor @Sendable (String?) -> Void
	let onEvents: @MainActor @Sendable ([Mango9Appointment]) -> Void

	func getCalendars() async throws -> [ProviderCalendar] {
		[ProviderCalendar(id: "mango9-appointments", title: "Appointments", color: .mango9Primary)]
	}
	func getEvents(from startDate: Date, to endDate: Date, selectedCalendarIDs: [String]) async throws -> [CalendarEvent] {
		guard let session else { return [] }
		do {
			let appointments = try await Mango9CalendarAPI.displayEvents(session: session, start: startDate, end: endDate, contactID: contactID, transport: transport)
			try Task.checkCancellation()
			await MainActor.run {
				guard Mango9SessionStore.isActive(session) else { return }
				onError(nil)
				onEvents(appointments)
			}
			return appointments.map { event in
				CalendarEvent(id: event.displayID, calendarID: "mango9-appointments", title: event.title,
					notes: event.description, calendarColor: event.tint,
					calendarName: "Appointments", startDate: event.startAt, endDate: event.endAt, isRecurringOccurrence: event.isRecurring)
			}
		} catch {
			if !Task.isCancelled {
				await MainActor.run {
					// A library fetch can finish after its view/account has gone away.
					// Keep the API's rejection, but do not replace the current account's
					// calendar or error banner with callbacks from that old request.
					guard Mango9SessionStore.isActive(session) else { return }
					onEvents([])
					onError(error.localizedDescription)
				}
			}
			throw error
		}
	}
	func getReminders(from startDate: Date, to endDate: Date, selectedCalendarIDs: [String]) async throws -> [CalendarReminder] { [] }
}

@available(iOS 18.0, *)
struct Mango9ExyteCalendar: View {
	@Environment(\.dynamicTypeSize) private var dynamicTypeSize
	@ObservedObject private var preferencesStore = Mango9CRMPreferencesStore.shared
	let session: Mango9Session?
	let contactID: Int?
	@Binding var date: Date
	let revision: UUID
	let onSelect: (Mango9Appointment) -> Void
	let onError: (String?) -> Void
	var onVisibleMonth: (Date) -> Void = { _ in }
	var onCreate: ((Date) -> Void)?
	var onCreateAtTime: ((Date) -> Void)?
	var transport: URLSession = .shared
	var businessHours: Mango9BusinessHours?
	@State private var mode: CalendarDisplayMode = .month
	@State private var events: [Mango9Appointment] = []
	@StateObject private var monthHours: Mango9MonthHoursState

	init(session: Mango9Session?, contactID: Int?, date: Binding<Date>, revision: UUID,
		onSelect: @escaping (Mango9Appointment) -> Void, onError: @escaping (String?) -> Void,
		onVisibleMonth: @escaping (Date) -> Void = { _ in }, onCreate: ((Date) -> Void)? = nil,
		onCreateAtTime: ((Date) -> Void)? = nil, transport: URLSession = .shared, businessHours: Mango9BusinessHours? = nil,
		initialMode: CalendarDisplayMode = .month) {
		self.session = session; self.contactID = contactID; self._date = date; self.revision = revision
		self.onSelect = onSelect; self.onError = onError; self.onVisibleMonth = onVisibleMonth; self.transport = transport
		self.onCreate = onCreate
		self.onCreateAtTime = onCreateAtTime
		self.businessHours = businessHours
		self._monthHours = StateObject(wrappedValue: Mango9MonthHoursState(value: businessHours))
		self._mode = State(initialValue: initialMode)
	}

	var body: some View {
		CalendarView(providers: [Mango9CalendarProvider(session: session, contactID: contactID, transport: transport,
			onError: { onError($0) }, onEvents: { events = $0 })], dayEventBuilder: { entity in
			let isRecurring = (entity as? CalendarEvent)?.isRecurringOccurrence == true
			return Mango9TimelineAppointment(title: entity.title, tint: entity.calendarColor,
				isRecurring: isRecurring, status: events.first(where: { $0.displayID == entity.id })?.status?.name)
				.accessibilityLabel("\(entity.title), \(entity.startDate.formatted(date: .abbreviated, time: .shortened))\(isRecurring ? ", recurring" : "")")
		}, monthDayBuilder: { params in
			Mango9UpdatingMonthDay(date: params.date, events: params.events, hours: monthHours)
		}, headerBuilder: { params in
			VStack(spacing: 8) {
				Mango9CalendarNavigation(date: params.displayMode.wrappedValue == .month ? params.anchorDate.wrappedValue : params.fullscreenDate.wrappedValue,
					mode: Binding(get: { Mango9LegacyCalendarMode(exyte: params.displayMode.wrappedValue) }, set: { value in
						params.displayMode.wrappedValue = value.exyte
					}), firstWeekday: firstWeekday, onToday: { params.tapGoToTodayClosure() }, onMove: { direction in
						let value = Mango9LegacyCalendarMode(exyte: params.displayMode.wrappedValue)
						let anchor = value == .month ? params.anchorDate.wrappedValue : params.fullscreenDate.wrappedValue
						params.fullscreenDate.wrappedValue = value.moved(anchor, direction: direction, calendar: navigationCalendar)
					})
				if params.displayMode.wrappedValue == .month {
					HStack(spacing: 0) {
						ForEach(0..<7, id: \.self) { offset in
							Text(Calendar.current.shortWeekdaySymbols[(firstWeekday - 1 + offset) % 7])
								.font(.caption).foregroundColor(.secondary).frame(maxWidth: .infinity)
						}
					}
				}
			}.padding(.horizontal, 16).padding(.vertical, 8)
				.onChange(of: params.anchorDate.wrappedValue) { _, value in onVisibleMonth(value) }
		})
		.fullscreenDate($date)
		.displayMode($mode)
		.idForUpdate(revision)
		.firstDayOfWeek(firstWeekday)
		.hourLabelFormat("h a")
		.hoursToFit(dynamicTypeSize.isAccessibilitySize ? 6 : 10)
		.minimumTimedEventHeight(Mango9TimelineAppointment.minimumHeight)
		.timedDayBackground { day, hourHeight in Mango9ClosedHoursBackground(day: day, hourHeight: hourHeight, hours: businessHours) }
		.useDynamicType(true)
		.headerBackground { Color(.systemBackground) }
		.eventDetailsClosure { entity in
			guard let session, let displayed = events.first(where: { $0.displayID == entity.id }) else { return }
			Task {
				do {
					let event = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session, path: "events/\(displayed.id)", transport: transport)
					guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
					onSelect(event.retainingOccurrence(from: displayed))
				} catch { if Mango9SessionStore.isActive(session) { onError(error.localizedDescription) } }
			}
		}
		.dateLongPressClosure(onCreate)
		.timeSlotLongPressClosure(onCreateAtTime)
		.calendarTheme(Self.theme)
		.onChange(of: businessHours) { _, value in monthHours.value = value }
		// idForUpdate refreshes data without discarding scroll/zoom/navigation state.
		// The vendored month switcher updates its existing cell snapshots in place.
	}
	private var firstWeekday: Int { preferencesStore.value(for: session)?.calendarFirstWeekday ?? Calendar.current.firstWeekday }
	private var navigationCalendar: Calendar { var calendar = Calendar.current; calendar.firstWeekday = firstWeekday; return calendar }
	static var theme: CalendarTheme {
		CalendarTheme(main: .init(text: .primary, secondaryText: .secondary, tertiaryText: .secondary,
			accent: .mango9Primary, accentLight: Color.mango9Primary.opacity(0.12), background: Color(.systemBackground),
			separator: Color(.separator), cardBackground: Color(.secondarySystemGroupedBackground), fieldBackground: Color(.secondarySystemBackground),
			switcherSelectedBackground: Color(.systemBackground), draggingCapsule: .secondary, reminderBorder: .secondary, deleteText: .red),
			day: .init(hourText: .secondary, separators: Color(.separator).opacity(0.35), todayLine: .mango9Primary),
			year: .init(monthText: .primary, todayText: .mango9Primary))
	}
}

/// UIKit-backed month cells retain their builder closure. Observe hours by reference
/// so saving hours redraws existing cells without a calendar reload/scroll reset.
@MainActor final class Mango9MonthHoursState: ObservableObject {
	@Published var value: Mango9BusinessHours?
	init(value: Mango9BusinessHours?) { self.value = value }
}

@available(iOS 18.0, *)
private struct Mango9UpdatingMonthDay: View {
	let date: Date
	let events: [any CalendarEntity]
	@ObservedObject var hours: Mango9MonthHoursState
	var body: some View { Mango9MonthDay(date: date, events: events, businessHours: hours.value) }
}

@available(iOS 18.0, *)
private extension Mango9LegacyCalendarMode {
	init(exyte: CalendarDisplayMode) {
		switch exyte { case .week: self = .week; case .month: self = .month; default: self = .day }
	}
	var exyte: CalendarDisplayMode {
		switch self { case .day: return .day; case .week: return .week; case .month: return .month }
	}
}

/// Keep Exyte's calendar navigation and event style, but bound the month-cell
/// preview. Its default cell creates 0..<(-1) when no event rows fit, which
/// caused the September 11 device crash during a compact layout transition.
@available(iOS 18.0, *)
struct Mango9MonthDay: View {
	let date: Date
	let events: [any CalendarEntity]
	var businessHours: Mango9BusinessHours? = nil
	private var recurringEventIDs: Set<String> { Set(events.compactMap { $0 as? CalendarEvent }.filter(\.isRecurringOccurrence).map(\.id)) }
	@ScaledMetric(relativeTo: .caption2) private var rowHeight = 17.0
	@ScaledMetric(relativeTo: .body) private var dayFontSize = 17.0

	static func visibleEventCount(total: Int, availableHeight: CGFloat, rowHeight: CGFloat) -> Int {
		guard total > 0, availableHeight.isFinite, rowHeight.isFinite,
			availableHeight > 0, rowHeight > 0 else { return 0 }
		let capacity = floor(availableHeight / (rowHeight + 6))
		// Bound before conversion to Int, including extremely large layout proposals.
		if capacity >= CGFloat(total) { return total }
		return max(0, Int(capacity) - 1) // Reserve the last row for the remaining count.
	}

	var body: some View {
		let recurringIDs = recurringEventIDs
		return VStack(spacing: 4) {
			Color(.separator).frame(height: 1).padding(.bottom, 4)
			Text(date, format: .dateTime.day())
				.font(.system(size: dayFontSize, weight: .semibold))
				.foregroundStyle(Calendar.current.isDateInToday(date) ? Color.white : Color.primary)
				.padding(4)
				.background(Calendar.current.isDateInToday(date) ? Color.mango9Primary : Color.clear, in: Circle())
				.padding(.vertical, -4)
			GeometryReader { geometry in
				let count = Self.visibleEventCount(total: events.count, availableHeight: geometry.size.height, rowHeight: rowHeight)
				VStack(spacing: 6) {
					ForEach(0..<count, id: \.self) { index in
						HStack(spacing: 2) {
							if recurringIDs.contains(events[index].id) { Image(systemName: "repeat").font(.caption2) }
							Text(events[index].title).font(.caption2.weight(.semibold)).lineLimit(1)
						}
							.foregroundStyle(Color.primary).padding(.horizontal, 2)
							.frame(maxWidth: .infinity, alignment: .leading).frame(height: rowHeight)
							.background(events[index].calendarColor.opacity(0.3), in: RoundedRectangle(cornerRadius: 4)).clipped()
					}
					if events.count > count {
						HStack(spacing: 2) {
							if count == 0, let first = events.first {
								Circle().fill(first.calendarColor).frame(width: 4, height: 4)
								if !recurringIDs.isEmpty { Image(systemName: "repeat") }
							}
							Text("+\(events.count - count)")
						}.font(.caption2).foregroundStyle(.secondary)
					}
				}.padding(.horizontal, 1).frame(maxWidth: .infinity, alignment: .top)
					.accessibilityElement(children: .ignore)
					.accessibilityLabel(events.map { $0.title + (recurringIDs.contains($0.id) ? ", recurring" : "") }.joined(separator: ", "))
			}
		}.clipped()
			.background(businessHours?.configured == true && businessHours?.openIntervals(on: date).isEmpty == true ? Color.secondary.opacity(0.10) : Color.clear)
	}
}
