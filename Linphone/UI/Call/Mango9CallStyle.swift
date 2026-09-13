import SwiftUI

/// The light Mango9 calling surface, shared by the call and its controls.
/// Video surfaces keep their own contrast treatment; signaling is unaffected.
enum Mango9CallStyle {
	static let canvas = Color.white
	static let tray = Color(hex: "#F5F6FA")
	static let control = Color(hex: "#E9EDF3")
	static let ink = Color.grayMain2c800
	static let secondary = Color.grayMain2c600
	static let accent = Color.mango9Primary
	static let destructive = Color.redDanger500
	static let divider = Color(hex: "#DDE3EB")

	static func controlBackground(active: Bool) -> Color { active ? accent : control }
	static func controlForeground(active: Bool) -> Color { active ? .white : secondary }
}

/// Press feedback must keep white symbols visible on selected blue/red controls.
struct Mango9CallPressedButtonStyle: ButtonStyle {
	var buttonSize: CGFloat
	func makeBody(configuration: Configuration) -> some View {
		configuration.label
			.frame(width: buttonSize, height: buttonSize)
			.opacity(configuration.isPressed ? 0.6 : 1)
	}
}
