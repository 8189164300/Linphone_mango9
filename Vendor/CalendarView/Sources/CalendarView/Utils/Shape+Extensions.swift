//
//  Shape+Extensions.swift
//  CalendarView
//
//  Created by Alisa Mylnikova on 15.04.2025.
//

import SwiftUI

@available(iOS 18.0, *)
extension Shape {
    func styled(_ foregroundColor: Color, border borderColor: Color = .clear, _ borderWidth: CGFloat = 0) -> some View {
        self.foregroundStyle(foregroundColor)
            .overlay(
                self
                    .stroke(borderColor, lineWidth: borderWidth)
            )
    }
}

@available(iOS 18.0, *)
extension RoundedRectangle {
    static func styled(_ cornerRadius: CGFloat, _ color: Color) -> some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .foregroundStyle(color)
    }
}

@available(iOS 18.0, *)
extension Image {
    func recolor(_ color: Color) -> some View {
        self.renderingMode(.template).foregroundStyle(color)
    }
}
