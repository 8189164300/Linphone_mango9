//
//  DayInMonthSwitcher.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 18.06.2025.
//

import SwiftUI

@Observable
@available(iOS 18.0, *)
final class MonthScrollCoordinator {
    fileprivate(set) var scrollToTodayToken = 0

    func scrollToToday() { scrollToTodayToken += 1 }
}

/// Select a day from a month, scroll between months
@available(iOS 18.0, *)
struct DayInMonthSwitcher<MonthDay: View>: View {
    @Environment(\.calendarTheme) var theme
    @Environment(\.calendarCustomizationParams) var customizationParams
    @Environment(CalendarViewModel.self) var viewModel
    @Environment(MonthScrollCoordinator.self) var monthCoordinator

    @Binding var fullscreenDate: Date
    @Binding var anchorDate: Date
    @Binding var calendarDisplayMode: CalendarDisplayMode
    @ViewBuilder var monthDayBuilder: (MonthDayBuilderParams) -> MonthDay

    @State private var items: [Int] = []
    @State private var models: [Int: MonthCellModel] = [:]
    @State private var tableUpdateID = UUID()

    @State private var containerHeight: CGFloat = 0

    // Reference type so the flag is visible immediately in UIKit callbacks
    // without waiting for a SwiftUI render cycle.
    private final class ScrollResetGuard { var active = false }
    @State private var resetGuard = ScrollResetGuard()

    var body: some View {
        GeometryReader { g in
            InfiniteTableView(data: $items, cellModels: $models) { direction, pageSize in
                switch direction {
                case .backward:
                    guard let first = items.first else { return }
                    for offset in 1...pageSize {
                        let item = first - offset
                        items.insert(item, at: 0)
                        models[item] = makeModel(item)
                    }
                case .forward:
                    guard let last = items.last else { return }
                    for offset in 1...pageSize {
                        let item = last + offset
                        items.append(item)
                        models[item] = makeModel(item)
                    }
                }
            } content: { item, model in
                VStack(alignment: .leading, spacing: 0) {
                    let monthDate = fullscreenDate.startOfMonth.adding(.month, value: item)
                    MonthLayout(date: monthDate, viewModel: model, monthDayBuilder: monthDayBuilder) { day in
                        fullscreenDate = day
                        calendarDisplayMode = .day
                    }
                    .padding(.horizontal, 16)
                }
                .frame(height: g.size.height)
                .background(theme.month.background)
            }
            .reloadTrigger(updateID: tableUpdateID)
            // Settle on a whole month, not a fragment of the previous month's
            // last week. Fetching belongs to the visible anchor, not every
            // neighboring UITableView cell that UIKit happens to preload.
            .scrollMode(scrollMode: .paged(max(1, g.size.height)))
            .isPagingEnabled(true)
            .onScrollChange { scrollView in
                let cellHeight = g.size.height
                guard cellHeight > 0 else { return }
                let centerY = scrollView.contentOffset.y + scrollView.bounds.height / 2
                let rowIndex = max(0, min(items.count - 1, Int(centerY / cellHeight)))
                guard let item = items[safe: rowIndex] else { return }
                let visibleMonth = fullscreenDate.startOfMonth.adding(.month, value: item).startOfMonth

                if resetGuard.active {
                    // Unblock once the table has re-centered on the target month.
                    // Use fullscreenDate (not anchorDate) as the target — the scroll can
                    // overwrite anchorDate, but fullscreenDate is only written by the button.
                    if visibleMonth == fullscreenDate.startOfMonth {
                        resetGuard.active = false
                        if anchorDate.startOfMonth != visibleMonth {
                            anchorDate = visibleMonth
                        }
                    }
                    return
                }

                if anchorDate.startOfMonth != visibleMonth {
                    anchorDate = visibleMonth
                }
            }
            .onChange(of: g.size.height, initial: true) { _, h in containerHeight = h }
        }
        .onChange(of: fullscreenDate, initial: true) {
            Task {
                items = Array(-3...3)
                models.removeAll()
                for item in items {
                    models[item] = makeModel(item)
                }
                if containerHeight > 0 {
                    tableUpdateID = UUID()
                }
            }
        }
        .onChange(of: monthCoordinator.scrollToTodayToken) {
            resetGuard.active = true
            Task {
                items = Array(-3...3)
                models.removeAll()
                for item in items {
                    models[item] = makeModel(item)
                }
                if containerHeight > 0 {
                    tableUpdateID = UUID()
                }
                anchorDate = fullscreenDate.startOfMonth
            }
        }
        .onChange(of: containerHeight) { _, h in
            guard h > 0 else { return }
            tableUpdateID = UUID()
        }
        // Mango9: data refreshes must update existing month snapshots, not require
        // replacing CalendarView's identity (which resets its scroll and zoom).
        .onChange(of: viewModel.events) {
            for (item, model) in models {
                let monthDate = fullscreenDate.startOfMonth.adding(.month, value: item)
                model.events = eventsFor(monthDate)
            }
        }
        .onChange(of: customizationParams.dateLongPressClosure != nil) {
            // UIHostingConfiguration cells do not inherit changing host closures.
            // Update their observable models in place, without resetting scrolling.
            for model in models.values { model.dateLongPressClosure = customizationParams.dateLongPressClosure }
        }
        .onChange(of: customizationParams.firstDayOfWeek) {
            for model in models.values { model.firstDayOfWeek = customizationParams.firstDayOfWeek }
        }
        .onDisappear {
            items.removeAll()
            models.removeAll()
        }
    }

    private func makeModel(_ id: Int) -> MonthCellModel {
        let model = MonthCellModel(id: id)
        model.dateLongPressClosure = customizationParams.dateLongPressClosure
        model.firstDayOfWeek = customizationParams.firstDayOfWeek
        model.events = eventsFor(fullscreenDate.startOfMonth.adding(.month, value: id))
        return model
    }

    func eventsFor(_ date: Date) -> [CalendarEvent] {
        let days = MonthLayout<MonthDay>.gridDates(containing: date, firstWeekday: customizationParams.firstDayOfWeek)
        guard let start = days.first, let last = days.last else { return [] }
        let end = last.adding(.day, value: 1)
        return viewModel.events.filter { $0.startDate < end && $0.endDate > start }
    }

    func remindersFor(_ date: Date) -> [CalendarReminder] {
        viewModel.reminders.filter { $0.startDate.startOfMonth == date }
    }
}

@Observable
@available(iOS 18.0, *)
class MonthCellModel: Identifiable {
    let id: Int

    var events: [CalendarEvent] = []
    var dateLongPressClosure: ((Date) -> Void)?
    var firstDayOfWeek: Int?

    init(id: Int) {
        self.id = id
    }
}
