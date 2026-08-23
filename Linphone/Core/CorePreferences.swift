/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of Linphone
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

import Foundation
import linphonesw

enum Mango9Configuration {
	static let provisioningHost = "provision.mango9.com"
	static let provisioningBaseURL = URL(string: "https://provision.mango9.com")!
	static let sipProxyHost = "proxy.mango9.com"
	static let sipProxyPort = 5061
	// iOS suspends SIP applications, so a short binding can expire before the
	// app is allowed to refresh it. OpenSIPS keeps this mobile contact locally
	// while AOR throttling maintains a separate one-hour PBX registration.
	static let mobileRegistrationExpires = 30 * 24 * 60 * 60
	// Omitting the explicit port allows RFC 3263 DNS/SRV resolution and
	// redundant OpenSIPS targets. TLS still uses the standard SIP port 5061.
	static let sipProxyURI = "sip:proxy.mango9.com;transport=tls"
	static let appleTeamID =
		(Bundle.main.object(forInfoDictionaryKey: "Mango9AppleTeamID") as? String)?
			.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
	static let bundleIdentifier = Bundle.main.bundleIdentifier ?? "com.mango9.phone"

	static var applePushProvider: String {
		applePushProvider(forAPSEnvironment: signedAPSEnvironment)
	}

	static func applePushProvider(forAPSEnvironment environment: String?) -> String {
		environment?.lowercased() == "development" ? "apns.dev" : "apns"
	}

	private static var signedAPSEnvironment: String? {
		Bundle.main.object(forInfoDictionaryKey: "Mango9APSEnvironment") as? String
	}

	static var applePushParam: String {
		guard !appleTeamID.isEmpty else { return "" }
		return "\(appleTeamID).\(bundleIdentifier).voip&remote"
	}

	static func configurePush(on params: AccountParams) {
		params.pushNotificationAllowed = true
		params.remotePushNotificationAllowed = true

		guard let pushConfig = params.pushNotificationConfig else { return }
		pushConfig.provider = applePushProvider
		pushConfig.teamId = appleTeamID
		pushConfig.bundleIdentifier = bundleIdentifier
		if !applePushParam.isEmpty {
			pushConfig.param = applePushParam
		}
	}

	static func verifiedProvisioningURL(from rawValue: String) -> URL? {
		guard var components = URLComponents(string: rawValue),
			  components.scheme?.lowercased() == "https",
			  components.host?.lowercased() == provisioningHost,
			  components.user == nil,
			  components.password == nil,
			  components.port == nil || components.port == 443 else {
			return nil
		}

		components.scheme = "https"
		components.host = provisioningHost
		components.fragment = nil
		return components.url
	}

	static func isOneTimeEnrollmentURL(_ rawValue: String?) -> Bool {
		guard let rawValue,
			  let url = verifiedProvisioningURL(from: rawValue) else {
			return false
		}
		return url.path.hasPrefix("/v1/enroll/")
	}
}

class CorePreferences: ObservableObject {
	
	private let config: Config
	
	init(config: Config) {
		self.config = config
	}
	
	var acceptEarlyMedia: Bool {
		get {
			config.getBool(section: "sip", key: "incoming_calls_early_media", defaultValue: false)
		}
		set {
			config.setBool(section: "sip", key: "incoming_calls_early_media", value: newValue)
		}
	}
	
	var allowOutgoingEarlyMedia: Bool {
		get {
			config.getBool(section: "sip", key: "real_early_media", defaultValue: false)
		}
		set {
			config.setBool(section: "sip", key: "real_early_media", value: newValue)
		}
	}
	
	var automaticallyStartCallRecording: Bool {
		get {
			config.getBool(section: "app", key: "auto_start_call_record", defaultValue: false)
		}
		set {
			config.setBool(section: "app", key: "auto_start_call_record", value: newValue)
		}
	}
	
	var changeMainColorAllowed: Bool {
		get {
			config.getBool(section: "ui", key: "change_main_color_allowed", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "change_main_color_allowed", value: newValue)
		}
	}
	
	var checkForUpdateServerUrl: String {
		get {
			let raw = config.getString(section: "misc", key: "version_check_url_root", defaultString: "")
			return safeString(raw, defaultValue: "")
		}
		set {
			config.setString(section: "misc", key: "version_check_url_root", value: newValue)
		}
	}
	
	var conditionsAndPrivacyPolicyAccepted: Bool {
		get {
			config.getBool(section: "app", key: "read_and_agree_terms_and_privacy", defaultValue: false)
		}
		set {
			config.setBool(section: "app", key: "read_and_agree_terms_and_privacy", value: newValue)
		}
	}
	
	var contactsFilter: String {
		get {
			let raw = config.getString(section: "ui", key: "contacts_filter", defaultString: "")
			return safeString(raw, defaultValue: "")
		}
		set {
			config.setString(section: "ui", key: "contacts_filter", value: newValue)
		}
	}
	
	var darkMode: Int {
		get {
			if !darkModeAllowed { return 0 }
			return config.getInt(section: "app", key: "dark_mode", defaultValue: -1)
		}
		set {
			config.setInt(section: "app", key: "dark_mode", value: newValue)
		}
	}
	
	var darkModeAllowed: Bool {
		get {
			config.getBool(section: "ui", key: "dark_mode_allowed", defaultValue: true)
		}
		set {
			config.setBool(section: "ui", key: "dark_mode_allowed", value: newValue)
		}
	}
	
	var defaultDomain: String {
		get {
			let raw = config.getString(section: "app", key: "default_domain", defaultString: Mango9Configuration.sipProxyHost)
			return safeString(raw, defaultValue: Mango9Configuration.sipProxyHost)
		}
		set {
			config.setString(section: "app", key: "default_domain", value: newValue)
		}
	}
	
	var defaultPass: String {
		get {
			config.getString(section: "app", key: "pass", defaultString: "")
		}
		set {
			config.setString(section: "app", key: "pass", value: newValue)
		}
	}
	
	var defaultUsername: String {
		get {
			config.getString(section: "app", key: "user", defaultString: "")
		}
		set {
			config.setString(section: "app", key: "user", value: newValue)
		}
	}
	
	var deviceName: String {
		get {
			let raw = config.getString(section: "app", key: "device", defaultString: "").trimmingCharacters(in: .whitespaces)
			return safeString(raw, defaultValue: "")
		}
		set {
			config.setString(section: "app", key: "device", value: newValue.trimmingCharacters(in: .whitespaces))
		}
	}
	
	var hideContactEdition: Bool {
		get {
			config.getBool(section: "ui", key: "hide_contact_edition", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "hide_contact_edition", value: newValue)
		}
	}

	var disableAddContact: Bool {
		get {
			config.getBool(section: "ui", key: "disable_add_contact", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "disable_add_contact", value: newValue)
		}
	}
	
	var disableCallRecordings: Bool {
		get {
			config.getBool(section: "ui", key: "disable_call_recordings_feature", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "disable_call_recordings_feature", value: newValue)
		}
	}
	
	var disableChatFeature: Bool {
		get {
			config.getBool(section: "ui", key: "disable_chat_feature", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "disable_chat_feature", value: newValue)
		}
	}
	
	var disableMeetings: Bool {
		get {
			config.getBool(section: "ui", key: "disable_meetings_feature", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "disable_meetings_feature", value: newValue)
		}
	}
	
	var earlymediaContentExtCatIdentifier: String {
		get {
			config.getString(section: "app", key: "extension_category", defaultString: "")
		}
		set {
			config.setString(section: "app", key: "extension_category", value: newValue)
		}
	}
	
	var enableSecureMode: Bool {
		get {
			config.getBool(section: "ui", key: "enable_secure_mode", defaultValue: true)
		}
		set {
			config.setBool(section: "ui", key: "enable_secure_mode", value: newValue)
		}
	}
	
	var firstLaunch: Bool {
		get {
			config.getBool(section: "app", key: "first_6.0_launch", defaultValue: true)
		}
		set {
			config.setBool(section: "app", key: "first_6.0_launch", value: newValue)
		}
	}
	
	var friendListInWhichStoreNewlyCreatedFriends: String {
		get {
			config.getString(section: "app", key: "friend_list_to_store_newly_created_contacts", defaultString: "Linphone address-book")
		}
		set {
			config.setString(section: "app", key: "friend_list_to_store_newly_created_contacts", value: newValue)
		}
	}
	
	var hideSettings: Bool {
		get {
			config.getBool(section: "ui", key: "hide_settings", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "hide_settings", value: newValue)
		}
	}
	
	var hideSipAddresses: Bool {
		get {
			config.getBool(section: "ui", key: "hide_sip_addresses", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "hide_sip_addresses", value: newValue)
		}
	}
	
	var keepServiceAlive: Bool {
		get {
			config.getBool(section: "app", key: "keep_service_alive", defaultValue: false)
		}
		set {
			config.setBool(section: "app", key: "keep_service_alive", value: newValue)
		}
	}
	
	var linphoneConfigurationVersion: Int {
		get {
			config.getInt(section: "app", key: "config_version", defaultValue: 52005)
		}
		set {
			config.setInt(section: "app", key: "config_version", value: newValue)
		}
	}
	
	var markConversationAsReadWhenDismissingMessageNotification: Bool {
		get {
			config.getBool(section: "app", key: "mark_as_read_notif_dismissal", defaultValue: false)
		}
		set {
			config.setBool(section: "app", key: "mark_as_read_notif_dismissal", value: newValue)
		}
	}
	
	var maxAccountsCount: Int {
		get {
			config.getInt(section: "ui", key: "max_account", defaultValue: 0)
		}
		set {
			config.setInt(section: "ui", key: "max_account", value: newValue)
		}
	}
	
	var onlyAllowEarpieceDuringCall: Bool {
		get {
			config.getBool(section: "ui", key: "only_allow_earpiece_during_call", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "only_allow_earpiece_during_call", value: newValue)
		}
	}

	var printLogsInLogcat: Bool {
		get {
			config.getBool(section: "app", key: "debug", defaultValue: true)
		}
		set {
			config.setBool(section: "app", key: "debug", value: newValue)
		}
	}
	
	var publishPresence: Bool {
		get {
			config.getBool(section: "app", key: "publish_presence", defaultValue: true)
		}
		set {
			config.setBool(section: "app", key: "publish_presence", value: newValue)
		}
	}
	
	var pushNotificationsInterval: Int {
		get {
			config.getInt(section: "net", key: "pn-call-remote-push-interval", defaultValue: 3)
		}
		set {
			config.setInt(section: "net", key: "pn-call-remote-push-interval", value: newValue)
		}
	}
	
	var routeAudioToSpeakerWhenVideoIsEnabled: Bool {
		get {
			config.getBool(section: "app", key: "route_audio_to_speaker_when_video_enabled", defaultValue: true)
		}
		set {
			config.setBool(section: "app", key: "route_audio_to_speaker_when_video_enabled", value: newValue)
		}
	}
	
	var serveraddress: String {
		get {
			config.getString(section: "app", key: "server", defaultString: "")
		}
		set {
			config.setString(section: "app", key: "server", value: newValue)
		}
	}
	
	var showChatMessageContentInNotification: Bool {
		get {
			config.getBool(section: "ui", key: "display_notification_content", defaultValue: true)
		}
		set {
			config.setBool(section: "ui", key: "display_notification_content", value: newValue)
		}
	}
	
	var showDeveloperSettings: Bool {
		get {
			config.getBool(section: "ui", key: "show_developer_settings", defaultValue: false)
		}
		set {
			config.setBool(section: "ui", key: "show_developer_settings", value: newValue)
		}
	}
	
	var showDialogWhenCallingDeviceUuidDirectly: Bool {
		get {
			config.getBool(section: "app", key: "show_confirmation_dialog_zrtp_trust_call", defaultValue: true)
		}
		set {
			config.setBool(section: "app", key: "show_confirmation_dialog_zrtp_trust_call", value: newValue)
		}
	}
	
	var showFavoriteContacts: Bool {
		get {
			config.getBool(section: "ui", key: "show_favorites_contacts", defaultValue: true)
		}
		set {
			config.setBool(section: "ui", key: "show_favorites_contacts", value: newValue)
		}
	}
	
	var showPastMeetings: Bool {
		get {
			config.getBool(section: "ui", key: "show_past_meetings", defaultValue: true)
		}
		set {
			DispatchQueue.main.async {
				self.objectWillChange.send()
			}
			config.setBool(section: "ui", key: "show_past_meetings", value: newValue)
		}
	}
	
	var singleSignOnClientId: String {
		get {
			config.getString(section: "app", key: "oidc_client_id", defaultString: "linphone")
		}
		set {
			config.setString(section: "app", key: "oidc_client_id", value: newValue)
		}
	}
	
	var teamID: String {
		get {
			config.getString(section: "app", key: "team_id", defaultString: "")
		}
		set {
			config.setString(section: "app", key: "team_id", value: newValue)
		}
	}
	
	var themeAboutPictureUrl: String? {
		get {
			config.getString(section: "ui", key: "theme_about_picture_url", defaultString: nil)
		}
		set {
			config.setString(section: "ui", key: "theme_about_picture_url", value: newValue)
		}
	}
	
	var themeMainColor: String {
		get {
			let raw = config.getString(section: "ui", key: "theme_main_color", defaultString: "orange")
			return safeString(raw, defaultValue: "orange")
		}
		set {
			config.setString(section: "ui", key: "theme_main_color", value: newValue)
		}
	}
	
	var vfsEnabled: Bool {
		get {
			config.getBool(section: "app", key: "vfs_enabled", defaultValue: false)
		}
		set {
			config.setBool(section: "app", key: "vfs_enabled", value: newValue)
		}
	}
	
	var voiceRecordingMaxDuration: Int {
		get {
			config.getInt(section: "app", key: "voice_recording_max_duration", defaultValue: 600000)
		}
		set {
			config.setInt(section: "app", key: "voice_recording_max_duration", value: newValue)
		}
	}
	
	private func copy(from: String, to: String, overrideIfExists: Bool = false) {
		let fileManager = FileManager.default
		if fileManager.fileExists(atPath: to), !overrideIfExists {
			return
		}
		
		if let assetPath = Bundle.main.path(forResource: from, ofType: "") {
			do {
				try fileManager.copyItem(atPath: assetPath, toPath: to)
			} catch {
				print("Error copying file: \(error)")
			}
		}
	}
	
	private func safeString(_ raw: String?, defaultValue: String = "") -> String {
		guard let raw = raw else { return defaultValue }
		if let data = raw.data(using: .utf8) {
			return String(decoding: data, as: UTF8.self)
		}
		return defaultValue
	}
}
