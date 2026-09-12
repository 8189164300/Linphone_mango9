//
//  HeaderBackground.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 22.05.2025.
//

import SwiftUI

@available(iOS 18.0, *)
public enum HeaderBackground {
    case none
    case color(Color, CGFloat)
    case view(AnyView)

    // Convenience initializer for `view` that automatically wraps the content in `AnyView`
    public init<Content: View>(viewBuilder: @escaping () -> Content) {
        self = .view(AnyView(viewBuilder()))
    }
}

@available(iOS 18.0, *)
struct HeaderBackgroundView: View {
    var background: HeaderBackground

    var body: some View {
        Group {
            switch background {
            case .none:
                EmptyView()
            case .color(let color, let radius):
                color.cornerRadius(radius)
            case .view(let anyView):
                anyView
            }
        }
        .ignoresSafeArea()
    }
}
