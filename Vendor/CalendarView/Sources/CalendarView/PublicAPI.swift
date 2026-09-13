//
//  PublicAPI.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 30.04.2025.
//

import SwiftUI
import UIKit

@available(iOS 18.0, *)
extension CalendarView {

    /// trigger for updates
    public func idForUpdate(_ idForUpdate: UUID) -> CalendarView {
        var copy = self
        copy.idForUpdate = idForUpdate
        return copy
    }

    /// how many hours will fit vertically in a day displayMode, default is 12
    public func hoursToFit(_ hoursToFit: CGFloat) -> CalendarView {
        var copy = self
        copy.customizationParams.hoursToFit = min(24, max(1, hoursToFit))
        return copy
    }

    /// Optional readable label height at low zoom. Visual collisions use this
    /// height too; the event's dates and start position are never modified.
    public func minimumTimedEventHeight(_ height: CGFloat) -> CalendarView {
        var copy = self
        copy.customizationParams.minimumTimedEventHeight = height.isFinite ? max(0, height) : 0
        return copy
    }

    /// default is "h a"
    public func hourLabelFormat(_ hourLabelFormat: String) -> CalendarView {
        var copy = self
        copy.customizationParams.hourLabelFormat = hourLabelFormat
        return copy
    }

    /// what day to start the week from, 1 - Sunday, 2 - Monday
    public func firstDayOfWeek(_ firstDayOfWeek: Int) -> CalendarView {
        var copy = self
        copy.customizationParams.firstDayOfWeek = firstDayOfWeek
        return copy
    }

    /// Background for header and week picker
    public func headerBackground(_ background: HeaderBackground) -> CalendarView {
        var copy = self
        copy.customizationParams.headerBackground = background
        return copy
    }

    public func headerBackground<Content: View>(viewBuilder: @escaping () -> Content) -> CalendarView {
        var copy = self
        copy.customizationParams.headerBackground = HeaderBackground(viewBuilder: viewBuilder)
        return copy
    }

    public func eventDetailsClosure(_ closure: @escaping (any CalendarEntity)->()) -> CalendarView {
        var copy = self
        copy.customizationParams.eventDetailsClosure = closure
        return copy
    }

    /// Optional host-owned creation action for month dates and day/week headings.
    /// No event is created or local editor presented by the calendar library.
    public func dateLongPressClosure(_ closure: ((Date) -> Void)?) -> CalendarView {
        var copy = self
        copy.customizationParams.dateLongPressClosure = closure
        return copy
    }

    /// Long-press an empty quarter-hour slot, using the displayed calendar time.
    public func timeSlotLongPressClosure(_ closure: ((Date) -> Void)?) -> CalendarView {
        var copy = self
        copy.customizationParams.timeSlotLongPressClosure = closure
        return copy
    }

    /// Host-owned availability shading. Does not alter or consume event gestures.
    public func timedDayBackground<Background: View>(@ViewBuilder _ background: @escaping (Date, CGFloat) -> Background) -> CalendarView {
        var copy = self
        copy.customizationParams.timedDayBackground = { AnyView(background($0, $1)) }
        return copy
    }

    public func isDayInWeekSwitcherPagingEnabled(_ value: Bool) -> CalendarView {
        var copy = self
        copy.customizationParams.isDayInWeekSwitcherPagingEnabled = value
        return copy
    }

    /// Use a custom font family by name. Font sizes and colors defined in the library are preserved.
    public func customFont(_ name: String) -> CalendarView {
        var copy = self
        copy.customizationParams.customFontName = name
        return copy
    }

    /// Scale all fonts with the system-wide Dynamic Type accessibility setting.
    public func useDynamicType(_ enabled: Bool) -> CalendarView {
        var copy = self
        copy.customizationParams.useDynamicType = enabled
        return copy
    }
}
