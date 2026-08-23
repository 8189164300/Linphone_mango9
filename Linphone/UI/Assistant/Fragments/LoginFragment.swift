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

import CryptoKit
import Security
import SwiftUI
import linphonesw

/// Mango9-managed enrollment.
///
/// SIP credentials and tenant details are delivered by the Mango9 provisioning
/// service after either CRM login or one-time QR enrollment. The public Linphone
/// account creator and arbitrary SIP-account paths are intentionally not exposed.
struct LoginFragment: View {
	@ObservedObject private var coreContext = CoreContext.shared

	@State private var isShowHelpFragment = false
	@State private var username = ""
	@State private var password = ""
	@State private var isPasswordVisible = false
	@State private var isSigningIn = false
	@State private var signInError: String?
	@AppStorage("mango9_remember_login") private var rememberLogin = true

	var isShowBack = false
	var onBackPressed: (() -> Void)?

	var body: some View {
		NavigationView {
			ZStack {
				Color.gray100
					.ignoresSafeArea()

				ScrollView(.vertical, showsIndicators: false) {
					VStack(spacing: 0) {
						header
						Spacer()
							.frame(height: 14)
						brandCard
						Spacer()
							.frame(height: 16)
						enrollmentCard
						Spacer()
							.frame(height: 16)
					}
				}

				if isShowHelpFragment {
					HelpFragment(isShowHelpFragment: $isShowHelpFragment)
						.transition(.move(edge: .trailing))
						.zIndex(3)
				}

				if coreContext.loggingInProgress {
					PopupLoadingView()
						.background(.black.opacity(0.65))
				}
			}
			.navigationTitle("")
			.navigationBarHidden(true)
		}
		.navigationViewStyle(StackNavigationViewStyle())
		.onAppear {
			if rememberLogin,
			   username.isEmpty,
			   let session = Mango9SessionStore.load() {
				username = session.loginId
				if !isShowBack {
					restoreSavedAccount(session)
				}
			}
		}
	}

	private func restoreSavedAccount(_ session: Mango9Session) {
		guard !isSigningIn else { return }
		isSigningIn = true
		signInError = nil

		Task {
			do {
				try await Mango9LoginService.restore(session: session)
				ToastViewModel.shared.show("Success_mango9_account_connected")
			} catch Mango9CRMAPIError.unauthorized {
				signInError = "Your saved Mango9 session has expired. Enter your password to connect again."
			} catch {
				signInError = Mango9LoginService.userFacingMessage(for: error)
			}
			isSigningIn = false
		}
	}

	private var header: some View {
		HStack {
			if isShowBack {
				Button {
					withAnimation {
						onBackPressed?()
					}
				} label: {
					Image("caret-left")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.grayMain2c600)
						.frame(width: 25, height: 25)
						.padding(10)
				}
			} else {
				Color.clear
					.frame(width: 45, height: 45)
			}

			Spacer()

			NavigationLink {
				ThirdPartySipAccountLoginFragment(
					accountLoginViewModel: AccountLoginViewModel()
				)
			} label: {
				Image("gear")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.grayMain2c600)
					.frame(width: 21, height: 21)
					.padding(12)
			}
			.accessibilityLabel("Configure SIP account manually")

			Button {
				withAnimation {
					isShowHelpFragment = true
				}
			} label: {
				Image("question")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.grayMain2c600)
					.frame(width: 20, height: 20)
					.padding(12)
			}
		}
		.padding(.horizontal, 12)
		.padding(.top, 8)
	}

	private var brandCard: some View {
		VStack(spacing: 10) {
			Image("mango9-logo")
				.resizable()
				.scaledToFit()
				.frame(maxWidth: 190, maxHeight: 50)

			Text("Business calling and messaging")
				.default_text_style_white_600(styleSize: 18)
				.multilineTextAlignment(.center)
				.lineLimit(2)
				.minimumScaleFactor(0.85)
				.fixedSize(horizontal: false, vertical: true)

			Text("Securely connect this device to your Mango9 account.")
				.default_text_style_white_600(styleSize: 13)
				.multilineTextAlignment(.center)
				.lineLimit(2)
				.minimumScaleFactor(0.85)
				.fixedSize(horizontal: false, vertical: true)
				.opacity(0.85)
		}
		.frame(maxWidth: .infinity)
		.padding(.horizontal, 24)
		.padding(.vertical, 20)
		.background(Color.orangeMain500)
		.cornerRadius(24)
		.padding(.horizontal, 24)
	}

	private var enrollmentCard: some View {
		VStack(spacing: 14) {
			Text("Connect your account")
				.default_text_style_800(styleSize: 20)

			Text("Sign in with your Mango9 account or scan the QR code shown in the CRM.")
				.default_text_style(styleSize: 14)
				.foregroundStyle(Color.grayMain2c600)
				.multilineTextAlignment(.center)
				.lineLimit(3)
				.fixedSize(horizontal: false, vertical: true)

			TextField("Email or username", text: $username)
				.textContentType(.username)
				.textInputAutocapitalization(.never)
				.autocorrectionDisabled()
				.padding(.horizontal, 18)
				.frame(height: 48)
				.background(Color.gray100)
				.overlay(
					RoundedRectangle(cornerRadius: 14)
						.stroke(Color.gray200, lineWidth: 1)
				)
				.cornerRadius(14)

			HStack(spacing: 8) {
				Group {
					if isPasswordVisible {
						TextField("Password", text: $password)
					} else {
						SecureField("Password", text: $password)
					}
				}
				.textContentType(.password)
				.textInputAutocapitalization(.never)
				.autocorrectionDisabled()
				.onSubmit(signIn)

				Button {
					isPasswordVisible.toggle()
				} label: {
					Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
						.foregroundStyle(Color.grayMain2c600)
						.frame(width: 28, height: 28)
				}
				.buttonStyle(.plain)
				.accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
			}
				.padding(.leading, 18)
				.padding(.trailing, 12)
				.frame(height: 48)
				.background(Color.gray100)
				.overlay(
					RoundedRectangle(cornerRadius: 14)
						.stroke(Color.gray200, lineWidth: 1)
				)
				.cornerRadius(14)

			Toggle(isOn: $rememberLogin) {
				VStack(alignment: .leading, spacing: 2) {
					Text("Keep me signed in")
						.default_text_style_600(styleSize: 14)
					Text("Your session is stored securely. Your CRM password is never saved.")
						.default_text_style(styleSize: 11)
						.foregroundStyle(Color.grayMain2c500)
				}
			}
			.toggleStyle(SwitchToggleStyle(tint: Color.orangeMain500))

			if let signInError {
				Text(signInError)
					.default_text_style(styleSize: 13)
					.foregroundStyle(Color.red)
					.multilineTextAlignment(.center)
			}

			Button(action: signIn) {
				HStack {
					if isSigningIn {
						ProgressView()
							.progressViewStyle(CircularProgressViewStyle(tint: Color.white))
					}

					Text(isSigningIn ? "Signing in…" : "Sign in")
						.default_text_style_white_600(styleSize: 18)
				}
				.frame(maxWidth: .infinity)
				.padding(.horizontal, 20)
				.padding(.vertical, 12)
				.background(Color.orangeMain500)
				.cornerRadius(60)
			}
			.buttonStyle(.plain)
			.allowsHitTesting(canSignIn)

			HStack(spacing: 12) {
				Divider()
				.frame(height: 1)

				Text("or")
					.default_text_style_600(styleSize: 14)
					.foregroundStyle(Color.grayMain2c500)

				Divider()
					.frame(height: 1)
			}

			NavigationLink {
				QrCodeScannerFragment()
			} label: {
				HStack {
					Image("qr-code")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.white)
						.frame(width: 22, height: 22)

					Text("Scan Mango9 QR code")
						.default_text_style_white_600(styleSize: 18)
				}
				.frame(maxWidth: .infinity)
				.padding(.horizontal, 20)
				.padding(.vertical, 12)
				.background(Color.orangeMain500)
				.cornerRadius(60)
			}

		}
		.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
		.padding(20)
		.background(Color.white)
		.cornerRadius(20)
		.padding(.horizontal, 24)
	}

	private var canSignIn: Bool {
		!username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
		!password.isEmpty &&
		!isSigningIn
	}

	private func signIn() {
		guard canSignIn else { return }

		let normalizedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
		let submittedPassword = password
		signInError = nil
		isSigningIn = true

		Task {
			do {
				try await Mango9LoginService.signIn(
					username: normalizedUsername,
					password: submittedPassword,
					rememberLogin: rememberLogin
				)
				password = ""
				ToastViewModel.shared.show("Success_mango9_account_connected")
			} catch {
				signInError = Mango9LoginService.userFacingMessage(for: error)
			}
			isSigningIn = false
		}
	}
}

@MainActor
private enum Mango9LoginService {
	private enum SignInFailure: LocalizedError {
		case invalidCredentials
		case accountNotProvisionable
		case rateLimited
		case crmUnavailable
		case invalidResponse

		var errorDescription: String? {
			switch self {
			case .invalidCredentials:
				return "The CRM username or password is incorrect."
			case .accountNotProvisionable:
				return "This CRM user does not have an active SIP extension."
			case .rateLimited:
				return "Too many sign-in attempts. Wait a moment and try again."
			case .crmUnavailable:
				return "The CRM is temporarily unavailable. Please try again."
			case .invalidResponse:
				return "The provisioning service returned an unexpected response."
			}
		}
	}

	private struct LoginRequest: Encodable {
		let username: String
		let password: String
		let deviceId: String
		let platform: String

		enum CodingKeys: String, CodingKey {
			case username
			case password
			case deviceId = "device_id"
			case platform
		}
	}

	private struct ErrorResponse: Decodable {
		let error: String?
	}

	private struct LoginResponse: Decodable {
		let enrollmentUrl: String
		let expiresIn: Int
		let crm: CRM
		let services: Services
		let tokens: Tokens
		let user: User
	}

	private struct CRM: Decodable {
		let id: String
		let baseUrl: String
		let apiBaseUrl: String
		let userId: String
		let parentClientId: String
		let role: String
	}

	private struct Services: Decodable {
		let smsChatApi: String
		let connectWebsocket: String
	}

	private struct Tokens: Decodable {
		let accessToken: String
		let refreshToken: String
	}

	private struct User: Decodable {
		let userId: Int
		let loginId: String
		let name: String?
		let email: String?
		let mobile: String?
		let category: String
	}

	private struct StoredProvisioning: Decodable {
		struct SIP: Decodable {
			let identity: String
			let username: String
			let activeNumber: String?
			let password: String
			let domain: String
			let realm: String
		}

		let sip: SIP
	}

	static func userFacingMessage(for error: Error) -> String {
		if let localizedError = error as? LocalizedError,
		   let message = localizedError.errorDescription {
			return message
		}
		return "We couldn't reach Mango9. Check your connection and try again."
	}

	static func signIn(
		username: String,
		password: String,
		rememberLogin: Bool
	) async throws {
		let endpoint = Mango9Configuration.provisioningBaseURL
			.appendingPathComponent("v1/mobile/login")
		var request = URLRequest(url: endpoint)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.httpBody = try JSONEncoder().encode(
			LoginRequest(
				username: username,
				password: password,
				deviceId: UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString,
				platform: "ios"
			)
		)

		let (data, response) = try await URLSession.shared.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw SignInFailure.invalidResponse
		}
		guard (200..<300).contains(httpResponse.statusCode) else {
			let serverError = try? JSONDecoder().decode(ErrorResponse.self, from: data)
			switch (httpResponse.statusCode, serverError?.error) {
			case (401, _), (403, _):
				throw SignInFailure.invalidCredentials
			case (409, "account_not_provisionable"):
				throw SignInFailure.accountNotProvisionable
			case (429, _):
				throw SignInFailure.rateLimited
			case (502, _), (503, _):
				throw SignInFailure.crmUnavailable
			default:
				throw SignInFailure.invalidResponse
			}
		}

		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		let login = try decoder.decode(LoginResponse.self, from: data)
		guard let provisioningURL = Mango9Configuration.verifiedProvisioningURL(from: login.enrollmentUrl) else {
			throw URLError(.badServerResponse)
		}
		let enrollment = try await Mango9AccountProvisioner.fetchEnrollment(
			from: provisioningURL
		)
		let session = Mango9Session(
			crmId: login.crm.id,
			crmBaseUrl: login.crm.baseUrl,
			crmApiBaseUrl: login.crm.apiBaseUrl,
			userId: login.crm.userId,
			parentClientId: login.crm.parentClientId,
			role: login.crm.role,
			loginId: login.user.loginId,
			displayName: login.user.name,
			accessToken: login.tokens.accessToken,
			refreshToken: login.tokens.refreshToken,
			smsChatApi: login.services.smsChatApi,
			connectWebsocket: login.services.connectWebsocket,
			enrollmentExpiresAt: Date().addingTimeInterval(TimeInterval(login.expiresIn)),
			sipIdentity: enrollment.identity
		)
		try Mango9SessionStore.save(
			session,
			for: enrollment.identity,
			persist: rememberLogin,
			makeActive: true
		)
		// CRM authentication and SIP registration are separate service lanes.
		// Persist the line identity before attempting SIP so a temporary proxy
		// outage cannot make an authenticated account lose its DID/extension.
		if let lineIdentity = try? await Mango9CRMAPI.lineIdentity(session: session) {
			Mango9LineIdentityStore.save(
				lineIdentity,
				sipIdentity: enrollment.identity
			)
		}
		CoreContext.shared.loggingInProgress = true
		do {
			try await Mango9AccountProvisioner.install(
				enrollment,
				displayName: login.user.name
			)
		} catch {
			// Do not roll back a valid CRM session because the independent SIP
			// service is temporarily unavailable. Linphone retains the account
			// and retries registration; the user can still see the correct line.
			Log.warn(
				"[Mango9 Login] SIP setup failed after CRM authentication; " +
				"preserving the CRM session and line identity for retry"
			)
			CoreContext.shared.loggingInProgress = false
			throw error
		}
		if let team = try? await Mango9CRMAPI.teamMembers(session: session),
		   Mango9SessionStore.isActive(session) {
			ContactsManager.shared.syncMango9Team(team)
		}
		await Mango9ChatStore.shared.connectIfNeeded(force: true)
	}

	static func restore(session: Mango9Session) async throws {
		CoreContext.shared.loggingInProgress = true
		defer { CoreContext.shared.loggingInProgress = false }

		var currentSession = session
		let provisioning: StoredProvisioning
		do {
			provisioning = try await storedProvisioning(session: currentSession)
		} catch Mango9CRMAPIError.unauthorized {
			currentSession = try await Mango9CRMAPI.refresh(session: currentSession)
			try Mango9SessionStore.save(
				currentSession,
				for: currentSession.sipIdentity,
				persist: true
			)
			provisioning = try await storedProvisioning(session: currentSession)
		}

		let sip = provisioning.sip
		let identity = sip.identity.trimmingCharacters(in: .whitespacesAndNewlines)
		let username = sip.username.trimmingCharacters(in: .whitespacesAndNewlines)
		let password = sip.password
		let domain = sip.domain.trimmingCharacters(in: .whitespacesAndNewlines)
		let realm = sip.realm.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !identity.isEmpty,
			  !username.isEmpty,
			  !password.isEmpty,
			  !domain.isEmpty,
			  !realm.isEmpty else {
			throw SignInFailure.accountNotProvisionable
		}

		let digestInput = Data("\(username):\(realm):\(password)".utf8)
		let ha1 = Insecure.MD5.hash(data: digestInput)
			.map { String(format: "%02x", $0) }
			.joined()
		let enrollment = Mango9SIPEnrollment(
			identity: identity,
			username: username,
			domain: domain,
			realm: realm,
			ha1: ha1
		)

		let associatedSession = currentSession.associated(with: identity)
		try Mango9SessionStore.save(
			associatedSession,
			for: identity,
			persist: true,
			makeActive: true
		)
		Mango9LineIdentityStore.save(
			Mango9LineIdentity(
				extensionNumber: username,
				activeNumber: sip.activeNumber
			),
			sipIdentity: identity
		)
		try await Mango9AccountProvisioner.install(
			enrollment,
			displayName: associatedSession.displayName
		)
		await Mango9ChatStore.shared.connectIfNeeded(force: true)
	}

	private static func storedProvisioning(
		session: Mango9Session
	) async throws -> StoredProvisioning {
		guard let baseURL = URL(string: session.crmApiBaseUrl) else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		var request = URLRequest(
			url: baseURL.appendingPathComponent("provisioning")
		)
		request.httpMethod = "GET"
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue(
			"Bearer \(session.accessToken)",
			forHTTPHeaderField: "Authorization"
		)
		let envelope: Mango9CRMAPI.Envelope<StoredProvisioning> =
			try await Mango9CRMAPI.send(request)
		guard envelope.success, let provisioning = envelope.data else {
			throw SignInFailure.accountNotProvisionable
		}
		return provisioning
	}
}

struct Mango9Session: Codable {
	let crmId: String
	let crmBaseUrl: String
	let crmApiBaseUrl: String
	let userId: String
	let parentClientId: String
	let role: String
	let loginId: String
	let displayName: String?
	let accessToken: String
	let refreshToken: String
	let smsChatApi: String
	let connectWebsocket: String
	let enrollmentExpiresAt: Date
	let sipIdentity: String?

	func associated(with identity: String) -> Mango9Session {
		Mango9Session(
			crmId: crmId,
			crmBaseUrl: crmBaseUrl,
			crmApiBaseUrl: crmApiBaseUrl,
			userId: userId,
			parentClientId: parentClientId,
			role: role,
			loginId: loginId,
			displayName: displayName,
			accessToken: accessToken,
			refreshToken: refreshToken,
			smsChatApi: smsChatApi,
			connectWebsocket: connectWebsocket,
			enrollmentExpiresAt: enrollmentExpiresAt,
			sipIdentity: identity
		)
	}
}

enum Mango9SessionStore {
	private static let rememberLoginKey = "mango9_remember_login"
	private static let activeIdentityKey = "mango9_active_sip_identity"
	private static let identitiesKey = "mango9_crm_session_identities"
	private static let service =
		(Bundle.main.bundleIdentifier ?? "com.mango9.linphone") + ".crm-session"
	private static let legacyAccount = "current"
	private static var volatileSessions: [String: Mango9Session] = [:]
	private static let volatileSessionsLock = NSLock()

	static func save(
		_ session: Mango9Session,
		for sipIdentity: String? = nil,
		persist: Bool? = nil,
		makeActive: Bool = false
	) throws {
		guard let identity = normalizedIdentity(
			sipIdentity ?? session.sipIdentity ?? activeIdentity
		) else {
			throw Mango9SessionStoreError.missingIdentity
		}
		let associatedSession = session.associated(with: identity)
		setVolatileSession(associatedSession, for: identity)
		var identities = storedIdentities
		identities.insert(identity)
		storeIdentities(identities)
		if makeActive {
			setActiveIdentity(identity)
		}
		let shouldPersist = persist ?? rememberedLoginIsEnabled
		guard shouldPersist else {
			deletePersistentSession(for: identity)
			return
		}

		let data = try JSONEncoder().encode(associatedSession)
		let key: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: identity
		]
		let attributes: [String: Any] = [
			kSecValueData as String: data,
			kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
		]

		let updateStatus = SecItemUpdate(key as CFDictionary, attributes as CFDictionary)
		if updateStatus == errSecSuccess {
			return
		}
		guard updateStatus == errSecItemNotFound else {
			throw Mango9SessionStoreError.keychain(updateStatus)
		}

		var newItem = key
		attributes.forEach { newItem[$0.key] = $0.value }
		let addStatus = SecItemAdd(newItem as CFDictionary, nil)
		guard addStatus == errSecSuccess else {
			throw Mango9SessionStoreError.keychain(addStatus)
		}
	}

	static func load() -> Mango9Session? {
		if let activeIdentity {
			return load(for: activeIdentity)
		}
		return loadLegacySession()
	}

	static func load(for sipIdentity: String) -> Mango9Session? {
		guard let identity = normalizedIdentity(sipIdentity) else {
			return nil
		}
		if let session = volatileSession(for: identity) {
			return session
		}
		guard rememberedLoginIsEnabled else {
			return nil
		}

		if let session = loadPersistentSession(account: identity) {
			let associatedSession = session.associated(with: identity)
			setVolatileSession(associatedSession, for: identity)
			return associatedSession
		}

		// Migrate the pre-multi-account "current" record to the active SIP
		// identity the first time this version runs.
		guard let legacy = loadLegacySession() else {
			return nil
		}
		let migrated = legacy.associated(with: identity)
		try? save(migrated, for: identity)
		deletePersistentSession(account: legacyAccount)
		return migrated
	}

	static func activate(sipIdentity: String?) {
		guard let identity = normalizedIdentity(sipIdentity) else {
			UserDefaults.standard.removeObject(forKey: activeIdentityKey)
			notifyContextChanged()
			return
		}
		setActiveIdentity(identity)
		_ = load(for: identity)
		notifyContextChanged()
	}

	static func remove(for sipIdentity: String) {
		guard let identity = normalizedIdentity(sipIdentity) else { return }
		setVolatileSession(nil, for: identity)
		deletePersistentSession(for: identity)
		var identities = storedIdentities
		identities.remove(identity)
		storeIdentities(identities)
		Mango9LineIdentityStore.clear(sipIdentity: identity)
		if activeIdentity == identity {
			UserDefaults.standard.removeObject(forKey: activeIdentityKey)
			notifyContextChanged()
		}
	}

	static func clear() {
		if let activeIdentity {
			remove(for: activeIdentity)
		} else {
			deletePersistentSession(account: legacyAccount)
			notifyContextChanged()
		}
	}

	static var activeIdentity: String? {
		normalizedIdentity(
			UserDefaults.standard.string(forKey: activeIdentityKey)
		)
	}

	static func isActive(_ session: Mango9Session) -> Bool {
		isActive(sipIdentity: session.sipIdentity)
	}

	static func isActive(sipIdentity: String?) -> Bool {
		guard let identity = normalizedIdentity(sipIdentity) else {
			return false
		}
		return identity == activeIdentity
	}

	static func identity(forCRMId crmId: String) -> String? {
		let normalizedCRMId = crmId
			.trimmingCharacters(in: .whitespacesAndNewlines)
			.lowercased()
		guard !normalizedCRMId.isEmpty else { return nil }
		let matches = storedIdentities.filter { identity in
			load(for: identity)?.crmId
				.trimmingCharacters(in: .whitespacesAndNewlines)
				.lowercased() == normalizedCRMId
		}
		if let activeIdentity, matches.contains(activeIdentity) {
			return activeIdentity
		}
		// A CRM instance can contain more than one mobile user. Without a
		// user-specific identifier in the push payload, choosing an arbitrary
		// account would risk opening another user's CRM context.
		return matches.count == 1 ? matches.first : nil
	}

	private static var rememberedLoginIsEnabled: Bool {
		let defaults = UserDefaults.standard
		guard defaults.object(forKey: rememberLoginKey) != nil else {
			return true
		}
		return defaults.bool(forKey: rememberLoginKey)
	}

	private static func loadLegacySession() -> Mango9Session? {
		if let legacy = volatileSession(for: legacyAccount) {
			return legacy
		}
		guard rememberedLoginIsEnabled,
			  let legacy = loadPersistentSession(account: legacyAccount) else {
			return nil
		}
		setVolatileSession(legacy, for: legacyAccount)
		return legacy
	}

	private static func volatileSession(for identity: String) -> Mango9Session? {
		volatileSessionsLock.lock()
		defer { volatileSessionsLock.unlock() }
		return volatileSessions[identity]
	}

	private static func setVolatileSession(
		_ session: Mango9Session?,
		for identity: String
	) {
		volatileSessionsLock.lock()
		defer { volatileSessionsLock.unlock() }
		volatileSessions[identity] = session
	}

	private static func loadPersistentSession(account: String) -> Mango9Session? {
		let query: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: account,
			kSecReturnData as String: true,
			kSecMatchLimit as String: kSecMatchLimitOne
		]
		var result: CFTypeRef?
		let status = SecItemCopyMatching(query as CFDictionary, &result)
		guard status == errSecSuccess,
			  let data = result as? Data else {
			if status != errSecItemNotFound {
				Log.warn("[Mango9 Session] Keychain lookup failed for the stored account (status: \(status))")
			}
			return nil
		}
		do {
			return try JSONDecoder().decode(Mango9Session.self, from: data)
		} catch {
			let keys = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?
				.keys.sorted().joined(separator: ",") ?? "unreadable"
			Log.error("[Mango9 Session] Stored session could not be decoded (keys: \(keys), error: \(error))")
			return nil
		}
	}

	private static func deletePersistentSession(for sipIdentity: String) {
		deletePersistentSession(account: sipIdentity)
	}

	private static func deletePersistentSession(account: String) {
		let query: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: account
		]
		SecItemDelete(query as CFDictionary)
	}

	private static func setActiveIdentity(_ identity: String) {
		Mango9LineIdentityStore.migrateLegacyActiveValuesIfNeeded(
			sipIdentity: identity
		)
		UserDefaults.standard.set(identity, forKey: activeIdentityKey)
		Mango9LineIdentityStore.activate(sipIdentity: identity)
	}

	private static var storedIdentities: Set<String> {
		Set(
			UserDefaults.standard.stringArray(forKey: identitiesKey) ?? []
		)
	}

	private static func storeIdentities(_ identities: Set<String>) {
		UserDefaults.standard.set(
			identities.sorted(),
			forKey: identitiesKey
		)
	}

	static func normalizedIdentity(_ rawValue: String?) -> String? {
		guard var value = rawValue?
			.trimmingCharacters(in: .whitespacesAndNewlines)
			.lowercased(),
			  !value.isEmpty else {
			return nil
		}
		if let openingBracket = value.firstIndex(of: "<"),
		   let closingBracket = value[openingBracket...].firstIndex(of: ">") {
			value = String(
				value[
					value.index(after: openingBracket)..<closingBracket
				]
			)
		}
		if let parameterIndex = value.firstIndex(of: ";") {
			value = String(value[..<parameterIndex])
		}
		return value
	}

	private static func notifyContextChanged() {
		DispatchQueue.main.async {
			NotificationCenter.default.post(
				name: .mango9AccountContextChanged,
				object: activeIdentity
			)
		}
	}
}

private enum Mango9SessionStoreError: Error {
	case keychain(OSStatus)
	case missingIdentity
}

struct Mango9SIPEnrollment {
	let identity: String
	let username: String
	let domain: String
	let realm: String
	let ha1: String
}

enum Mango9AccountProvisioningError: LocalizedError {
	case invalidResponse
	case coreUnavailable
	case registrationFailed
	case registrationTimedOut

	var errorDescription: String? {
		switch self {
		case .invalidResponse:
			return "The Mango9 provisioning response is invalid."
		case .coreUnavailable:
			return "The phone service is not ready. Please try again."
		case .registrationFailed:
			return "The Mango9 extension could not register. Please try again."
		case .registrationTimedOut:
			return "The Mango9 extension registration timed out. Please try again."
		}
	}
}

@MainActor
enum Mango9AccountProvisioner {
	static func fetchEnrollment(from url: URL) async throws -> Mango9SIPEnrollment {
		let (data, response) = try await URLSession.shared.data(from: url)
		guard let httpResponse = response as? HTTPURLResponse,
			  (200..<300).contains(httpResponse.statusCode) else {
			throw Mango9AccountProvisioningError.invalidResponse
		}
		return try parseEnrollment(data)
	}

	static func parseEnrollment(_ data: Data) throws -> Mango9SIPEnrollment {
		try Mango9EnrollmentXMLParser.parse(data)
	}

	static func install(
		_ enrollment: Mango9SIPEnrollment,
		displayName: String?
	) async throws {
		guard let normalizedEnrollmentIdentity =
			Mango9SessionStore.normalizedIdentity(enrollment.identity) else {
			throw Mango9AccountProvisioningError.invalidResponse
		}
		try await withCheckedThrowingContinuation { continuation in
			CoreContext.shared.doOnCoreQueue { core in
				do {
					guard core.globalState == .On else {
						throw Mango9AccountProvisioningError.coreUnavailable
					}
					let identity = try Factory.Instance.createAddress(
						addr: enrollment.identity
					)
					if let displayName = displayName?
						.trimmingCharacters(in: .whitespacesAndNewlines),
					   !displayName.isEmpty {
						try identity.setDisplayname(newValue: displayName)
					}

					let authInfo = try Factory.Instance.createAuthInfo(
						username: enrollment.username,
						userid: nil,
						passwd: nil,
						ha1: enrollment.ha1,
						realm: enrollment.realm,
						domain: enrollment.domain,
						algorithm: "MD5"
					)
					let params = try core.createAccountParams()
					try params.setIdentityaddress(newValue: identity)
					let serverAddress = try Factory.Instance.createAddress(
						addr: Mango9Configuration.sipProxyURI
					)
					try params.setServeraddress(newValue: serverAddress)
					let routeAddress = try Factory.Instance.createAddress(
						addr: Mango9Configuration.sipProxyURI
					)
					try params.setRoutesaddresses(newValue: [routeAddress])
					params.registerEnabled = true
					Mango9Configuration.configurePush(on: params)

					let matchingAccounts = core.accountList.filter {
						Mango9SessionStore.normalizedIdentity(
							$0.params?.identityAddress?.asStringUriOnly()
						) == normalizedEnrollmentIdentity
					}
					let existingAccount = matchingAccounts.first { account in
						core.defaultAccount.map { $0 == account } ?? false
					} ?? matchingAccounts.first
					let selectedAccount: Account
					if let existingAccount {
						// A SIP identity represents one line. Older multi-account builds
						// could save the same identity more than once, leaving a stale
						// registration that continued to raise global connection errors.
						for duplicateAccount in matchingAccounts
						where duplicateAccount !== existingAccount {
							core.removeAccount(account: duplicateAccount)
						}
						if let oldAuthInfo = existingAccount.findAuthInfo() {
							core.removeAuthInfo(info: oldAuthInfo)
						}
						core.addAuthInfo(info: authInfo)
						existingAccount.params = params
						selectedAccount = existingAccount
					} else {
						let account = try core.createAccount(params: params)
						core.addAuthInfo(info: authInfo)
						try core.addAccount(account: account)
						selectedAccount = account
					}

					core.defaultAccount = selectedAccount
					selectedAccount.refreshRegister()
					core.config?.setString(
						section: "misc",
						key: "config-uri",
						value: nil
					)
					try core.setProvisioninguri(newValue: nil)
					continuation.resume()
				} catch {
					continuation.resume(throwing: error)
				}
			}
		}
		try await waitForRegistration(
			identity: normalizedEnrollmentIdentity,
			timeout: 15
		)
	}

	private static func waitForRegistration(
		identity: String,
		timeout: TimeInterval
	) async throws {
		let deadline = Date().addingTimeInterval(timeout)
		var failedSince: Date?
		while Date() < deadline {
			let state: RegistrationState? = await withCheckedContinuation {
				continuation in
				CoreContext.shared.doOnCoreQueue { core in
					let account = core.accountList.first {
						Mango9SessionStore.normalizedIdentity(
							$0.params?.identityAddress?.asStringUriOnly()
						) == identity
					}
					continuation.resume(returning: account?.state)
				}
			}
			switch state {
			case .Ok:
				return
			case .Failed:
				if let failedSince,
				   Date().timeIntervalSince(failedSince) >= 1 {
					throw Mango9AccountProvisioningError.registrationFailed
				}
				failedSince = failedSince ?? Date()
			case nil:
				throw Mango9AccountProvisioningError.invalidResponse
			default:
				failedSince = nil
				break
			}
			try await Task.sleep(nanoseconds: 250_000_000)
		}
		throw Mango9AccountProvisioningError.registrationTimedOut
	}
}

private final class Mango9EnrollmentXMLParser: NSObject, XMLParserDelegate {
	private var values: [String: [String: String]] = [:]
	private var section: String?
	private var entry: String?
	private var text = ""

	static func parse(_ data: Data) throws -> Mango9SIPEnrollment {
		let delegate = Mango9EnrollmentXMLParser()
		let parser = XMLParser(data: data)
		parser.delegate = delegate
		guard parser.parse() else {
			throw parser.parserError ?? Mango9AccountProvisioningError.invalidResponse
		}
		guard let proxy = delegate.values["proxy_0"],
			  let auth = delegate.values["auth_info_0"],
			  let rawIdentity = proxy["reg_identity"],
			  let identity = Mango9SessionStore.normalizedIdentity(rawIdentity),
			  let username = auth["username"], !username.isEmpty,
			  let domain = auth["domain"], !domain.isEmpty,
			  let realm = auth["realm"], !realm.isEmpty,
			  let ha1 = auth["ha1"], !ha1.isEmpty else {
			throw Mango9AccountProvisioningError.invalidResponse
		}
		return Mango9SIPEnrollment(
			identity: identity,
			username: username,
			domain: domain,
			realm: realm,
			ha1: ha1
		)
	}

	func parser(
		_ parser: XMLParser,
		didStartElement elementName: String,
		namespaceURI: String?,
		qualifiedName qName: String?,
		attributes attributeDict: [String: String] = [:]
	) {
		if elementName == "section" {
			section = attributeDict["name"]
		} else if elementName == "entry" {
			entry = attributeDict["name"]
			text = ""
		}
	}

	func parser(_ parser: XMLParser, foundCharacters string: String) {
		if entry != nil {
			text.append(string)
		}
	}

	func parser(
		_ parser: XMLParser,
		didEndElement elementName: String,
		namespaceURI: String?,
		qualifiedName qName: String?
	) {
		if elementName == "entry", let section, let entry {
			values[section, default: [:]][entry] =
				text.trimmingCharacters(in: .whitespacesAndNewlines)
			self.entry = nil
			text = ""
		} else if elementName == "section" {
			section = nil
		}
	}
}

#Preview {
	LoginFragment()
}
