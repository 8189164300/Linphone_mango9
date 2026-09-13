import SwiftUI
import XCTest
@testable import LinphoneApp

@MainActor final class Mango9LeadLayoutTests: XCTestCase {
	private func field(_ key: String, type: String = "text", section: String = "personal", custom: Bool = false) -> Mango9LeadSchema.Field {
		.init(key: key, fieldId: nil, name: nil, label: key.replacingOccurrences(of: "_", with: " ").capitalized,
			type: type, section: section, required: key == "first_name", editable: true, visible: true, custom: custom, options: nil)
	}

	func testPairsNamesAndAddressWithoutLosingFields() {
		let fields = [field("last_name"), field("email", type: "email"), field("first_name"),
			field("address", section: "location"), field("city", section: "location"), field("state", section: "location"),
			field("zip_code", section: "location"), field("country", section: "location")]
		let rows = Mango9LeadFieldLayout.rows(fields)
		XCTAssertEqual(rows.map { $0.fields.map(\.key) }, [["first_name", "last_name"], ["email"], ["address"], ["city", "state", "zip_code"], ["country"]])
		XCTAssertEqual(Set(rows.flatMap(\.fields).map(\.key)), Set(fields.map(\.key)))
		XCTAssertEqual(rows.flatMap(\.fields).count, fields.count)
	}

	func testCompactCustomFieldsShareRowsButLongContentKeepsSpace() {
		let fields = [field("company"), field("source", type: "select"), field("custom_1", custom: true),
			field("custom_2", type: "number", custom: true), field("notes", type: "textarea"),
			field("phone", type: "phone"), field("email", type: "email"), field("url", type: "url"), field("long_value", custom: true)]
		let rows = Mango9LeadFieldLayout.rows(fields, values: ["email": "long.email.address@example.com", "long_value": String(repeating: "Long content ", count: 15)])
		XCTAssertEqual(rows.map { $0.fields.map(\.key) }, [["company", "source"], ["custom_1", "custom_2"], ["notes"], ["phone"], ["email"], ["url"], ["long_value"]])
		XCTAssertEqual(rows.flatMap(\.fields).map(\.key), fields.map(\.key))
	}

	func testMissingPartnersAndDifferentSectionsDoNotInventFields() {
		XCTAssertTrue(Mango9LeadFieldLayout.rows([]).isEmpty)
		let fields = [field("first_name"), field("last_name", section: "different"), field("custom_1", custom: true)]
		let rows = Mango9LeadFieldLayout.rows(fields)
		XCTAssertEqual(rows.flatMap(\.fields).map(\.key), fields.map(\.key))
		XCTAssertTrue(rows.allSatisfy { Set($0.fields.map(\.section)).count == 1 })
		XCTAssertEqual(rows.first?.fields.count, 1)
	}

	func testStandardLargeTextKeepsColumnsAndAccessibilityStacks() {
		XCTAssertEqual(Mango9LeadFieldLayout.columnCount(width: 329, textSize: .large, fieldCount: 2), 2)
		XCTAssertEqual(Mango9LeadFieldLayout.columnCount(width: 256, textSize: .large, fieldCount: 2), 2)
		XCTAssertEqual(Mango9LeadFieldLayout.columnCount(width: 376, textSize: .xxxLarge, fieldCount: 3), 3)
		XCTAssertEqual(Mango9LeadFieldLayout.columnCount(width: 200, textSize: .large, fieldCount: 2), 1)
		XCTAssertEqual(Mango9LeadFieldLayout.columnCount(width: 800, textSize: .accessibility3, fieldCount: 2), 1)
	}

	func testEmptyContactFieldsCompactButStreetAndLongEmailStayFullWidth() {
		let fields = [field("phone", type: "phone"), field("email", type: "email"), field("address"), field("city"), field("state"), field("zip_code")]
		XCTAssertEqual(Mango9LeadFieldLayout.rows(fields).map { $0.fields.map(\.key) }, [["phone", "email"], ["address"], ["city", "state", "zip_code"]])
		let model = Mango9LeadDetailViewModel(leadId: 1, initialValues: ["email": ""], startsInEditMode: true)
		model.values["email"] = "a.long.email.address@example.com"
		XCTAssertEqual(Mango9LeadFieldLayout.rows(fields, values: model.layoutValues).first?.fields.count, 2, "Typing must not rearrange focused fields")
		model.isEditing = false
		XCTAssertEqual(Mango9LeadFieldLayout.rows(fields, values: model.layoutValues).first?.fields.count, 1)
	}

	func testEditingBindingsStillUseOriginalSchemaKeysAndCancelRestoresValues() {
		let model = Mango9LeadDetailViewModel(leadId: 1, initialValues: ["first_name": "Alex", "last_name": "Morgan", "custom_1": "Original"], startsInEditMode: true)
		model.valueBinding(for: "first_name").wrappedValue = "Alexandra"
		model.valueBinding(for: "last_name").wrappedValue = "Reed"
		model.valueBinding(for: "custom_1").wrappedValue = "Changed"
		XCTAssertEqual(model.values, ["first_name": "Alexandra", "last_name": "Reed", "custom_1": "Changed"])
		model.cancelEditing()
		XCTAssertEqual(model.values, ["first_name": "Alex", "last_name": "Morgan", "custom_1": "Original"])
		XCTAssertFalse(model.isEditing)
	}

	func testSectionCardsRenderPairedFieldsAndStackForLargeText() async throws {
		let fields = [field("first_name"), field("last_name"), field("company"), field("source", type: "select"),
			field("phone", type: "phone"), field("email", type: "email"),
			field("address", section: "location"), field("city", section: "location"), field("state", section: "location"),
			field("zip_code", section: "location"), field("country", section: "location")]
		let values = ["first_name": "Alexandra", "last_name": "Morgan", "company": "Example Studio", "source": "Referral",
			"phone": "+1 (202) 555-0142", "email": "alexandra.morgan@example.com", "address": "123 Example Street, Suite 200",
			"city": "Los Angeles", "state": "California", "zip_code": "90012", "country": "United States"]
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true; window.rootViewController = nil }
		for (width, size) in [(CGFloat(393), DynamicTypeSize.large), (440, .xxxLarge), (320, .large), (393, .accessibility3)] {
			var frames: [String: CGRect] = [:]
			window.frame = CGRect(x: 0, y: 0, width: width, height: 852)
			window.rootViewController = UIHostingController(rootView: ScrollView {
				VStack(spacing: 18) {
					ForEach([Mango9LeadSchema.Section(id: "personal", label: "Contact information"), .init(id: "location", label: "Address")]) { section in
						Mango9LeadSectionCard(section: section) {
							Mango9LeadFieldRows(fields: fields.filter { $0.section == section.id }, values: values, width: width - 64) { field in
								VStack(alignment: .leading, spacing: 4) {
									Text(field.label).default_text_style_700(styleSize: 11).foregroundStyle(Color.grayMain2c500)
									Text(values[field.key] ?? "").default_text_style(styleSize: 14).frame(maxWidth: .infinity, alignment: .leading)
								}
								.background(GeometryReader { geometry in
									Color.clear.preference(key: LeadFieldFrames.self, value: [field.key: geometry.frame(in: .named("fields"))])
								})
							}
						}
					}
				}.padding(16)
			}.coordinateSpace(name: "fields").background(Color.gray100)
				.dynamicTypeSize(size).onPreferenceChange(LeadFieldFrames.self) { frames = $0 })
			window.makeKeyAndVisible()
			for _ in 0..<50 {
				if frames.count == fields.count { break }
				try await Task.sleep(nanoseconds: 100_000_000)
			}
			window.layoutIfNeeded()
			XCTAssertEqual(frames.count, fields.count)
			let first = try XCTUnwrap(frames["first_name"]); let last = try XCTUnwrap(frames["last_name"])
			if !size.isAccessibilitySize {
				XCTAssertEqual(first.minY, last.minY, accuracy: 1)
				XCTAssertGreaterThan(last.minX, first.maxX)
				XCTAssertEqual(first.width, last.width, accuracy: 1)
				XCTAssertGreaterThan(try XCTUnwrap(frames["email"]).width, first.width * 1.5)
				if width >= 393 {
					let city = try XCTUnwrap(frames["city"]), state = try XCTUnwrap(frames["state"]), zip = try XCTUnwrap(frames["zip_code"])
					XCTAssertEqual(city.minY, state.minY, accuracy: 1)
					XCTAssertEqual(city.minY, zip.minY, accuracy: 1)
					XCTAssertGreaterThan(state.minX, city.maxX)
					XCTAssertGreaterThan(zip.minX, state.maxX)
				}
			} else {
				XCTAssertGreaterThan(last.minY, first.maxY)
				XCTAssertEqual(first.minX, last.minX, accuracy: 1)
			}
			XCTAssertTrue(frames.values.allSatisfy { $0.width > 0 && $0.width <= width && $0.height > 0 })
			let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
			let attachment = XCTAttachment(image: image); attachment.name = "Lead cards \(Int(width)) \(size)"; attachment.lifetime = .keepAlways; add(attachment)
		}
	}
}

private struct LeadFieldFrames: PreferenceKey {
	static var defaultValue: [String: CGRect] = [:]
	static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue(), uniquingKeysWith: { _, new in new }) }
}
