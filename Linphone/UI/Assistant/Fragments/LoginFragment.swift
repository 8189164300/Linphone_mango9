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

import Security
import SwiftUI

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
			}
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
				let provisioningURL = try await Mango9LoginService.signIn(
					username: normalizedUsername,
					password: submittedPassword,
					rememberLogin: rememberLogin
				)
				password = ""
				coreContext.loggingInProgress = true
				coreContext.doOnCoreQueue { core in
					try? core.setProvisioninguri(newValue: provisioningURL.absoluteString)
					core.stop()
					try? core.start()
				}
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
	) async throws -> URL {
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
			enrollmentExpiresAt: Date().addingTimeInterval(TimeInterval(login.expiresIn))
		)
		try Mango9SessionStore.save(session, persist: rememberLogin)
		if let lineIdentity = try? await Mango9CRMAPI.lineIdentity(session: session) {
			Mango9LineIdentityStore.save(lineIdentity)
		}
		if let team = try? await Mango9CRMAPI.teamMembers(session: session) {
			ContactsManager.shared.syncMango9Team(team)
		}
		await Mango9ChatStore.shared.connectIfNeeded(force: true)
		return provisioningURL
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
}

enum Mango9SessionStore {
	private static let rememberLoginKey = "mango9_remember_login"
	private static let service =
		(Bundle.main.bundleIdentifier ?? "com.mango9.linphone") + ".crm-session"
	private static let account = "current"
	private static var volatileSession: Mango9Session?

	static func save(_ session: Mango9Session, persist: Bool? = nil) throws {
		volatileSession = session
		let shouldPersist = persist ?? rememberedLoginIsEnabled
		guard shouldPersist else {
			deletePersistentSession()
			return
		}

		let data = try JSONEncoder().encode(session)
		let key: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: account
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
		if let volatileSession {
			return volatileSession
		}
		guard rememberedLoginIsEnabled else {
			return nil
		}

		let query: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: account,
			kSecReturnData as String: true,
			kSecMatchLimit as String: kSecMatchLimitOne
		]
		var result: CFTypeRef?
		guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
			  let data = result as? Data else {
			return nil
		}
		let session = try? JSONDecoder().decode(Mango9Session.self, from: data)
		volatileSession = session
		return session
	}

	static func clear() {
		volatileSession = nil
		deletePersistentSession()
		Mango9LineIdentityStore.clear()
	}

	private static var rememberedLoginIsEnabled: Bool {
		let defaults = UserDefaults.standard
		guard defaults.object(forKey: rememberLoginKey) != nil else {
			return true
		}
		return defaults.bool(forKey: rememberLoginKey)
	}

	private static func deletePersistentSession() {
		let query: [String: Any] = [
			kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service,
			kSecAttrAccount as String: account
		]
		SecItemDelete(query as CFDictionary)
	}
}

private enum Mango9SessionStoreError: Error {
	case keychain(OSStatus)
}

#Preview {
	LoginFragment()
}
