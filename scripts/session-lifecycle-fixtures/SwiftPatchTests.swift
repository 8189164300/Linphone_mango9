/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation
import Security

enum AuditSecurity {
 static var failure: OSStatus?
 static func isolated(_ query:CFDictionary)->Bool {
  let values=query as NSDictionary
  if let tag=values[kSecAttrApplicationTag] as? Data, let value=String(data:tag,encoding:.utf8) {
   return value.hasPrefix(Mango9Configuration.bundleIdentifier+".")
  }
  return (values[kSecAttrService] as? String)?.hasPrefix(Mango9Configuration.bundleIdentifier+".") == true
 }
 static func update(_ query:CFDictionary,_ attributes:CFDictionary)->OSStatus { guard isolated(query) else { return errSecParam }; return failure ?? SecItemUpdate(query,attributes) }
 static func add(_ query:CFDictionary,_ result:UnsafeMutablePointer<CFTypeRef?>?)->OSStatus { guard isolated(query) else { return errSecParam }; return failure ?? SecItemAdd(query,result) }
 static func read(_ query:CFDictionary,_ result:UnsafeMutablePointer<CFTypeRef?>?)->OSStatus { guard isolated(query) else { return errSecParam }; return failure ?? SecItemCopyMatching(query,result) }
}

final class RotationProtocol: URLProtocol {
 enum Mode { case normal, loseResponse, delayResponse, forbidden, invalidGrant, proxyUnauthorized, rateLimited, unavailable, malformed }
 static let lock = NSLock()
 static var mode: Mode = .normal
 static var consumed = Set<String>()
 static var count = 0
 static func reset(_ value: Mode = .normal) {
  lock.lock(); defer { lock.unlock() }
  mode = value; consumed = []; count = 0
 }
 static var requests: Int { lock.lock(); defer { lock.unlock() }; return count }
 private var stopped = false
 private let stateLock = NSLock()
 override class func canInit(with request: URLRequest) -> Bool { true }
 override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
 override func startLoading() {
  guard request.url?.host == "crm.example.invalid" else { client?.urlProtocol(self,didFailWithError:URLError(.unsupportedURL));return }
  let object = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String:String]
  let token = object?["refresh_token"] ?? "missing"
  Self.lock.lock()
  Self.count += 1
  let ordinal = Self.count
  let accepted = Self.consumed.insert(token).inserted
  let mode = Self.mode
  Self.lock.unlock()
  let fixture:(Int,String,[String:String])?
  switch mode {
  case .forbidden: fixture=(403,"{\"success\":false,\"message\":\"Permission removed\"}",[:])
  case .invalidGrant: fixture=(401,"{\"success\":false,\"error\":\"invalid_grant\"}",[:])
  case .proxyUnauthorized: fixture=(401,"<html>Proxy authentication unavailable</html>",[:])
  case .rateLimited: fixture=(429,"{}",["Retry-After":"60"])
  case .unavailable: fixture=(503,"{}",["Retry-After":"30"])
  case .malformed: fixture=(200,"invalid json",[:])
  default: fixture=nil
  }
  if let (status,body,headers)=fixture {
   client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:nil,headerFields:headers)!,cacheStoragePolicy:.notAllowed)
   client?.urlProtocol(self,didLoad:Data(body.utf8));client?.urlProtocolDidFinishLoading(self);return
  }
  if accepted && mode == .loseResponse {
   client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost)); return
  }
  let completion = { [self] in
   stateLock.lock(); let ignore = stopped; stateLock.unlock()
   guard !ignore else { return }
   let response = HTTPURLResponse(url: request.url!, statusCode: accepted ? 200 : 401,
       httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!
   let body: [String:Any] = accepted
    ? ["success":true,"message":"Token refreshed","data":["tokens":["access_token":"access-\(ordinal)","refresh_token":"refresh-\(ordinal)"]]]
    : ["success":false,"message":"Invalid or expired refresh token"]
   client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
   client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
   client?.urlProtocolDidFinishLoading(self)
  }
  if accepted && mode == .delayResponse { DispatchQueue.global().asyncAfter(deadline:.now()+0.3, execute:completion) }
  else { completion() }
 }
 override func stopLoading() { stateLock.lock(); stopped=true; stateLock.unlock() }
}

enum AuditTransport {
 static let session: URLSession = {
  let config = URLSessionConfiguration.ephemeral
  config.protocolClasses = [RotationProtocol.self]
  return URLSession(configuration: config)
 }()
}

@main struct SwiftPatchTests {
 static var results: [[String:Any]] = []
 static func check(_ name:String,_ passed:Bool) throws {
  results.append(["scenario":name,"passed":passed])
  if !passed { throw NSError(domain:name,code:1) }
 }
 static func fresh(_ identity:String = "sip:a@fixture.example.invalid") throws -> Mango9Session {
  let s=Mango9Session(crmId:"fixture",crmBaseUrl:"https://crm.example.invalid",crmApiBaseUrl:"https://crm.example.invalid/api",
   userId:"1",parentClientId:"1",role:"client",loginId:"test@example.invalid",displayName:nil,
   accessToken:"old-access-"+UUID().uuidString,refreshToken:"old-refresh-"+UUID().uuidString,smsChatApi:"https://chat.example.invalid",connectWebsocket:"wss://chat.example.invalid",
   enrollmentExpiresAt:.distantFuture,sipIdentity:identity)
  return try Mango9SessionStore.save(s,persist:true,makeActive:true)
 }
 static func waitStarted() async throws {
  for _ in 0..<2000 { if RotationProtocol.requests>0 { return }; try await Task.sleep(nanoseconds:1_000_000) }
  throw NSError(domain:"request never started",code:1)
 }
 static func attempt(_ s:Mango9Session) async -> Bool {
  do { _ = try await Mango9CRMAPI.refresh(session:s); return true } catch { return false }
 }
 static func main() async throws {
  AuditDefaults.shared.set(true,forKey:"mango9_remember_login")
  defer {
   AuditDefaults.shared.removePersistentDomain(forName:"com.mango9.lifecycle-audit."+AuditDefaults.runID)
   for suffix in [".crm-session", ".logout-cleanup"] {
    SecItemDelete([kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:Mango9Configuration.bundleIdentifier+suffix] as CFDictionary)
   }
  }
  RotationProtocol.reset(.delayResponse); let original=try fresh()
  async let a=Mango9CRMAPI.refresh(session:original); async let b=Mango9CRMAPI.refresh(session:original)
  let pair=try await [a,b]
  try check("same_login_single_flight",RotationProtocol.requests==1 && pair[0].refreshToken==pair[1].refreshToken)
  try check("coordinator_persists_before_return",Mango9SessionStore.load()?.refreshToken==pair[0].refreshToken)

  RotationProtocol.reset(.delayResponse); let cancelled=try fresh()
  let waiter=Task { try await Mango9CRMAPI.refresh(session:cancelled) }
  try await waitStarted(); waiter.cancel(); let renewed=try await waiter.value
  try check("cancelled_waiter_does_not_discard_rotation",Mango9SessionStore.load()?.refreshToken==renewed.refreshToken)

  RotationProtocol.reset(.delayResponse); let switched=try fresh()
  let switchTask=Task { try await Mango9CRMAPI.refresh(session:switched) }
  try await waitStarted(); let other=try fresh("sip:b@fixture.example.invalid")
  _ = try await switchTask.value
  try check("account_switch_preserves_original_renewal",Mango9SessionStore.load(for:switched.sipIdentity!)?.refreshToken != switched.refreshToken && Mango9SessionStore.activeIdentity==other.sipIdentity)

  RotationProtocol.reset(.delayResponse); let removed=try fresh()
  let removal=Task { await attempt(removed) }; try await waitStarted()
  try Mango9SessionStore.remove(for:removed.sipIdentity!); let removeAccepted=await removal.value
  try check("late_response_cannot_resurrect_logout",!removeAccepted && Mango9SessionStore.load(for:removed.sipIdentity!)==nil)

  RotationProtocol.reset(.delayResponse); let previous=try fresh()
  let relogin=Task { await attempt(previous) }; try await waitStarted(); let next=try fresh()
  let oldAccepted=await relogin.value
  try check("late_response_cannot_overwrite_new_login",!oldAccepted && Mango9SessionStore.load()?.loginGeneration==next.loginGeneration && Mango9SessionStore.load()?.refreshToken==next.refreshToken)

  RotationProtocol.reset(); let cleanup=try fresh(); let lease=LeaseHarness()
  let record=Mango9LogoutRecord(identity:cleanup.sipIdentity!,voipToken:"fixture",provider:"fixture",topic:"fixture")
  let capability=try await lease.authorizeLease(record,session:cleanup)
  try check("cleanup_lease_uses_durable_renewal",capability=="test-capability" && Mango9SessionStore.load()?.refreshToken != cleanup.refreshToken)

  RotationProtocol.reset(.loseResponse); let unavailable=try fresh()
  _ = await attempt(unavailable); _ = await attempt(unavailable)
  try check("network_failure_keeps_credentials_and_cools_retries",Mango9SessionStore.load()?.refreshToken==unavailable.refreshToken && RotationProtocol.requests==1)
  RotationProtocol.reset(); let locked = try fresh()
  AuditSecurity.failure = errSecInteractionNotAllowed
  let saved = await attempt(locked)
  AuditSecurity.failure = nil
  try check("failed_keychain_write_does_not_publish_tokens",!saved && Mango9SessionStore.load()?.refreshToken==locked.refreshToken)
  AuditSecurity.failure = errSecInteractionNotAllowed
  var removedDespiteFailure = false
  do { try Mango9SessionStore.remove(for:locked.sipIdentity!); removedDespiteFailure=true } catch {}
  AuditSecurity.failure = nil
  try check("failed_logout_tombstone_retains_login",!removedDespiteFailure && Mango9SessionStore.load()?.loginGeneration==locked.loginGeneration)
  try Mango9SessionStore.remove(for:locked.sipIdentity!)
  try check("durable_tombstone_blocks_saved_credentials",Mango9SessionStore.load(for:locked.sipIdentity!)==nil)
  try? Mango9SessionStore.remove(for:"sip:a@fixture.example.invalid")
  try? Mango9SessionStore.remove(for:"sip:b@fixture.example.invalid")
  try await httpFailureChecks()
  try await storageChecks()
  try await cleanupChecks()
  try await socialCacheChecks()
  print(String(data:try JSONSerialization.data(withJSONObject:results,options:[.prettyPrinted,.sortedKeys]),encoding:.utf8)!)
 }
}


extension SwiftPatchTests {
 @MainActor static func socialCacheChecks() async throws {
  let original = try fresh()
  let cache = Mango9SocialChatCache()
  let first = cache.store(session: original)
  RotationProtocol.reset()
  let renewed = try await Mango9CRMAPI.refresh(session: original)
  try check("social_cache_preserves_same_login_after_renewal",
      cache.store(session: renewed) === first && Mango9SessionStore.isActive(first.session))
  let replacement = try fresh()
  let current = cache.store(session: replacement)
  try check("social_cache_replaces_same_account_after_new_login",
      current !== first && first.cleared && Mango9SessionStore.isActive(current.session))
  try check("social_cache_keeps_previous_login_fenced", !Mango9SessionStore.isActive(first.session))
  let other = try fresh("sip:b@fixture.example.invalid")
  let switched = cache.store(session: other)
  try check("social_cache_clears_after_account_switch", switched !== current && current.cleared)
 }
}
