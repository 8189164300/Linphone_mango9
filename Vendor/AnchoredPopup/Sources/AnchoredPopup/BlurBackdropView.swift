//
//  UIBackdropView.swift
//  Beyond
//
//  Created by Alisa Mylnikova on 30.10.2024.
//

import SwiftUI

@available(iOS 18.0, *)
open class UIBackdropView: UIView {
    open override class var layerClass: AnyClass {
        NSClassFromString("CABackdropLayer") ?? CALayer.self
    }
}

@available(iOS 18.0, *)
public struct Backdrop: UIViewRepresentable {
    public init() {}

    public func makeUIView(context: Context) -> UIBackdropView {
        UIBackdropView()
    }

    public func updateUIView(_ uiView: UIBackdropView, context: Context) {}
}

@available(iOS 18.0, *)
public struct Blur: View {
    public var radius: CGFloat
    public var opaque: Bool

    public init(radius: CGFloat = 3.0, opaque: Bool = false) {
        self.radius = radius
        self.opaque = opaque
    }

    public var body: some View {
        Backdrop()
            .blur(radius: radius, opaque: opaque)
    }
}
