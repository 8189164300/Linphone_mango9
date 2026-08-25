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

import SwiftUI
import linphonesw
import UserNotifications
import Intents
import PushKit

let accountTokenNotification = Notification.Name("AccountCreationTokenReceived")
var displayedChatroomPeerAddr: String?

extension Notification.Name {
	static let mango9LeadDidChange = Notification.Name("mango9LeadDidChange")
	static let mango9ClientDidChange = Notification.Name("mango9ClientDidChange")
	static let mango9OpenLead = Notification.Name("mango9OpenLead")
	static let mango9OpenChat = Notification.Name("mango9OpenChat")
	static let mango9OpenSMS = Notification.Name("mango9OpenSMS")
	static let mango9AccountContextChanged =
		Notification.Name("mango9AccountContextChanged")
	static let mango9LineIdentityChanged =
		Notification.Name("mango9LineIdentityChanged")
}

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
	
	var launchNotificationCallId: String?
	var launchNotificationPeerAddr: String?
	var launchNotificationLocalAddr: String?
	var launchMango9LeadId: Int?
	var launchMango9SMSTarget: Mango9SMSTarget?
	var launchMango9ChatTarget: Mango9ChatTarget?
	
	var coreContext: CoreContext? {
		didSet {
			forwardPendingRemotePushToken()
		}
	}
 	var navigationManager: NavigationManager?
	private var pendingRemotePushToken: String?
	
	func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
		let tokenStr = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
		Log.info("Received remote push token")
		UserDefaults.standard.set(
			tokenStr,
			forKey: Mango9ChatStore.remotePushTokenDefaultsKey
		)
		Task { @MainActor in
			await Mango9ChatStore.shared.registerRemotePushTokenIfAvailable()
		}
		pendingRemotePushToken = tokenStr + ":remote"
		forwardPendingRemotePushToken()
	}

	private func forwardPendingRemotePushToken() {
		guard let coreContext, let token = pendingRemotePushToken else { return }
		pendingRemotePushToken = nil
		coreContext.doOnCoreQueue { core in
			Log.info("Forwarding remote push token to core")
			core.didRegisterForRemotePushWithStringifiedToken(deviceTokenStr: token)
		}
	}
	
	func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
		Log.error("Failed to register for push notifications : \(error.localizedDescription)")
	}
	
	func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
		Log.info("Received background push notification, payload = \(userInfo.description)")
		if mango9ChatTarget(from: userInfo) != nil {
			Task { @MainActor in
				await Mango9ChatStore.shared.refreshDirectory()
			}
			completionHandler(.newData)
			return
		}
		if mango9SMSTarget(from: userInfo) != nil {
			Task { @MainActor in
				await Mango9ChatStore.shared.refreshSMSDirectory()
			}
			completionHandler(.newData)
			return
		}
		if let leadId = mango9LeadId(from: userInfo) {
			NotificationCenter.default.post(name: .mango9LeadDidChange, object: leadId)
			completionHandler(.newData)
			return
		}
		let creationToken = (userInfo["customPayload"] as? NSDictionary)?["token"] as? String
		if let creationToken = creationToken {
			NotificationCenter.default.post(name: accountTokenNotification, object: nil, userInfo: ["token": creationToken])
		}
		
		completionHandler(UIBackgroundFetchResult.newData)
	}
					 
	func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
		// Set up notifications
		let notificationCenter = UNUserNotificationCenter.current()
		notificationCenter.delegate = self
		notificationCenter.getNotificationSettings { settings in
			switch settings.authorizationStatus {
			case .notDetermined:
				notificationCenter.requestAuthorization(options: [.alert, .sound, .badge]) {
					granted, error in
					if let error {
						Log.error("Failed to request notification permission: \(error.localizedDescription)")
					}
					guard granted else {
						Log.warn("Mango9 notification permission was not granted")
						return
					}
					DispatchQueue.main.async {
						application.registerForRemoteNotifications()
					}
				}
			case .authorized, .provisional, .ephemeral:
				DispatchQueue.main.async {
					application.registerForRemoteNotifications()
				}
			case .denied:
				Log.warn("Mango9 notifications are disabled in iOS Settings")
			@unknown default:
				Log.warn("Unknown Mango9 notification authorization status")
			}
		}

		return true
	}
	
	// Called when the user interacts with the notification
	func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
		let userInfo = response.notification.request.content.userInfo
		activateMango9AccountIfNeeded(from: userInfo)

		if let target = mango9ChatTarget(from: userInfo) {
			if navigationManager == nil {
				launchMango9ChatTarget = target
			} else {
				NotificationCenter.default.post(name: .mango9OpenChat, object: target)
			}
			completionHandler()
			return
		}

		if let target = mango9SMSTarget(from: userInfo) {
			if navigationManager == nil {
				launchMango9SMSTarget = target
			} else {
				NotificationCenter.default.post(name: .mango9OpenSMS, object: target)
			}
			completionHandler()
			return
		}

		if let leadId = mango9LeadId(from: userInfo) {
			if navigationManager == nil {
				launchMango9LeadId = leadId
			} else {
				NotificationCenter.default.post(name: .mango9OpenLead, object: leadId)
			}
			completionHandler()
			return
		}
		
		if let callId = userInfo["CallId"] as? String, let peerAddr = userInfo["peer_addr"] as? String, let localAddr = userInfo["local_addr"] as? String {
			if self.navigationManager != nil {
				self.navigationManager!.selectedCallId = callId
				self.navigationManager!.peerAddr = peerAddr
				self.navigationManager!.localAddr = localAddr
			} else {
				launchNotificationCallId = callId
				launchNotificationPeerAddr = peerAddr
				launchNotificationLocalAddr = localAddr
			}
		}
		
		completionHandler()
	}
	
	// Display notifications on foreground
	func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
		let userInfo = notification.request.content.userInfo
		Log.info("Received push notification in foreground, payload= \(userInfo)")

		if mango9ChatTarget(from: userInfo) != nil {
			Task { @MainActor in
				await Mango9ChatStore.shared.refreshDirectory()
			}
			completionHandler([.banner, .sound, .badge])
			return
		}

		if mango9SMSTarget(from: userInfo) != nil {
			Task { @MainActor in
				await Mango9ChatStore.shared.refreshSMSDirectory()
			}
			completionHandler([.banner, .sound, .badge])
			return
		}

		if let leadId = mango9LeadId(from: userInfo) {
			NotificationCenter.default.post(name: .mango9LeadDidChange, object: leadId)
			completionHandler([.banner, .sound])
			return
		}
		
		let strPeerAddr = userInfo["peer_addr"] as? String
		if strPeerAddr == nil {
			completionHandler([.banner, .sound])
		} else {
			// Only display notification if we're not in the chatroom they come from
			if displayedChatroomPeerAddr != strPeerAddr {
				if let coreContext = coreContext {
					coreContext.doOnCoreQueue { core in
						let nilParams: ConferenceParams? = nil
						if 	let peerAddr = try? Factory.Instance.createAddress(addr: strPeerAddr!)
								, let chatroom = core.searchChatRoom(params: nilParams, localAddr: nil, remoteAddr: peerAddr, participants: nil), chatroom.muted {
							Log.info("message comes from a muted chatroom, ignore it")
							return
						}
						completionHandler([.banner, .sound])
					}
				}
			}
		}
	}

	private func mango9LeadId(from userInfo: [AnyHashable: Any]) -> Int? {
		let nested = userInfo["mango9"] as? [String: Any]
		let event = (nested?["event"] as? String)
			?? (userInfo["mango9_event"] as? String)
			?? (userInfo["event"] as? String)
		guard event == "lead.created" || event == "lead.assigned" else {
			return nil
		}

		let rawLeadId = nested?["lead_id"]
			?? userInfo["lead_id"]
			?? userInfo["leadId"]
		if let leadId = rawLeadId as? Int {
			return leadId
		}
		if let leadId = rawLeadId as? NSNumber {
			return leadId.intValue
		}
		if let leadId = rawLeadId as? String {
			return Int(leadId)
		}
		return nil
	}

	private func mango9SMSTarget(
		from userInfo: [AnyHashable: Any]
	) -> Mango9SMSTarget? {
		let nested = userInfo["mango9"] as? [String: Any]
		let event = (nested?["event"] as? String)
			?? (userInfo["mango9_event"] as? String)
			?? (userInfo["event"] as? String)
		guard event == "sms.received" else {
			return nil
		}

		let rawPhone = (nested?["phone"] as? String)
			?? (userInfo["phone"] as? String)
			?? ""
		let digits = rawPhone.filter(\.isNumber)
		guard digits.count >= 10 else {
			return nil
		}
		let phone = digits.count == 10 ? "1\(digits)" : digits
		let rawName = (nested?["name"] as? String)
			?? (userInfo["name"] as? String)
		let name = rawName?
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let displayName = (name?.isEmpty == false) ? name! : phone
		return Mango9SMSTarget(phone: phone, name: displayName)
	}

	private func mango9ChatTarget(
		from userInfo: [AnyHashable: Any]
	) -> Mango9ChatTarget? {
		let nested = userInfo["mango9"] as? [String: Any]
		let event = (nested?["event"] as? String)
			?? (userInfo["mango9_event"] as? String)
			?? (userInfo["event"] as? String)
		guard event == "chat.message" else {
			return nil
		}

		let rawUserId = nested?["sender_user_id"]
			?? userInfo["sender_user_id"]
			?? userInfo["senderUserId"]
		let userId: Int
		if let value = rawUserId as? Int {
			userId = value
		} else if let value = rawUserId as? NSNumber {
			userId = value.intValue
		} else if let value = rawUserId as? String {
			userId = Int(value) ?? 0
		} else {
			userId = 0
		}

		let roomId = ((nested?["room_id"] as? String)
			?? (userInfo["room_id"] as? String)
			?? (userInfo["roomId"] as? String))?
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let rawName = (nested?["name"] as? String)
			?? (userInfo["name"] as? String)
		let name = rawName?
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let displayName = (name?.isEmpty == false) ? name! : "Team Chat"

		guard userId > 0 || roomId?.isEmpty == false else {
			return nil
		}
		return Mango9ChatTarget(
			userId: userId,
			name: displayName,
			roomId: roomId?.isEmpty == false ? roomId : nil
		)
	}

	private func activateMango9AccountIfNeeded(
		from userInfo: [AnyHashable: Any]
	) {
		let nested = userInfo["mango9"] as? [String: Any]
		let pushedIdentity = (nested?["sip_identity"] as? String)
			?? (nested?["sipIdentity"] as? String)
			?? (userInfo["sip_identity"] as? String)
			?? (userInfo["sipIdentity"] as? String)
		let crmId = (nested?["crm_id"] as? String)
			?? (nested?["crmId"] as? String)
			?? (userInfo["crm_id"] as? String)
			?? (userInfo["crmId"] as? String)
		let identity = Mango9SessionStore.normalizedIdentity(pushedIdentity)
			?? crmId.flatMap(Mango9SessionStore.identity(forCRMId:))
		guard let identity,
		      Mango9SessionStore.hasSession(for: identity) else {
			Log.info("Ignoring Mango9 message push for an account not stored on this device")
			return
		}

		Mango9SessionStore.activate(sipIdentity: identity)
		CoreContext.shared.doOnCoreQueue { core in
			guard let account = core.accountList.first(where: {
				Mango9SessionStore.normalizedIdentity(
					$0.params?.identityAddress?.asStringUriOnly()
				) == identity
			}) else {
				return
			}
			core.defaultAccount = account
		}
	}
	
	func application(_ application: UIApplication,
					 continue userActivity: NSUserActivity,
					 restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
		
		guard let interaction = userActivity.interaction,
			  let intent = interaction.intent as? INStartCallIntent,
			  let person = intent.contacts?.first,
			  let number = person.personHandle?.value else { return false }

		let isVideo = intent.callCapability == .videoCall
		
		Log.info("[AppDelegate][INStartCallIntent] Generic call intent received for number: \(number) isVideo: \(isVideo)")

		CoreContext.shared.doOnCoreQueue { core in
			if let address = core.interpretUrl(url: number, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) {
				TelecomManager.shared.doCallOrJoinConf(address: address, isVideo: isVideo)
			}
		}
		
		return true
	}
	
	func applicationWillTerminate(_ application: UIApplication) {
		Log.info("IOS applicationWillTerminate")
		if let coreContext = coreContext {
			coreContext.doOnCoreQueue(synchronous: true) { core in
				Log.info("applicationWillTerminate - Stopping linphone core")
				MagicSearchSingleton.shared.destroyMagicSearch()
				if core.globalState != GlobalState.Off {
					core.stop()
				} else {
					Log.info("applicationWillTerminate - Core already stopped")
				}
			}
		}
	}
}

@main
struct LinphoneApp: App {
	@UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

	@State private var configAvailable = AppServices.configIfAvailable != nil
	private let earlyPushDelegate = EarlyPushkitDelegate()
	private let voipRegistry = PKPushRegistry(queue: coreQueue)

	init() {
#if DEBUG
		LinphoneApp.applyUITestMDMConfigIfNeeded()
#endif
		if !configAvailable {
			voipRegistry.delegate = earlyPushDelegate
			voipRegistry.desiredPushTypes = [.voIP]
			waitForConfig()
		} else {
			let _ = CoreContext.shared
		}
	}

#if DEBUG
	/// UI-test hook: reads `UITEST_MDM_CONFIG` (JSON) or `UITEST_MDM_CONFIG_CLEAR=1` from
	/// the launch environment and writes it to the managed config key before MDMManager runs.
	/// Only active in DEBUG builds.
	private static func applyUITestMDMConfigIfNeeded() {
		let env = ProcessInfo.processInfo.environment
		let key = "com.apple.configuration.managed"
		if env["UITEST_MDM_CONFIG_CLEAR"] == "1" {
			UserDefaults.standard.removeObject(forKey: key)
			return
		}
		guard let json = env["UITEST_MDM_CONFIG"],
			  let data = json.data(using: .utf8),
			  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
			return
		}
		UserDefaults.standard.set(dict, forKey: key)
	}
#endif

	var body: some Scene {
		WindowGroup {
			if configAvailable {
				AppView(delegate: delegate)
			} else {
				SplashScreen(showSpinner: true)
					.onAppear {
						waitForConfig()
				}
			}
		}
	}

	private func waitForConfig() {
		if AppServices.configIfAvailable != nil {
			let coreContext = CoreContext.shared
			earlyPushDelegate.handOff(to: coreContext)
			configAvailable = true
		} else {
			Log.warn("AppServices.config not available yet, retrying in 1s...")
			DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
				waitForConfig()
			}
		}
	}
}

struct AppView: View {
	@Environment(\.scenePhase) var scenePhase
	let delegate: AppDelegate

	@StateObject private var coreContext = CoreContext.shared
	@StateObject private var navigationManager = NavigationManager()
	@StateObject private var telecomManager = TelecomManager.shared
	@StateObject private var sharedMainViewModel = SharedMainViewModel.shared

	var body: some View {
		RootView(
			coreContext: coreContext,
			telecomManager: telecomManager,
			sharedMainViewModel: sharedMainViewModel,
			navigationManager: navigationManager,
			appDelegate: delegate
		)
		.environmentObject(coreContext)
		.environmentObject(navigationManager)
		.environmentObject(telecomManager)
		.environmentObject(sharedMainViewModel)
		.onChange(of: scenePhase) { newPhase in
			if !telecomManager.callInProgress {
				switch newPhase {
				case .active:
					Log.info("Entering foreground")
					coreContext.onEnterForeground()
				case .background:
					Log.info("Entering background")
					coreContext.onEnterBackground()
				default:
					break
				}
			}
		}
	}
}

struct RootView: View {
	@ObservedObject var coreContext: CoreContext
	@ObservedObject var telecomManager: TelecomManager
	@ObservedObject var sharedMainViewModel: SharedMainViewModel
	@ObservedObject var navigationManager: NavigationManager
	@State private var pendingURL: URL?
	let appDelegate: AppDelegate

	var body: some View {
		Group {
			if coreContext.coreHasStartedOnce {
				if showWelcome {
					ZStack {
						WelcomeView()
						ToastView().zIndex(3)
					}
					.onAppear {
						appDelegate.coreContext = coreContext
					}
				} else if showAssistant {
					ZStack {
						AssistantView()
						ToastView().zIndex(3)
					}
					.onAppear {
						appDelegate.coreContext = coreContext
					}
					
					if coreContext.coreIsStarted {
						   VStack {} // Force trigger .onAppear
							   .onAppear {
								   if let url = pendingURL {
									   URIHandler.handleURL(url: url)
									   pendingURL = nil
								   }
							   }
					   }
				} else {
					ZStack {
						MainViewSwitcher(
							coreContext: coreContext,
							navigationManager: navigationManager,
							sharedMainViewModel: sharedMainViewModel,
							pendingURL: $pendingURL,
							appDelegate: appDelegate
						)
						
						if coreContext.coreIsStarted {
							VStack {} // Force trigger .onAppear
								.onAppear {
									if let url = pendingURL {
										URIHandler.handleURL(url: url)
										pendingURL = nil
									}
								}
						}
					}
				}
			} else {
				SplashScreen()
			}
		}
		.onOpenURL { url in
			if SharedMainViewModel.shared.displayedConversation != nil && url.absoluteString.contains("mango9-message://") {
				SharedMainViewModel.shared.displayedConversation = nil
			}
			if coreContext.coreIsStarted {
				URIHandler.handleURL(url: url)
			} else {
				pendingURL = url
			}
		}
		.onContinueUserActivity("INStartCallIntent") { activity in
			guard let interaction = activity.interaction,
				  let intent = interaction.intent as? INStartCallIntent,
				  let person = intent.contacts?.first,
				  let number = person.personHandle?.value else { return }
			
			let isVideo = intent.callCapability == .videoCall
			
			Log.info("[INStartCallIntent] Generic call intent received for number: \(number) isVideo: \(isVideo)")
			
			coreContext.doOnCoreQueue { core in
				if let address = core.interpretUrl(url: number, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) {
					telecomManager.doCallOrJoinConf(address: address, isVideo: isVideo)
				}
			}
		}
	}
	
	
	var showWelcome: Bool {
		!sharedMainViewModel.welcomeViewDisplayed
	}

	var showAssistant: Bool {
		(coreContext.coreIsStarted && coreContext.accounts.isEmpty)
		|| sharedMainViewModel.displayProfileMode
	}
}

struct MainViewSwitcher: View {
	let coreContext: CoreContext
	let navigationManager: NavigationManager
	let sharedMainViewModel: SharedMainViewModel
	@Binding var pendingURL: URL?
	let appDelegate: AppDelegate
	@ObservedObject private var colors = ColorProvider.shared

	var body: some View {
		selectedMainView()
	}
	
	@ViewBuilder
	func selectedMainView() -> some View {
		ContentView()
			.onAppear {
				appDelegate.coreContext = coreContext
				appDelegate.navigationManager = navigationManager
				
				if let callId = appDelegate.launchNotificationCallId,
				   let peerAddr = appDelegate.launchNotificationPeerAddr,
				   let localAddr = appDelegate.launchNotificationLocalAddr {
					navigationManager.openChatRoom(callId: callId, peerAddr: peerAddr, localAddr: localAddr)
				}

				if let leadId = appDelegate.launchMango9LeadId {
					appDelegate.launchMango9LeadId = nil
					DispatchQueue.main.async {
						NotificationCenter.default.post(name: .mango9OpenLead, object: leadId)
					}
				}

				if let smsTarget = appDelegate.launchMango9SMSTarget {
					appDelegate.launchMango9SMSTarget = nil
					DispatchQueue.main.async {
						NotificationCenter.default.post(name: .mango9OpenSMS, object: smsTarget)
					}
				}

				if let chatTarget = appDelegate.launchMango9ChatTarget {
					appDelegate.launchMango9ChatTarget = nil
					DispatchQueue.main.async {
						NotificationCenter.default.post(name: .mango9OpenChat, object: chatTarget)
					}
				}
			}
			.id(colors.theme.name)
	}
}
