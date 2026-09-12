// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CalendarView",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "CalendarView",
            targets: ["CalendarView"]),
    ],
    dependencies: [
        .package(path: "../AnchoredPopup")
    ],
    targets: [
        .target(
            name: "CalendarView",
            dependencies: [
                .product(name: "AnchoredPopup", package: "AnchoredPopup")
            ],
            swiftSettings: [
                .enableExperimentalFeature("StrictConcurrency")
            ]
        ),
    ]
)
