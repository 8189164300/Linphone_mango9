/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 *
 * This file is part of linphone-iphone.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import SwiftUI

struct Mango9CRMFragment: View {
	@Binding var isPresented: Bool
	let initialLeadId: Int?
	let onConnectAccount: () -> Void
	let onOpenConversations: () -> Void

	@StateObject private var viewModel = Mango9CRMViewModel()
	@ObservedObject private var chatStore = Mango9ChatStore.shared
	@State private var pushedLeadId: Int?
	@State private var showPushedLead: Bool

	init(
		isPresented: Binding<Bool>,
		initialLeadId: Int? = nil,
		onConnectAccount: @escaping () -> Void,
		onOpenConversations: @escaping () -> Void
	) {
		_isPresented = isPresented
		self.initialLeadId = initialLeadId
		self.onConnectAccount = onConnectAccount
		self.onOpenConversations = onOpenConversations
		_pushedLeadId = State(initialValue: initialLeadId)
		_showPushedLead = State(initialValue: initialLeadId != nil)
	}

	var body: some View {
		NavigationView {
			ZStack {
				Color.gray100
					.ignoresSafeArea()

				VStack(spacing: 0) {
					header

					ScrollView {
						VStack(spacing: 16) {
							accountCard

							if viewModel.session != nil {
								workspace
							} else {
								connectAccountCard
							}

							if let errorMessage = viewModel.errorMessage {
								errorCard(message: errorMessage)
							}
						}
						.padding(16)
						.padding(.bottom, 24)
					}
					.refreshable {
						await viewModel.reload()
					}
				}
			}
			.navigationTitle("")
			.navigationBarHidden(true)
			.background {
				if let pushedLeadId {
					NavigationLink(
						destination: Mango9LeadDetailFragment(leadId: pushedLeadId),
						isActive: $showPushedLead
					) {
						EmptyView()
					}
					.hidden()
				}
			}
		}
		.navigationViewStyle(StackNavigationViewStyle())
		.task {
			await viewModel.reload()
		}
		.onReceive(
			NotificationCenter.default.publisher(for: .mango9AccountContextChanged)
		) { _ in
			Task {
				await viewModel.reload()
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AppointmentDidChange)) { _ in Task { await viewModel.reloadAppointmentCount() } }
		.onChange(of: initialLeadId) { leadId in
			guard let leadId else { return }
			pushedLeadId = leadId
			showPushedLead = true
		}
	}

	private var header: some View {
		HStack(spacing: 8) {
			Button {
				withAnimation {
					isPresented = false
				}
			} label: {
				Image("caret-left")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 25, height: 25)
					.padding(10)
			}

			VStack(alignment: .leading, spacing: 1) {
				Text("CRM")
					.default_text_style_orange_800(styleSize: 18)
				Text("Mango9 customer workspace")
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
			}

			Spacer()
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle()
				.fill(Color.gray200)
				.frame(height: 1)
		}
	}

	private var accountCard: some View {
		HStack(spacing: 14) {
			ZStack {
				RoundedRectangle(cornerRadius: 14)
					.fill(Color.orangeMain500)
				Image("address-book")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.white)
					.frame(width: 28, height: 28)
			}
			.frame(width: 54, height: 54)

			VStack(alignment: .leading, spacing: 4) {
				Text(viewModel.session?.displayName ?? viewModel.session?.loginId ?? "Mango9 CRM")
					.default_text_style_800(styleSize: 16)
					.lineLimit(1)

				if let session = viewModel.session {
					Text(session.loginId)
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
						.lineLimit(1)

					Text(session.role.capitalized)
						.default_text_style_orange_600(styleSize: 11)
				} else {
					Text("Connect your Mango9 account to open CRM data.")
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
				}
			}

			Spacer()

			if viewModel.isLoading {
				ProgressView()
					.tint(Color.orangeMain500)
			} else if viewModel.session != nil {
				Text(viewModel.connectionLabel)
					.font(.system(size: 9, weight: .bold))
					.foregroundStyle(viewModel.connectionLabel == "CONNECTED" ? Color.greenSuccess500 : Color.grayMain2c500)
					.padding(.horizontal, 8)
					.padding(.vertical, 5)
					.background(Color.greenSuccess200.opacity(0.45))
					.cornerRadius(20)
			}
		}
		.padding(16)
		.background(Color.white)
		.cornerRadius(16)
		.shadow(color: Color.gray200.opacity(0.6), radius: 5, x: 0, y: 2)
	}

	private var workspace: some View {
		VStack(alignment: .leading, spacing: 12) {
			Text("CRM workspace")
				.default_text_style_800(styleSize: 17)
				.frame(maxWidth: .infinity, alignment: .leading)

			NavigationLink(destination: Mango9LeadsFragment()) {
				workspaceRow(
					icon: "person.2.fill",
					title: "Leads",
					subtitle: "Your sales pipeline",
					color: .purple,
					count: viewModel.dashboard?.leads
				)
			}
			.buttonStyle(.plain)

			NavigationLink(destination: Mango9ClientsFragment()) {
				workspaceRow(
					icon: "person.crop.square.fill",
					title: "Clients",
					subtitle: "Customer records and relationships",
					color: .orange,
					count: viewModel.dashboard?.clients
				)
			}
			.buttonStyle(.plain)

			Button(action: onOpenConversations) {
				workspaceRow(
					icon: "bubble.left.and.bubble.right.fill",
					title: "Conversations",
					subtitle: "SMS, Team and Social messages",
					color: .pink,
					unreadCount: chatStore.teamUnreadCount
				)
			}
			.buttonStyle(.plain)

			NavigationLink(destination: Mango9AppointmentsFragment(presentation: .calendar)) {
				workspaceRow(icon: "calendar", title: "Calendar",
					subtitle: "Your schedule by month, week or day", color: .mango9Primary)
			}.buttonStyle(.plain)
			NavigationLink(destination: Mango9AppointmentsFragment(presentation: .activities)) {
				workspaceRow(icon: "checklist", title: "Events & Tasks",
					subtitle: "Appointments, events and tasks", color: .indigo)
			}.buttonStyle(.plain)
			NavigationLink(destination: Mango9CRMSettings()) {
				workspaceRow(icon: "slider.horizontal.3", title: "CRM Settings", subtitle: "Calendar, reminders and message notifications", color: .indigo)
			}.buttonStyle(.plain)
		}
	}

	private func workspaceRow(
		icon: String,
		title: String,
		subtitle: String,
		color: Color,
		count: Int? = nil,
		unreadCount: Int = 0
	) -> some View {
		HStack(spacing: 12) {
			Image(systemName: icon)
				.renderingMode(.template)
				.resizable()
				.scaledToFit()
				.foregroundStyle(color)
				.frame(width: 25, height: 25)
				.padding(10)
				.background(color.opacity(0.12))
				.cornerRadius(11)

			VStack(alignment: .leading, spacing: 3) {
				Text(title)
					.default_text_style_700(styleSize: 14)
				Text(subtitle)
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
			}

			Spacer()
			if let count {
				Text(count.formatted()).font(.system(size: 19, weight: .bold, design: .rounded))
					.foregroundColor(color).accessibilityLabel("\(count) \(title)")
			}

			if unreadCount > 0 {
				Text(unreadCount < 100 ? String(unreadCount) : "99+")
					.font(.system(size: 10, weight: .bold))
					.foregroundStyle(Color.white)
					.frame(minWidth: 22, minHeight: 22)
					.padding(.horizontal, unreadCount > 9 ? 4 : 0)
					.background(Color.redDanger500)
					.clipShape(Capsule())
					.accessibilityLabel("\(unreadCount) unread Team Chat messages")
			}
			Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundColor(.secondary)
		}
		.padding(13)
		.background(Color.white)
		.cornerRadius(14)
		.overlay {
			RoundedRectangle(cornerRadius: 14)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private var connectAccountCard: some View {
		VStack(spacing: 14) {
			Image("user-circle-gear")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.orangeMain500)
				.frame(width: 42, height: 42)

			Text("CRM sign-in required")
				.default_text_style_800(styleSize: 17)

			Text("Connect with a Mango9 CRM login to load the correct tenant, permissions, dashboard, messaging services, and SIP assignment.")
				.default_text_style(styleSize: 13)
				.foregroundStyle(Color.grayMain2c500)
				.multilineTextAlignment(.center)

			Button {
				isPresented = false
				onConnectAccount()
			} label: {
				Text("Connect Mango9 account")
					.default_text_style_white_700(styleSize: 14)
					.frame(maxWidth: .infinity)
					.padding(.vertical, 13)
					.background(Color.orangeMain500)
					.cornerRadius(30)
			}
		}
		.padding(22)
		.background(Color.white)
		.cornerRadius(16)
	}

	private func errorCard(message: String) -> some View {
		HStack(alignment: .top, spacing: 10) {
			Image("warning-circle")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.redDanger500)
				.frame(width: 22, height: 22)

			VStack(alignment: .leading, spacing: 4) {
				Text("CRM data unavailable")
					.default_text_style_700(styleSize: 13)
				Text(message)
					.default_text_style(styleSize: 12)
					.foregroundStyle(Color.grayMain2c500)
			}

			Spacer()
		}
		.padding(14)
		.background(Color.redDanger200.opacity(0.45))
		.cornerRadius(12)
	}
}

@MainActor
final class Mango9CRMViewModel: ObservableObject {
	@Published private(set) var appointmentCount: Int?
	@Published private(set) var session: Mango9Session?
	@Published private(set) var dashboard: Mango9CRMDashboard?
	@Published private(set) var isLoading = false
	@Published private(set) var errorMessage: String?
	private var reloadGeneration = 0
	@Published private(set) var connectionLabel = "CONNECTING"

	func reload() async {
		reloadGeneration += 1
		let generation = reloadGeneration
		let storedSession = Mango9SessionStore.load()
		if session.map(Mango9CalendarAPI.accountKey) != storedSession.map(Mango9CalendarAPI.accountKey) {
			dashboard = nil
			appointmentCount = nil
		}
		session = storedSession
		errorMessage = nil
		connectionLabel = "CONNECTING"

		guard var currentSession = storedSession else {
			errorMessage = Mango9SessionStore.lastStorageError?.localizedDescription
			dashboard = nil
			isLoading = false
			return
		}

		isLoading = true
		defer {
			if reloadGeneration == generation {
				isLoading = false
			}
		}

		do {
			let context: (
				dashboard: Mango9CRMDashboard,
				team: [Mango9TeamMember]
			)
			do {
				context = try await loadContext(session: currentSession)
			} catch Mango9CRMAPIError.unauthorized {
				currentSession = try await Mango9CRMAPI.refresh(session: currentSession)

				context = try await loadContext(session: currentSession)
			}
			guard reloadGeneration == generation,
				  Mango9SessionStore.isActive(currentSession) else {
				return
			}
			session = currentSession
			dashboard = context.dashboard
			connectionLabel = "CONNECTED"
			ContactsManager.shared.syncMango9Team(context.team)
			await reloadAppointmentCount()
		} catch let error as Mango9CRMAPIError where error.isSignInRequired {
			if reloadGeneration == generation,
			   Mango9SessionStore.isActive(currentSession) {
				connectionLabel = "SIGN IN REQUIRED"
				errorMessage =
					"Your CRM session has expired. Connect the Mango9 account again."
			}
		} catch Mango9CRMAPIError.permissionDenied(let message) {
			if reloadGeneration == generation, Mango9SessionStore.isActive(currentSession) {
				connectionLabel = "ACCESS UNAVAILABLE"
				errorMessage = message
			}
		} catch is CancellationError {
			// Navigation and view refreshes routinely cancel view-bound work.
		} catch let error as URLError where error.code == .cancelled {
			// Keep the last dashboard instead of presenting a false network error.
		} catch {
			if reloadGeneration == generation,
			   Mango9SessionStore.isActive(currentSession) {
				Log.error(
					"[Mango9 CRM] Dashboard reload failed: \(String(reflecting: error))"
				)
				connectionLabel = "RECONNECTING"
				errorMessage = error is Mango9SessionStoreError ? error.localizedDescription : "Check the network connection and try again."
			}
		}
	}

	private func loadContext(
		// Calendar failures are intentionally isolated from the working leads/team dashboard.
		session: Mango9Session
	) async throws -> (
		dashboard: Mango9CRMDashboard,
		team: [Mango9TeamMember]
	) {
		let dashboard = try await Mango9CRMAPI.dashboard(session: session)
		let team = try await Mango9CRMAPI.teamMembers(session: session)
		return (dashboard, team)
	}
	func reloadAppointmentCount() async {
		guard let current = session, Mango9SessionStore.isActive(current) else { appointmentCount = nil; return }
		let generation = reloadGeneration
		do {
			let month = Mango9AppointmentsStore.monthRange(Date())
			let result = try await Mango9CalendarAPI.send(Mango9AppointmentPage.self, session: current, path: "events", query: [
				.init(name: "start_at", value: Mango9CalendarAPI.timestamp(month.start)),
				.init(name: "end_at", value: Mango9CalendarAPI.timestamp(month.end)), .init(name: "limit", value: "1")])
			guard generation == reloadGeneration, Mango9SessionStore.isActive(current) else { return }
			appointmentCount = result.pagination.total
		} catch { if generation == reloadGeneration, Mango9SessionStore.isActive(current) { appointmentCount = nil } }
	}
}

struct Mango9CRMDashboard: Decodable {
	struct Balance: Decodable {
		let total: Double
		let used: Double
		let remaining: Double
	}

	let balance: Balance
	let leads: Int
	let clients: Int
}

enum Mango9CRMAPI {
	struct Envelope<Payload: Decodable>: Decodable {
		let success: Bool
		let message: String
		let data: Payload?
	}

	struct RefreshPayload: Decodable {
		struct Tokens: Decodable {
			let accessToken: String
			let refreshToken: String
			let sessionId: String?
			let refreshProtocol: Int?
			let dpopJkt: String?
			let logoutToken: String?
		}

		let tokens: Tokens
	}

	static func dashboard(session: Mango9Session) async throws -> Mango9CRMDashboard {
		guard let baseURL = URL(string: session.crmApiBaseUrl) else {
			throw Mango9CRMAPIError.invalidConfiguration
		}

		var request = URLRequest(url: baseURL.appendingPathComponent("dashboard"))
		request.httpMethod = "GET"
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")

		let envelope: Envelope<Mango9CRMDashboard> = try await send(request)
		guard envelope.success, let dashboard = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return dashboard
	}

	static func refresh(session: Mango9Session, transport: URLSession = .shared) async throws -> Mango9Session {
		try await Mango9SessionRenewal.shared.refresh(session: session, transport: transport)
	}

	fileprivate static func rotate(session: Mango9Session, transport: URLSession) async throws -> Mango9Session {
		guard let baseURL = URL(string: session.crmApiBaseUrl), baseURL.scheme == "https",
			baseURL.host != nil, baseURL.user == nil, baseURL.password == nil,
			baseURL.query == nil, baseURL.fragment == nil else {
			throw Mango9CRMAPIError.invalidConfiguration
		}

		let refreshURL = baseURL
			.appendingPathComponent("auth")
			.appendingPathComponent("refresh")
		var request = URLRequest(url: refreshURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
		request.httpShouldHandleCookies = false
		request.httpMethod = "POST"
		if let keyID = session.proofKeyID {
			request.setValue(try Mango9DeviceProof.proof(keyID: keyID, url: refreshURL), forHTTPHeaderField: "DPoP")
		}
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(
			withJSONObject: ["refresh_token": session.refreshToken]
		)

		let envelope: Envelope<RefreshPayload> = try await send(request, transport: transport === URLSession.shared ? Mango9AuthTransport.session : transport, isRenewal: true)
		guard envelope.success, let tokens = envelope.data?.tokens else {
			throw Mango9CRMAPIError.server
		}

		if let keyID = session.proofKeyID {
			guard tokens.refreshProtocol == 2, tokens.sessionId == session.sessionFamilyID,
				tokens.dpopJkt == (try Mango9DeviceProof.thumbprint(keyID: keyID)) else { throw Mango9CRMAPIError.server }
		}
		return Mango9Session(
			loginGeneration: session.loginGeneration,
			proofKeyID: session.proofKeyID,
			sessionFamilyID: tokens.sessionId ?? session.sessionFamilyID,
			logoutToken: tokens.logoutToken ?? session.logoutToken,
			crmId: session.crmId,
			crmBaseUrl: session.crmBaseUrl,
			crmApiBaseUrl: session.crmApiBaseUrl,
			userId: session.userId,
			parentClientId: session.parentClientId,
			role: session.role,
			loginId: session.loginId,
			displayName: session.displayName,
			accessToken: tokens.accessToken,
			refreshToken: tokens.refreshToken,
			smsChatApi: session.smsChatApi,
			connectWebsocket: session.connectWebsocket,
			enrollmentExpiresAt: session.enrollmentExpiresAt,
			sipIdentity: session.sipIdentity
		)
	}

	static func send<Payload: Decodable>(
		_ request: URLRequest,
		transport: URLSession = .shared,
		isRenewal: Bool = false
	) async throws -> Envelope<Payload> {
		try await Mango9SessionRenewal.shared.checkKnownExpiredBearer(request)
		let (data, response) = try await transport.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw Mango9CRMAPIError.server
		}
		if isRenewal && [400,401].contains(httpResponse.statusCode) {
			let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String:Any]
			let code = payload?["error"] as? String
			let message = (payload?["message"] as? String)?.lowercased() ?? ""
			if code == "invalid_grant" || code == "stale_refresh_generation" || message == "invalid or expired refresh token" {
				throw Mango9CRMAPIError.unauthorized
			}
			if code == "invalid_dpop_proof" {
				throw Mango9CRMAPIError.serverMessage("Device authorization could not be verified. Check your iPhone’s date and time, then try again.")
			}
			throw Mango9CRMAPIError.server // A proxy challenge does not prove the login expired.
		}
		if httpResponse.statusCode == 401 { throw Mango9CRMAPIError.unauthorized }
		if httpResponse.statusCode == 403 {
			let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String:Any]
			throw Mango9CRMAPIError.permissionDenied(payload?["message"] as? String ?? "Account access is unavailable. Check your Mango9 permissions.")
		}
		if httpResponse.statusCode == 429 || httpResponse.statusCode == 503 {
			let value = httpResponse.value(forHTTPHeaderField: "Retry-After") ?? ""
			let format = DateFormatter()
			format.locale = Locale(identifier: "en_US_POSIX"); format.timeZone = TimeZone(secondsFromGMT: 0)
			format.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss z"
			let delay = TimeInterval(value) ?? format.date(from: value)?.timeIntervalSinceNow ?? 2
			throw Mango9CRMAPIError.retryAfter(min(max(delay, 1), 3600))
		}
		guard (200..<300).contains(httpResponse.statusCode) else {
			if let payload = try? JSONDecoder().decode(Mango9CRMErrorPayload.self, from: data),
			   let message = payload.message?.trimmingCharacters(in: .whitespacesAndNewlines),
			   !message.isEmpty {
				throw Mango9CRMAPIError.serverMessage(message)
			}
			throw Mango9CRMAPIError.server
		}

		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		return try decoder.decode(Envelope<Payload>.self, from: data)
	}
}

private struct Mango9CRMErrorPayload: Decodable {
	let message: String?
}

enum Mango9CRMAPIError: LocalizedError {
	case unauthorized
	case sessionExpired
	case staleSession
	case retryAfter(TimeInterval)
	case permissionDenied(String)
	case invalidConfiguration
	case server
	case serverMessage(String)
	var isSignInRequired: Bool {
		if case .unauthorized = self { return true }
		if case .sessionExpired = self { return true }
		return false
	}
	var errorDescription: String? {
		switch self {
		case .unauthorized, .sessionExpired: return "Your Mango9 session has expired. Sign in again to continue."
		case .staleSession: return "This account changed. Reopen the conversation to continue."
		case .retryAfter: return "Mango9 is temporarily unavailable. Please try again shortly."
		case .invalidConfiguration: return "Your Mango9 connection needs to be set up again. Reconnect your account in Settings."
		case .server: return "Mango9 is temporarily unavailable. Please try again shortly."
		case .serverMessage(let message), .permissionDenied(let message): return message
		}
	}
}

/// One credential operation per login, independent of the screen's task lifetime.
actor Mango9SessionRenewal {
	static let shared = Mango9SessionRenewal()
	private init() {}
	private var flights: [String: Task<Mango9Session, Error>] = [:]
	private var expired = Set<String>()
	private var expiredAccessTokens = Set<String>()

	func checkKnownExpiredBearer(_ request: URLRequest) throws {
		let fields = (request.value(forHTTPHeaderField: "Authorization") ?? "").split(separator: " ", maxSplits: 1)
		if fields.count == 2, fields[0].lowercased() == "bearer", expiredAccessTokens.contains(String(fields[1])) {
			throw Mango9CRMAPIError.sessionExpired
		}
	}
	private var retryAt: [String: Date] = [:]
	private var failures: [String: Int] = [:]

	func refresh(session snapshot: Mango9Session, transport: URLSession) async throws -> Mango9Session {
		guard let current = Mango9SessionStore.currentLogin(for: snapshot) else {
			if let storage = Mango9SessionStore.lastStorageError { throw storage }
			throw Mango9CRMAPIError.staleSession
		}
		let key = [current.crmApiBaseUrl, current.userId, current.sipIdentity ?? "", current.loginGeneration ?? "legacy"].joined(separator: "|")
		if current.refreshToken != snapshot.refreshToken || current.accessToken != snapshot.accessToken { return current }
		if expired.contains(key) { throw Mango9CRMAPIError.sessionExpired }
		if let flight = flights[key] { return try await flight.value }
		if let next = retryAt[key], next > Date() { throw Mango9CRMAPIError.retryAfter(next.timeIntervalSinceNow) }
		let flight = Task.detached(priority: .userInitiated) {
			let renewed = try await Mango9CRMAPI.rotate(session: current, transport: transport)
			return try Mango9SessionStore.replaceTokens(expected: current, renewed: renewed)
		}
		flights[key] = flight
		defer { flights.removeValue(forKey: key) }
		do {
			let result = try await flight.value
			failures.removeValue(forKey: key); retryAt.removeValue(forKey: key)
			return result
		} catch {
			guard let latest = Mango9SessionStore.currentLogin(for: current) else {
				if let storage = Mango9SessionStore.lastStorageError { throw storage }
				throw Mango9CRMAPIError.staleSession
			}
			if latest.refreshToken != current.refreshToken || latest.accessToken != current.accessToken { return latest }
			if case Mango9CRMAPIError.unauthorized = error {
				expired.insert(key); expiredAccessTokens.insert(current.accessToken)
				throw Mango9CRMAPIError.sessionExpired
			}
			let count = min((failures[key] ?? 0) + 1, 5); failures[key] = count
			var delay = Double.random(in: 1...min(30, pow(2, Double(count))))
			if case Mango9CRMAPIError.retryAfter(let serverDelay) = error { delay = max(delay, serverDelay) }
			retryAt[key] = Date().addingTimeInterval(delay)
			throw error
		}
	}
}

final class Mango9AuthTransport: NSObject, URLSessionTaskDelegate {
	static let delegate = Mango9AuthTransport()
	static let session: URLSession = {
		let config = URLSessionConfiguration.ephemeral
		config.timeoutIntervalForRequest = 25; config.timeoutIntervalForResource = 30
		return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
	}()
	func urlSession(_ session: URLSession, task: URLSessionTask,
		willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
		completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

#Preview {
	Mango9CRMFragment(
		isPresented: .constant(true),
		initialLeadId: nil,
		onConnectAccount: {},
		onOpenConversations: {}
	)
}
