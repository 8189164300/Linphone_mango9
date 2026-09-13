import Foundation
import XCTest
@testable import LinphoneApp

private final class LeadStatusURLProtocol: URLProtocol {
	static var handler: ((URLRequest) throws -> (Int, Data, TimeInterval))?
	override class func canInit(with request: URLRequest) -> Bool { true }
	override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
	override func startLoading() {
		do {
			let (code, data, delay) = try XCTUnwrap(Self.handler)(request)
			let response = try XCTUnwrap(HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: ["Content-Type": "application/json"]))
			DispatchQueue.global().asyncAfter(deadline: .now() + delay) {
				self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
				self.client?.urlProtocol(self, didLoad: data)
				self.client?.urlProtocolDidFinishLoading(self)
			}
		} catch { client?.urlProtocol(self, didFailWithError: error) }
	}
	override func stopLoading() {}
}

@MainActor final class Mango9LeadStatusTests: XCTestCase {
	private func session(_ user: String = "status-fixture") -> Mango9Session {
		.init(crmId: "fixture", crmBaseUrl: "https://crm.example.invalid", crmApiBaseUrl: "https://crm.example.invalid/api/v2",
			userId: user, parentClientId: "fixture", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
			accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "", connectWebsocket: "",
			enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:\(user)@crm.example.invalid")
	}

	private func withSession(_ body: (Mango9Session, URLSession) async throws -> Void) async throws {
		let previous = Mango9SessionStore.activeIdentity
		let current = session()
		try Mango9SessionStore.save(current, persist: false, makeActive: true)
		let configuration = URLSessionConfiguration.ephemeral
		configuration.protocolClasses = [LeadStatusURLProtocol.self]
		let transport = URLSession(configuration: configuration)
		defer {
			transport.invalidateAndCancel()
			LeadStatusURLProtocol.handler = nil
			Mango9SessionStore.remove(for: current.sipIdentity!)
			Mango9SessionStore.remove(for: session("replacement").sipIdentity!)
			Mango9SessionStore.activate(sipIdentity: previous)
		}
		try await body(current, transport)
	}

	private func model(_ transport: URLSession, kind: Mango9CRMRecordKind = .lead, editable: Bool = true, visible: Bool = true) -> Mango9LeadDetailViewModel {
		let field = Mango9LeadSchema.Field(key: "lead_status", fieldId: nil, name: nil, label: "Lead Status", type: "select",
			section: "personal", required: true, editable: editable, visible: visible, custom: false, options: ["New", "Qualified", "Qualified"])
		return .init(leadId: 101, recordKind: kind,
			initialSchema: .init(entity: "lead", version: "fixture", sections: [.init(id: "personal", label: "Personal Information")], fields: [field], statuses: ["New", "Qualified"]),
			initialValues: ["lead_status": "New", "first_name": "Alex", "custom_1": "Untouched"], statusTransport: transport)
	}

	private func response(kind: Mango9CRMRecordKind = .lead, status: String = "Qualified", id: Int = 101) throws -> Data {
		try JSONSerialization.data(withJSONObject: ["success": true, "message": "Success", "data": [
			kind == .lead ? "lead" : "client": ["id": id, "owner_user_id": 1, "owner_name": "Fixture", "name": "Alex Morgan",
				"phone": "", "email": "", "status": status, "source": "", "created_at": ""],
			"schema_version": "fixture", "values": ["lead_status": status, "first_name": "Server value must not overwrite another draft"]]])
	}

	private func body(_ request: URLRequest) throws -> [String: Any] {
		let data: Data
		if let bytes = request.httpBody { data = bytes }
		else {
			let stream = try XCTUnwrap(request.httpBodyStream); stream.open(); defer { stream.close() }
			var buffer = [UInt8](repeating: 0, count: 1024), bytes = Data()
			while stream.hasBytesAvailable {
				let count = stream.read(&buffer, maxLength: buffer.count)
				guard count > 0 else { break }; bytes.append(buffer, count: count)
			}
			data = bytes
		}
		return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
	}

	func testQuickStatusPatchesOnlyStatusForLeadAndClient() async throws {
		try await withSession { current, transport in
			for kind in [Mango9CRMRecordKind.lead, .client] {
				let model = model(transport, kind: kind)
				let data = try response(kind: kind)
				var requests = 0
				LeadStatusURLProtocol.handler = { request in
					requests += 1
					XCTAssertEqual(request.httpMethod, "PATCH")
					XCTAssertEqual(request.url?.path, "/api/v2/mobile/\(kind == .lead ? "leads" : "clients")/101")
					XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(current.accessToken)")
					let object = try self.body(request)
					XCTAssertEqual(Set(object.keys), ["values"])
					XCTAssertEqual(object["values"] as? [String: String], ["lead_status": "Qualified"])
					return (200, data, 0)
				}
				await model.changeStatus("Qualified")
				XCTAssertEqual(requests, 1)
				XCTAssertEqual(model.values, ["lead_status": "Qualified", "first_name": "Alex", "custom_1": "Untouched"])
				XCTAssertEqual(model.lead?.status, "Qualified")
				XCTAssertFalse(model.isEditing); XCTAssertFalse(model.isSavingStatus); XCTAssertNil(model.statusError)
				model.cancelEditing()
				XCTAssertEqual(model.values["lead_status"], "Qualified", "Cancel must not undo a confirmed quick update")
			}
		}
	}

	func testReadOnlyHiddenUnknownAndUnchangedStatusDoNotWrite() async throws {
		try await withSession { _, transport in
			LeadStatusURLProtocol.handler = { _ in XCTFail("This status must not be sent"); return (500, Data(), 0) }
			let model = model(transport)
			XCTAssertEqual(model.statusOptions, ["New", "Qualified"])
			await model.changeStatus("Unknown")
			await model.changeStatus("New")
			model.isEditing = true
			await model.changeStatus("Qualified")
			let readOnly = self.model(transport, editable: false)
			XCTAssertTrue(readOnly.statusOptions.isEmpty)
			await readOnly.changeStatus("Qualified")
			let hidden = self.model(transport, visible: false)
			XCTAssertTrue(hidden.statusOptions.isEmpty)
			await hidden.changeStatus("Qualified")
			XCTAssertEqual(model.values["lead_status"], "New")
		}
	}

	func testSchemaStatusKeysUseTheirExactWireKeyAndServerOptions() async throws {
		try await withSession { _, transport in
			for key in ["lead_status", "status"] {
				// Decode the wire-format schema, not a summary field named status.
				let schemaJSON: [String: Any] = ["entity": "leads", "version": "fixture",
					"sections": [["id": "personal", "label": "Personal Information"]],
					"fields": [["key": key, "label": "Lead Status", "type": "select", "section": "personal",
						"required": false, "editable": true, "custom": false, "options": []]],
					"statuses": ["New", "Qualified", "Qualified"]]
				let schema = try JSONDecoder().decode(Mango9LeadSchema.self, from: JSONSerialization.data(withJSONObject: schemaJSON))
				let model = Mango9LeadDetailViewModel(leadId: 101, initialSchema: schema,
					initialValues: [key: "New"], statusTransport: transport)
				XCTAssertFalse(model.isEditing)
				XCTAssertEqual(model.editableStatusField?.key, key)
				XCTAssertEqual(model.statusOptions, ["New", "Qualified"])
				XCTAssertEqual(model.currentStatus, "New")
				let data = try response()
				LeadStatusURLProtocol.handler = { request in
					XCTAssertEqual(try self.body(request)["values"] as? [String: String], [key: "Qualified"])
					return (200, data, 0)
				}
				await model.changeStatus("Qualified")
				XCTAssertEqual(model.currentStatus, "Qualified")
				XCTAssertEqual(model.values, [key: "Qualified"])
				XCTAssertFalse(model.isEditing)
			}
		}
	}

	func testSummaryAliasCannotBypassReadOnlySchemaStatus() async throws {
		try await withSession { _, transport in
			let fields = ["status", "lead_status"].map { key in
				Mango9LeadSchema.Field(key: key, fieldId: nil, name: nil, label: "Status", type: "select",
					section: "personal", required: false, editable: key == "status", visible: true, custom: false, options: ["New", "Qualified"])
			}
			let model = Mango9LeadDetailViewModel(leadId: 101,
				initialSchema: .init(entity: "leads", version: "fixture", sections: [], fields: fields, statuses: []),
				initialValues: ["lead_status": "New", "status": "Summary"], statusTransport: transport)
			XCTAssertNil(model.editableStatusField)
			XCTAssertEqual(model.currentStatus, "New")
			LeadStatusURLProtocol.handler = { _ in XCTFail("A summary alias must not bypass permissions"); return (500, Data(), 0) }
			await model.changeStatus("Qualified")
		}
	}

	func testFailureAndWrongRecordResponseKeepTheConfirmedValue() async throws {
		try await withSession { _, transport in
			for (code, data) in [(403, Data()), (200, try response(id: 999))] {
				let model = model(transport)
				LeadStatusURLProtocol.handler = { _ in (code, data, 0) }
				await model.changeStatus("Qualified")
				XCTAssertEqual(model.values["lead_status"], "New")
				XCTAssertNotNil(model.statusError); XCTAssertFalse(model.isSavingStatus)
			}
		}
	}

	func testExpiredTokenRefreshesOnceThenRetriesSameStatusOnly() async throws {
		try await withSession { _, transport in
			let model = model(transport), data = try response()
			var requests = 0
			LeadStatusURLProtocol.handler = { request in
				requests += 1
				if requests == 1 { return (401, Data(), 0) }
				if requests == 2 {
					XCTAssertEqual(request.url?.path, "/api/v2/auth/refresh")
					return (200, Data(#"{"success":true,"message":"Success","data":{"tokens":{"access_token":"renewed-fixture","refresh_token":"renewed-refresh"}}}"#.utf8), 0)
				}
				XCTAssertEqual(requests, 3)
				XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer renewed-fixture")
				XCTAssertEqual(try self.body(request)["values"] as? [String: String], ["lead_status": "Qualified"])
				return (200, data, 0)
			}
			await model.changeStatus("Qualified")
			XCTAssertEqual(requests, 3); XCTAssertEqual(model.values["lead_status"], "Qualified"); XCTAssertNil(model.statusError)
		}
	}

	func testDuplicateTapsAndAccountSwitchIgnoreLateResponse() async throws {
		try await withSession { _, transport in
			let model = model(transport), data = try response()
			let sent = expectation(description: "Request started")
			var count = 0
			LeadStatusURLProtocol.handler = { _ in count += 1; sent.fulfill(); return (200, data, 0.4) }
			let pending = Task { await model.changeStatus("Qualified") }
			await fulfillment(of: [sent], timeout: 5)
			XCTAssertTrue(model.isSavingStatus)
			await model.changeStatus("Qualified")
			await model.load() // Refresh must not race a pending write.
			try Mango9SessionStore.save(session("replacement"), persist: false, makeActive: true)
			await pending.value
			XCTAssertEqual(count, 1); XCTAssertEqual(model.values["lead_status"], "New"); XCTAssertFalse(model.isSavingStatus)
			await model.changeStatus("Qualified")
			XCTAssertEqual(count, 1); XCTAssertNotNil(model.statusError)
		}
	}
}
