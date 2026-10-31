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
/// service after either CRM password login or the CRM's existing email-code
/// verification flow. The public Linphone account creator and arbitrary
/// SIP-account paths are intentionally not exposed.
struct LoginFragment: View {
	private enum LoginMode: Equatable {
		case password
		case emailRequest
		case emailVerification
	}

	private enum LoginField: Hashable { case username, password }
	@FocusState private var focusedField: LoginField?

	@ObservedObject private var coreContext = CoreContext.shared

	@State private var isShowHelpFragment = false
	@State private var isShowingManual = false
	@State private var isShowingSettingsGate = false
	@State private var settingsUnlocked = false
	@State private var settingsPassword = ""
	@State private var settingsPasswordError = false
	@State private var username = ""
	@State private var password = ""
	@State private var loginMode: LoginMode = .password
	@State private var email = ""
	@State private var verificationCode = ""
	@State private var resendCountdown = 0
	@State private var resendTimerTask: Task<Void, Never>?
	@State private var isPasswordVisible = false
	@State private var isSigningIn = false
	@State private var signInError: String?
	@AppStorage("mango9_remember_login") private var rememberLogin = true

	private let signInBackground = Color.mango9Primary
	private let signInAccent = Color.mango9Primary

	var isShowBack = false
	var onBackPressed: (() -> Void)?

	var body: some View {
		NavigationView {
			ZStack {
				signInBackground.ignoresSafeArea()
				GeometryReader { geometry in
					ScrollView(.vertical, showsIndicators: false) {
						VStack(spacing: 0) {
							header
							brandCard.padding(.top, geometry.size.height > 500 ? 26 : 8)
							enrollmentCard.padding(.top, geometry.size.height > 500 ? min(116, geometry.size.height * 0.14) : 24)
							Spacer(minLength: 32)
						}.frame(maxWidth: .infinity, minHeight: geometry.size.height, alignment: .top)
					}.mango9SignInScroll()
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
		.sheet(isPresented: $isShowingSettingsGate, onDismiss: {
			settingsPassword = ""
			if settingsUnlocked { settingsUnlocked = false; isShowingManual = true }
		}) { settingsGate }
		.navigationViewStyle(StackNavigationViewStyle())
		.preferredColorScheme(isShowHelpFragment || isShowingManual ? nil : .dark)
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
		.onDisappear {
			resendTimerTask?.cancel()
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
		HStack(spacing: 6) {
			if isShowBack {
				Button { onBackPressed?() } label: {
					Image(systemName: "chevron.left").frame(width: 44, height: 44)
				}.accessibilityLabel("Back")
			}
			Spacer()
			NavigationLink(isActive: $isShowingManual) {
				ThirdPartySipAccountLoginFragment(accountLoginViewModel: AccountLoginViewModel())
			} label: { EmptyView() }
			Button {
				settingsPassword = ""; settingsPasswordError = false; settingsUnlocked = false
				isShowingSettingsGate = true
			} label: {
				Image(systemName: "gearshape").font(.system(size: 21)).frame(width: 44, height: 44)
			}.accessibilityLabel("Connection settings")
			Button { withAnimation { isShowHelpFragment = true } } label: {
				Image(systemName: "questionmark.circle").font(.system(size: 21)).frame(width: 44, height: 44)
			}.accessibilityLabel("Help")
		}.foregroundStyle(Color.white.opacity(0.85)).padding(.horizontal, 12).padding(.top, 6)
	}

	private var settingsGate: some View {
		NavigationView {
			Form {
				SecureField("Settings password", text: $settingsPassword)
					.textInputAutocapitalization(.never).disableAutocorrection(true)
					.submitLabel(.go).onSubmit(unlockSettings)
					.accessibilityIdentifier("settings.unlockPassword")
				if settingsPasswordError {
					Text("Incorrect settings password").foregroundStyle(Color.red)
				}
			}.navigationTitle("Connection settings").navigationBarTitleDisplayMode(.inline)
				.toolbar {
					ToolbarItem(placement: .cancellationAction) {
						Button("Cancel") { isShowingSettingsGate = false }
					}
					ToolbarItem(placement: .confirmationAction) {
						Button("Unlock", action: unlockSettings).disabled(settingsPassword.isEmpty)
					}
				}
		}.navigationViewStyle(.stack).preferredColorScheme(.light).tint(.mango9Primary)
	}

	private func unlockSettings() {
		let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
		guard let version, !version.isEmpty,
			settingsPassword.trimmingCharacters(in: .whitespacesAndNewlines) == version else {
			settingsPasswordError = true
			return
		}
		settingsUnlocked = true
		isShowingSettingsGate = false
	}

	private var brandCard: some View {
		Image("mango9-logo").renderingMode(.template).resizable().scaledToFit().foregroundStyle(Color.white)
			.frame(width: 190, height: 56).accessibilityLabel("Mango9")
	}

	private var enrollmentCard: some View {
		VStack(spacing: 12) {
			Text(loginMode == .password ? "Sign in" : loginMode == .emailRequest ? "Email sign in" : "Check your email")
				.font(.largeTitle.weight(.bold)).foregroundStyle(Color.white)
				.padding(.bottom, loginMode == .password ? 18 : 2)
			if loginMode != .password {
				Text(loginSubtitle).font(.subheadline).foregroundStyle(Color.white.opacity(0.8))
					.multilineTextAlignment(.center).padding(.bottom, 8)
			}
			Group {
				switch loginMode {
				case .password: passwordLoginFields
				case .emailRequest: emailRequestFields
				case .emailVerification: emailVerificationFields
				}
			}
			Toggle("Keep me signed in", isOn: $rememberLogin)
				.font(.body).foregroundStyle(Color.white)
				.toggleStyle(SwitchToggleStyle(tint: Color.white.opacity(0.35)))
				.frame(minHeight: 44).padding(.top, 6)
			if let signInError {
				Text(signInError).font(.footnote).foregroundStyle(Color(red: 1, green: 0.84, blue: 0.87))
					.multilineTextAlignment(.center)
			}
			primaryLoginButton
			Button {
				if loginMode == .password { showEmailLogin() } else { showPasswordLogin() }
			} label: {
				Label(loginMode == .password ? "Use an email code" : "Use password instead",
					systemImage: loginMode == .password ? "envelope" : "key")
					.font(.headline).foregroundStyle(Color.white)
					.frame(maxWidth: .infinity, minHeight: 52)
					.overlay(Capsule().stroke(Color.white.opacity(0.8), lineWidth: 1))
			}.buttonStyle(.plain).disabled(isSigningIn)
		}.frame(maxWidth: 520).padding(.horizontal, 25)
	}

	private var loginSubtitle: String {
		switch loginMode {
		case .password:
			return "Sign in with your Mango9 account or use a code sent to your email."
		case .emailRequest:
			return "Enter the email address on your Mango9 account."
		case .emailVerification:
			return "Enter the 6-digit code sent to \(email)."
		}
	}

	private var passwordLoginFields: some View {
		Group {
			TextField("Email or username", text: $username, prompt: Text("Email or username").foregroundColor(Color.white.opacity(0.85)))
				.textContentType(.username).textInputAutocapitalization(.never).disableAutocorrection(true)
				.submitLabel(.next).focused($focusedField, equals: .username)
				.onSubmit {
					focusedField = nil
					DispatchQueue.main.async { focusedField = .password }
				}.mango9SignInField()
			HStack(spacing: 8) {
				Group {
					if isPasswordVisible {
						TextField("Password", text: $password, prompt: Text("Password").foregroundColor(Color.white.opacity(0.85)))
							.focused($focusedField, equals: .password)
					} else {
						SecureField("Password", text: $password, prompt: Text("Password").foregroundColor(Color.white.opacity(0.85)))
							.focused($focusedField, equals: .password)
					}
				}.textContentType(.password).textInputAutocapitalization(.never).disableAutocorrection(true)
					.submitLabel(.go).onSubmit(signIn)
				Button { isPasswordVisible.toggle() } label: {
					Image(systemName: isPasswordVisible ? "eye.slash" : "eye").font(.title3)
						.frame(width: 44, height: 44)
				}.buttonStyle(.plain).accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
			}.mango9SignInField()
		}
	}

	private var emailRequestFields: some View {
		TextField("Email address", text: $email, prompt: Text("Email address").foregroundColor(Color.white.opacity(0.85)))
			.textContentType(.emailAddress).keyboardType(.emailAddress)
			.textInputAutocapitalization(.never).disableAutocorrection(true)
			.onSubmit(requestEmailCode).mango9SignInField()
	}

	private var emailVerificationFields: some View {
		VStack(spacing: 12) {
			TextField("6-digit code", text: $verificationCode)
				.textContentType(.oneTimeCode)
				.keyboardType(.numberPad)
				.multilineTextAlignment(.center)
				.font(.system(size: 24, weight: .semibold, design: .rounded))
				.onChange(of: verificationCode) { value in
					let digits = value.filter(\.isNumber)
					let normalizedCode = String(digits.prefix(6))
					if verificationCode != normalizedCode {
						verificationCode = normalizedCode
					}
				}
				.mango9SignInField()

			HStack {
				Button("Change email") {
					showEmailLogin()
				}
				.default_text_style_600(styleSize: 13)
				.foregroundStyle(Color.white)
				.buttonStyle(.plain)
				.disabled(isSigningIn)

				Spacer()

				Button {
					requestEmailCode()
				} label: {
					Text(resendCountdown > 0 ? "Resend in \(resendCountdown)s" : "Resend code")
						.default_text_style_600(styleSize: 13)
						.foregroundStyle(resendCountdown > 0 ? Color.grayMain2c500 : Color.mango9Primary)
				}
				.buttonStyle(.plain)
				.disabled(resendCountdown > 0 || isSigningIn)
			}
		}
	}

	private var primaryLoginButton: some View {
		Button(action: primaryLoginAction) {
			HStack {
				if isSigningIn { ProgressView().tint(signInAccent) }
				Text(primaryButtonTitle).font(.title3.weight(.bold))
			}.foregroundStyle(signInAccent).frame(maxWidth: .infinity, minHeight: 52)
				.background(Color.white)
				.clipShape(Capsule())
		}.buttonStyle(.plain).disabled(!canPerformPrimaryAction).opacity(isSigningIn ? 0.75 : 1)
	}

	private var primaryButtonTitle: String {
		if isSigningIn {
			return loginMode == .emailRequest ? "Sending…" : "Signing in…"
		}
		switch loginMode {
		case .password:
			return "Sign in"
		case .emailRequest:
			return "Send Login Code"
		case .emailVerification:
			return "Verify and Sign In"
		}
	}

	private var canPerformPrimaryAction: Bool {
		switch loginMode {
		case .password:
			return canSignIn
		case .emailRequest:
			return isValidEmail(email) && !isSigningIn
		case .emailVerification:
			return verificationCode.count == 6 && !isSigningIn
		}
	}

	private func primaryLoginAction() {
		switch loginMode {
		case .password:
			signIn()
		case .emailRequest:
			requestEmailCode()
		case .emailVerification:
			verifyEmailCode()
		}
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
				closeAccountLoginAfterSuccess()
			} catch {
				signInError = Mango9LoginService.userFacingMessage(for: error)
			}
			isSigningIn = false
		}
	}

	private func showEmailLogin() {
		resendTimerTask?.cancel()
		resendCountdown = 0
		signInError = nil
		verificationCode = ""
		if email.isEmpty, username.contains("@") {
			email = username
		}
		loginMode = .emailRequest
	}

	private func showPasswordLogin() {
		resendTimerTask?.cancel()
		resendCountdown = 0
		verificationCode = ""
		signInError = nil
		loginMode = .password
	}

	private func isValidEmail(_ candidate: String) -> Bool {
		let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
		guard let at = trimmed.firstIndex(of: "@"), at != trimmed.startIndex else {
			return false
		}
		return trimmed[trimmed.index(after: at)...].contains(".")
	}

	private func requestEmailCode() {
		guard isValidEmail(email), !isSigningIn else { return }
		let submittedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		email = submittedEmail
		signInError = nil
		isSigningIn = true

		Task {
			do {
				let resendAfter = try await Mango9LoginService.requestLoginCode(email: submittedEmail)
				verificationCode = ""
				loginMode = .emailVerification
				startResendCountdown(seconds: resendAfter)
			} catch {
				signInError = Mango9LoginService.userFacingMessage(for: error)
			}
			isSigningIn = false
		}
	}

	private func verifyEmailCode() {
		guard verificationCode.count == 6, !isSigningIn else { return }
		let submittedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		let submittedCode = verificationCode
		signInError = nil
		isSigningIn = true

		Task {
			do {
				try await Mango9LoginService.verifyLoginCode(
					email: submittedEmail,
					code: submittedCode,
					rememberLogin: rememberLogin
				)
				verificationCode = ""
				resendTimerTask?.cancel()
				ToastViewModel.shared.show("Success_mango9_account_connected")
				closeAccountLoginAfterSuccess()
			} catch {
				verificationCode = ""
				signInError = Mango9LoginService.userFacingMessage(for: error)
			}
			isSigningIn = false
		}
	}

	private func closeAccountLoginAfterSuccess() {
		guard isShowBack else { return }
		withAnimation {
			onBackPressed?()
		}
	}

	private func startResendCountdown(seconds: Int) {
		resendTimerTask?.cancel()
		resendCountdown = max(0, seconds)
		guard resendCountdown > 0 else { return }

		resendTimerTask = Task {
			while !Task.isCancelled && resendCountdown > 0 {
				try? await Task.sleep(nanoseconds: 1_000_000_000)
				guard !Task.isCancelled else { return }
				resendCountdown -= 1
			}
		}
	}
}

private extension View {
	@ViewBuilder func mango9SignInScroll() -> some View {
		if #available(iOS 16.0, *) { self.scrollDismissesKeyboard(.interactively) } else { self }
	}

	func mango9SignInField() -> some View {
		self.font(.body).foregroundStyle(Color.white).tint(Color.white)
			.padding(.horizontal, 18).frame(minHeight: 56)
			.background(Color.white.opacity(0.10))
			.clipShape(RoundedRectangle(cornerRadius: 14))
			.overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.4), lineWidth: 0.8))
	}
}

@MainActor
private enum Mango9LoginService {
	private enum SignInFailure: LocalizedError {
		case invalidEmail
		case invalidOrExpiredCode
		case emailCodeUnavailable
		case invalidCredentials
		case deviceAuthorization
		case accountChanged
		case accountNotProvisionable
		case rateLimited
		case crmUnavailable
		case invalidResponse

		var errorDescription: String? {
			switch self {
			case .invalidEmail:
				return "Enter a valid email address."
			case .invalidOrExpiredCode:
				return "That code is invalid or expired. Request a new code and try again."
			case .emailCodeUnavailable:
				return "Email code sign-in is temporarily unavailable. Use your password or try again later."
			case .invalidCredentials:
				return "The CRM username or password is incorrect."
			case .deviceAuthorization:
				return "Device authorization could not be verified. Check your iPhone’s date and time, then try again."
			case .accountChanged:
				return "Account security changed while signing in. Please try again."
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
		let deviceProof: String

		enum CodingKeys: String, CodingKey {
			case username
			case password
			case deviceId = "device_id"
			case platform
			case deviceProof = "device_proof"
		}
	}

	private struct LoginCodeRequest: Encodable {
		let email: String
		let deviceId: String

		enum CodingKeys: String, CodingKey {
			case email
			case deviceId = "device_id"
		}
	}

	private struct LoginCodeVerifyRequest: Encodable {
		let email: String
		let code: String
		let deviceId: String
		let platform: String
		let deviceProof: String

		enum CodingKeys: String, CodingKey {
			case email
			case code
			case deviceId = "device_id"
			case platform
			case deviceProof = "device_proof"
		}
	}

	private struct LoginCodeRequestResponse: Decodable {
		let success: Bool
		let resendAfter: Int?
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
		let sessionId: String?
		let refreshProtocol: Int?
		let dpopJkt: String?
		let logoutToken: String?
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
		let proofKeyID = try Mango9DeviceProof.create()
		defer { Mango9DeviceProof.removeIfUnused(proofKeyID) }
		let deviceProof = try Mango9DeviceProof.proof(keyID: proofKeyID, url: endpoint)
		var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.httpBody = try JSONEncoder().encode(
			LoginRequest(
				username: username,
				password: password,
				deviceId: deviceId,
				platform: "ios",
				deviceProof: deviceProof
			)
		)

		let (data, response) = try await Mango9AuthTransport.session.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw SignInFailure.invalidResponse
		}
		guard (200..<300).contains(httpResponse.statusCode) else {
			let serverError = try? JSONDecoder().decode(ErrorResponse.self, from: data)
			switch (httpResponse.statusCode, serverError?.error) {
			case (400, "invalid_dpop_proof"):
				throw SignInFailure.deviceAuthorization
			case (409, "authentication_state_changed"):
				throw SignInFailure.accountChanged
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
		try await completeLogin(login, rememberLogin: rememberLogin, proofKeyID: proofKeyID)
	}

	static func requestLoginCode(email: String) async throws -> Int {
		let endpoint = Mango9Configuration.provisioningBaseURL
			.appendingPathComponent("v1/mobile/login-code/request")
		var request = URLRequest(url: endpoint)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.httpBody = try JSONEncoder().encode(
			LoginCodeRequest(email: email, deviceId: deviceId)
		)

		let (data, response) = try await Mango9AuthTransport.session.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw SignInFailure.invalidResponse
		}
		guard (200..<300).contains(httpResponse.statusCode) else {
			let serverError = try? JSONDecoder().decode(ErrorResponse.self, from: data)
			switch (httpResponse.statusCode, serverError?.error) {
			case (400, "invalid_dpop_proof"):
				throw SignInFailure.deviceAuthorization
			case (409, "authentication_state_changed"):
				throw SignInFailure.accountChanged
			case (404, _):
				throw SignInFailure.emailCodeUnavailable
			case (400, "invalid_email"):
				throw SignInFailure.invalidEmail
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
		let result = try decoder.decode(LoginCodeRequestResponse.self, from: data)
		guard result.success else {
			throw SignInFailure.invalidResponse
		}
		return max(0, result.resendAfter ?? 60)
	}

	static func verifyLoginCode(
		email: String,
		code: String,
		rememberLogin: Bool
	) async throws {
		let endpoint = Mango9Configuration.provisioningBaseURL
			.appendingPathComponent("v1/mobile/login-code/verify")
		let proofKeyID = try Mango9DeviceProof.create()
		defer { Mango9DeviceProof.removeIfUnused(proofKeyID) }
		let deviceProof = try Mango9DeviceProof.proof(keyID: proofKeyID, url: endpoint)
		var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.httpBody = try JSONEncoder().encode(
			LoginCodeVerifyRequest(
				email: email,
				code: code,
				deviceId: deviceId,
				platform: "ios",
				deviceProof: deviceProof
			)
		)

		let (data, response) = try await Mango9AuthTransport.session.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw SignInFailure.invalidResponse
		}
		guard (200..<300).contains(httpResponse.statusCode) else {
			let serverError = try? JSONDecoder().decode(ErrorResponse.self, from: data)
			switch (httpResponse.statusCode, serverError?.error) {
			case (400, "invalid_dpop_proof"):
				throw SignInFailure.deviceAuthorization
			case (409, "authentication_state_changed"):
				throw SignInFailure.accountChanged
			case (404, _):
				throw SignInFailure.emailCodeUnavailable
			case (400, "invalid_email"):
				throw SignInFailure.invalidEmail
			case (400, "invalid_code"),
				 (401, "invalid_or_expired_code"):
				throw SignInFailure.invalidOrExpiredCode
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
		try await completeLogin(login, rememberLogin: rememberLogin, proofKeyID: proofKeyID)
	}

	private static var deviceId: String {
		let defaultsKey = "mango9_login_device_uuid"
		if let storedDeviceId = UserDefaults.standard.string(forKey: defaultsKey),
		   !storedDeviceId.isEmpty {
			return storedDeviceId
		}

		let generatedDeviceId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
		UserDefaults.standard.set(generatedDeviceId, forKey: defaultsKey)
		return generatedDeviceId
	}

	private static func completeLogin(
		_ login: LoginResponse,
		rememberLogin: Bool,
		proofKeyID: String
	) async throws {
		guard login.tokens.refreshProtocol == 2, login.tokens.sessionId != nil, login.tokens.logoutToken != nil,
			login.tokens.dpopJkt == (try Mango9DeviceProof.thumbprint(keyID: proofKeyID)) else {
			throw SignInFailure.invalidResponse
		}
		guard let provisioningURL = Mango9Configuration.verifiedProvisioningURL(from: login.enrollmentUrl) else {
			throw URLError(.badServerResponse)
		}
		let enrollment = try await Mango9AccountProvisioner.fetchEnrollment(
			from: provisioningURL
		)
		var session = Mango9Session(
			proofKeyID: proofKeyID,
			sessionFamilyID: login.tokens.sessionId,
			logoutToken: login.tokens.logoutToken,
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
		try await Mango9LogoutCoordinator.shared.prepareLogin(identity: enrollment.identity, session: session, freshAuthentication: true)
		session = try Mango9SessionStore.save(
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
		try await Mango9LogoutCoordinator.shared.prepareLogin(identity: identity, session: associatedSession)
		try Mango9SessionStore.activateExisting(associatedSession)
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


/// The Secure Enclave private key stays on this device; only its public JWK leaves it.
enum Mango9DeviceProof {
	private static func tag(_ id: String) -> Data { Data(((Bundle.main.bundleIdentifier ?? "com.mango9.linphone") + ".session-proof." + id).utf8) }
	static func create() throws -> String {
		let id = UUID().uuidString
		var error: Unmanaged<CFError>?
		guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, &error) else {
			throw Mango9SessionStoreError.keychain(errSecInteractionNotAllowed)
		}
		let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
			kSecAttrKeySizeInBits as String: 256, kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
			kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true,
				kSecAttrApplicationTag as String: tag(id), kSecAttrAccessControl as String: access]]
		guard SecKeyCreateRandomKey(attributes as CFDictionary, &error) != nil else {
			if let error { throw error.takeRetainedValue() }
			throw Mango9SessionStoreError.keychain(errSecNotAvailable)
		}
		return id
	}
	static func removeIfUnused(_ id: String) {
		guard !Mango9SessionStore.retainsProofKey(id) else { return }
		SecItemDelete([kSecClass as String:kSecClassKey, kSecAttrApplicationTag as String:tag(id),
			kSecAttrKeyType as String:kSecAttrKeyTypeECSECPrimeRandom] as CFDictionary)
	}
	private static func key(_ id: String) throws -> SecKey {
		let query: [String: Any] = [kSecClass as String: kSecClassKey,
			kSecAttrApplicationTag as String: tag(id), kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
			kSecReturnRef as String: true]
		var result: CFTypeRef?
		let status = SecItemCopyMatching(query as CFDictionary, &result)
		if status == errSecItemNotFound { throw Mango9CRMAPIError.sessionExpired }
		guard status == errSecSuccess, let result else { throw Mango9SessionStoreError.keychain(status) }
		return result as! SecKey
	}
	private static func jwk(_ key: SecKey) throws -> [String: String] {
		var error: Unmanaged<CFError>?
		guard let publicKey = SecKeyCopyPublicKey(key),
			let data = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?, data.count == 65, data.first == 4 else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		return ["kty":"EC", "crv":"P-256", "x":base64(Data(data[1..<33])), "y":base64(Data(data[33..<65]))]
	}
	static func thumbprint(keyID: String) throws -> String {
		let data = try JSONSerialization.data(withJSONObject: jwk(key(keyID)), options: [.sortedKeys, .withoutEscapingSlashes])
		return base64(Data(SHA256.hash(data: data)))
	}
	static func proof(keyID: String, url: URL) throws -> String {
		guard url.scheme == "https", url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		let privateKey = try key(keyID)
		let header: [String: Any] = ["typ":"dpop+jwt", "alg":"ES256", "jwk":try jwk(privateKey)]
		let payload: [String: Any] = ["jti":UUID().uuidString, "htm":"POST", "htu":url.absoluteString, "iat":Int(Date().timeIntervalSince1970)]
		let input = try base64(JSONSerialization.data(withJSONObject: header, options: [.sortedKeys, .withoutEscapingSlashes])) + "." + base64(JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .withoutEscapingSlashes]))
		var error: Unmanaged<CFError>?
		guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, Data(input.utf8) as CFData, &error) as Data? else {
			if let error { throw error.takeRetainedValue() }
			throw Mango9SessionStoreError.keychain(errSecInteractionNotAllowed)
		}
		let raw = try P256.Signing.ECDSASignature(derRepresentation: signature).rawRepresentation
		return input + "." + base64(raw)
	}
	private static func base64(_ data: Data) -> String {
		data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
	}
}

struct Mango9Session: Codable {
	var loginGeneration: String? = nil
	var proofKeyID: String? = nil
	var sessionFamilyID: String? = nil
	var logoutToken: String? = nil
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
			loginGeneration: loginGeneration,
			proofKeyID: proofKeyID,
			sessionFamilyID: sessionFamilyID,
			logoutToken: logoutToken,
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
	private static let lifecycleLock = NSRecursiveLock()
	private struct Record: Codable {
		let version: Int
		let session: Mango9Session?
		let revoked: Bool
	}
	private static var storageFailure: Error?
	static var lastStorageError: Error? {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		return storageFailure
	}


	static func retainsProofKey(_ keyID: String) -> Bool {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		volatileSessionsLock.lock()
		let memoryUsesKey = volatileSessions.values.contains { $0.proofKeyID == keyID }
		volatileSessionsLock.unlock()
		if memoryUsesKey { return true }
		do {
			for identity in storedIdentities.union([legacyAccount]) {
				if try read(account: identity)?.session?.proofKeyID == keyID { return true }
			}
			return false
		} catch { return true } // Unavailable storage cannot justify destroying a key.
	}

	/// New authentication only. Renewal uses replaceTokens and cannot create a login.
	@discardableResult
	static func save(_ session: Mango9Session, for sipIdentity: String? = nil,
		persist: Bool? = nil, makeActive: Bool = false) throws -> Mango9Session {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let identity = normalizedIdentity(sipIdentity ?? session.sipIdentity ?? activeIdentity) else {
			throw Mango9SessionStoreError.missingIdentity
		}
		var associated = session.associated(with: identity)
		associated.loginGeneration = UUID().uuidString
		try write(associated, account: identity, persist: persist ?? rememberedLoginIsEnabled)
		setVolatileSession(associated, for: identity)
		var identities = storedIdentities; identities.insert(identity); storeIdentities(identities)
		if makeActive { setActiveIdentity(identity) }
		storageFailure = nil
		return associated
	}

	static func currentLogin(for snapshot: Mango9Session) -> Mango9Session? {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let identity = normalizedIdentity(snapshot.sipIdentity), let current = load(for: identity),
			(current.loginGeneration == snapshot.loginGeneration ||
				(snapshot.loginGeneration == nil && current.refreshToken == snapshot.refreshToken)),
			current.crmId == snapshot.crmId, current.userId == snapshot.userId,
			current.crmApiBaseUrl == snapshot.crmApiBaseUrl else { return nil }
		return current
	}

	/// Compare-and-save protects against logout, re-login and out-of-order completions.
	static func replaceTokens(expected: Mango9Session, renewed: Mango9Session) throws -> Mango9Session {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let current = currentLogin(for: expected), let identity = current.sipIdentity,
			current.refreshToken == expected.refreshToken, current.accessToken == expected.accessToken else {
			throw Mango9CRMAPIError.staleSession
		}
		guard renewed.crmApiBaseUrl == current.crmApiBaseUrl, renewed.crmId == current.crmId,
			renewed.userId == current.userId, renewed.sipIdentity == current.sipIdentity,
			renewed.loginGeneration == current.loginGeneration,
			renewed.proofKeyID == current.proofKeyID,
			(current.sessionFamilyID == nil || renewed.sessionFamilyID == current.sessionFamilyID),
			!renewed.accessToken.isEmpty, !renewed.refreshToken.isEmpty else { throw Mango9CRMAPIError.server }
		try write(renewed, account: identity, persist: rememberedLoginIsEnabled)
		setVolatileSession(renewed, for: identity)
		storageFailure = nil
		return renewed
	}

	static func activateExisting(_ snapshot: Mango9Session) throws {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let current = currentLogin(for: snapshot), let identity = current.sipIdentity else {
			throw Mango9CRMAPIError.staleSession
		}
		setActiveIdentity(identity); notifyContextChanged()
	}

	private static func write(_ session: Mango9Session?, account: String, persist: Bool) throws {
		let record = Record(version: 1, session: persist ? session : nil, revoked: !persist || session == nil)
		let key: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service, kSecAttrAccount as String: account]
		let attributes: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(record),
			kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
		let status = SecItemUpdate(key as CFDictionary, attributes as CFDictionary)
		if status == errSecSuccess { return }
		guard status == errSecItemNotFound else { throw Mango9SessionStoreError.keychain(status) }
		var item = key; attributes.forEach { item[$0.key] = $0.value }
		let added = SecItemAdd(item as CFDictionary, nil)
		guard added == errSecSuccess else { throw Mango9SessionStoreError.keychain(added) }
	}

	static func load() -> Mango9Session? {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		storageFailure = nil
		if let activeIdentity {
			return load(for: activeIdentity)
		}
		return loadLegacySession()
	}

	static func load(for sipIdentity: String) -> Mango9Session? {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		storageFailure = nil
		guard let identity = normalizedIdentity(sipIdentity) else {
			return nil
		}
		do {
			if try Mango9LogoutKeychain.blocksLogin(identity) { return nil }
		} catch { storageFailure = error; return nil }
		if let session = volatileSession(for: identity) {
			return session
		}
		guard rememberedLoginIsEnabled else {
			return nil
		}

		do {
			if let record = try read(account: identity) {
				guard !record.revoked, let session = record.session,
					normalizedIdentity(session.sipIdentity) == identity else { return nil }
				let associated = session.associated(with: identity)
				setVolatileSession(associated, for: identity)
				return associated
			}
			guard let legacy = try read(account: legacyAccount), !legacy.revoked,
				let session = legacy.session,
				normalizedIdentity(session.sipIdentity) == identity else { return nil }
			let migrated = try save(session, for: identity)
			try write(nil, account: legacyAccount, persist: false)
			return migrated
		} catch { storageFailure = error; return nil }
	}

	static func hasSession(for sipIdentity: String) -> Bool { load(for: sipIdentity) != nil }

	static func activate(sipIdentity: String?) {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let identity = normalizedIdentity(sipIdentity) else {
			UserDefaults.standard.removeObject(forKey: activeIdentityKey)
			notifyContextChanged()
			return
		}
		setActiveIdentity(identity)
		_ = load(for: identity)
		notifyContextChanged()
	}

	static func remove(for sipIdentity: String) throws {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		guard let identity = normalizedIdentity(sipIdentity) else { return }
		// A checked secure tombstone survives a crash and defeats late renewal callbacks.
		try write(nil, account: identity, persist: false)
		setVolatileSession(nil, for: identity)
		Mango9SupervisorLocalStore.remove(for: identity)
		var identities = storedIdentities
		identities.remove(identity)
		storeIdentities(identities)
		Mango9LineIdentityStore.clear(sipIdentity: identity)
		if activeIdentity == identity {
			UserDefaults.standard.removeObject(forKey: activeIdentityKey)
			notifyContextChanged()
		}
	}

	static func clear() throws {
		lifecycleLock.lock(); defer { lifecycleLock.unlock() }
		if let activeIdentity {
			try remove(for: activeIdentity)
		} else {
			try write(nil, account: legacyAccount, persist: false)
			notifyContextChanged()
		}
	}

	static var activeIdentity: String? {
		normalizedIdentity(
			UserDefaults.standard.string(forKey: activeIdentityKey)
		)
	}

	static func isActive(_ session: Mango9Session) -> Bool {
		isActive(sipIdentity: session.sipIdentity) && currentLogin(for: session) != nil
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
		guard rememberedLoginIsEnabled else { return nil }
		do {
			guard let record = try read(account: legacyAccount), !record.revoked,
				let identity = normalizedIdentity(record.session?.sipIdentity) else { return nil }
			return load(for: identity)
		} catch { storageFailure = error; return nil }
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

	private static func read(account: String) throws -> Record? {
		let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
			kSecAttrService as String: service, kSecAttrAccount as String: account,
			kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
		var result: CFTypeRef?
		let status = SecItemCopyMatching(query as CFDictionary, &result)
		if status == errSecItemNotFound { return nil }
		guard status == errSecSuccess, let data = result as? Data else { throw Mango9SessionStoreError.keychain(status) }
		if let record = try? JSONDecoder().decode(Record.self, from: data) {
			guard record.version == 1 else { throw Mango9SessionStoreError.invalidRecord }
			return record
		}
		guard let session = try? JSONDecoder().decode(Mango9Session.self, from: data) else {
			throw Mango9SessionStoreError.invalidRecord
		}
		return Record(version: 1, session: session, revoked: false)
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

enum Mango9SessionStoreError: LocalizedError {
	case keychain(OSStatus), missingIdentity, invalidRecord
	var errorDescription: String? { "Mango9 couldn’t access secure account storage. Unlock your iPhone and try again." }
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
