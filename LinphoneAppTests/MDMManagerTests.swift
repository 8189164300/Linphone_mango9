/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-iphone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import XCTest
import linphonesw
@testable import LinphoneApp

class MDMManagerTests: XCTestCase {

	private let managedKey = "com.apple.configuration.managed"
	private let dummyRootCa = """
	-----BEGIN CERTIFICATE-----
	MIICvDCCAaQCCQC6XZwbCO63HTANBgkqhkiG9w0BAQsFADAgMR4wHAYDVQQDDBVN
	YW5nbzkgVW5pdCBUZXN0IFJvb3QwHhcNMjYwODIxMDk0OTUxWhcNMzYwODE4MDk0
	OTUxWjAgMR4wHAYDVQQDDBVNYW5nbzkgVW5pdCBUZXN0IFJvb3QwggEiMA0GCSqG
	SIb3DQEBAQUAA4IBDwAwggEKAoIBAQC5EwHeXpxak95nQ/wX1sjVp7gljarL3kpz
	4xA1uFcdqMOcaOANLCWlzLvXktv/X/MOG0EEcKXhrsX7NvOd16/xYy7VJ/ca7O6E
	k1wR9tkApT/Rl4E1uStBlHQRm821IP8YYBb7lxuLFL84ebzxdZT/BsYsAFBG5QtP
	wn4rwQaIYfR35/alwR4TJCUYHpzEcm1uLUOZPrFh9sFm4r5b/gXmkgucFZEime6C
	KS3oTdozKdimcTeeUVoYzKgKLKKXHBuxNRbJwNzr9UJ03eFRBjA905aGWNpIfbRV
	hMSjVh+lSn1LCOmuL/MJHAe22eUgwElbXPyuvEbl+Wrj6ZKiF9PdAgMBAAEwDQYJ
	KoZIhvcNAQELBQADggEBAIofUxeO1KbRFjAqiLx3V45ooL4F9m0Aipc7m6+A39PC
	v1NR6pSCOilBrCvj8WxnSF1BJlS0Oa7GuIMBInVwZTl5TtA9gePQKoBCD/wIOKtB
	Zz8GoUZ/X+OoJj2kvaDPs2f+4qU764qlX8wX68PHcOGNsvhyc7UsAaM5hHuV/e01
	4oW/47cXOxGGKrZIjOTSRldbwPA2CHXBoQvjtzrmTuwFeiVmb+Ali+o3K2UbEV/l
	wLVTz1Rs3ydFsDOvlC5Xh6S5uv3bn4yu0nZ6Iy30O/jf1/HHzUNndTpBJnDVUjp7
	WqvXE97rzwmuoNs+PjmR3uNFtAynrKpd2mhRRVy+e5w=
	-----END CERTIFICATE-----
	"""

	override func setUp() {
		super.setUp()
		UserDefaults.standard.removeObject(forKey: managedKey)
		UserDefaults.standard.removeObject(forKey: "MDMManager.hasMDMConfig")
		UserDefaults.standard.removeObject(forKey: "MDMManager.lastXMLConfigSHA256")
		UserDefaults.standard.removeObject(forKey: "MDMManager.lastCoreConfigSHA256")
	}

	override func tearDown() {
		UserDefaults.standard.removeObject(forKey: managedKey)
		UserDefaults.standard.removeObject(forKey: "MDMManager.hasMDMConfig")
		UserDefaults.standard.removeObject(forKey: "MDMManager.lastXMLConfigSHA256")
		UserDefaults.standard.removeObject(forKey: "MDMManager.lastCoreConfigSHA256")
		super.tearDown()
	}

	func testApplyMdmConfigSetsRootCa() throws {
		let mdmConfig: [String: Any] = ["rootCa": dummyRootCa]
		UserDefaults.standard.set(mdmConfig, forKey: managedKey)

		let config = Config.newForSharedCore(
			appGroupId: Bundle.main.object(forInfoDictionaryKey: "APP_GROUP_NAME") as? String ?? "group.test",
			configFilename: "linphonerc-test",
			factoryConfigFilename: nil
		)

		let core = try Factory.Instance.createCoreWithConfig(config: config!, systemContext: nil)

		let appliedExpectation = expectation(forNotification: MDMManager.configurationAppliedNotification, object: nil) { notification in
			guard let config = notification.userInfo?["config"] as? [String: Any] else { return false }
			return (config["rootCa"] as? String) == self.dummyRootCa
		}

		MDMManager.shared.applyMdmConfigToCore(core: core)

		wait(for: [appliedExpectation], timeout: 5)
		// linphonesw exposes rootCaData as a write-only value: its generated getter
		// always returns the empty Swift backing value. The notification is posted
		// synchronously after the SDK setter and verifies the exact applied payload.
	}

}
