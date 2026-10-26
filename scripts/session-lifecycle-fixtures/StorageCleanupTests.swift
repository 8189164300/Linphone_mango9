/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation
import Security

extension SwiftPatchTests {
 static func altered(_ session:Mango9Session, _ values:[String:Any]) throws -> Mango9Session {
  var json=try JSONSerialization.jsonObject(with:JSONEncoder().encode(session)) as! [String:Any]
  values.forEach { json[$0.key]=$0.value }
  return try JSONDecoder().decode(Mango9Session.self,from:JSONSerialization.data(withJSONObject:json))
 }
 static func rawRecord(_ account:String,_ data:Data) throws {
  let key:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:Mango9Configuration.bundleIdentifier+".crm-session",kSecAttrAccount as String:account]
  let attrs=[kSecValueData as String:data]
  var status=SecItemUpdate(key as CFDictionary,attrs as CFDictionary)
  if status==errSecItemNotFound { var item=key;attrs.forEach{item[$0.key]=$0.value};status=SecItemAdd(item as CFDictionary,nil) }
  guard status==errSecSuccess else { throw NSError(domain:"fixture keychain",code:Int(status)) }
 }
 static func httpFailureChecks() async throws {
  for mode in [RotationProtocol.Mode.forbidden,.invalidGrant,.proxyUnauthorized,.rateLimited,.unavailable,.malformed] {
   RotationProtocol.reset(mode);let session=try fresh()
   var failure:Error?
   do { _ = try await Mango9CRMAPI.refresh(session:session) } catch { failure=error }
   var second:Error?
   do { _ = try await Mango9CRMAPI.refresh(session:session) } catch { second=error }
   let retained=Mango9SessionStore.load()?.refreshToken==session.refreshToken
   switch mode {
   case .forbidden:
    var permission=false;if let failure, case Mango9CRMAPIError.permissionDenied = failure { permission=true }
    try check("permission_failure_does_not_expire_login",permission && retained && RotationProtocol.requests==1)
   case .invalidGrant:
    var expired=false;if let second, case Mango9CRMAPIError.sessionExpired = second { expired=true }
    try check("invalid_grant_stops_repeat_refresh_requests",expired && RotationProtocol.requests==1)
    var gated=false
    do { _ = try await Mango9CRMAPI.dashboard(session:session) } catch Mango9CRMAPIError.sessionExpired { gated=true }
    try check("invalid_grant_stops_subsequent_resource_polling",gated && RotationProtocol.requests==1)
   case .proxyUnauthorized:
    var temporary=false;if let failure, case Mango9CRMAPIError.server = failure { temporary=true }
    try check("proxy_401_does_not_expire_login",temporary && retained && RotationProtocol.requests==1)
   case .rateLimited,.unavailable:
    var honorsDelay=false;if let second, case Mango9CRMAPIError.retryAfter(let wait) = second { honorsDelay=wait>25 }
    try check(mode == .rateLimited ? "rate_limit_honors_retry_after" : "service_outage_honors_retry_after",honorsDelay && retained && RotationProtocol.requests==1)
   case .malformed:
    try check("malformed_response_preserves_login",failure != nil && retained && RotationProtocol.requests==1)
   default: break
   }
  }
 }
 static func storageChecks() async throws {
  try Mango9LogoutKeychain.save([])
  let old=try fresh(); Mango9SessionStore.auditResetMemory()
  AuditSecurity.failure=errSecInteractionNotAllowed
  let locked=Mango9SessionStore.load(for:old.sipIdentity!);let reported=Mango9SessionStore.lastStorageError != nil
  AuditSecurity.failure=nil
  try check("locked_keychain_is_temporary_after_restart",locked==nil && reported && Mango9SessionStore.load(for:old.sipIdentity!)?.refreshToken==old.refreshToken)
  try Mango9SessionStore.remove(for:old.sipIdentity!);Mango9SessionStore.auditResetMemory()
  try rawRecord("current",JSONEncoder().encode(old))
  try check("tombstone_defeats_legacy_fallback_after_restart",Mango9SessionStore.load(for:old.sipIdentity!)==nil)
  let corrupt=try fresh();Mango9SessionStore.auditResetMemory()
  try rawRecord(corrupt.sipIdentity!,Data("invalid-json".utf8))
  let rejected=Mango9SessionStore.load(for:corrupt.sipIdentity!)==nil
  try check("corrupt_record_does_not_fall_back_to_legacy",rejected && Mango9SessionStore.lastStorageError != nil)
  let legacyIdentity="sip:legacy@fixture.example.invalid"
  let legacy=try altered(old,["sipIdentity":legacyIdentity])
  try rawRecord("current",JSONEncoder().encode(legacy));Mango9SessionStore.auditResetMemory()
  try check("known_identity_legacy_record_migrates",Mango9SessionStore.load(for:legacyIdentity)?.refreshToken==legacy.refreshToken)
  var unknown=try JSONSerialization.jsonObject(with:JSONEncoder().encode(old)) as! [String:Any]
  unknown.removeValue(forKey:"sipIdentity")
  try rawRecord("current",JSONSerialization.data(withJSONObject:unknown));Mango9SessionStore.auditResetMemory()
  try check("unbound_legacy_record_cannot_attach_to_another_account",Mango9SessionStore.load(for:"sip:other@fixture.example.invalid")==nil)
  let current=try fresh()
  let foreign=try altered(current,["crmApiBaseUrl":"https://other.example.invalid/api","refreshToken":"foreign-refresh"])
  var accepted=false
  do { _ = try Mango9SessionStore.replaceTokens(expected:current,renewed:foreign);accepted=true } catch {}
  try check("renewal_cannot_change_origin",!accepted && Mango9SessionStore.load()?.refreshToken==current.refreshToken)
  let unsaved=try altered(current,["accessToken":"unsaved-access","refreshToken":"unsaved-refresh"])
  RotationProtocol.reset()
  let recovered=try await Mango9CRMAPI.refresh(session:unsaved)
  try check("unsaved_successor_is_not_used_for_renewal",recovered.refreshToken==current.refreshToken && RotationProtocol.requests==0)
  _ = try Mango9SessionStore.save(current,persist:false,makeActive:true);Mango9SessionStore.auditResetMemory()
  try check("remember_disabled_does_not_restore_credentials",Mango9SessionStore.load(for:current.sipIdentity!)==nil)
 }

 @MainActor static func cleanupChecks() async throws {
  try Mango9LogoutKeychain.save([])
  let token=String(repeating:"a",count:64)
  let session=try altered(fresh(),["logoutToken":"crm-old"])
  let identity=session.sipIdentity!
  var initial=Mango9LogoutRecord(identity:identity,voipToken:token,provider:"fixture",topic:"fixture-topic")
  initial.cleanupToken="voip-old";initial.crmCleanupToken="crm-old";initial.crmApiBaseURL=session.crmApiBaseUrl
  try Mango9LogoutKeychain.save([initial])
  var sent:[URLRequest]=[]
  var unavailable=true
  var exists=false
  let coordinator=Mango9LogoutCoordinator(send:{ req in
   sent.append(req)
   if req.url!.path.hasSuffix("push-lease") {
    let body=try JSONSerialization.jsonObject(with:req.httpBody!) as! [String:String]
    return (try JSONSerialization.data(withJSONObject:["success":true,"data":["cleanup_token":"voip-new","generation":body["generation"]!]]),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)
   }
   if unavailable { throw URLError(.notConnectedToInternet) }
   return (Data("{\"success\":true,\"data\":{\"completed\":true}}".utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)
  },accountExists:{ _ in exists })
  try await coordinator.prepareLogout(identity:identity,token:token,session:session)
  try check("offline_logout_uses_saved_capability_without_refresh",sent.isEmpty && (try Mango9LogoutKeychain.load()).allSatisfy(\.pendingLogout))
  Mango9SessionStore.auditResetMemory()
  try check("durable_logout_intent_blocks_restart_before_tombstone",Mango9SessionStore.load(for:identity)==nil)
  var restored=false
  do { try await coordinator.prepareLogin(identity:identity,token:token,session:session);restored=true } catch {}
  try check("late_restore_cannot_supersede_logout_intent",!restored && sent.isEmpty)
  let next=try altered(session,["logoutToken":"crm-new","accessToken":"new-login-access","refreshToken":"new-login-refresh"])
  var unsafeLogin=false
  do { try await coordinator.prepareLogin(identity:identity,token:nil,session:next,freshAuthentication:true);unsafeLogin=true } catch {}
  try check("fresh_login_requires_device_scope_when_cleanup_is_pending",!unsafeLogin && Mango9SessionStore.load(for:identity)==nil)
  try await coordinator.prepareLogin(identity:identity,token:token,session:next,freshAuthentication:true)
  let saved=try Mango9SessionStore.save(next,persist:true,makeActive:true)
  let records=try Mango9LogoutKeychain.load()
  try check("fresh_login_can_supersede_failed_remote_cleanup",records.count==2 && records.filter(\.pendingLogout).count==1 && Mango9SessionStore.load()?.loginGeneration==saved.loginGeneration)
  try check("old_intent_cannot_remove_fresh_login_on_restart",!Mango9LogoutCoordinator.needsLocalRemoval(identity:identity,records:records))
  exists=true;unavailable=false;sent=[]
  await coordinator.retryPending()
  let kept=try Mango9LogoutKeychain.load()
  let crmRequest=sent.first{ $0.url!.path.hasSuffix("auth/logout") }
  let oldBody=crmRequest.flatMap{ try? JSONSerialization.jsonObject(with:$0.httpBody!) as? [String:String] }
  try check("late_cleanup_only_revokes_original_login",kept.count==1 && kept[0].crmCleanupToken=="crm-new" && oldBody?["cleanup_token"]=="crm-old" && Mango9SessionStore.load()?.loginGeneration==saved.loginGeneration)
  let failWrite=Mango9LogoutCoordinator(write:{ _ in throw Mango9LogoutError.secureStorage },send:{ _ in throw URLError(.notConnectedToInternet) },accountExists:{ _ in true })
  var ignoredFailure=false
  do { try await failWrite.prepareLogout(identity:identity,token:token,session:saved);ignoredFailure=true } catch {}
  try check("failed_logout_intent_keeps_active_account",!ignoredFailure && !(try Mango9LogoutKeychain.load())[0].pendingLogout && Mango9SessionStore.load()?.loginGeneration==saved.loginGeneration)
  var older=initial;older.pendingLogout=true
  try Mango9LogoutKeychain.save([older]+kept)
  try await coordinator.prepareLogout(identity:identity,token:token,session:saved)
  let allPending=try Mango9LogoutKeychain.load()
  try check("second_logout_preserves_older_family_capability",allPending.first(where:{$0.id==older.id})?.crmCleanupToken=="crm-old" && allPending.allSatisfy(\.pendingLogout))
  exists=false;sent=[];AuditSecurity.failure=errSecInteractionNotAllowed
  await coordinator.retryPending()
  AuditSecurity.failure=nil
  try check("cleanup_cannot_drop_intent_when_tombstone_storage_fails",sent.isEmpty && (try Mango9LogoutKeychain.load()).contains(where:{$0.pendingLogout}))
  var independent=initial;independent.pendingLogout=true
  try Mango9LogoutKeychain.save([independent])
  var voiceCleanups=0
  let separateServices=Mango9LogoutCoordinator(send:{ req in
   if req.url!.path.hasSuffix("auth/logout") { throw URLError(.cannotConnectToHost) }
   voiceCleanups += 1
   return (Data("{\"success\":true,\"data\":{\"completed\":true}}".utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)
  },accountExists:{ _ in false })
  await separateServices.retryPending()
  try check("crm_outage_does_not_block_voice_cleanup",voiceCleanups==1 && (try Mango9LogoutKeychain.load()).contains(where:{$0.pendingLogout}))
  try Mango9LogoutKeychain.save([])
 }
}
