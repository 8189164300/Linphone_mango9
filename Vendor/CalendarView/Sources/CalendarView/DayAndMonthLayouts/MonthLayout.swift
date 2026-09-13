//
//  MonthLayout.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 24.04.2025.
//

import SwiftUI

@available(iOS 18.0, *)
public struct MonthLayout<MonthDay: View>: View {
    @Environment(\.calendarTheme) var theme
    @Environment(\.calendarCustomizationParams) var customizationParams

    var date: Date
    var viewModel: MonthCellModel
    @ViewBuilder var monthDayBuilder: (MonthDayBuilderParams) -> MonthDay
    var didSelectDay: (Date)->()

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)

    var startOfMonth: Date {
        date.startOfMonth
    }

    // number of empty spaces for days of week before 1st of the month
    var inset: Int {
        let startOfWeek = startOfMonth.startOfWeek(viewModel.firstDayOfWeek ?? customizationParams.firstDayOfWeek)
        var count = startOfMonth.getWeekday() - startOfWeek.getWeekday()
        if count < 0 {
            count += 7
        }
        return count
    }

    public var body: some View {
        GeometryReader { g in
            let days = Self.gridDates(containing: date, firstWeekday: viewModel.firstDayOfWeek ?? customizationParams.firstDayOfWeek)
            let rowHeight = g.size.height / CGFloat(numberOfCalendarRows())

            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(days, id: \.self) { date in
                        CalendarDateButton(date: date, onHold: viewModel.dateLongPressClosure, onTap: {
                            didSelectDay(date)
                        }) {
                            monthDayBuilder(
                                MonthDayBuilderParams(
                                    date: date,
                                    events: eventsFor(date),
                                    viewHeight: rowHeight
                                )
                            )
                            .opacity(Calendar.current.isDate(date, equalTo: startOfMonth, toGranularity: .month) ? 1 : 0.45)
                            .frame(height: rowHeight)
                        }
                        .accessibilityLabel(date.formatted(date: .complete, time: .omitted))
                }
            }
            .frame(height: g.size.height)
        }
    }

    // Full weeks keep adjacent-month dates in their correct weekday column.
    // Explicit calendar injection makes timezone/locale boundary tests deterministic.
    static func gridDates(containing date: Date, firstWeekday: Int?, calendar: Calendar = .current) -> [Date] {
        guard let month = calendar.dateInterval(of: .month, for: date),
              let count = calendar.range(of: .day, in: .month, for: date)?.count else { return [] }
        let first = firstWeekday.flatMap { (1...7).contains($0) ? $0 : nil } ?? calendar.firstWeekday
        let padding = (calendar.component(.weekday, from: month.start) - first + 7) % 7
        let cells = ((padding + count + 6) / 7) * 7
        return (0..<cells).compactMap { calendar.date(byAdding: .day, value: $0 - padding, to: month.start) }
    }

    func numberOfCalendarRows() -> Int {
        Int(ceil(Double(inset + startOfMonth.daysInMonth) / 7.0))
    }

    func eventsFor(_ date: Date) -> [any CalendarEntity] {
        var result: [any CalendarEntity] = []
        for event in viewModel.events {
            if event.startDate < date.adding(.day, value: 1) && event.endDate > date {
                result.append(event)
            }
        }
        return result.sorted { $0.startDate == $1.startDate ? $0.id < $1.id : $0.startDate < $1.startDate }
    }
}
