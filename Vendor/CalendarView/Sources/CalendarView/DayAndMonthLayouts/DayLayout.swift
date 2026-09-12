//
//  DayLayout.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 14.04.2025.
//

import SwiftUI

@available(iOS 18.0, *)
public struct DayLayout<Content: View>: View {
    struct Grouped {
        var allDayEvents: [CalendarEvent] = []
        var nonAllDayEvents: [CalendarEvent] = []
        var allDayEventsByDay: [Date: [CalendarEvent]] = [:]
        var nonAllDayEventsByDay: [Date: [CalendarEvent]] = [:]
        var remindersByDay: [Date: [CalendarReminder]] = [:]

        static func compute(
            events: [CalendarEvent],
            reminders: [CalendarReminder],
            anchorDate: Date,
            daysCount: Int
        ) -> Grouped {
            let split = Dictionary(grouping: events, by: \.isAllDay)
            let allDay = split[true] ?? []
            let nonAllDay = split[false] ?? []

            var allDayByDay: [Date: [CalendarEvent]] = [:]
            var timedByDay: [Date: [CalendarEvent]] = [:]
            for i in 0..<daysCount {
                let dayStart = anchorDate.adding(.day, value: i).startOfDay
                let dayEnd = dayStart.adding(.day, value: 1)
                allDayByDay[dayStart] = allDay
                    .filter { $0.startDate < dayEnd && $0.endDate > dayStart }
                    .sorted(by: \.id)
                timedByDay[dayStart] = nonAllDay.compactMap { event in
                    guard event.startDate < dayEnd, event.endDate > dayStart else { return nil }
                    var segment = event
                    segment.startDate = max(dayStart, event.startDate)
                    segment.endDate = min(dayEnd, event.endDate)
                    return segment
                }
            }
            return Grouped(
                allDayEvents: allDay,
                nonAllDayEvents: nonAllDay,
                allDayEventsByDay: allDayByDay,
                nonAllDayEventsByDay: timedByDay,
                remindersByDay: reminders.groupedByDay()
            )
        }
    }

    struct GroupingKey: Equatable {
        var events: [CalendarEvent]
        var reminders: [CalendarReminder]
        var anchorDate: Date
        var daysCount: Int
    }

    @Environment(\.calendarTheme) var theme
    @Environment(\.calendarCustomizationParams) var customizationParams
    @Environment(\.hoursFittingCurrentZoom) var hoursFittingCurrentZoom
    @Environment(\.showEventDetailsClosure) var showEventDetailsClosure

    @Binding var hoursLabelsInset: CGFloat
    @Binding var isCalendarScrolling: Bool

    var anchorDate: Date
    var daysCount: Int
    var events: [CalendarEvent]
    var reminders: [CalendarReminder]
    var isScrollDisabled: Bool
    var pinchAnchor: CGFloat = 0.5
    var onSelectDay: (Date) -> Void = { _ in }

    @ViewBuilder var dayEventBuilder: (any CalendarEntity) -> Content

    // MARK: - inner state

    @State private var grouped = Grouped()
    @State private var hourLabelsSize: CGSize = .zero
    @State private var hourTextHeight: CGFloat = 0

    var hoursToFit: CGFloat {
        hoursFittingCurrentZoom ?? customizationParams.hoursToFit
    }

    let allDaysViewMaxHeight = 90.0
    let horizontalPadding = 8.0
    private var gutterWidth: CGFloat { max(52, hourLabelsSize.width) }

    public var body: some View {
        VStack(spacing: 4) {
            // Same columns/gutter as the timeline; not an unrelated week picker.
            HStack(spacing: 0) {
                Color.clear.frame(width: gutterWidth, height: 1)
                ForEach(0..<daysCount, id: \.self) { index in
                    let day = anchorDate.adding(.day, value: index).startOfDay
                    Button { onSelectDay(day) } label: {
                        DayColumnHeading(date: day, daysCount: daysCount)
                    }.buttonStyle(.plain).frame(maxWidth: .infinity)
                }
            }.padding(.trailing, horizontalPadding)
            // all day events
            if !grouped.allDayEvents.isEmpty {
                allDayEventsView
                    .padding(.top, 10)
            }

            // events by hour
            GeometryReader { global in
                ScrollView {
                    HStack(spacing: 0) {
                        let oneHourHeight = global.size.height / CGFloat(hoursToFit)

                        hourLabels(oneHourHeight)
                            .padding(.horizontal, horizontalPadding)
                            .sizeGetter($hourLabelsSize)
                            .frame(width: gutterWidth)

                        ZStack(alignment: .top) {
                            separatorsView(oneHourHeight)
                            dayEventsAndRemindersView(availableWidth: max(0, global.size.width - gutterWidth), oneHourHeight: oneHourHeight)
                        }
                        .padding(.top, hourTextHeight + 8)
                    }
                }
                .contentMargins(.trailing, horizontalPadding, for: .scrollIndicators)
                .scrollDisabled(isScrollDisabled)
                .modifier(DayScrollModifier(
                    isCalendarScrolling: $isCalendarScrolling,
                    isScrollDisabled: isScrollDisabled,
                    pinchAnchor: pinchAnchor,
                    hourTextHeight: hourTextHeight,
                    containerHeight: global.size.height,
                    anchorDate: anchorDate,
                    firstEventHour: events.filter { !$0.isAllDay }.map { $0.startDate.getHour() }.min()
                ))
            }
        }
        .onChange(of: hourLabelsSize) {
            hoursLabelsInset = gutterWidth
        }
        .task(id: GroupingKey(events: events, reminders: reminders, anchorDate: anchorDate, daysCount: daysCount)) {
            grouped = Grouped.compute(events: events, reminders: reminders, anchorDate: anchorDate, daysCount: daysCount)
        }
    }

    func hourLabels(_ oneHourHeight: CGFloat) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            ForEach(0..<25, id: \.self) { i in
                Text(Date().setHour(to: i).setMinute(to: 0).formatted(customizationParams.hourLabelFormat))
                    .libraryFont(13, theme.day.hourText)
                    .fixedSize(horizontal: true, vertical: false)
                    .maxHeightGetter($hourTextHeight)
                    .frame(height: oneHourHeight, alignment: .top)
                    .id(i)
            }
        }
    }

    func separatorsView(_ oneHourHeight: CGFloat) -> some View {
        VStack(spacing: 0) {
            ForEach(0..<25, id: \.self) { i in
                VStack(spacing: 0) {
                    theme.day.separators.frame(height: 1)
                    Spacer()
                }
                .frame(height: oneHourHeight, alignment: .top)
            }
        }
    }

    func nowLine(_ oneHourHeight: CGFloat) -> some View {
        theme.day.todayLine.frame(height: 2)
            .overlay(alignment: .leading) {
                theme.day.todayLine.frame(width: 2, height: 12)
                    .padding(.leading, 1)
            }
            .offset(y: oneHourHeight * startCoeff(Date()))
    }

    @ViewBuilder
    var allDayEventsView: some View {
        let spaceBetweenDays = 2 * customizationParams.horSpacing + 1
        HStack(alignment: .top, spacing: customizationParams.horSpacing) {
            Color.clear.frame(width: max(0, hoursLabelsInset - spaceBetweenDays), height: 1)

            ForEach(0..<daysCount, id: \.self) { i in
                let date = anchorDate.adding(.day, value: i).startOfDay
                ScrollView {
                    VStack {
                        let events = grouped.allDayEventsByDay[date] ?? []
                        let eventsCount = events.count
                        if events.isEmpty {
                            Color.clear.frame(height: 1)
                        } else {
                            ForEach(Array(stride(from: 0, to: eventsCount, by: 2)), id: \.self) { index in
                                HStack(spacing: spaceBetweenDays) {
                                    allDayEventsBuilderView(event: events[index])
                                    if index + 1 < eventsCount {
                                        allDayEventsBuilderView(event: events[index + 1])
                                    }
                                }
                            }
                        }
                    }
                }
                .frame(maxHeight: allDaysViewMaxHeight)
                .scrollBounceBehavior(.basedOnSize)
                .scrollDisabled(isScrollDisabled)
                .onScrollPhaseChange { _, newVal in
                    isCalendarScrolling = newVal != .idle
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.trailing, customizationParams.horSpacing)
        // can't be a part of layout to be able to align 3-day view correctly
        .overlay(alignment: .topLeading) {
            Text("all-day")
                .libraryFont(13, theme.day.hourText)
                .padding(8, 4)
        }
    }

    private func allDayEventsBuilderView(event: CalendarEvent) -> some View {
        dayEventBuilder(event)
            .frame(height: 30)
            .fixedSize(horizontal: false, vertical: true)
            .onTapGesture {
                showEventDetailsClosure(event)
            }
    }

    func dayEventsAndRemindersView(availableWidth: CGFloat, oneHourHeight: CGFloat) -> some View {
        // each day cell: 1pt separator + cell. trailing padding is one horSpacing.
        // cells share the remaining width equally.
        let cellWidth = max(0, (availableWidth - horizontalPadding) / CGFloat(daysCount))
        return HStack(spacing: 0) {
            ForEach(0..<daysCount, id: \.self) { i in
                let date = anchorDate.adding(.day, value: i).startOfDay
                DayEventsLayout(
                    events: grouped.nonAllDayEventsByDay[date] ?? [],
                    reminders: grouped.remindersByDay[date] ?? [],
                    oneHourHeight: oneHourHeight,
                    horSpacing: customizationParams.horSpacing,
                    verSpacing: customizationParams.verSpacing,
                    trailingPadding: customizationParams.horSpacing,
                    dayEventBuilder: dayEventBuilder
                )
                .padding(.leading, 2)
                .frame(width: cellWidth)
                .overlay(alignment: .leading) { theme.day.separators.frame(width: 1) }
                .overlay(alignment: .topLeading) {
                    TimelineView(.periodic(from: .now, by: 60)) { context in
                        if Calendar.current.isDate(date, inSameDayAs: context.date) {
                            theme.day.todayLine.frame(height: 1)
                                .offset(y: oneHourHeight * startCoeff(context.date))
                        }
                    }.allowsHitTesting(false)
                }
            }
        }
        .padding(.trailing, horizontalPadding)
    }

    private func startCoeff(_ date: Date) -> CGFloat {
        CGFloat((date.getHour() * 60 + date.getMinute())) / CGFloat(60)
    }
}

// MARK: - Scroll modifier

@available(iOS 18.0, *)
private struct DayScrollModifier: ViewModifier {
    struct ScrollInfo: Equatable {
        let yOffset: CGFloat
        let maxOffset: CGFloat
    }

    @Environment(\.hoursFittingCurrentZoom) var hoursFittingCurrentZoom
    @Environment(\.calendarCustomizationParams) var customizationParams

    @Binding var isCalendarScrolling: Bool

    var isScrollDisabled: Bool
    var pinchAnchor: CGFloat
    var hourTextHeight: CGFloat
    var containerHeight: CGFloat
    var anchorDate: Date
    var firstEventHour: Int?

    @State private var scrollPosition = ScrollPosition()
    @State private var targetOffset = CGFloat.zero
    @State private var scrollInfo = ScrollInfo(yOffset: 0, maxOffset: 100)
    @State private var needsInitialScroll = false

    func body(content: Content) -> some View {
        content
            .scrollPosition($scrollPosition, anchor: .topLeading)
            .onScrollGeometryChange(for: ScrollInfo.self) { geo in
                ScrollInfo(
                    yOffset: geo.contentOffset.y + geo.contentInsets.top,
                    maxOffset: geo.contentSize.height - geo.containerSize.height
                )
            } action: { _, newVal in
                scrollInfo = newVal
            }
            .onScrollPhaseChange { _, newVal in
                isCalendarScrolling = newVal != .idle
                if newVal == .interacting { needsInitialScroll = false }
            }
            .onChange(of: hoursFittingCurrentZoom) { oldZoom, newZoom in
                guard isScrollDisabled else { return }
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) {
                    let focalY = max(0, min(containerHeight, pinchAnchor * containerHeight))
                    let centerY = scrollInfo.yOffset + focalY
                    let hoursOld = oldZoom ?? customizationParams.hoursToFit
                    let hoursNew = newZoom ?? customizationParams.hoursToFit
                    let oneHourOld = containerHeight / max(3.0, min(12.0, hoursOld))
                    let oneHourNew = containerHeight / max(3.0, min(12.0, hoursNew))
                    let hourIndexAtFocal = max(0, min(24, (centerY - hourTextHeight) / oneHourOld))
                    let newCenterY = hourIndexAtFocal * oneHourNew + hourTextHeight
                    targetOffset = max(0, min(newCenterY - focalY, scrollInfo.maxOffset)).rounded()
                    scrollPosition.scrollTo(y: targetOffset)
                }
            }
            .onChange(of: scrollInfo) {
                if isScrollDisabled {
                    let clamped = max(0, min(targetOffset, scrollInfo.maxOffset))
                    if clamped != targetOffset {
                        targetOffset = clamped
                    }
                }
            }
            .task(id: targetOffset) {
                if !isCalendarScrolling && targetOffset != scrollInfo.yOffset {
                    var t = Transaction()
                    t.disablesAnimations = true
                    withTransaction(t) {
                        scrollPosition.scrollTo(y: targetOffset)
                    }
                }
            }
            .task(id: scrollInfo) {
                if isCalendarScrolling {
                    let yOffset = max(0, min(scrollInfo.yOffset.rounded(), scrollInfo.maxOffset))
                    if yOffset != targetOffset {
                        targetOffset = yOffset
                    }
                }
            }
            .onChange(of: firstEventHour) { _, newHour in
                guard needsInitialScroll, let hour = newHour else { return }
                let hoursToFit = hoursFittingCurrentZoom ?? customizationParams.hoursToFit
                let oneHourHeight = containerHeight / CGFloat(hoursToFit)
                targetOffset = max(0, CGFloat(hour) * oneHourHeight)
                needsInitialScroll = false
            }
            .task(id: anchorDate) {
                needsInitialScroll = true
                if let hour = firstEventHour {
                    let hoursToFit = hoursFittingCurrentZoom ?? customizationParams.hoursToFit
                    let oneHourHeight = containerHeight / CGFloat(hoursToFit)
                    targetOffset = max(0, CGFloat(hour) * oneHourHeight)
                    needsInitialScroll = false
                } else {
                    let hour = Calendar.current.isDateInToday(anchorDate) ? max(0, Date().getHour() - 1) : 8
                    let hoursToFit = hoursFittingCurrentZoom ?? customizationParams.hoursToFit
                    targetOffset = CGFloat(hour) * containerHeight / CGFloat(hoursToFit)
                }
            }
    }
}

/// Always-visible dates above the actual event columns, including month boundaries.
@available(iOS 18.0, *)
struct DayColumnHeading: View {
    @Environment(\.calendarTheme) private var theme
    let date: Date
    let daysCount: Int
    var body: some View {
        let today = Calendar.current.isDateInToday(date)
        VStack(spacing: 4) {
            Text(date.formatted(daysCount == 7 ? "EEEEE" : "EEE"))
                .font(.caption2.weight(.medium)).foregroundStyle(theme.main.secondaryText)
            Text(date.formatted(daysCount == 1 ? "MMM d" : "d"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(today ? theme.main.accent : theme.main.text)
            Rectangle().fill(today ? theme.main.accent : theme.main.separator.opacity(0.6)).frame(height: today ? 3 : 1)
        }
        .padding(.top, 6).frame(maxWidth: .infinity, minHeight: 54)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(today ? "Today, " : "")\(date.formatted(date: .complete, time: .omitted))")
    }
}

// MARK: - Extensions

@available(iOS 18.0, *)
extension Sequence where Element == CalendarEvent {
    func groupedByDay() -> [Date: [CalendarEvent]] {
        Dictionary(grouping: self) {
            $0.startDate.startOfDay
        }
    }
}

@available(iOS 18.0, *)
extension Sequence where Element == CalendarReminder {
    func groupedByDay() -> [Date: [CalendarReminder]] {
        Dictionary(grouping: self) {
            $0.startDate.startOfDay
        }
    }
}
