# Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import json,hashlib,re,sys

root=Path(sys.argv[1]).resolve()
out=Path(sys.argv[2]).resolve()
out.mkdir(exist_ok=True)
login=(root/'Linphone/UI/Assistant/Fragments/LoginFragment.swift').read_text()
crm=(root/'Linphone/UI/Main/Fragments/Mango9CRMFragment.swift').read_text()
logout=(root/'Linphone/Core/Mango9LogoutCoordinator.swift').read_text()
session=login[login.index('enum Mango9DeviceProof {'):login.index('struct Mango9SIPEnrollment {')]
# Test isolation only: unique defaults/keychain namespace and no app-specific side effects.
session=session.replace('SecItemUpdate(', 'AuditSecurity.update(').replace('SecItemAdd(', 'AuditSecurity.add(').replace('SecItemCopyMatching(', 'AuditSecurity.read(')
session=session.replace('UserDefaults.standard','AuditDefaults.shared').replace('(Bundle.main.bundleIdentifier ?? "com.mango9.linphone")','("com.mango9.lifecycle-audit." + AuditDefaults.runID)')
api=crm[crm.index('enum Mango9CRMAPI {'):crm.index('#Preview {')]
api=api.replace('URLSession = .shared','URLSession = AuditTransport.session')
lease=logout[logout.index('\tprivate func authorizeLease('):logout.index('\n\tfunc prepareLogout(')].replace('private func authorizeLease','func authorizeLease')
records=logout[logout.index('struct Mango9LogoutRecord:'):logout.index('/// A single serialized queue')]
coordinator=logout[logout.index('@MainActor\nfinal class Mango9LogoutCoordinator'):logout.index('\n\tfunc start()')]+"\n}\n"
coordinator,n=re.subn(r"accountExists: @escaping \(String\) async -> Bool = \{ identity in[\s\S]*?\n\t\t \}\) \{", "accountExists: @escaping (String) async -> Bool = { _ in false }) {",coordinator)
assert n==1
records=records.replace('SecItemUpdate(', 'AuditSecurity.update(').replace('SecItemAdd(', 'AuditSecurity.add(').replace('SecItemCopyMatching(', 'AuditSecurity.read(')
session=session.replace('private static var storageFailure: Error?', 'static func auditResetMemory() {\n lifecycleLock.lock(); defer { lifecycleLock.unlock() }\n volatileSessionsLock.lock(); volatileSessions = [:]; volatileSessionsLock.unlock()\n storageFailure = nil\n}\nprivate static var storageFailure: Error?')
prelude='''import Foundation
import Security
import CryptoKit
import Network
enum AuditDefaults {
 static let runID = UUID().uuidString
 static let shared = UserDefaults(suiteName: "com.mango9.lifecycle-audit." + runID)!
}
enum Log { static func warn(_ value: String) {} ; static func error(_ value: String) {} }
enum Mango9LineIdentityStore {
 static func clear(sipIdentity: String) {}
 static func migrateLegacyActiveValuesIfNeeded(sipIdentity: String) {}
 static func activate(sipIdentity: String) {}
}
extension Notification.Name { static let mango9AccountContextChanged = Notification.Name("audit-context") }
struct Mango9CRMDashboard: Decodable {}
enum Mango9Configuration {
 static var bundleIdentifier: String { "com.mango9.lifecycle-audit." + AuditDefaults.runID }
 static let provisioningBaseURL = URL(string: "https://provision.example.invalid")!
 static let applePushProvider = "fixture"
 static let applePushParam = "fixture-topic"
}
'''
wrapper='''
final class LeaseHarness {
 var calls = 0
 func request(path: String, bearer: String, body: [String:String]) async throws -> [String:Any] {
  calls += 1
  if calls == 1 { throw Mango9CRMAPIError.unauthorized }
  return ["cleanup_token":"test-capability", "generation":body["generation"]!]
 }
'''+lease+'\n}\n'
(out/'ReleasedSource.swift').write_text(prelude+session+api+records+wrapper+coordinator)
(out/'extraction.json').write_text(json.dumps({'sections':['Mango9Session','Mango9SessionStore','Mango9CRMAPI','Mango9LogoutCoordinator through retryPending'],'adaptations':['unique test defaults/keychain namespace','stub app identity and logging side effects','inject URLSession transport','expose private authorizeLease for test; inject cleanup HTTP/account state dependencies', 'omit UIKit/Core SIP callbacks; test cleanup decision and durable queue', 'test-only memory reset models process restart'],'hashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [root/'Linphone/UI/Assistant/Fragments/LoginFragment.swift',root/'Linphone/UI/Main/Fragments/Mango9CRMFragment.swift',root/'Linphone/Core/Mango9LogoutCoordinator.swift']}},indent=2))
