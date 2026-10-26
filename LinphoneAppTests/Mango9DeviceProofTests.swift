/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
import XCTest
import CryptoKit
@testable import LinphoneApp

final class Mango9DeviceProofTests: XCTestCase {
    private let endpoint = URL(string: "https://crm.example.invalid/app/api/v2/auth/refresh")!
    private func decode(_ value: String) throws -> Data {
        let base = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return try XCTUnwrap(Data(base64Encoded: base + String(repeating: "=", count: (4-base.count%4)%4)))
    }
    func testSecureEnclaveProofVerifiesWithPublicKey() throws {
        let key = try Mango9DeviceProof.create()
        defer { Mango9DeviceProof.removeIfUnused(key) }
        let proof = try Mango9DeviceProof.proof(keyID: key, url: endpoint)
        let parts = proof.components(separatedBy: ".")
        XCTAssertEqual(parts.count, 3)
        let header = try XCTUnwrap(JSONSerialization.jsonObject(with: decode(parts[0])) as? [String:Any])
        let jwk = try XCTUnwrap(header["jwk"] as? [String:String])
        var point = Data([4])
        point.append(try decode(XCTUnwrap(jwk["x"])))
        point.append(try decode(XCTUnwrap(jwk["y"])))
        let publicKey = try P256.Signing.PublicKey(x963Representation: point)
        let signature = try P256.Signing.ECDSASignature(rawRepresentation: decode(parts[2]))
        XCTAssertTrue(publicKey.isValidSignature(signature, for: Data((parts[0]+"."+parts[1]).utf8)))
        XCTAssertEqual(header["typ"] as? String,"dpop+jwt")
        XCTAssertEqual(header["alg"] as? String,"ES256")
        XCTAssertNil(jwk["d"])
        XCTAssertEqual(try Mango9DeviceProof.thumbprint(keyID:key).count,43)
        let fixture = try JSONSerialization.data(withJSONObject: ["proof":proof,"url":endpoint.absoluteString,"jkt":Mango9DeviceProof.thumbprint(keyID:key)])
        let attachment = XCTAttachment(data:fixture,uniformTypeIdentifier:"public.json")
        attachment.name = "device-proof-fixture"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    func testEachProofHasUniqueReplayIdentifier() throws {
        let key = try Mango9DeviceProof.create()
        defer { Mango9DeviceProof.removeIfUnused(key) }
        func claims() throws -> [String:Any] {
            let part = try Mango9DeviceProof.proof(keyID:key,url:endpoint).components(separatedBy:".")[1]
            return try XCTUnwrap(JSONSerialization.jsonObject(with:decode(part)) as? [String:Any])
        }
        let first = try claims(), second = try claims()
        XCTAssertNotEqual(first["jti"] as? String,second["jti"] as? String)
        XCTAssertEqual(first["htu"] as? String,endpoint.absoluteString)
        XCTAssertEqual(first["htm"] as? String,"POST")
    }
    func testMissingDeviceKeyDoesNotSilentlyReplaceIt() {
        XCTAssertThrowsError(try Mango9DeviceProof.proof(keyID:UUID().uuidString,url:endpoint)) { error in
            guard case Mango9CRMAPIError.sessionExpired = error else { return XCTFail("Expected explicit missing-key authentication state") }
        }
    }
    func testProofRejectsInsecureOrCredentialBearingEndpoint() throws {
        let key = try Mango9DeviceProof.create()
        defer { Mango9DeviceProof.removeIfUnused(key) }
        for endpoint in ["http://crm.example.invalid/refresh","https://user:secret@crm.example.invalid/refresh","https://crm.example.invalid/refresh?token=value"] {
            XCTAssertThrowsError(try Mango9DeviceProof.proof(keyID:key,url:URL(string:endpoint)!))
        }
    }
}
