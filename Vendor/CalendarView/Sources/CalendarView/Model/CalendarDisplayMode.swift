//
//  CalendarDisplayMode.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 25.06.2026.
//

import SwiftUI

@available(iOS 18.0, *)
public enum CalendarDisplayMode: Int, Sendable, CaseIterable, Hashable {
    case day = 1
    case twoDays = 2
    case threeDays = 3
    case week = 7
    case month = 30

    func interval(_ start: Date) -> DateInterval {
        let start = start.startOfDay
        return switch self {
        case .day:
            DateInterval(start: start, end: start.adding(.day, value: 1))
        case .twoDays:
            DateInterval(start: start, end: start.adding(.day, value: 2))
        case .threeDays:
            DateInterval(start: start, end: start.adding(.day, value: 3))
        case .week:
            DateInterval(start: start, end: start.adding(.day, value: 7))
        case .month:
            DateInterval(start: start.startOfMonth, end: start.startOfMonth.adding(.month, value: 1))
        }
    }

    var icon: ImageResource {
        switch self {
        case .day:
            return .modeDay
        case .twoDays:
            return .mode2Days
        case .threeDays:
            return .mode3Days
        case .week:
            return .modeMonth
        case .month:
            return .modeMonth
        }
    }

    public var title: String {
        switch self {
        case .day:
            return "Day"
        case .twoDays:
            return "2 Days"
        case .threeDays:
            return "3 Days"
        case .week:
            return "Week"
        case .month:
            return "Month"
        }
    }
}
