import Foundation

/// Read-only occurrences from the CRM contract's masters. IDs/revisions and
/// permissions continue to refer to the original record; no local event database.
enum Mango9CalendarOccurrences {
	static let maximumOccurrences = 10_000
	static func expand(_ masters: [Mango9Appointment], start: Date, end: Date,
		limit: Int = maximumOccurrences) throws -> [Mango9Appointment] {
		guard end > start, end.timeIntervalSince(start) <= 366 * 86400 else {
			throw Mango9CalendarFailure(status: 422, code: "invalid_range", message: "Choose a smaller calendar range.")
		}
		var result: [Mango9Appointment] = []
		var seen = Set<Int>()
		func append(_ master: Mango9Appointment, at date: Date, ending: Date) throws {
			guard date < end, ending > start else { return }
			guard result.count < limit else {
				throw Mango9CalendarFailure(status: 0, code: "too_many_occurrences",
					message: "There are too many appointments in this view. Choose Day or Week to see a smaller range.")
			}
			var value = master
			value.startAt = date; value.endAt = ending
			if master.isRecurring { value.occurrenceKey = "\(master.id)|\(date.timeIntervalSince1970)" }
			result.append(value)
		}
		for master in masters where seen.insert(master.id).inserted {
			try Task.checkCancellation()
			guard master.endAt > master.startAt else {
				throw Mango9CalendarFailure(status: 409, code: "invalid_stored_event",
					message: "An appointment has an invalid end time. Correct it in the web calendar, then retry.")
			}
			try append(master, at: master.startAt, ending: master.endAt)
			guard master.isRecurring else { continue }
			guard let zone = TimeZone(identifier: master.timezone) else {
				throw Mango9CalendarFailure(status: 409, code: "invalid_stored_event",
					message: "An appointment has an invalid time zone. Correct it in the web calendar, then retry.")
			}
			var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
			// Match the web's twelve-month expansion horizon and ISO weekdays (Mon=1).
			let horizon = addingMonths(12, to: master.startAt, calendar: calendar)
			let until = min(end, horizon.addingTimeInterval(1))
			guard until > master.startAt else { continue }
			let startTime = calendar.dateComponents([.hour, .minute, .second], from: master.startAt)
			let endTime = calendar.dateComponents([.hour, .minute, .second], from: master.endAt)
			let daySpan = calendar.dateComponents([.day], from: calendar.startOfDay(for: master.startAt),
				to: calendar.startOfDay(for: master.endAt)).day ?? 0
			func onDay(_ day: Date, time: DateComponents) -> Date? {
				calendar.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0, second: time.second ?? 0,
					of: day, matchingPolicy: .nextTimePreservingSmallerComponents, repeatedTimePolicy: .first)
			}
			func addOccurrence(_ date: Date) throws {
				guard date > master.startAt, date <= horizon,
					let endDay = calendar.date(byAdding: .day, value: daySpan, to: calendar.startOfDay(for: date)),
					let finish = onDay(endDay, time: endTime) else { return }
				// Keep overnight spans and wall-clock times through DST; a nonexistent
				// local end must not produce a negative-height calendar item.
				let safeEnd = finish > date ? finish : date.addingTimeInterval(master.endAt.timeIntervalSince(master.startAt))
				try append(master, at: date, ending: safeEnd)
			}
			switch master.recurrence.frequency {
			case "daily", "weekly", "custom":
				let weekdays = Set(master.recurrence.weekdays.filter { (1...7).contains($0) })
				if master.recurrence.frequency != "daily" && weekdays.isEmpty { continue }
				// Jump to the visible range, including overnight overlap, instead of
				// walking every day since the original appointment was created.
				let overlapStart = calendar.date(byAdding: .day, value: -daySpan - 1, to: calendar.startOfDay(for: start)) ?? start
				var day = max(calendar.startOfDay(for: master.startAt), overlapStart)
				while day < until {
					try Task.checkCancellation()
					let weekday = (calendar.component(.weekday, from: day) + 5) % 7 + 1
					if master.recurrence.frequency == "daily" || weekdays.contains(weekday), let date = onDay(day, time: startTime) {
						try addOccurrence(date)
					}
					guard let next = calendar.date(byAdding: .day, value: 1, to: day), next > day else { break }
					day = next
				}
			case "monthly":
				var date = addingMonths(1, to: master.startAt, calendar: calendar)
				while date < until {
					try Task.checkCancellation()
					try addOccurrence(date)
					let next = addingMonths(1, to: date, calendar: calendar)
					guard next > date else { break }; date = next
				}
			default:
				// Unsupported future rules are not silently fabricated.
				throw Mango9CalendarFailure(status: 0, code: "unsupported_recurrence",
					message: "This calendar contains a repeat rule that requires an app update. View it in the web calendar for now.")
			}
		}
		return result.sorted { $0.startAt == $1.startAt ? $0.displayID < $1.displayID : $0.startAt < $1.startAt }
	}

	/// PHP's web calendar rolls overflowing month days forward (Jan 31 + one
	/// month becomes Mar 3), rather than Foundation's end-of-month clamping.
	static func addingMonths(_ months: Int, to date: Date, calendar: Calendar) -> Date {
		let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
		var first = components; first.day = 1
		guard let base = calendar.date(from: first), let next = calendar.date(byAdding: .month, value: months, to: base),
			let result = calendar.date(byAdding: .day, value: (components.day ?? 1) - 1, to: next) else { return date }
		return result
	}
}
