import SwiftUI

/// One weekly schedule in the CRM account's time zone; no separate device schedule.
struct Mango9BusinessHours: Decodable, Equatable, Sendable {
	struct Day: Codable, Equatable, Identifiable, Sendable {
		let weekday: Int // Sunday = 0, matching the web calendar.
		var open: String?
		var close: String?
		var id: Int { weekday }
		var isOpen: Bool { open != nil && close != nil }
	}
	var configured: Bool
	var timezone: String
	var timezoneEditable: Bool? = nil
	var quickEnable: Bool
	var days: [Day]
	let defaults: [Day]
	let revision: String
	var zone: TimeZone { TimeZone(identifier: timezone) ?? TimeZone(secondsFromGMT: 0)! }

	static func seconds(_ value: String?) -> Int? {
		guard let value else { return nil }
		let parts = value.split(separator: ":", omittingEmptySubsequences: false)
		guard (2...3).contains(parts.count), parts.allSatisfy({ $0.count == 2 && $0.allSatisfy(\.isNumber) }),
			let hour = Int(parts[0]), let minute = Int(parts[1]), (0..<24).contains(hour), (0..<60).contains(minute),
			let second = parts.count == 3 ? Int(parts[2]) : 0, (0..<60).contains(second) else { return nil }
		return hour * 3600 + minute * 60 + second
	}
	var validationMessage: String? {
		guard TimeZone(identifier: timezone) != nil else { return "Choose a valid time zone." }
		guard days.count == 7, Set(days.map(\.weekday)) == Set(0...6) else { return "Reload business hours to retrieve all seven days." }
		for day in days {
			if day.open == nil && day.close == nil { continue }
			guard let start = Self.seconds(day.open), let end = Self.seconds(day.close), start <= end else {
				return "Choose an opening and closing time for each open day. Closing time cannot be earlier than opening time."
			}
		}
		return nil
	}
	var payload: [String: Any] {
		["action": "replace", "quick_enable": quickEnable,
		 "days": days.map { ["weekday": $0.weekday, "open": $0.open as Any? ?? NSNull(), "close": $0.close as Any? ?? NSNull()] }]
	}
	func changes(from original: Self) -> [String: Any] {
		let hoursChanged = days != original.days || quickEnable != original.quickEnable || configured != original.configured
		var body = hoursChanged ? payload : ["action": "timezone"]
		if timezone != original.timezone || !hoursChanged { body["timezone"] = timezone }
		return body
	}
	mutating func useDefaults() { days = defaults; quickEnable = true; configured = true }
	mutating func closeAll() { days = (0...6).map { Day(weekday: $0, open: nil, close: nil) }; quickEnable = false; configured = true }

	func openIntervals(on date: Date, calendar display: Calendar = .current) -> [DateInterval] {
		guard let visible = display.dateInterval(of: .day, for: date) else { return [] }
		guard configured else { return [visible] }
		var business = Calendar(identifier: .gregorian); business.timeZone = zone
		var day = business.startOfDay(for: visible.start)
		var result: [DateInterval] = []
		// A display day can straddle two different business weekdays/time zones.
		for _ in 0..<4 {
			guard day < visible.end else { break }
			let weekday = business.component(.weekday, from: day) - 1
			if let hours = days.first(where: { $0.weekday == weekday }),
				let start = Self.seconds(hours.open), let end = Self.seconds(hours.close), end > start,
				let opening = business.date(bySettingHour: start / 3600, minute: (start % 3600) / 60, second: start % 60, of: day,
					matchingPolicy: .nextTimePreservingSmallerComponents),
				let closing = business.date(bySettingHour: end / 3600, minute: (end % 3600) / 60, second: end % 60, of: day,
					matchingPolicy: .nextTimePreservingSmallerComponents),
				min(closing, visible.end) > max(opening, visible.start) {
				result.append(DateInterval(start: max(opening, visible.start), end: min(closing, visible.end)))
			}
			guard let next = business.date(byAdding: .day, value: 1, to: day) else { break }; day = next
		}
		return result
	}
	func allows(start: Date, end: Date) -> Bool {
		guard end > start else { return false }
		guard configured else { return true }
		var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
		return openIntervals(on: start, calendar: calendar).contains { start >= $0.start && end <= $0.end }
	}
	func firstStart(on day: Date, duration: TimeInterval = 1800, calendar: Calendar = .current) -> Date? {
		openIntervals(on: day, calendar: calendar).first { $0.duration >= duration }?.start
	}
	func closedMinutes(on day: Date, calendar: Calendar = .current) -> [Range<Double>] {
		guard configured, let visible = calendar.dateInterval(of: .day, for: day) else { return [] }
		func minute(_ date: Date) -> Double {
			if date >= visible.end { return 1440 }
			let parts = calendar.dateComponents([.hour, .minute, .second], from: date)
			return Double((parts.hour ?? 0) * 60 + (parts.minute ?? 0)) + Double(parts.second ?? 0) / 60
		}
		let open = openIntervals(on: day, calendar: calendar).map { minute($0.start)..<max(minute($0.start), minute($0.end)) }.sorted { $0.lowerBound < $1.lowerBound }
		var cursor = 0.0; var closed: [Range<Double>] = []
		for range in open {
			if range.lowerBound > cursor { closed.append(cursor..<range.lowerBound) }
			cursor = max(cursor, range.upperBound)
		}
		if cursor < 1440 { closed.append(cursor..<1440) }
		return closed
	}
}

struct Mango9ClosedHoursBackground: View {
	let day: Date
	let hourHeight: CGFloat
	let hours: Mango9BusinessHours?
	var body: some View {
		GeometryReader { geometry in
			let ranges = hours?.closedMinutes(on: day) ?? []
			ForEach(ranges.indices, id: \.self) { index in
				Rectangle().fill(Color.secondary.opacity(0.10))
					.frame(width: geometry.size.width, height: CGFloat(ranges[index].upperBound - ranges[index].lowerBound) * hourHeight / 60)
					.offset(y: CGFloat(ranges[index].lowerBound) * hourHeight / 60)
			}
		}.allowsHitTesting(false).accessibilityHidden(true)
	}
}

struct Mango9BusinessHoursEditor: View {
	@Environment(\.dismiss) private var dismiss
	let session: Mango9Session
	var transport: URLSession = .shared
	let onSave: (Mango9BusinessHours) -> Void
	@State private var draft: Mango9BusinessHours?
	@State private var loaded: Mango9BusinessHours?
	@State private var busy = false
	@State private var error: String?
	@State private var needsReload = false
	@State private var confirmClose = false
	@State private var confirmTimezone = false
	private var changed: Bool { draft != loaded }
	var body: some View {
		NavigationView {
			Form {
				if let error {
					Section { Text(error).foregroundColor(.red).fixedSize(horizontal: false, vertical: true)
						if needsReload || draft == nil { Button("Reload business hours") { Task { await load() } } }
					}
				}
				if let value = draft {
					Section {
						if value.timezoneEditable == true {
							NavigationLink {
								Mango9AppointmentTimezonePicker(selection: Binding(get: { draft?.timezone ?? value.timezone }, set: { draft?.timezone = $0 }))
							} label: {
								Label {
									VStack(alignment: .leading, spacing: 4) {
										Text("CRM time zone").foregroundColor(.primary)
										Text(value.timezone.replacingOccurrences(of: "_", with: " ")).font(.subheadline).foregroundColor(.mango9Primary)
									}
								} icon: { Image(systemName: "globe").foregroundColor(.mango9Primary) }
							}.accessibilityIdentifier("businessHours.timezone")
						} else { Label(value.timezone.replacingOccurrences(of: "_", with: " "), systemImage: "globe").font(.subheadline) }
					} footer: { Text("This shared time zone controls business hours on the app and web, and new appointments using the CRM default. Existing appointments keep their saved times. The calendar grid displays these hours in your phone’s time zone.") }
					Section {
						Button { draft?.useDefaults(); error = nil } label: { Label("Use default hours", systemImage: "clock.arrow.circlepath") }
						Button { confirmClose = true } label: { Label("Close all days", systemImage: "moon.zzz") }
					} footer: { Text("Default: Monday–Friday, 9 AM–6 PM. Saturday and Sunday closed. Review your hours, then tap Save.") }
					if !value.configured && !changed {
						Section { Text("No hours are configured yet. Scheduling is currently unrestricted. Choose your hours before saving.").font(.subheadline).foregroundColor(.secondary) }
					}
					ForEach([1, 2, 3, 4, 5, 6, 0], id: \.self) { weekday in
						daySection(weekday, value: value)
					}
				} else if busy { ProgressView("Loading business hours…") }
			}.disabled(busy).navigationTitle("Business hours").navigationBarTitleDisplayMode(.inline)
				.toolbar {
					ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) }
					ToolbarItem(placement: .confirmationAction) {
						Button("Save") {
							if draft?.timezone != loaded?.timezone { confirmTimezone = true }
							else { Task { await save() } }
						}.disabled(busy || !changed || needsReload || draft?.validationMessage != nil)
					}
				}
		}.navigationViewStyle(.stack).tint(.mango9Primary).interactiveDismissDisabled(busy)
			.task { await load() }
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
			.alert("Close every day?", isPresented: $confirmClose) {
				Button("Close all days", role: .destructive) { draft?.closeAll(); error = nil }
				Button("Cancel", role: .cancel) {}
			} message: { Text("After you save, new appointments cannot be scheduled until you reopen a day. Existing appointments stay unchanged.") }
			.alert("Update CRM time zone?", isPresented: $confirmTimezone) {
				Button("Save time zone") { Task { await save() } }
				Button("Cancel", role: .cancel) {}
			} message: { Text("Use \(draft?.timezone.replacingOccurrences(of: "_", with: " ") ?? "") for this CRM account on the app and web? Opening and closing times stay the same in the new zone. Existing appointments will not move.") }
	}
	private func daySection(_ weekday: Int, value: Mango9BusinessHours) -> some View {
		let day = value.days.first { $0.weekday == weekday }
		return Section {
			Toggle(isOn: Binding(get: { day?.isOpen == true }, set: { enabled in
				update(weekday) { $0.open = enabled ? "09:00:00" : nil; $0.close = enabled ? "18:00:00" : nil }
			})) { Text(Calendar.current.weekdaySymbols[weekday]) }
			if day?.isOpen == true {
				DatePicker("Opens", selection: time(weekday, opening: true), displayedComponents: .hourAndMinute)
				DatePicker("Closes", selection: time(weekday, opening: false), displayedComponents: .hourAndMinute)
			} else { Text("Closed").font(.subheadline).foregroundColor(.secondary) }
		}.environment(\.timeZone, TimeZone(secondsFromGMT: 0)!)
	}
	private func update(_ weekday: Int, change: (inout Mango9BusinessHours.Day) -> Void) {
		guard let index = draft?.days.firstIndex(where: { $0.weekday == weekday }) else { return }
		change(&draft!.days[index])
		draft?.configured = true
		error = draft?.validationMessage
	}
	private func time(_ weekday: Int, opening: Bool) -> Binding<Date> {
		Binding(get: {
			let day = draft?.days.first { $0.weekday == weekday }
			let seconds = Mango9BusinessHours.seconds(opening ? day?.open : day?.close) ?? 0
			return Date(timeIntervalSince1970: TimeInterval(seconds))
		}, set: { date in
			var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
			let parts = calendar.dateComponents([.hour, .minute], from: date)
			let text = String(format: "%02d:%02d:00", parts.hour ?? 0, parts.minute ?? 0)
			update(weekday) { if opening { $0.open = text } else { $0.close = text } }
		})
	}
	@MainActor private func load() async {
		busy = true; defer { busy = false }
		do {
			let value = try await Mango9CalendarAPI.send(Mango9BusinessHours.self, session: session, path: "business-hours", transport: transport)
			guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
			loaded = value; draft = value; needsReload = false; error = nil
		} catch { self.error = error.localizedDescription }
	}
	@MainActor private func save() async {
		guard let draft, let loaded, draft.validationMessage == nil, Mango9SessionStore.isActive(session) else { return }
		busy = true; defer { busy = false }
		do {
			let saved = try await Mango9CalendarAPI.send(Mango9BusinessHours.self, session: session, path: "business-hours",
				method: "PATCH", body: draft.changes(from: loaded), revision: loaded.revision, transport: transport)
			guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
			onSave(saved); dismiss()
		} catch {
			// Do not retry an uncertain write or overwrite a concurrent web edit.
			self.error = error.localizedDescription
			needsReload = true
		}
	}
}
