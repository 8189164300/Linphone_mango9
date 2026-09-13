import SwiftUI
import UIKit

/// Text follows the available height as the timeline zooms. Both renderers reserve
/// a one-line minimum and separate visually overlapping labels into columns.
/// This affects presentation only, never the appointment's actual start/end.
struct Mango9TimelineAppointment: View {
	let title: String
	let tint: Color
	let isRecurring: Bool
	var status: String?
	@ScaledMetric(relativeTo: .caption2) private var preferredFontSize: CGFloat = 11
	static let minimumFontSize: CGFloat = 9
	static var minimumHeight: CGFloat { ceil(UIFont.systemFont(ofSize: minimumFontSize, weight: .semibold).lineHeight) + 4 }

	struct TextLayout {
		let fontSize: CGFloat
		let lineHeight: CGFloat
		let titleLines: Int
		let showsStatus: Bool
		let showsRecurrence: Bool
		var contentHeight: CGFloat { CGFloat(titleLines) * lineHeight + (showsStatus ? lineHeight + 2 : 0) }
	}

	static func textLayout(size: CGSize, preferredFontSize: CGFloat, hasStatus: Bool, isRecurring: Bool) -> TextLayout {
		let empty = TextLayout(fontSize: 0, lineHeight: 0, titleLines: 0, showsStatus: false, showsRecurrence: false)
		guard size.width.isFinite, size.height.isFinite, preferredFontSize.isFinite,
			size.width > 12, size.height > 2, preferredFontSize > 0 else { return empty }
		// Reserve a pixel-safe inset, using font metrics rather than fixed height
		// thresholds that stop working with Dynamic Type or a compressed timeline.
		// Layout/zoom supplies fractional point heights. Round the budget down
		// before fitting a whole line, otherwise ceil(lineHeight) can exceed a
		// fractional budget by less than one point and incorrectly hide the title.
		let available = floor(max(0, size.height - 2))
		let preferredLine = ceil(UIFont.systemFont(ofSize: preferredFontSize, weight: .semibold).lineHeight)
		let fontSize = min(preferredFontSize, preferredFontSize * available / preferredLine)
		guard fontSize >= minimumFontSize else { return empty } // A transient, unmeasured layout is not a visible slot.
		let lineHeight = ceil(UIFont.systemFont(ofSize: fontSize, weight: .semibold).lineHeight)
		guard lineHeight <= available else { return empty }
		let titleLines = available >= lineHeight * 2 + 4 ? 2 : 1
		let showsStatus = hasStatus && size.width > 80 && available >= CGFloat(titleLines + 1) * lineHeight + 6
		return TextLayout(fontSize: fontSize, lineHeight: lineHeight, titleLines: titleLines,
			showsStatus: showsStatus, showsRecurrence: isRecurring && size.width >= fontSize * 4 + 12)
	}

	var body: some View {
		GeometryReader { geometry in
			let layout = Self.textLayout(size: geometry.size, preferredFontSize: preferredFontSize,
				hasStatus: status != nil, isRecurring: isRecurring)
			if layout.titleLines > 0 {
				VStack(alignment: .leading, spacing: 2) {
					HStack(alignment: .top, spacing: 3) {
						if layout.showsRecurrence {
							Image(systemName: "repeat").frame(height: layout.lineHeight).accessibilityHidden(true)
						}
						Text(title).lineLimit(layout.titleLines).truncationMode(.tail)
							.frame(maxWidth: .infinity, alignment: .leading)
					}.frame(height: CGFloat(layout.titleLines) * layout.lineHeight, alignment: .topLeading)
					if layout.showsStatus, let status {
						Text(status).lineLimit(1).frame(height: layout.lineHeight, alignment: .leading)
					}
				}.font(.system(size: layout.fontSize, weight: .semibold)).foregroundColor(.primary)
					.padding(.leading, 6).padding(.trailing, 3)
					.frame(width: geometry.size.width, height: geometry.size.height,
						alignment: layout.titleLines == 1 ? .leading : .topLeading)
			}
		}
		.background(tint.opacity(0.18))
		.overlay(alignment: .leading) { tint.frame(width: 3) }
		.clipShape(RoundedRectangle(cornerRadius: 5))
		.contentShape(Rectangle())
		.accessibilityElement(children: .ignore)
		.accessibilityLabel(title + (isRecurring ? ", recurring" : "") + (status.map { ", " + $0 } ?? ""))
	}
}

/// The iOS 15–17 calendar uses the same authenticated API and detail/editor flow.
/// It does not import the iOS 18-only Exyte module or store a second appointment database.
struct Mango9LegacyCalendar: View {
	@Environment(\.dynamicTypeSize) private var dynamicTypeSize
	let session: Mango9Session?
	let contactID: Int?
	@Binding var date: Date
	let revision: UUID
	let firstWeekday: Int
	let onSelect: (Mango9Appointment) -> Void
	let onError: (String?) -> Void
	var onVisibleMonth: (Date) -> Void = { _ in }
	var onCreate: ((Date) -> Void)?
	var onCreateAtTime: ((Date) -> Void)?
	var transport: URLSession = .shared
	var businessHours: Mango9BusinessHours?
	@State private var mode = Mango9LegacyCalendarMode.month
	@State private var events: [Mango9Appointment] = []
	@State private var busy = false
	@State private var loadGeneration = UUID()
	@State private var loadedRequestScope: String?

	init(session: Mango9Session?, contactID: Int?, date: Binding<Date>, revision: UUID,
		firstWeekday: Int, onSelect: @escaping (Mango9Appointment) -> Void,
		onError: @escaping (String?) -> Void, onVisibleMonth: @escaping (Date) -> Void = { _ in },
		onCreate: ((Date) -> Void)? = nil, onCreateAtTime: ((Date) -> Void)? = nil,
		transport: URLSession = .shared, businessHours: Mango9BusinessHours? = nil, initialMode: Mango9LegacyCalendarMode = .month) {
		self.session = session; self.contactID = contactID; self._date = date; self.revision = revision
		self.firstWeekday = firstWeekday; self.onSelect = onSelect; self.onError = onError
		self.onVisibleMonth = onVisibleMonth; self.transport = transport
		self.onCreate = onCreate
		self.onCreateAtTime = onCreateAtTime
		self.businessHours = businessHours
		self._mode = State(initialValue: initialMode)
	}
	private var calendar: Calendar {
		var result = Calendar.current
		result.firstWeekday = (1...7).contains(firstWeekday) ? firstWeekday : result.firstWeekday
		return result
	}
	private var range: DateInterval {
		if mode == .month {
			let days = Mango9LegacyCalendarMode.monthGridDays(containing: date, calendar: calendar)
			if let first = days.first, let last = days.last, let end = calendar.date(byAdding: .day, value: 1, to: last) {
				return DateInterval(start: first, end: end)
			}
		}
		return mode.range(containing: date, calendar: calendar)
	}
	private var requestID: String {
		"\(requestScope)|\(revision)"
	}
	private var requestScope: String { "\(session.map(Mango9CalendarAPI.accountKey) ?? "none")|\(contactID ?? 0)|\(range.start)|\(range.end)" }

	var body: some View {
		VStack(spacing: 8) {
			Mango9CalendarNavigation(date: date, mode: $mode, firstWeekday: firstWeekday,
				onToday: { date = mode.range(containing: Date(), calendar: calendar).start }, onMove: move)
				.padding(.horizontal, 16)
			if mode == .month {
				ScrollView { monthGrid }.refreshable { await load() }
			} else {
				Mango9LegacyTimeline(days: mode.days(containing: date, calendar: calendar), events: events,
					onDay: { date = $0; mode = .day }, onSelect: { event in Task { await select(event) } },
					onCreate: onCreate, onCreateAtTime: onCreateAtTime, businessHours: businessHours)
			}
		}.background(Color(.systemBackground))
		.overlay(alignment: .top) { if busy { ProgressView().accessibilityLabel("Loading appointments").allowsHitTesting(false) } }
		.task(id: requestID) { await load() }
		.onChange(of: date) { onVisibleMonth($0) }
		.onChange(of: mode) { value in
			if value == .week { date = value.range(containing: date, calendar: calendar).start }
		}
		.onAppear { onVisibleMonth(date) }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in
			loadGeneration = UUID(); events = []; busy = false; loadedRequestScope = nil
		}
		.accessibilityIdentifier("appointments.legacyCalendar")
	}

	private var monthGrid: some View {
		let days = Mango9LegacyCalendarMode.monthGridDays(containing: date, calendar: calendar)
		return VStack(spacing: 6) {
			HStack(spacing: 0) {
				ForEach(0..<7, id: \.self) { offset in
					Text(calendar.shortWeekdaySymbols[(calendar.firstWeekday - 1 + offset) % 7])
						.font(.caption).foregroundColor(.secondary).frame(maxWidth: .infinity)
				}
			}
			LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
				ForEach(days, id: \.self) { day in
					dayCell(day).opacity(calendar.isDate(day, equalTo: date, toGranularity: .month) ? 1 : 0.45)
				}
			}
		}.padding(.horizontal, 8)
	}

	private func dayCell(_ day: Date) -> some View {
		let values = eventsOn(day)
		return Mango9CalendarDateButton(date: day, onHold: onCreate, onTap: {
			date = day; mode = .day
		}) {
			VStack(spacing: 5) {
				Divider()
				Text(day, format: .dateTime.day()).font(.subheadline.weight(.semibold))
					.foregroundColor(calendar.isDateInToday(day) ? .white : .primary)
					.padding(4).background(calendar.isDateInToday(day) ? Color.mango9Primary : .clear).clipShape(Circle())
				ForEach(values.prefix(2), id: \.displayID) { event in
					HStack(spacing: 2) {
						if event.isRecurring { Image(systemName: "repeat").accessibilityLabel("Recurring appointment") }
						Text(event.title).lineLimit(1)
					}.font(.caption2).foregroundColor(.primary)
						.frame(maxWidth: .infinity, alignment: .leading).padding(2)
						.background(event.tint.opacity(0.2)).cornerRadius(3)
				}
				if values.count > 2 { Text("+\(values.count - 2)").font(.caption2).foregroundColor(.secondary) }
				Spacer(minLength: 0)
			}.frame(minHeight: 96, alignment: .top).contentShape(Rectangle())
				.background(businessHours?.configured == true && businessHours?.openIntervals(on: day).isEmpty == true ? Color.secondary.opacity(0.10) : Color.clear)
		}
			.accessibilityLabel("\(day.formatted(date: .complete, time: .omitted)), \(values.count) appointments")
	}

	private func eventsOn(_ day: Date) -> [Mango9Appointment] {
		Mango9LegacyCalendarMode.events(events, on: day, calendar: calendar)
	}
	private func move(_ direction: Int) {
		date = mode.moved(date, direction: direction, calendar: calendar)
	}
	@MainActor private func load() async {
		let generation = UUID(); loadGeneration = generation
		let scope = requestScope
		if loadedRequestScope != scope { events = [] }
		guard let session, Mango9SessionStore.isActive(session) else { events = []; loadedRequestScope = nil; busy = false; return }
		busy = true
		defer { if loadGeneration == generation { busy = false } }
		do {
			let values = try await Mango9CalendarAPI.displayEvents(session: session, start: range.start, end: range.end, contactID: contactID, transport: transport)
			guard !Task.isCancelled, loadGeneration == generation, Mango9SessionStore.isActive(session) else { return }
			events = values; loadedRequestScope = scope; onError(nil)
		} catch {
			guard !Task.isCancelled, loadGeneration == generation, Mango9SessionStore.isActive(session) else { return }
			events = []; onError(error.localizedDescription)
		}
	}
	@MainActor private func select(_ event: Mango9Appointment) async {
		guard let session else { return }
		do {
			let current = try await Mango9CalendarAPI.send(Mango9Appointment.self, session: session, path: "events/\(event.id)", transport: transport)
			guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
			onSelect(current.retainingOccurrence(from: event))
		} catch { if Mango9SessionStore.isActive(session) { onError(error.localizedDescription) } }
	}
}

/// Exclusive gestures prevent a successful hold from also navigating on release.
/// Movement cancels the hold so the surrounding calendar can still scroll.
struct Mango9CalendarDateButton<Content: View>: View {
	let date: Date
	let onHold: ((Date) -> Void)?
	let onTap: () -> Void
	@ViewBuilder var content: () -> Content

	var body: some View {
		if let onHold {
			content().contentShape(Rectangle())
				.gesture(LongPressGesture(minimumDuration: 0.5, maximumDistance: 10)
					.exclusively(before: TapGesture()).onEnded { value in
						switch value { case .first(true): onHold(date); case .second: onTap(); default: break }
					})
				.accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
				.accessibilityAction { onTap() }
				.accessibilityAction(named: Text("New appointment")) { onHold(date) }
				.accessibilityHint("Touch and hold to add an appointment")
		} else {
			Button(action: onTap, label: content).buttonStyle(.plain)
		}
	}
}

/// Quarter-hour hit targets sit behind events, so event taps and scrolling keep
/// their existing behavior. Calendar arithmetic preserves wall-clock slots at DST.
struct Mango9CalendarTimeSlots: View {
	let day: Date
	let hourHeight: CGFloat
	let onCreate: (Date) -> Void
	static func date(on day: Date, slot: Int, calendar: Calendar = .current) -> Date? {
		guard (0..<96).contains(slot) else { return nil }
		return calendar.date(bySettingHour: slot / 4, minute: slot % 4 * 15, second: 0, of: day)
	}
	var body: some View {
		VStack(spacing: 0) {
			ForEach(0..<96, id: \.self) { slot in
				Color.clear.frame(height: hourHeight / 4).contentShape(Rectangle())
					.onLongPressGesture(minimumDuration: 0.5, maximumDistance: 10) {
						if let time = Self.date(on: day, slot: slot) { onCreate(time) }
					}
			}
		}.accessibilityHidden(true)
	}
}

enum Mango9LegacyCalendarMode: String, CaseIterable {
	case day, week, month
	var title: String { switch self { case .day: return "Day"; case .week: return "Week"; case .month: return "Month" } }
	var dayCount: Int { self == .week ? 7 : 1 }
	func range(containing date: Date, calendar: Calendar) -> DateInterval {
		if self == .month { return calendar.dateInterval(of: .month, for: date)! }
		let day = calendar.startOfDay(for: date)
		let offset = self == .week ? (calendar.component(.weekday, from: day) - calendar.firstWeekday + 7) % 7 : 0
		let start = calendar.date(byAdding: .day, value: -offset, to: day)!
		return DateInterval(start: start, end: calendar.date(byAdding: .day, value: dayCount, to: start)!)
	}
	func days(containing date: Date, calendar: Calendar) -> [Date] {
		if self == .month { return Self.monthDays(containing: date, calendar: calendar) }
		let start = range(containing: date, calendar: calendar).start
		return (0..<dayCount).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
	}
	func moved(_ date: Date, direction: Int, calendar: Calendar) -> Date {
		calendar.date(byAdding: self == .month ? .month : .day, value: direction * (self == .month ? 1 : dayCount),
			to: range(containing: date, calendar: calendar).start) ?? date
	}
	func heading(_ date: Date, calendar: Calendar) -> String {
		let range = range(containing: date, calendar: calendar)
		let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US"); formatter.calendar = calendar; formatter.timeZone = calendar.timeZone
		if self == .month { formatter.dateFormat = "MMMM yyyy"; return formatter.string(from: range.start) }
		if self == .day { formatter.dateFormat = "EEE, MMM d, yyyy"; return formatter.string(from: range.start) }
		let last = calendar.date(byAdding: .day, value: -1, to: range.end)!
		formatter.dateFormat = "MMM d"; let start = formatter.string(from: range.start)
		formatter.dateFormat = "MMM d, yyyy"
		if calendar.component(.year, from: range.start) != calendar.component(.year, from: last) {
			return "\(formatter.string(from: range.start)) – \(formatter.string(from: last))"
		}
		return "\(start) – \(formatter.string(from: last))"
	}
	static func monthDays(containing date: Date, calendar: Calendar) -> [Date] {
		guard let range = calendar.dateInterval(of: .month, for: date), let days = calendar.range(of: .day, in: .month, for: date) else { return [] }
		return days.compactMap { calendar.date(byAdding: .day, value: $0 - 1, to: range.start) }
	}
	static func monthGridDays(containing date: Date, calendar: Calendar) -> [Date] {
		let days = monthDays(containing: date, calendar: calendar)
		guard let first = days.first else { return [] }
		let padding = (calendar.component(.weekday, from: first) - calendar.firstWeekday + 7) % 7
		return (0..<((padding + days.count + 6) / 7) * 7).compactMap {
			calendar.date(byAdding: .day, value: $0 - padding, to: first)
		}
	}
	static func events(_ events: [Mango9Appointment], on day: Date, calendar: Calendar) -> [Mango9Appointment] {
		let range = Self.day.range(containing: day, calendar: calendar)
		return events.filter { (!$0.isRecurring || $0.occurrenceKey != nil) && $0.startAt < range.end && $0.endAt > range.start }
			.sorted { $0.startAt == $1.startAt ? $0.id < $1.id : $0.startAt < $1.startAt }
	}
}

/// Shared date/navigation wording on both supported calendar implementations.
struct Mango9CalendarNavigation: View {
	@Environment(\.dynamicTypeSize) private var dynamicTypeSize
	let date: Date
	@Binding var mode: Mango9LegacyCalendarMode
	let firstWeekday: Int
	let onToday: () -> Void
	let onMove: (Int) -> Void
	private var calendar: Calendar { var value = Calendar.current; value.firstWeekday = firstWeekday; return value }
	var body: some View {
		VStack(alignment: .leading, spacing: 2) {
			Text(mode.heading(date, calendar: calendar)).font(.headline)
				.fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("calendar.visibleDates")
			if dynamicTypeSize.isAccessibilitySize {
				HStack { todayButton; Spacer(); modeMenu }.foregroundColor(.mango9Primary)
				HStack { arrows; Spacer() }.foregroundColor(.mango9Primary)
			} else {
				HStack(spacing: 12) { todayButton; modeMenu; Spacer(minLength: 0); arrows }.foregroundColor(.mango9Primary)
			}
			Text("Times in \(TimeZone.current.identifier.replacingOccurrences(of: "_", with: " "))")
				.font(.caption2).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
		}.padding(.vertical, 4)
	}
	private var todayButton: some View {
		Button(action: onToday) { Text("Today").font(.subheadline.weight(.semibold)) }.frame(minHeight: 44)
	}
	private var modeMenu: some View {
		Menu {
			Picker("Calendar view", selection: $mode) {
				ForEach(Mango9LegacyCalendarMode.allCases, id: \.self) { Text($0.title).tag($0) }
			}
		} label: {
			HStack(spacing: 5) { Text(mode.title); Image(systemName: "chevron.down").font(.caption2) }
				.font(.subheadline.weight(.semibold)).frame(minHeight: 44)
		}.accessibilityLabel("Calendar view, \(mode.title)").accessibilityIdentifier("calendar.viewMode")
	}
	@ViewBuilder private var arrows: some View {
		Button { onMove(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
			.accessibilityLabel("Previous \(mode.title.lowercased())")
		Button { onMove(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
			.accessibilityLabel("Next \(mode.title.lowercased())")
	}
}

/// iOS 15–17 timeline: the same dates, API records, and detail action, without
/// depending on iOS 18 scroll APIs. Frames are recomputed from the current snapshot.
struct Mango9LegacyTimeline: View {
	@Environment(\.dynamicTypeSize) private var dynamicTypeSize
	let days: [Date]
	let events: [Mango9Appointment]
	let onDay: (Date) -> Void
	let onSelect: (Mango9Appointment) -> Void
	var onCreate: ((Date) -> Void)? = nil
	var onCreateAtTime: ((Date) -> Void)? = nil
	var businessHours: Mango9BusinessHours? = nil
	private let hourHeight: CGFloat = 60
	@ScaledMetric(relativeTo: .caption2) private var gutter: CGFloat = 54
	var body: some View {
		GeometryReader { geometry in
			let width = dynamicTypeSize.isAccessibilitySize ? max(geometry.size.width, gutter + CGFloat(days.count) * 110) : geometry.size.width
			ScrollView(.horizontal, showsIndicators: width > geometry.size.width) {
				VStack(spacing: 0) {
					HStack(spacing: 0) {
						Color.clear.frame(width: gutter, height: 1)
						ForEach(days, id: \.self) { day in
							Mango9CalendarDateButton(date: day, onHold: onCreate, onTap: { onDay(day) }) {
								VStack(spacing: 4) {
									Text(day, format: days.count == 7 ? .dateTime.weekday(.narrow) : .dateTime.weekday(.abbreviated)).font(.caption2).foregroundColor(.secondary)
									Text(day, format: .dateTime.day()).font(.subheadline.weight(.semibold)).foregroundColor(Calendar.current.isDateInToday(day) ? .mango9Primary : .primary)
									Rectangle().fill(Calendar.current.isDateInToday(day) ? Color.mango9Primary : Color(.separator).opacity(0.5)).frame(height: 2)
								}.frame(maxWidth: .infinity, minHeight: 54)
							}.accessibilityLabel(day.formatted(date: .complete, time: .omitted))
						}
					}.padding(.trailing, 8)
					ScrollViewReader { proxy in
						ScrollView {
							HStack(alignment: .top, spacing: 0) {
								VStack(alignment: .trailing, spacing: 0) {
									ForEach(0..<24, id: \.self) { hour in
										Text(hour == 0 ? "12 AM" : hour < 12 ? "\(hour) AM" : hour == 12 ? "12 PM" : "\(hour - 12) PM")
											.font(.caption2).foregroundColor(.secondary).frame(height: hourHeight, alignment: .top).id(hour)
									}
								}.padding(.trailing, 7).frame(width: gutter)
								ForEach(days, id: \.self) { day in
									dayColumn(day, width: max(0, (width - gutter - 8) / CGFloat(max(1, days.count))))
								}
							}.padding(.trailing, 8)
						}.onChange(of: days) { _ in proxy.scrollTo(initialHour, anchor: .top) }
						.onAppear { proxy.scrollTo(initialHour, anchor: .top) }
					}
				}.frame(width: width)
			}
		}
	}
	private var initialHour: Int {
		if let first = events.filter({ event in (!event.isRecurring || event.occurrenceKey != nil) && days.contains { day in event.startAt >= day && event.startAt < Calendar.current.date(byAdding: .day, value: 1, to: day)! } }).map(\.startAt).min() {
			return max(0, Calendar.current.component(.hour, from: first) - 1)
		}
		return 8
	}
	private func dayColumn(_ day: Date, width: CGFloat) -> some View {
		ZStack(alignment: .topLeading) {
			Mango9ClosedHoursBackground(day: day, hourHeight: hourHeight, hours: businessHours)
			VStack(spacing: 0) { ForEach(0..<24, id: \.self) { _ in
				Color.clear.frame(height: hourHeight).overlay(alignment: .top) { Color(.separator).opacity(0.3).frame(height: 1) }
			} }.allowsHitTesting(false)
			if let onCreateAtTime {
				Mango9CalendarTimeSlots(day: day, hourHeight: hourHeight, onCreate: onCreateAtTime)
			}
			ForEach(Self.placements(events, on: day, width: width, hourHeight: hourHeight,
				minimumEventHeight: Mango9TimelineAppointment.minimumHeight)) { placement in
				Button { onSelect(placement.event) } label: {
					Mango9TimelineAppointment(title: placement.event.title, tint: placement.event.tint,
						isRecurring: placement.event.isRecurring, status: placement.event.status?.name)
				}.buttonStyle(.plain).frame(width: placement.frame.width, height: placement.frame.height)
					.offset(x: placement.frame.minX, y: placement.frame.minY)
					.accessibilityLabel("\(placement.event.title), \(placement.event.startAt.formatted(date: .abbreviated, time: .shortened))\(placement.event.isRecurring ? ", recurring" : "")")
			}
			TimelineView(.periodic(from: .now, by: 60)) { context in
				if Calendar.current.isDate(day, inSameDayAs: context.date) {
					Color.mango9Primary.frame(height: 1).offset(y: CGFloat(Self.minute(context.date, calendar: .current)) * hourHeight / 60)
				}
			}.allowsHitTesting(false)
		}.frame(width: width, height: 24 * hourHeight + Mango9TimelineAppointment.minimumHeight, alignment: .topLeading).clipped()
			.overlay(alignment: .leading) { Color(.separator).opacity(0.3).frame(width: 1) }
	}
	struct Placement: Identifiable { let event: Mango9Appointment; let frame: CGRect; var id: String { event.displayID } }
	private static func minute(_ date: Date, calendar: Calendar) -> Int { calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date) }
	static func placements(_ events: [Mango9Appointment], on day: Date, width: CGFloat, hourHeight: CGFloat,
		minimumEventHeight: CGFloat = 0, calendar: Calendar = .current) -> [Placement] {
		guard width.isFinite, hourHeight.isFinite, hourHeight > 0 else { return [] }
		let minimumHeight = minimumEventHeight.isFinite ? max(0, minimumEventHeight) : 0
		let start = calendar.startOfDay(for: day); let end = calendar.date(byAdding: .day, value: 1, to: start)!
		let values = Mango9LegacyCalendarMode.events(events, on: day, calendar: calendar)
		let ranges = values.map { event -> (Int, Int) in
			let first = minute(max(start, event.startAt), calendar: calendar)
			let last = event.endAt >= end ? 1440 : minute(event.endAt, calendar: calendar)
			let length = last > first ? last - first : max(1, Int(event.endAt.timeIntervalSince(max(start, event.startAt)) / 60))
			return (first, min(1440, first + length))
		}
		let visualEnds = ranges.map { range in
			minimumHeight > 0 ? max(CGFloat(range.1), CGFloat(range.0) + (minimumHeight + 2) * 60 / hourHeight) : CGFloat(range.1)
		}
		var result: [Placement] = []; var index = 0
		while index < values.count {
			var groupEnd = visualEnds[index]; var last = index; var laneEnds: [CGFloat] = []; var lanes: [Int] = []
			while last < values.count && (last == index || CGFloat(ranges[last].0) < groupEnd - 0.000001) {
				let lane = laneEnds.firstIndex { $0 <= CGFloat(ranges[last].0) + 0.000001 } ?? laneEnds.count
				if lane == laneEnds.count { laneEnds.append(visualEnds[last]) } else { laneEnds[lane] = visualEnds[last] }
				lanes.append(lane); groupEnd = max(groupEnd, visualEnds[last]); last += 1
			}
			let laneWidth = max(0, (width - 4) / CGFloat(max(1, laneEnds.count)))
			for i in index..<last {
				result.append(Placement(event: values[i], frame: CGRect(x: 2 + CGFloat(lanes[i - index]) * laneWidth,
					y: CGFloat(ranges[i].0) * hourHeight / 60, width: max(0, laneWidth - 2), height: max(max(2, minimumHeight), CGFloat(ranges[i].1 - ranges[i].0) * hourHeight / 60 - 2))))
			}
			index = last
		}
		return result
	}
}
