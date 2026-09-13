import SwiftUI

/// Host-owned long-press action; ordinary taps retain calendar navigation.
@available(iOS 18.0, *)
struct CalendarDateButton<Content: View>: View {
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

@available(iOS 18.0, *)
struct CalendarTimeSlots: View {
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
