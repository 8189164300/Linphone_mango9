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
        let startOfWeek = startOfMonth.startOfWeek(customizationParams.firstDayOfWeek)
        var count = startOfMonth.getWeekday() - startOfWeek.getWeekday()
        if count < 0 {
            count += 7
        }
        return count
    }

    public var body: some View {
        GeometryReader { g in
            let maxMonthDay = startOfMonth.daysInMonth
            let totalCount = inset + maxMonthDay
            let rowHeight = g.size.height / CGFloat(numberOfCalendarRows())

            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(0..<totalCount, id: \.self) { index in
                    if index < inset {
                        Color.clear
                    } else {
                        let date = startOfMonth.adding(.day, value: index - inset)
                        Button {
                            didSelectDay(date)
                        } label: {
                            monthDayBuilder(
                                MonthDayBuilderParams(
                                    date: date,
                                    events: eventsFor(date),
                                    viewHeight: rowHeight
                                )
                            )
                            .frame(height: rowHeight)
                        }
                    }
                }
            }
            .frame(height: g.size.height)
        }
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
