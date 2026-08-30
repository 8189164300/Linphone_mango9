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
import UIKit
import AVFoundation
import AVKit
import PhotosUI
import UniformTypeIdentifiers
import MapKit
import linphonesw

struct Mango9LeadSchema: Decodable {
	struct Section: Decodable, Identifiable {
		let id: String
		let label: String
	}

	struct Field: Decodable, Identifiable {
		let key: String
		let fieldId: Int?
		let name: String?
		let label: String
		let type: String
		let section: String
		let required: Bool
		let editable: Bool
		let visible: Bool?
		let custom: Bool
		let options: [String]?

		var id: String { key }
		var isVisible: Bool { visible ?? true }
	}

	let entity: String
	let version: String
	let sections: [Section]
	let fields: [Field]
	let statuses: [String]
}

private enum Mango9ChatError: LocalizedError {
	case noSession
	case invalidEndpoint
	case disconnected
	case invalidResponse
	case server(String)

	var errorDescription: String? {
		switch self {
		case .noSession:
			return "Connect your Mango9 CRM account first."
		case .invalidEndpoint:
			return "The Mango9 chat endpoint is not configured."
		case .disconnected:
			return "The Mango9 chat server is disconnected."
		case .invalidResponse:
			return "The Mango9 chat server returned an invalid response."
		case .server(let message):
			return message
		}
	}
}

@MainActor
private final class Mango9ChatModerationStore: ObservableObject {
	static let shared = Mango9ChatModerationStore()

	@Published private(set) var blockedUserIds: Set<Int>
	@Published private(set) var hiddenMessageIds: Set<String>
	@Published private(set) var deletedRoomIds: Set<String>

	private let blockedUsersKeyPrefix = "mango9_chat_blocked_user_ids"
	private let hiddenMessagesKeyPrefix = "mango9_chat_hidden_message_ids"
	private let deletedRoomsKeyPrefix = "mango9_chat_deleted_room_ids"
	private var accountContextObserver: NSObjectProtocol?

	private init() {
		blockedUserIds = []
		hiddenMessageIds = []
		deletedRoomIds = []
		reloadForActiveAccount()
		accountContextObserver = NotificationCenter.default.addObserver(
			forName: .mango9AccountContextChanged,
			object: nil,
			queue: .main
		) { [weak self] _ in
			Task { @MainActor in
				self?.reloadForActiveAccount()
			}
		}
	}

	deinit {
		if let accountContextObserver {
			NotificationCenter.default.removeObserver(accountContextObserver)
		}
	}

	private var contextSuffix: String {
		Mango9SessionStore.activeIdentity ?? "no-account"
	}

	private var blockedUsersKey: String {
		"\(blockedUsersKeyPrefix).\(contextSuffix)"
	}

	private var hiddenMessagesKey: String {
		"\(hiddenMessagesKeyPrefix).\(contextSuffix)"
	}

	private var deletedRoomsKey: String {
		"\(deletedRoomsKeyPrefix).\(contextSuffix)"
	}

	private func reloadForActiveAccount() {
		migrateLegacyValuesIfNeeded()
		let storedValues =
			UserDefaults.standard.array(forKey: blockedUsersKey) ?? []
		blockedUserIds = Set(
			storedValues.compactMap { value in
				if let number = value as? NSNumber {
					return number.intValue
				}
				if let string = value as? String {
					return Int(string)
				}
				return nil
			}
		)
		hiddenMessageIds = Set(
			UserDefaults.standard.stringArray(forKey: hiddenMessagesKey) ?? []
		)
		deletedRoomIds = Set(
			UserDefaults.standard.stringArray(forKey: deletedRoomsKey) ?? []
		)
	}

	private func migrateLegacyValuesIfNeeded() {
		let defaults = UserDefaults.standard
		if defaults.object(forKey: blockedUsersKey) == nil,
		   let legacy = defaults.array(forKey: blockedUsersKeyPrefix) {
			defaults.set(legacy, forKey: blockedUsersKey)
			defaults.removeObject(forKey: blockedUsersKeyPrefix)
		}
		if defaults.object(forKey: hiddenMessagesKey) == nil,
		   let legacy = defaults.stringArray(forKey: hiddenMessagesKeyPrefix) {
			defaults.set(legacy, forKey: hiddenMessagesKey)
			defaults.removeObject(forKey: hiddenMessagesKeyPrefix)
		}
	}

	func isBlocked(_ userId: Int) -> Bool {
		blockedUserIds.contains(userId)
	}

	func setBlocked(_ blocked: Bool, userId: Int) {
		if blocked {
			blockedUserIds.insert(userId)
		} else {
			blockedUserIds.remove(userId)
		}
		UserDefaults.standard.set(blockedUserIds.sorted(), forKey: blockedUsersKey)
	}

	func isHidden(_ messageId: String) -> Bool {
		hiddenMessageIds.contains(messageId)
	}

	func setHidden(_ hidden: Bool, messageId: String) {
		if hidden {
			hiddenMessageIds.insert(messageId)
		} else {
			hiddenMessageIds.remove(messageId)
		}
		UserDefaults.standard.set(hiddenMessageIds.sorted(), forKey: hiddenMessagesKey)
	}

	func restoreMessages(_ messageIds: [String]) {
		hiddenMessageIds.subtract(messageIds)
		UserDefaults.standard.set(hiddenMessageIds.sorted(), forKey: hiddenMessagesKey)
	}

	func isConversationDeleted(_ roomId: String) -> Bool {
		deletedRoomIds.contains(roomId)
	}

	func setConversationDeleted(_ deleted: Bool, roomId: String) {
		if deleted {
			deletedRoomIds.insert(roomId)
		} else {
			deletedRoomIds.remove(roomId)
		}
		UserDefaults.standard.set(deletedRoomIds.sorted(), forKey: deletedRoomsKey)
	}
}

@MainActor
final class Mango9ChatStore: ObservableObject {
	static let shared = Mango9ChatStore()
	static let remotePushTokenDefaultsKey = "mango9_remote_push_token"

	@Published private(set) var users: [Mango9ChatUser] = []
	@Published private(set) var rooms: [Mango9ChatRoom] = []
	@Published private(set) var messages: [Mango9ChatMessage] = []
	@Published private(set) var smsParties: [Mango9SMSParty] = []
	@Published private(set) var smsMessages: [Mango9ServerSMSMessage] = []
	@Published private(set) var smsSenders: [Mango9ServerSMSSender] = []
	@Published private(set) var activeSMSPhone: String?
	@Published private(set) var onlineUserIds: Set<Int> = []
	@Published private(set) var typingUserIds: Set<Int> = []
	@Published private(set) var currentUserId: Int?
	@Published private(set) var activeRoomId: String?
	@Published private(set) var isConnecting = false
	@Published private(set) var isConnected = false
	@Published private(set) var errorMessage: String?

	private struct PendingCall {
		let method: String
		let continuation: CheckedContinuation<Any, Error>
		let timeoutTask: Task<Void, Never>
	}

	private var socket: URLSessionWebSocketTask?
	private var pendingCalls: [String: PendingCall] = [:]
	private var reconnectTask: Task<Void, Never>?
	private var intentionallyDisconnected = false
	private var openingUserId: Int?
	private var chatToken: String?
	private var chatTokenExpiresAt: Date?
	private var uploadURL: URL?
	private var connectionGeneration = 0
	private var connectingIdentity: String?
	private var connectedIdentity: String?
	private var smsMessageCache: [String: [Mango9ServerSMSMessage]] = [:]
	private var pendingSMSStatuses: [String: Int] = [:]

	private init() {}

	func connectIfNeeded(force: Bool = false) async {
		guard var session = Mango9SessionStore.load(),
			  let requestedIdentity = Mango9SessionStore.normalizedIdentity(
				session.sipIdentity
			  ),
			  Mango9SessionStore.isActive(sipIdentity: requestedIdentity) else {
			errorMessage = Mango9ChatError.noSession.localizedDescription
			return
		}
		if isConnected,
		   connectedIdentity == requestedIdentity,
		   !force {
			return
		}
		if isConnecting,
		   connectingIdentity == requestedIdentity,
		   !force {
			while isConnecting && connectingIdentity == requestedIdentity {
				guard !Task.isCancelled else { return }
				try? await Task.sleep(nanoseconds: 50_000_000)
			}
			if isConnected && connectedIdentity == requestedIdentity {
				return
			}
		}

			if isConnecting || isConnected || socket != nil {
				let preserveData = connectedIdentity == requestedIdentity ||
					connectingIdentity == requestedIdentity
				disconnect(clearData: !preserveData)
		}
		connectionGeneration += 1
		let generation = connectionGeneration
		connectingIdentity = requestedIdentity

		isConnecting = true
		errorMessage = nil
		intentionallyDisconnected = false
		defer {
			if connectionGeneration == generation {
				isConnecting = false
				connectingIdentity = nil
			}
		}

		do {
			let bootstrap: Mango9ChatBootstrap
			do {
				bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session)
			}
			guard isCurrentConnection(
				generation: generation,
				identity: requestedIdentity
			) else {
				return
			}

			guard let websocketURL = URL(string: bootstrap.websocketUrl) else {
				throw Mango9ChatError.invalidEndpoint
			}

			intentionallyDisconnected = false
			currentUserId = bootstrap.userId
			chatToken = bootstrap.token
			chatTokenExpiresAt = Date().addingTimeInterval(TimeInterval(bootstrap.expiresIn))
			uploadURL = URL(string: session.smsChatApi)?.appendingPathComponent("upload")

			var request = URLRequest(url: websocketURL)
			request.timeoutInterval = 20
			request.setValue(
				"\(bootstrap.token)#\(Self.deviceIdentifier)",
				forHTTPHeaderField: "Sec-WebSocket-Protocol"
			)
			let task = URLSession.shared.webSocketTask(with: request)
			socket = task
			task.resume()
			isConnected = true
			connectedIdentity = requestedIdentity
			receiveNext(on: task)

			try await loadDirectory()
			await registerRemotePushTokenIfAvailable()
		} catch {
			guard isCurrentConnection(
				generation: generation,
				identity: requestedIdentity
			) else {
				return
			}
			isConnected = false
			connectedIdentity = nil
			errorMessage = error.localizedDescription
			scheduleReconnect()
		}
	}

	func disconnect(clearData: Bool = true) {
		connectionGeneration += 1
		intentionallyDisconnected = true
		reconnectTask?.cancel()
		reconnectTask = nil
		socket?.cancel(with: .normalClosure, reason: nil)
		socket = nil
		isConnecting = false
		isConnected = false
		connectingIdentity = nil
		connectedIdentity = nil
		activeRoomId = nil
		activeSMSPhone = nil
		openingUserId = nil
		chatToken = nil
		chatTokenExpiresAt = nil
		uploadURL = nil
		for pending in pendingCalls.values {
			pending.timeoutTask.cancel()
			pending.continuation.resume(throwing: Mango9ChatError.disconnected)
		}
		pendingCalls.removeAll()
		if clearData {
			users = []
			rooms = []
			messages = []
			smsParties = []
			smsMessages = []
			smsSenders = []
			onlineUserIds = []
			typingUserIds = []
			smsMessageCache = [:]
			pendingSMSStatuses = [:]
		}
	}

	private func isCurrentConnection(
		generation: Int,
		identity: String
	) -> Bool {
		connectionGeneration == generation &&
		Mango9SessionStore.isActive(sipIdentity: identity)
	}

	func refreshAfterForeground() async {
		guard Mango9SessionStore.load() != nil else {
			return
		}

		if activeRoomId == nil && activeSMSPhone == nil {
			await connectIfNeeded(force: true)
			return
		}

		await connectIfNeeded()
		guard isConnected else {
			return
		}

		do {
			try await loadDirectory()
			if let activeSMSPhone {
				try await loadSMSMessages(phone: activeSMSPhone)
			}
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func closeConversation(roomId: String?) {
		guard let roomId, activeRoomId == roomId else {
			return
		}
		activeRoomId = nil
		openingUserId = nil
		messages = []
	}

	func closeSMSConversation(phone: String? = nil) {
		if let phone, let activeSMSPhone,
		   Self.normalizedPhone(phone) != Self.normalizedPhone(activeSMSPhone) {
			return
		}
		activeSMSPhone = nil
		smsMessages = []
	}

	func openSMSConversation(phone: String) async {
		let normalized = Self.normalizedPhone(phone)
		guard !normalized.isEmpty else {
			errorMessage = "Enter a valid mobile number."
			return
		}
		activeRoomId = nil
		messages = []
		activeSMSPhone = normalized
		smsMessages = smsMessageCache[smsCacheKey(phone: normalized)] ?? []
		markSMSReadLocally(normalized)
		errorMessage = nil
		await connectIfNeeded()
		guard isConnected else { return }
		activeSMSPhone = normalized
		markSMSReadLocally(normalized)
		do {
			try await loadSMSMessages(phone: normalized)
			if smsSenders.isEmpty {
				try await loadSMSDirectory()
			}
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func refreshSMSDirectory() async {
		await connectIfNeeded()
		guard isConnected else { return }
		do {
			try await loadSMSDirectory()
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func refreshSMSConversation() async {
		guard let activeSMSPhone else { return }
		await connectIfNeeded()
		guard isConnected else { return }
		do {
			try await loadSMSMessages(phone: activeSMSPhone)
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func deleteConversation(roomId: String) {
		guard connectedIdentity == Mango9SessionStore.activeIdentity else {
			errorMessage = Mango9ChatError.disconnected.localizedDescription
			return
		}
		Mango9ChatModerationStore.shared.setConversationDeleted(true, roomId: roomId)
		for message in messages where message.roomId == roomId {
			Mango9ChatModerationStore.shared.setHidden(true, messageId: message.id)
		}
		closeConversation(roomId: roomId)
	}

	func openConversation(with userId: Int, fallbackName: String = "") async {
		closeSMSConversation()
		if let existingRoom = directRoom(with: userId) {
			markRoomReadLocally(existingRoom.id)
		}
		openingUserId = userId
		activeRoomId = nil
		messages = []
		errorMessage = nil
		await connectIfNeeded()
		guard isConnected, openingUserId == userId else {
			return
		}

		do {
			var room = directRoom(with: userId)
			if room == nil {
				let result = try await rpcCall("createChatGroup", params: [[String(userId)]])
				room = Self.room(from: result)
				if let room, !rooms.contains(where: { $0.id == room.id }) {
					rooms.append(room)
				}
			}
			guard let room else {
				throw Mango9ChatError.invalidResponse
			}
			activeRoomId = room.id
			markRoomReadLocally(room.id)
			try await loadMessages(roomId: room.id)

			if users.first(where: { $0.id == userId }) == nil, !fallbackName.isEmpty {
				users.append(
					Mango9ChatUser(
						id: userId,
						name: fallbackName,
						avatar: "",
						category: "agent"
					)
				)
			}
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func openRoom(_ room: Mango9ChatRoom) async {
		closeSMSConversation()
		markRoomReadLocally(room.id)
		openingUserId = nil
		activeRoomId = nil
		messages = []
		errorMessage = nil
		await connectIfNeeded()
		guard isConnected else {
			return
		}

		do {
			activeRoomId = room.id
			markRoomReadLocally(room.id)
			try await loadMessages(roomId: room.id)
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func createGroup(userIds: Set<Int>) async -> Mango9ChatRoom? {
		guard userIds.count >= 2 else {
			errorMessage = "Choose at least two teammates for a group."
			return nil
		}
		await connectIfNeeded()
		do {
			let result = try await rpcCall(
				"createChatGroup",
				params: [userIds.sorted().map(String.init)]
			)
			guard let room = Self.room(from: result) else {
				throw Mango9ChatError.invalidResponse
			}
			if let index = rooms.firstIndex(where: { $0.id == room.id }) {
				rooms[index] = room
			} else {
				rooms.insert(room, at: 0)
			}
			return room
		} catch {
			errorMessage = error.localizedDescription
			return nil
		}
	}

	func updateGroupMembers(room: Mango9ChatRoom, userIds: Set<Int>) async -> Bool {
		guard !room.isDirect else {
			return false
		}
		do {
			let existing = Set(room.userIds)
			for userId in userIds.subtracting(existing) {
				_ = try await rpcCall(
					"addChatGroupUser",
					params: [room.id, String(userId)]
				)
			}
			for userId in existing.subtracting(userIds) {
				_ = try await rpcCall(
					"removeChatGroupUser",
					params: [room.id, String(userId)]
				)
			}
			try await loadDirectory()
			return true
		} catch {
			errorMessage = error.localizedDescription
			return false
		}
	}

	func refreshDirectory() async {
		await connectIfNeeded()
		guard isConnected else {
			return
		}
		do {
			try await loadDirectory()
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func registerRemotePushTokenIfAvailable() async {
		guard isConnected,
		      let authToken = chatToken,
		      let session = Mango9SessionStore.load(),
		      let token = UserDefaults.standard.string(
				forKey: Self.remotePushTokenDefaultsKey
		      ),
		      token.range(of: "^[a-fA-F0-9]{64,256}$", options: .regularExpression) != nil,
		      let baseURL = URL(string: session.smsChatApi) else {
			return
		}

		var request = URLRequest(
			url: baseURL
				.appendingPathComponent("push")
				.appendingPathComponent("register")
		)
		request.httpMethod = "POST"
		request.timeoutInterval = 15
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization")
		#if DEBUG
		let pushEnvironment = "sandbox"
		#else
		let pushEnvironment = "production"
		#endif
		let payload: [String: String] = [
			"token": token.lowercased(),
			"device_id": Self.deviceIdentifier,
			"crm_id": session.crmId,
			"sip_identity": connectedIdentity ?? session.sipIdentity ?? "",
			"environment": pushEnvironment
		]

		do {
			request.httpBody = try JSONSerialization.data(withJSONObject: payload)
			let (_, response) = try await URLSession.shared.data(for: request)
			guard let http = response as? HTTPURLResponse,
			      (200..<300).contains(http.statusCode) else {
				Log.warn("Mango9 message push registration was rejected")
				return
			}
			Log.info("Mango9 message push registration refreshed")
		} catch {
			Log.warn("Mango9 message push registration failed")
		}
	}

	func unregisterRemotePushToken(
		for sipIdentity: String,
		session: Mango9Session
	) async -> Bool {
		guard Mango9SessionStore.normalizedIdentity(sipIdentity) != nil,
		let baseURL = URL(string: session.smsChatApi) else {
			return false
		}

		var authorizedSession = session
		for attempt in 1...2 {
			do {
				let bootstrap: Mango9ChatBootstrap
				do {
					bootstrap = try await Mango9CRMAPI.chatBootstrap(
						session: authorizedSession
					)
				} catch Mango9CRMAPIError.unauthorized {
					authorizedSession = try await Mango9CRMAPI.refresh(
						session: authorizedSession
					)
					bootstrap = try await Mango9CRMAPI.chatBootstrap(
						session: authorizedSession
					)
				}

				var request = URLRequest(
					url: baseURL
						.appendingPathComponent("push")
						.appendingPathComponent("register")
				)
				request.httpMethod = "DELETE"
				request.timeoutInterval = 15
				request.setValue(
					"application/json",
					forHTTPHeaderField: "Content-Type"
				)
				request.setValue(
					"Bearer \(bootstrap.token)",
					forHTTPHeaderField: "Authorization"
				)
				request.httpBody = try JSONSerialization.data(
					withJSONObject: ["device_id": Self.deviceIdentifier]
				)

				let (_, response) = try await URLSession.shared.data(for: request)
				guard let http = response as? HTTPURLResponse else {
					throw Mango9ChatError.invalidResponse
				}
				if (200..<300).contains(http.statusCode) {
					Log.info("Mango9 message push registration removed for signed-out account")
					return true
				}
				Log.warn(
					"Mango9 message push unregistration was rejected " +
					"(status \(http.statusCode), attempt \(attempt))"
				)
			} catch {
				Log.warn(
					"Mango9 message push unregistration failed " +
					"(attempt \(attempt))"
				)
			}
			if attempt < 2 {
				try? await Task.sleep(nanoseconds: 500_000_000)
			}
		}
		return false
	}

	func disconnectIfConnected(to sipIdentity: String) {
		guard let identity = Mango9SessionStore.normalizedIdentity(sipIdentity),
		      connectedIdentity == identity || connectingIdentity == identity else {
			return
		}
		disconnect()
	}

	func sendMessage(_ text: String, attachments: [Attachment] = []) async -> Bool {
		let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
		guard (!message.isEmpty || !attachments.isEmpty), let roomId = activeRoomId else {
			return false
		}
		do {
			let files = try await upload(attachments)
			_ = try await rpcCall(
				"sendChatMessage",
				params: [roomId, message, files, UUID().uuidString.lowercased()]
			)
			Mango9ChatModerationStore.shared.setConversationDeleted(
				false,
				roomId: roomId
			)
			return true
		} catch {
			errorMessage = error.localizedDescription
			return false
		}
	}

	func uploadForMessaging(_ attachments: [Attachment]) async throws -> [String] {
		guard !attachments.isEmpty else { return [] }
		await connectIfNeeded()
		return try await upload(attachments)
	}

	func sendSMS(
		to phone: String,
		from senderID: String,
		message: String,
		attachments: [Attachment] = []
	) async throws {
		await connectIfNeeded()
		guard isConnected,
			  connectedIdentity == Mango9SessionStore.activeIdentity else {
			throw Mango9ChatError.disconnected
		}
		let files = try await upload(attachments)
		_ = try await rpcCall(
			"sendSmsMessage",
			params: [
				phone,
				senderID,
				message,
				files,
				UUID().uuidString.lowercased(),
			]
		)
		// The send RPC has succeeded. Keep the composer responsive while live socket events and
		// fallback refreshes reconcile the server/carrier delivery state.
		Task { [weak self] in
			guard let self else { return }
			try? await self.loadSMSMessages(phone: phone)
			try? await self.loadSMSDirectory()
			try? await Task.sleep(nanoseconds: 5_000_000_000)
			try? await self.loadSMSMessages(phone: phone)
		}
	}

	func notifyTyping() {
		guard let roomId = activeRoomId else {
			return
		}
		Task {
			_ = try? await rpcCall("notifyTyping", params: [roomId])
		}
	}

	func isOnline(_ userId: Int) -> Bool {
		onlineUserIds.contains(userId)
	}

	func isTyping(_ userId: Int) -> Bool {
		typingUserIds.contains(userId)
	}

	func reportError(_ error: Error) {
		errorMessage = error.localizedDescription
	}

	func roomPreview(for userId: Int) -> Mango9ChatRoom? {
		guard let room = directRoom(with: userId),
			  !Mango9ChatModerationStore.shared.isConversationDeleted(room.id) else {
			return nil
		}
		return room
	}

	var teamUnreadCount: Int {
		rooms
			.filter { room in
				guard !Mango9ChatModerationStore.shared.isConversationDeleted(room.id) else {
					return false
				}
				guard room.isDirect, let userId = room.userIds.first else {
					return true
				}
				return !Mango9ChatModerationStore.shared.isBlocked(userId)
			}
			.reduce(0) { total, room in
			total + max(0, room.unread)
		}
	}

	var smsUnreadCount: Int {
		smsParties.reduce(0) { $0 + max(0, $1.unread) }
	}

	var unreadCount: Int {
		teamUnreadCount + smsUnreadCount
	}

	var inboxPreviewRoom: Mango9ChatRoom? {
		rooms
			.filter {
				!Mango9ChatModerationStore.shared.isConversationDeleted($0.id)
			}
			.filter { $0.unread > 0 }
			.max { $0.latest < $1.latest }
			?? rooms.max { $0.latest < $1.latest }
	}

	func roomTitle(_ room: Mango9ChatRoom) -> String {
		if room.isDirect, let userId = room.userIds.first {
			return userName(userId)
		}
		return groupTitle(room)
	}

	func groupTitle(_ room: Mango9ChatRoom) -> String {
		let names = room.userIds.compactMap { userId in
			users.first(where: { $0.id == userId })?.name
		}
		return names.isEmpty ? "Team group" : names.joined(separator: ", ")
	}

	func userName(_ userId: Int) -> String {
		if userId == currentUserId {
			return "You"
		}
		return users.first(where: { $0.id == userId })?.name ?? "Teammate"
	}

	private func upload(_ attachments: [Attachment]) async throws -> [String] {
		guard !attachments.isEmpty else {
			return []
		}
		try await refreshUploadCredentialsIfNeeded()
		guard let uploadURL, let chatToken else {
			throw Mango9ChatError.invalidEndpoint
		}

		var uploaded: [String] = []
		for attachment in attachments {
			let values = try attachment.full.resourceValues(forKeys: [.fileSizeKey])
			let mimeType = Self.mimeType(for: attachment)
			var prepare = URLRequest(url: uploadURL)
			prepare.httpMethod = "POST"
			prepare.setValue("application/json", forHTTPHeaderField: "Accept")
			prepare.setValue("application/json", forHTTPHeaderField: "Content-Type")
			prepare.setValue("Bearer \(chatToken)", forHTTPHeaderField: "Authorization")
			prepare.httpBody = try JSONSerialization.data(
				withJSONObject: [
					"ext": attachment.full.pathExtension,
					"name": attachment.name,
					"type": mimeType,
					"size": values.fileSize ?? attachment.size,
				]
			)

			let (responseData, prepareResponse) = try await URLSession.shared.data(for: prepare)
			guard let prepareHTTP = prepareResponse as? HTTPURLResponse,
				  (200..<300).contains(prepareHTTP.statusCode) else {
				throw Mango9ChatError.server("The attachment upload could not be prepared.")
			}
			let upload = try JSONDecoder().decode(Mango9ChatUpload.self, from: responseData)
			guard let putURL = URL(string: upload.putUrl) else {
				throw Mango9ChatError.invalidResponse
			}

			var put = URLRequest(url: putURL)
			put.httpMethod = "PUT"
			put.setValue(mimeType, forHTTPHeaderField: "Content-Type")
			if !upload.contentDisposition.isEmpty {
				put.setValue(upload.contentDisposition, forHTTPHeaderField: "Content-Disposition")
			}
			let (_, putResponse) = try await URLSession.shared.upload(
				for: put,
				fromFile: attachment.full
			)
			guard let putHTTP = putResponse as? HTTPURLResponse,
				  (200..<300).contains(putHTTP.statusCode) else {
				throw Mango9ChatError.server("The attachment could not be uploaded.")
			}
			uploaded.append(upload.getUrl)
		}
		return uploaded
	}

	private func refreshUploadCredentialsIfNeeded() async throws {
		if let expiresAt = chatTokenExpiresAt,
		   expiresAt.timeIntervalSinceNow > 60,
		   chatToken != nil,
		   uploadURL != nil {
			return
		}
		guard var session = Mango9SessionStore.load() else {
			throw Mango9ChatError.noSession
		}
		guard connectedIdentity == Mango9SessionStore.normalizedIdentity(
			session.sipIdentity
		) else {
			throw Mango9ChatError.disconnected
		}
		let bootstrap: Mango9ChatBootstrap
		do {
			bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session)
		} catch Mango9CRMAPIError.unauthorized {
			session = try await Mango9CRMAPI.refresh(session: session)
			try Mango9SessionStore.save(session)
			bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session)
		}
		chatToken = bootstrap.token
		chatTokenExpiresAt = Date().addingTimeInterval(TimeInterval(bootstrap.expiresIn))
		uploadURL = URL(string: session.smsChatApi)?.appendingPathComponent("upload")
	}

	private static func mimeType(for attachment: Attachment) -> String {
		switch attachment.full.pathExtension.lowercased() {
		case "m4a":
			return "audio/mp4"
		case "mka":
			return "audio/x-matroska"
		default:
			break
		}
		let detected = attachment.full.mimeType()
		if detected != "application/octet-stream" {
			return detected
		}
		switch attachment.type {
		case .image, .gif:
			return "image/jpeg"
		case .video:
			return "video/mp4"
		case .audio, .voiceRecording:
			return "audio/mp4"
		case .pdf:
			return "application/pdf"
		case .text:
			return "text/plain"
		default:
			return detected
		}
	}

	private func loadDirectory() async throws {
		let rawUsers = try await rpcCall("getAllUsers", params: [])
		let rawRooms = try await rpcCall("getAllRooms", params: [])
		try await loadSMSDirectory()
		let rawPresence = try await rpcCall("getPresence", params: [])

		users = Self.array(from: rawUsers).compactMap(Self.user(from:))
			.filter { $0.id != currentUserId }
			.sorted {
				$0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
			}
		let loadedRooms = Self.array(from: rawRooms).compactMap(Self.room(from:))
			.sorted { $0.latest > $1.latest }
		for room in loadedRooms
			where room.unread > 0 &&
				Mango9ChatModerationStore.shared.isConversationDeleted(room.id) {
			Mango9ChatModerationStore.shared.setConversationDeleted(
				false,
				roomId: room.id
			)
		}
		rooms = loadedRooms
		if let activeRoomId {
			markRoomReadLocally(activeRoomId)
		}
		applyPresence(Self.array(from: rawPresence))
	}

	private func loadSMSDirectory() async throws {
		let rawParties = try await rpcCall("getUserSmsParties", params: [])
		let rawSenders = try await rpcCall("getSmsSenders", params: [])
		smsParties = Self.array(from: rawParties)
			.compactMap(Self.smsParty(from:))
			.sorted { $0.latest > $1.latest }
		smsSenders = Self.array(from: rawSenders)
			.compactMap(Self.smsSender(from:))
		if let activeSMSPhone {
			markSMSReadLocally(activeSMSPhone)
		}
	}

	private func loadSMSMessages(phone: String) async throws {
		let normalized = Self.normalizedPhone(phone)
		let result = try await rpcCall(
			"getSmsMessages",
			params: [normalized, 0, "json"]
		)
		guard let dictionary = result as? [String: Any] else {
			throw Mango9ChatError.invalidResponse
		}
		let loadedMessages = Self.array(from: dictionary["list"] as Any)
			.compactMap(Self.smsMessage(from:))
			.map(applyPendingSMSStatus)
			.sorted { $0.time < $1.time }
		let cacheKey = smsCacheKey(phone: normalized)
		let mergedMessages = Self.mergeSMSMessages(
			server: loadedMessages,
			live: smsMessageCache[cacheKey] ?? []
		)
		smsMessageCache[cacheKey] = mergedMessages
		if let activeSMSPhone,
		   Self.normalizedPhone(activeSMSPhone) == normalized {
			smsMessages = mergedMessages
			markSMSReadLocally(normalized)
		}
	}

	private func loadMessages(roomId: String) async throws {
		let result = try await rpcCall("getChatMessages", params: [roomId, 0])
		guard let dictionary = result as? [String: Any] else {
			throw Mango9ChatError.invalidResponse
		}
		messages = Self.array(from: dictionary["list"] as Any)
			.compactMap(Self.message(from:))
			.sorted { $0.time < $1.time }

		let inboundIds = messages
			.filter { $0.fromUserId != currentUserId && $0.status < 3 }
			.map(\.id)
		if !inboundIds.isEmpty {
			_ = try? await rpcCall(
				"notifyChatMessageStatus",
				params: [roomId, inboundIds, 3]
			)
		}
		markRoomReadLocally(roomId)
	}

	private func markRoomReadLocally(_ roomId: String) {
		guard let index = rooms.firstIndex(where: { $0.id == roomId }),
			  rooms[index].unread > 0 else {
			return
		}
		let room = rooms[index]
		rooms[index] = Mango9ChatRoom(
			id: room.id,
			userIds: room.userIds,
			latest: room.latest,
			lastMessage: room.lastMessage,
			unread: 0,
			isDirect: room.isDirect
		)
		synchronizeApplicationBadge()
	}

	private func markSMSReadLocally(_ phone: String) {
		let normalized = Self.normalizedPhone(phone)
		guard let index = smsParties.firstIndex(where: {
			Self.normalizedPhone($0.phone) == normalized
		}), smsParties[index].unread > 0 else {
			return
		}
		let party = smsParties[index]
		smsParties[index] = Mango9SMSParty(
			phone: party.phone,
			latest: party.latest,
			lastMessage: party.lastMessage,
			unread: 0,
			avatar: party.avatar
		)
		synchronizeApplicationBadge()
	}

	private func synchronizeApplicationBadge() {
		UIApplication.shared.applicationIconBadgeNumber =
			max(0, SharedMainViewModel.shared.unreadMessages) + unreadCount
	}

	private func directRoom(with userId: Int) -> Mango9ChatRoom? {
		rooms.first { room in
			room.isDirect && room.userIds.contains(userId)
		}
	}

	private func rpcCall(_ method: String, params: [Any]) async throws -> Any {
		guard let socket,
			  isConnected,
			  connectedIdentity == Mango9SessionStore.activeIdentity else {
			throw Mango9ChatError.disconnected
		}
		let id = UUID().uuidString.lowercased()
		let payload: [String: Any] = [
			"jsonrpc": "2.0",
			"method": method,
			"params": params,
			"id": id,
		]
		guard JSONSerialization.isValidJSONObject(payload) else {
			throw Mango9ChatError.invalidResponse
		}
		let data = try JSONSerialization.data(withJSONObject: payload)
		guard let text = String(data: data, encoding: .utf8) else {
			throw Mango9ChatError.invalidResponse
		}

		return try await withCheckedThrowingContinuation { continuation in
			let timeoutTask = Task { [weak self] in
				try? await Task.sleep(nanoseconds: 20_000_000_000)
				guard !Task.isCancelled, let self,
					  let pending = self.pendingCalls.removeValue(forKey: id) else {
					return
				}
				pending.continuation.resume(
					throwing: Mango9ChatError.server("\(method) timed out. Please try again.")
				)
			}
			pendingCalls[id] = PendingCall(
				method: method,
				continuation: continuation,
				timeoutTask: timeoutTask
			)
			socket.send(.string(text)) { [weak self] error in
				guard let error else {
					return
				}
				Task { @MainActor in
					guard let self,
						  let pending = self.pendingCalls.removeValue(forKey: id) else {
						return
					}
					pending.timeoutTask.cancel()
					pending.continuation.resume(throwing: error)
				}
			}
		}
	}

	private func receiveNext(on observedSocket: URLSessionWebSocketTask) {
		observedSocket.receive { [weak self, weak observedSocket] result in
			Task { @MainActor in
				guard let self,
					  let observedSocket,
					  self.socket === observedSocket else {
					return
				}
				switch result {
				case .success(let message):
					switch message {
					case .string(let text):
						self.handleSocketText(text)
					case .data(let data):
						if let text = String(data: data, encoding: .utf8) {
							self.handleSocketText(text)
						}
					@unknown default:
						break
					}
					self.receiveNext(on: observedSocket)
				case .failure(let error):
					self.isConnected = false
					self.errorMessage = error.localizedDescription
					self.scheduleReconnect()
				}
			}
		}
	}

	private func handleSocketText(_ text: String) {
		guard let data = text.data(using: .utf8),
			  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
			return
		}

		if let id = json["id"] as? String,
		   let pending = pendingCalls.removeValue(forKey: id) {
			pending.timeoutTask.cancel()
			if let error = json["error"] as? [String: Any] {
				pending.continuation.resume(
					throwing: Mango9ChatError.server(
						"\(pending.method): \((error["message"] as? String) ?? "Chat request failed.")"
					)
				)
			} else {
				pending.continuation.resume(returning: json["result"] ?? "")
			}
			return
		}

		guard let method = json["method"] as? String else {
			return
		}
		let params = json["params"] as? [Any] ?? []
		switch method {
		case "newChatMessage":
			guard let message = params.first.flatMap(Self.message(from:)) else {
				return
			}
			Mango9ChatModerationStore.shared.setConversationDeleted(
				false,
				roomId: message.roomId
			)
			upsert(message)
			if message.roomId == activeRoomId,
			   message.fromUserId != currentUserId {
				Task {
					_ = try? await rpcCall(
						"notifyChatMessageStatus",
						params: [message.roomId, [message.id], 3]
					)
				}
			}
		case "updateChatMessage":
			let updates = Self.array(from: params.first as Any)
			for update in updates {
				guard let dictionary = update as? [String: Any],
					  let id = Self.string(dictionary["id"]),
					  let status = Self.integer(dictionary["status"]),
					  let index = messages.firstIndex(where: { $0.id == id }) else {
					continue
				}
				let existing = messages[index]
				messages[index] = Mango9ChatMessage(
					id: existing.id,
					fromUserId: existing.fromUserId,
					roomId: existing.roomId,
					text: existing.text,
					time: existing.time,
					status: status,
					files: existing.files
				)
			}
		case "updateChatGroup":
			if let room = params.first.flatMap(Self.room(from:)) {
				if let index = rooms.firstIndex(where: { $0.id == room.id }) {
					rooms[index] = room
				} else {
					rooms.append(room)
				}
			}
		case "updatePresence":
			applyPresence(Self.array(from: params.first as Any))
		case "newSmsMessage":
			guard let message = params.first.flatMap(Self.smsMessage(from:)) else {
				return
			}
			upsertSMS(message)
			Task { try? await loadSMSDirectory() }
		case "updateSmsMessage":
			guard let dictionary = params.first as? [String: Any],
				  let id = Self.string(dictionary["id"]),
				  let status = Self.integer(dictionary["status"]) else {
				return
			}
			pendingSMSStatuses[id] = pendingSMSStatuses[id]
				.map { Mango9SMSDeliveryPolicy.newest($0, status) } ?? status
			if let existing = smsMessages.first(where: { $0.id == id }) {
				upsertSMS(existing)
			}
		default:
			break
		}
	}

	private func upsertSMS(_ message: Mango9ServerSMSMessage) {
		let resolved = applyPendingSMSStatus(message)
		let cacheKey = smsCacheKey(phone: resolved.phone)
		let cached = Self.mergeSMSMessages(
			server: [resolved],
			live: smsMessageCache[cacheKey] ?? []
		)
		smsMessageCache[cacheKey] = cached
		if let activeSMSPhone,
		   Self.normalizedPhone(activeSMSPhone) == Self.normalizedPhone(resolved.phone) {
			smsMessages = cached
			markSMSReadLocally(resolved.phone)
		}
	}

	private func applyPendingSMSStatus(
		_ message: Mango9ServerSMSMessage
	) -> Mango9ServerSMSMessage {
		guard let pending = pendingSMSStatuses.removeValue(forKey: message.id) else {
			return message
		}
		return message.withStatus(
			Mango9SMSDeliveryPolicy.newest(message.status, pending)
		)
	}

	private func smsCacheKey(phone: String) -> String {
		let identity = Mango9SessionStore.activeIdentity ?? connectedIdentity ?? "inactive"
		return "\(identity)|\(Self.normalizedPhone(phone))"
	}

	private func upsert(_ message: Mango9ChatMessage) {
		if let index = messages.firstIndex(where: { $0.id == message.id }) {
			messages[index] = message
		} else if message.roomId == activeRoomId {
			messages.append(message)
			messages.sort { $0.time < $1.time }
		}

		if let index = rooms.firstIndex(where: { $0.id == message.roomId }) {
			let existing = rooms[index]
			let unread = message.roomId == activeRoomId || message.fromUserId == currentUserId
				? (message.roomId == activeRoomId ? 0 : existing.unread)
				: existing.unread + 1
			rooms[index] = Mango9ChatRoom(
				id: existing.id,
				userIds: existing.userIds,
				latest: message.time,
				lastMessage: message.text,
				unread: unread,
				isDirect: existing.isDirect
			)
			if message.roomId == activeRoomId {
				synchronizeApplicationBadge()
			}
		}
	}

	private func applyPresence(_ raw: [Any]) {
		for item in raw {
			guard let dictionary = item as? [String: Any],
				  let userId = Self.integer(dictionary["user"]),
				  let status = Self.integer(dictionary["stat"]) else {
				continue
			}
			if status == 2 {
				onlineUserIds.insert(userId)
			} else {
				onlineUserIds.remove(userId)
			}

			if let room = Self.integer(dictionary["typing"]), room > 0 {
				typingUserIds.insert(userId)
				Task {
					try? await Task.sleep(nanoseconds: 3_500_000_000)
					if Self.integer(dictionary["typing"]) == room {
						typingUserIds.remove(userId)
					}
				}
			} else {
				typingUserIds.remove(userId)
			}
		}
	}

	private func scheduleReconnect() {
		guard !intentionallyDisconnected, reconnectTask == nil else {
			return
		}
		reconnectTask = Task {
			try? await Task.sleep(nanoseconds: 2_000_000_000)
			guard !Task.isCancelled else {
				return
			}
			reconnectTask = nil
			await connectIfNeeded(force: true)
		}
	}

	private static var deviceIdentifier: String {
		let key = "mango9_chat_client_uuid"
		if let existing = UserDefaults.standard.string(forKey: key), !existing.isEmpty {
			return existing
		}
		let value = UUID().uuidString.lowercased()
		UserDefaults.standard.set(value, forKey: key)
		return value
	}

	private static func array(from value: Any) -> [Any] {
		value as? [Any] ?? []
	}

	private static func integer(_ value: Any?) -> Int? {
		if let value = value as? Int {
			return value
		}
		if let value = value as? NSNumber {
			return value.intValue
		}
		if let value = value as? String {
			return Int(value)
		}
		return nil
	}

	private static func string(_ value: Any?) -> String? {
		if let value = value as? String {
			return value
		}
		if let value = value as? NSNumber {
			return value.stringValue
		}
		return nil
	}

	private static func user(from value: Any) -> Mango9ChatUser? {
		guard let dictionary = value as? [String: Any],
			  let id = integer(dictionary["id"]) else {
			return nil
		}
		let fallback = "User \(id)"
		return Mango9ChatUser(
			id: id,
			name: (dictionary["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? fallback,
			avatar: dictionary["avatar"] as? String ?? "",
			category: dictionary["category"] as? String ?? ""
		)
	}

	private static func room(from value: Any) -> Mango9ChatRoom? {
		guard let dictionary = value as? [String: Any],
			  let id = string(dictionary["id"]) else {
			return nil
		}
		let users = array(from: dictionary["users"] as Any).compactMap(integer)
		return Mango9ChatRoom(
			id: id,
			userIds: users,
			latest: dictionary["latest"] as? String ?? "",
			lastMessage: dictionary["lastMsg"] as? String ?? "",
			unread: integer(dictionary["unread"]) ?? 0,
			isDirect: integer(dictionary["roomType"]) == 0
		)
	}

	private static func message(from value: Any) -> Mango9ChatMessage? {
		guard let dictionary = value as? [String: Any],
			  let id = string(dictionary["id"]),
			  let from = integer(dictionary["from"]),
			  let room = string(dictionary["room"]) else {
			return nil
		}
		return Mango9ChatMessage(
			id: id,
			fromUserId: from,
			roomId: room,
			text: dictionary["msg"] as? String ?? "",
			time: dictionary["time"] as? String ?? "",
			status: integer(dictionary["status"]) ?? 0,
			files: dictionary["files"] as? String ?? ""
		)
	}

	private static func smsParty(from value: Any) -> Mango9SMSParty? {
		guard let dictionary = value as? [String: Any],
			  let rawPhone = string(dictionary["phone"]) else {
			return nil
		}
		let phone = normalizedPhone(rawPhone)
		guard !phone.isEmpty else { return nil }
		return Mango9SMSParty(
			phone: phone,
			latest: dictionary["latest"] as? String ?? "",
			lastMessage: dictionary["lastMsg"] as? String ?? "",
			unread: integer(dictionary["unread"]) ?? 0,
			avatar: dictionary["avatar"] as? String ?? ""
		)
	}

	private static func smsSender(from value: Any) -> Mango9ServerSMSSender? {
		guard let dictionary = value as? [String: Any],
			  let senderID = string(dictionary["senderId"]) else {
			return nil
		}
		return Mango9ServerSMSSender(
			id: integer(dictionary["id"]) ?? senderID.hashValue,
			senderID: normalizedPhone(senderID)
		)
	}

	private static func smsMessage(from value: Any) -> Mango9ServerSMSMessage? {
		guard let dictionary = value as? [String: Any],
			  let id = string(dictionary["id"]),
			  let rawPhone = string(dictionary["phone"]) else {
			return nil
		}
		return Mango9ServerSMSMessage(
			id: id,
			phone: normalizedPhone(rawPhone),
			text: dictionary["msg"] as? String ?? "",
			time: dictionary["time"] as? String ?? "",
			senderID: string(dictionary["did"]) ?? "",
			status: integer(dictionary["status"]) ?? 0,
			isIncoming: (dictionary["dir"] as? String) == "i",
			files: dictionary["files"] as? String ?? ""
		)
	}

	static func mergeSMSMessages(
		server: [Mango9ServerSMSMessage],
		live: [Mango9ServerSMSMessage]
	) -> [Mango9ServerSMSMessage] {
		Swift.Dictionary(grouping: server + live, by: { $0.id })
			.values
			.compactMap { versions in
				guard let latest = versions.last else { return nil }
				let status = versions
					.map { $0.status }
					.reduce(latest.status, Mango9SMSDeliveryPolicy.newest)
				return latest.withStatus(status)
			}
			.sorted { $0.time < $1.time }
	}

	private static func normalizedPhone(_ value: String) -> String {
		let digits = value.filter(\.isNumber)
		if digits.count == 10 { return "1\(digits)" }
		return digits
	}
}

private extension String {
	var nilIfEmpty: String? {
		isEmpty ? nil : self
	}
}

enum Mango9ChatRouting {
	static func openIfNeeded(remote: linphonesw.Address) -> Bool {
		guard let target = ContactsManager.shared.mango9ChatTarget(for: remote) else {
			return false
		}
		DispatchQueue.main.async {
			NotificationCenter.default.post(name: .mango9OpenChat, object: target)
		}
		return true
	}
}

struct Mango9SMSTarget: Identifiable, Equatable {
	let phone: String
	let name: String

	var id: String { phone }
}

enum Mango9SMSRouting {
	static func open(_ target: Mango9SMSTarget) {
		DispatchQueue.main.async {
			NotificationCenter.default.post(name: .mango9OpenSMS, object: target)
		}
	}

	static func openIfNeeded(remote: linphonesw.Address, fallbackName: String = "") -> Bool {
		guard Mango9SessionStore.load() != nil,
			  let target = target(remote: remote, fallbackName: fallbackName) else {
			return false
		}
		open(target)
		return true
	}

	static func target(
		remote: linphonesw.Address,
		fallbackName: String = ""
	) -> Mango9SMSTarget? {
		let raw = remote.username?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		guard let phone = Mango9CallerIdentity.externalPhoneNumber(raw),
			  phone.filter(\.isNumber).count >= 10 else {
			return nil
		}

		let ownName = Mango9CallerIdentity.normalizedLabel(Mango9SessionStore.load()?.displayName)?
			.lowercased()
		let displayName = Mango9CallerIdentity.normalizedLabel(remote.displayName)
		let fallback = Mango9CallerIdentity.normalizedLabel(fallbackName)
		let preferredName = [displayName, fallback]
			.compactMap { $0 }
			.first { $0.lowercased() != ownName }
		let name = preferredName ?? Mango9CallerIdentity.formattedPhoneNumber(phone)
		return Mango9SMSTarget(phone: phone, name: name)
	}
}

struct Mango9TeamChatListFragment: View {
	@ObservedObject private var store = Mango9ChatStore.shared
	@State private var isShowingCreateGroup = false
	@State private var createdRoom: Mango9ChatRoom?
	let embedded: Bool
	let searchText: String

	init(embedded: Bool = false, searchText: String = "") {
		self.embedded = embedded
		self.searchText = searchText
	}

	var body: some View {
		ZStack {
			Color.gray100.ignoresSafeArea()
			if store.isConnecting && store.users.isEmpty {
				ProgressView("Connecting to Mango9 chat…")
					.tint(Color.orangeMain500)
			} else if let errorMessage = store.errorMessage, store.users.isEmpty {
				VStack(spacing: 14) {
					Image("warning-circle")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.redDanger500)
						.frame(width: 34, height: 34)
					Text(errorMessage)
						.default_text_style(styleSize: 13)
						.multilineTextAlignment(.center)
					Button("Try again") {
						Task {
							await store.connectIfNeeded(force: true)
						}
					}
					.buttonStyle(.borderedProminent)
					.tint(Color.orangeMain500)
				}
				.padding(24)
			} else {
				List {
					if !groupRooms.isEmpty {
						Section("Groups") {
							ForEach(groupRooms) { room in
								NavigationLink(destination: Mango9ChatFragment(room: room)) {
									Mango9GroupChatRow(room: room)
								}
								.listRowBackground(Color.white)
							}
						}
					}
					Section("People") {
						ForEach(visibleUsers) { user in
							NavigationLink(destination: Mango9ChatFragment(user: user)) {
								Mango9TeamChatRow(user: user)
							}
							.listRowBackground(Color.white)
						}
					}
				}
				.listStyle(.plain)
				.refreshable {
					await store.refreshDirectory()
				}
			}
			NavigationLink(
				destination: Group {
					if let createdRoom {
						Mango9ChatFragment(room: createdRoom)
					}
				},
				isActive: Binding(
					get: { createdRoom != nil },
					set: { if !$0 { createdRoom = nil } }
				)
			) {
				EmptyView()
			}
			.hidden()
		}
		.navigationTitle(embedded ? "" : "Team Chat")
		.navigationBarTitleDisplayMode(.inline)
		.toolbar {
			ToolbarItem(placement: .navigationBarTrailing) {
				Button {
					isShowingCreateGroup = true
				} label: {
					Image(systemName: "person.2.badge.plus")
						.foregroundStyle(Color.orangeMain500)
				}
				.opacity(embedded ? 0 : 1)
				.disabled(embedded)
				.accessibilityHidden(embedded)
				.accessibilityLabel("New group")
			}
		}
		.sheet(isPresented: $isShowingCreateGroup) {
			Mango9GroupMembersSheet(
				title: "New group",
				initialSelection: [],
				minimumSelection: 2,
				actionTitle: "Create"
			) { selection in
				if let room = await store.createGroup(userIds: selection) {
					createdRoom = room
					return true
				}
				return false
			}
		}
		.task {
			await store.connectIfNeeded()
		}
	}

	private var groupRooms: [Mango9ChatRoom] {
		let rooms = store.rooms.filter {
			!$0.isDirect && !Mango9ChatModerationStore.shared.isConversationDeleted($0.id)
		}
		let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !query.isEmpty else { return rooms }
		return rooms.filter {
			store.groupTitle($0).localizedCaseInsensitiveContains(query)
				|| $0.lastMessage.localizedCaseInsensitiveContains(query)
		}
	}

	private var visibleUsers: [Mango9ChatUser] {
		let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !query.isEmpty else { return store.users }
		return store.users.filter { user in
			user.name.localizedCaseInsensitiveContains(query)
				|| user.category.localizedCaseInsensitiveContains(query)
				|| (store.roomPreview(for: user.id)?.lastMessage
					.localizedCaseInsensitiveContains(query) ?? false)
		}
	}
}

private struct Mango9TeamChatRow: View {
	@ObservedObject private var store = Mango9ChatStore.shared
	let user: Mango9ChatUser

	var body: some View {
		HStack(spacing: 12) {
			ZStack(alignment: .bottomTrailing) {
				Circle()
					.fill(Color.orangeMain100)
					.frame(width: 46, height: 46)
					.overlay {
						Text(initials)
							.default_text_style_orange_800(styleSize: 15)
					}
				Circle()
					.fill(store.isOnline(user.id) ? Color.greenSuccess500 : Color.grayMain2c400)
					.frame(width: 12, height: 12)
					.overlay(Circle().stroke(Color.white, lineWidth: 2))
			}

			VStack(alignment: .leading, spacing: 3) {
				Text(user.name)
					.default_text_style_700(styleSize: 14)
					.lineLimit(1)
				if let room = store.roomPreview(for: user.id), !room.lastMessage.isEmpty {
					Text(room.lastMessage)
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
						.lineLimit(1)
				} else {
					Text(store.isOnline(user.id) ? "Online" : user.category.capitalized)
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
				}
			}

			Spacer()

			if let unread = store.roomPreview(for: user.id)?.unread, unread > 0 {
				Text(String(unread))
					.font(.system(size: 11, weight: .bold))
					.foregroundStyle(Color.white)
					.padding(.horizontal, 7)
					.frame(minHeight: 22)
					.background(Color.orangeMain500)
					.clipShape(Capsule())
			}
		}
		.padding(.vertical, 5)
	}

	private var initials: String {
		let parts = user.name.split(separator: " ")
		return parts.prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
	}
}

private struct Mango9GroupChatRow: View {
	@ObservedObject private var store = Mango9ChatStore.shared
	let room: Mango9ChatRoom

	var body: some View {
		HStack(spacing: 12) {
			ZStack {
				Circle()
					.fill(Color.grayMain2c800)
					.frame(width: 46, height: 46)
				Image(systemName: "person.2.fill")
					.foregroundStyle(Color.white)
			}
			VStack(alignment: .leading, spacing: 3) {
				Text(store.groupTitle(room))
					.default_text_style_700(styleSize: 14)
					.lineLimit(1)
				Text(room.lastMessage.isEmpty ? "\(room.userIds.count + 1) members" : room.lastMessage)
					.default_text_style(styleSize: 12)
					.foregroundStyle(Color.grayMain2c500)
					.lineLimit(1)
			}
			Spacer()
			if room.unread > 0 {
				Text(String(room.unread))
					.font(.system(size: 11, weight: .bold))
					.foregroundStyle(Color.white)
					.padding(.horizontal, 7)
					.frame(minHeight: 22)
					.background(Color.orangeMain500)
					.clipShape(Capsule())
			}
		}
		.padding(.vertical, 5)
	}
}

struct Mango9ChatFragment: View {
	@ObservedObject private var store = Mango9ChatStore.shared
	@ObservedObject private var moderation = Mango9ChatModerationStore.shared
	@Environment(\.openURL) private var openURL
	@Environment(\.dismiss) private var dismiss
	private let user: Mango9ChatUser?
	private let room: Mango9ChatRoom?
	private let onClose: (() -> Void)?

	@State private var text = ""
	@State private var selectedMedia: [Attachment] = []
	@State private var isSending = false
	@State private var isShowingAttachmentMenu = false
	@State private var isShowingPhotoPicker = false
	@State private var isShowingFilePicker = false
	@State private var isRecordingVoice = false
	@State private var isManagingMembers = false
	@State private var isConfirmingReport = false
	@State private var isConfirmingDelete = false
	@State private var reportedMessage: Mango9ChatMessage?
	@State private var displayedRoomId: String?
	@FocusState private var composerFocused: Bool

	init(user: Mango9ChatUser, onClose: (() -> Void)? = nil) {
		self.user = user
		self.room = nil
		self.onClose = onClose
	}

	init(room: Mango9ChatRoom, onClose: (() -> Void)? = nil) {
		self.user = nil
		self.room = room
		self.onClose = onClose
	}

	var body: some View {
		VStack(spacing: 0) {
			statusHeader
			Divider()
			messageList
			Divider()
			composer
		}
		.background(Color.gray100)
		.navigationTitle(conversationTitle)
		.navigationBarTitleDisplayMode(.inline)
		.navigationBarItems(
			leading:
				Group {
					if let onClose {
						Button {
							onClose()
						} label: {
							Image("caret-left")
								.renderingMode(.template)
								.foregroundStyle(Color.orangeMain500)
						}
						.accessibilityLabel("Back")
					}
				},
			trailing:
				HStack(spacing: 14) {
					if room?.isDirect == false {
						Button {
							isManagingMembers = true
						} label: {
							Image(systemName: "person.2")
								.foregroundStyle(Color.orangeMain500)
						}
						.accessibilityLabel("Group members")
					}

					Menu {
						Button {
							reportedMessage = nil
							isConfirmingReport = true
						} label: {
							Label("Report conversation", systemImage: "exclamationmark.bubble")
						}

						if hasHiddenMessages {
							Button {
								moderation.restoreMessages(store.messages.map(\.id))
							} label: {
								Label("Show hidden messages", systemImage: "eye")
							}
						}

						if let directUserId {
							Button(role: isDirectUserBlocked ? nil : .destructive) {
								moderation.setBlocked(!isDirectUserBlocked, userId: directUserId)
							} label: {
								Label(
									isDirectUserBlocked ? "Unblock user" : "Block user",
									systemImage: isDirectUserBlocked ? "person.crop.circle.badge.checkmark" : "hand.raised"
								)
							}
						}

						if conversationRoomId != nil {
							Divider()
							Button(role: .destructive) {
								isConfirmingDelete = true
							} label: {
								Label("Delete conversation", systemImage: "trash")
							}
						}
					} label: {
						Image(systemName: "ellipsis.circle")
							.foregroundStyle(Color.orangeMain500)
					}
					.accessibilityLabel("Conversation safety options")
				}
		)
		.task(id: conversationKey) {
			if let user {
				await store.openConversation(with: user.id, fallbackName: user.name)
			} else if let room {
				await store.openRoom(room)
			}
			let openedRoomId = store.activeRoomId
			if Task.isCancelled {
				store.closeConversation(roomId: openedRoomId)
			} else {
				displayedRoomId = openedRoomId
			}
		}
		.onDisappear {
			store.closeConversation(roomId: displayedRoomId)
			displayedRoomId = nil
		}
		.confirmationDialog(
			"Add an attachment",
			isPresented: $isShowingAttachmentMenu,
			titleVisibility: .visible
		) {
			Button("Photo or video library") {
				isShowingPhotoPicker = true
			}
			Button("Browse files") {
				isShowingFilePicker = true
			}
		}
		.sheet(isPresented: $isShowingPhotoPicker) {
			PhotoPicker(filter: .any(of: [.images, .videos])) { results in
				PhotoPicker.convertToAttachmentArray(fromResults: results) { attachments, error in
					if let attachments {
						selectedMedia.append(contentsOf: attachments)
					} else if let error {
						store.reportError(error)
					}
				}
				isShowingPhotoPicker = false
			}
		}
		.sheet(isPresented: $isShowingFilePicker) {
			FilePicker { urls in
				FilePicker.convertToAttachmentArray(fromResults: urls) { attachments, error in
					if let attachments {
						selectedMedia.append(contentsOf: attachments)
					} else if let error {
						store.reportError(error)
					}
				}
				isShowingFilePicker = false
			}
		}
		.sheet(isPresented: $isManagingMembers) {
			if let room {
				Mango9GroupMembersSheet(
					title: "Group members",
					initialSelection: Set(room.userIds),
					minimumSelection: 1,
					actionTitle: "Save"
				) { selection in
					await store.updateGroupMembers(room: room, userIds: selection)
				}
			}
		}
		.alert(reportConfirmationTitle, isPresented: $isConfirmingReport) {
			Button("Cancel", role: .cancel) {}
			Button("Continue") {
				openURL(reportURL)
			}
		} message: {
			Text(
				"Mango9 will open a pre-addressed email to support. "
				+ "You can add details or screenshots before sending the report."
			)
		}
		.confirmationDialog(
			"Delete this conversation?",
			isPresented: $isConfirmingDelete,
			titleVisibility: .visible
		) {
			Button("Delete conversation", role: .destructive) {
				guard let roomId = conversationRoomId else {
					return
				}
				store.deleteConversation(roomId: roomId)
				displayedRoomId = nil
				if let onClose {
					onClose()
				} else {
					dismiss()
				}
			}
			Button("Cancel", role: .cancel) {}
		} message: {
			Text(
				"The conversation history will be removed from this app. "
					+ "A new incoming message can start the conversation again."
			)
		}
	}

	private var statusHeader: some View {
		HStack(spacing: 8) {
			Circle()
				.fill(statusColor)
				.frame(width: 8, height: 8)
			Text(statusText)
				.default_text_style(styleSize: 11)
				.foregroundStyle(Color.grayMain2c500)
			Spacer()
			Text("Mango9 chat")
				.font(.system(size: 10, weight: .semibold))
				.foregroundStyle(Color.orangeMain500)
		}
		.padding(.horizontal, 16)
		.frame(height: 34)
		.background(Color.white)
	}

	private var messageList: some View {
		ScrollViewReader { proxy in
			ScrollView {
				LazyVStack(spacing: 8) {
					if isDirectUserBlocked {
						Label(
							"This user is blocked. Their messages are hidden.",
							systemImage: "hand.raised.fill"
						)
						.font(.system(size: 12, weight: .semibold))
						.foregroundStyle(Color.grayMain2c600)
						.padding(.vertical, 24)
					}
					if store.messages.isEmpty && store.activeRoomId != nil {
						Text(emptyPrompt)
							.default_text_style(styleSize: 12)
							.foregroundStyle(Color.grayMain2c500)
							.padding(.top, 36)
					}
					ForEach(visibleMessages) { message in
						Mango9ChatBubble(
							message: message,
							isOutgoing: message.fromUserId == store.currentUserId,
							senderName: room?.isDirect == false
								? store.userName(message.fromUserId)
								: nil
						)
						.id(message.id)
						.contextMenu {
							if message.fromUserId != store.currentUserId {
								Button {
									moderation.setHidden(true, messageId: message.id)
								} label: {
									Label("Hide message", systemImage: "eye.slash")
								}

								Button {
									reportedMessage = message
									isConfirmingReport = true
								} label: {
									Label("Report message", systemImage: "exclamationmark.bubble")
								}
							}
						}
					}
				}
				.padding(.horizontal, 12)
				.padding(.vertical, 14)
			}
			.onChange(of: store.messages.count) { _ in
				guard let lastId = store.messages.last?.id else {
					return
				}
				withAnimation {
					proxy.scrollTo(lastId, anchor: .bottom)
				}
			}
		}
	}

	private var composer: some View {
		VStack(spacing: 7) {
			if isDirectUserBlocked {
				Text("Unblock this user from the conversation menu to send a message.")
					.font(.system(size: 12, weight: .medium))
					.foregroundStyle(Color.grayMain2c600)
					.frame(maxWidth: .infinity, minHeight: 40)
			} else if isRecordingVoice {
				Mango9VoiceRecorderComposer(
					onCancel: {
						isRecordingVoice = false
					},
					onComplete: { attachment in
						selectedMedia.append(attachment)
						isRecordingVoice = false
					}
				)
			} else {
				if !selectedMedia.isEmpty {
					Mango9PendingAttachmentStrip(attachments: $selectedMedia)
				}

				HStack(alignment: .bottom, spacing: 8) {
					Button {
						isShowingAttachmentMenu = true
					} label: {
						Image(systemName: "paperclip")
							.font(.system(size: 19, weight: .semibold))
							.foregroundStyle(Color.grayMain2c700)
							.frame(width: 34, height: 40)
					}
					.disabled(isSending)
					.accessibilityLabel("Attach photo, video, or file")

					growingMessageInput
						.padding(.horizontal, 14)
						.padding(.vertical, 6)
						.background(Color.gray100)
						.cornerRadius(20)
						.onChange(of: text) { newValue in
							if !newValue.isEmpty {
								store.notifyTyping()
							}
						}

					Button {
						isRecordingVoice = true
					} label: {
						Image(systemName: "mic.fill")
							.font(.system(size: 18, weight: .semibold))
							.foregroundStyle(Color.grayMain2c700)
							.frame(width: 32, height: 40)
					}
					.disabled(isSending)
					.accessibilityLabel("Record a voice message")

					Button {
						sendCurrentMessage()
					} label: {
						Group {
							if isSending {
								ProgressView()
									.tint(Color.white)
							} else {
								Image("paper-plane-tilt")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.white)
									.frame(width: 21, height: 21)
							}
						}
						.frame(width: 21, height: 21)
						.padding(10)
						.background(canSend ? Color.orangeMain500 : Color.grayMain2c400)
						.clipShape(Circle())
					}
					.disabled(!canSend)
				}
			}
		}
		.padding(.horizontal, 12)
		.padding(.vertical, 9)
		.background(Color.white)
	}

	@ViewBuilder
	private var growingMessageInput: some View {
		if #available(iOS 16.0, *) {
			TextField("Write a message", text: $text, axis: .vertical)
				.lineLimit(1...5)
				.default_text_style_uncolored(styleSize: 14)
				.focused($composerFocused)
		} else {
			ZStack(alignment: .topLeading) {
				if text.isEmpty {
					Text("Write a message")
						.default_text_style_uncolored(styleSize: 14)
						.foregroundStyle(Color.grayMain2c400)
						.padding(.leading, 4)
						.padding(.top, 8)
						.allowsHitTesting(false)
				}
				TextEditor(text: $text)
					.multilineTextAlignment(.leading)
					.frame(minHeight: 24, maxHeight: 108)
					.fixedSize(horizontal: false, vertical: true)
					.default_text_style_uncolored(styleSize: 14)
					.focused($composerFocused)
					.background(Color.clear)
			}
			.frame(minHeight: 28, maxHeight: 108)
			.contentShape(Rectangle())
			.onTapGesture {
				composerFocused = true
			}
		}
	}

	private var conversationTitle: String {
		if let user {
			return user.name
		}
		if let room {
			return store.groupTitle(room)
		}
		return "Team Chat"
	}

	private var conversationKey: String {
		user.map { "user-\($0.id)" } ?? "room-\(room?.id ?? "")"
	}

	private var conversationRoomId: String? {
		store.activeRoomId ?? displayedRoomId ?? room?.id
	}

	private var statusColor: Color {
		if let user {
			return store.isOnline(user.id) ? Color.greenSuccess500 : Color.grayMain2c400
		}
		return room?.userIds.contains(where: store.isOnline) == true
			? Color.greenSuccess500
			: Color.grayMain2c400
	}

	private var statusText: String {
		if isDirectUserBlocked {
			return "Blocked"
		}
		if let user {
			return store.isTyping(user.id)
				? "\(user.name) is typing…"
				: (store.isOnline(user.id) ? "Online" : "Offline")
		}
		guard let room else {
			return "Offline"
		}
		let typingNames = room.userIds
			.filter(store.isTyping)
			.map(store.userName)
		if !typingNames.isEmpty {
			return "\(typingNames.joined(separator: ", ")) typing…"
		}
		let online = room.userIds.filter(store.isOnline).count
		return "\(room.userIds.count + 1) members · \(online) online"
	}

	private var emptyPrompt: String {
		if room?.isDirect == false {
			return "Start the group conversation."
		}
		return "Start a private conversation with \(user?.name ?? "your teammate")."
	}

	private var canSend: Bool {
		(!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !selectedMedia.isEmpty)
			&& store.activeRoomId != nil
			&& !isSending
			&& !isDirectUserBlocked
	}

	private var directUserId: Int? {
		if let user {
			return user.id
		}
		guard let room, room.isDirect else {
			return nil
		}
		return room.userIds.first
	}

	private var isDirectUserBlocked: Bool {
		guard let directUserId else {
			return false
		}
		return moderation.isBlocked(directUserId)
	}

	private var visibleMessages: [Mango9ChatMessage] {
		store.messages.filter { message in
			!moderation.isHidden(message.id)
				&& (
					message.fromUserId == store.currentUserId
						|| !moderation.isBlocked(message.fromUserId)
				)
		}
	}

	private var hasHiddenMessages: Bool {
		store.messages.contains { moderation.isHidden($0.id) }
	}

	private var reportConfirmationTitle: String {
		reportedMessage == nil ? "Report this conversation?" : "Report this message?"
	}

	private var reportURL: URL {
		let session = Mango9SessionStore.load()
		var bodyLines = [
			"Please describe the content or behavior you are reporting:",
			"",
			"Conversation: \(conversationTitle)",
			"Room ID: \(store.activeRoomId ?? room?.id ?? "not available")",
			"Reported user ID: \(reportedMessage.map { String($0.fromUserId) } ?? directUserId.map(String.init) ?? "group conversation")",
			"CRM environment: \(session?.crmId ?? "not available")",
			"Reporter user ID: \(session?.userId ?? "not available")",
			"App version: \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown") "
				+ "(\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"))",
		]
		if let reportedMessage {
			bodyLines.insert("Message ID: \(reportedMessage.id)", at: 4)
		}
		bodyLines.append("")
		bodyLines.append("Do not include passwords or SIP credentials.")

		var components = URLComponents()
		components.scheme = "mailto"
		components.path = "support@mango9.com"
		components.queryItems = [
			URLQueryItem(
				name: "subject",
				value: reportedMessage == nil
					? "Mango9 conversation safety report"
					: "Mango9 message safety report"
			),
			URLQueryItem(name: "body", value: bodyLines.joined(separator: "\n")),
		]
		return components.url ?? URL(string: "https://www.mango9.com/support")!
	}

	private func sendCurrentMessage() {
		let outgoing = text
		let attachments = selectedMedia
		text = ""
		selectedMedia = []
		isSending = true
		Task {
			let didSend = await store.sendMessage(outgoing, attachments: attachments)
			isSending = false
			if !didSend {
				text = outgoing
				selectedMedia = attachments
			}
		}
	}
}

private struct Mango9ChatBubble: View {
	let message: Mango9ChatMessage
	let isOutgoing: Bool
	let senderName: String?

	var body: some View {
		HStack {
			if isOutgoing {
				Spacer(minLength: 52)
			}
			VStack(alignment: isOutgoing ? .trailing : .leading, spacing: 4) {
				if let senderName, !isOutgoing {
					Text(senderName)
						.font(.system(size: 10, weight: .semibold))
						.foregroundStyle(Color.grayMain2c500)
						.padding(.horizontal, 6)
				}

				VStack(alignment: .leading, spacing: 8) {
					ForEach(Mango9ChatMedia.parse(message.files)) { media in
						Mango9ChatMediaView(
							media: media,
							isOutgoing: isOutgoing
						)
					}
					if !message.text.isEmpty {
						Text(message.text)
							.default_text_style_white(styleSize: 14)
							.multilineTextAlignment(.leading)
							.lineLimit(nil)
							.fixedSize(horizontal: false, vertical: true)
							.layoutPriority(1)
					}
				}
				.layoutPriority(1)
				.padding(.horizontal, 13)
				.padding(.vertical, 9)
				.background(Color(uiColor: .systemBlue))
				.clipShape(RoundedRectangle(cornerRadius: 16))

				HStack(spacing: 4) {
					Text(Self.shortTime(message.time))
						.font(.system(size: 9))
						.foregroundStyle(Color.grayMain2c500)
					if isOutgoing {
						Text(message.status >= 3 ? "Read" : (message.status >= 2 ? "Delivered" : "Sent"))
							.font(.system(size: 9))
							.foregroundStyle(Color.grayMain2c500)
					}
				}
			}
			.layoutPriority(1)
			if !isOutgoing {
				Spacer(minLength: 52)
			}
		}
		.frame(maxWidth: .infinity)
	}

	private static func shortTime(_ value: String) -> String {
		let formatter = ISO8601DateFormatter()
		formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
		let date = formatter.date(from: value)
			?? ISO8601DateFormatter().date(from: value)
		guard let date else {
			return ""
		}
		let display = DateFormatter()
		display.timeStyle = .short
		return display.string(from: date)
	}
}

struct Mango9ChatMedia: Identifiable {
	enum Kind: Equatable {
		case image
		case video
		case audio
		case file
	}

	let id: String
	let url: URL
	let name: String
	let mimeType: String
	let kind: Kind

	static func parse(_ value: String) -> [Mango9ChatMedia] {
		value
			.split(separator: ",", omittingEmptySubsequences: true)
			.compactMap { rawValue in
				let raw = String(rawValue)
				guard let metadataURL = URL(string: raw) else {
					return nil
				}
				let query = URLComponents(url: metadataURL, resolvingAgainstBaseURL: false)?
					.queryItems?
					.reduce(into: [String: String]()) { values, item in
						values[item.name] = item.value ?? ""
					} ?? [:]
				let downloadValue = raw.range(of: "&ffName=").map {
					String(raw[..<$0.lowerBound])
				} ?? raw.range(of: "?ffName=").map {
					String(raw[..<$0.lowerBound])
				} ?? raw
				guard let url = URL(string: downloadValue) else {
					return nil
				}
				let name = query["ffName"]?.nilIfEmpty
					?? url.lastPathComponent.removingPercentEncoding
					?? "Attachment"
				let mimeType = query["ffType"] ?? url.mimeType()
				let extensionValue = (query["ffExt"] ?? url.pathExtension).lowercased()
				let kind: Kind
				if mimeType.hasPrefix("image/") || ["jpg", "jpeg", "png", "gif", "webp"].contains(extensionValue) {
					kind = .image
				} else if mimeType.hasPrefix("video/") || ["mp4", "mov", "webm"].contains(extensionValue) {
					kind = .video
				} else if mimeType.hasPrefix("audio/") || ["m4a", "mka", "mp3", "ogg", "wav"].contains(extensionValue) {
					kind = .audio
				} else {
					kind = .file
				}
				return Mango9ChatMedia(
					id: raw,
					url: url,
					name: name,
					mimeType: mimeType,
					kind: kind
				)
			}
	}
}

private struct Mango9ChatMediaView: View {
	let media: Mango9ChatMedia
	let isOutgoing: Bool

	var body: some View {
		switch media.kind {
		case .image:
			AsyncImage(url: media.url) { phase in
				switch phase {
				case .success(let image):
					image
						.resizable()
						.scaledToFit()
						.frame(maxWidth: 230, maxHeight: 240)
						.clipShape(RoundedRectangle(cornerRadius: 10))
				case .failure:
					attachmentLink(icon: "photo", title: media.name)
				default:
					ProgressView()
						.frame(width: 180, height: 120)
				}
			}
		case .video:
			Mango9VideoAttachmentView(media: media)
		case .audio:
			Mango9RemoteAudioPlayer(
				audioURL: media.url,
				name: media.name,
				isOutgoing: isOutgoing
			)
			.frame(width: 220)
		case .file:
			attachmentLink(icon: "doc.fill", title: media.name)
		}
	}

	private func attachmentLink(icon: String, title: String) -> some View {
		Link(destination: media.url) {
			HStack(spacing: 8) {
				Image(systemName: icon)
					.foregroundStyle(Color.orangeMain500)
				Text(title)
					.font(.system(size: 12, weight: .semibold))
					.foregroundStyle(Color.white)
					.lineLimit(2)
			}
			.frame(maxWidth: 220, alignment: .leading)
		}
	}
}

private struct Mango9VideoAttachmentView: View {
	let media: Mango9ChatMedia
	@State private var isPresentingPlayer = false

	var body: some View {
		Button {
			isPresentingPlayer = true
		} label: {
			VStack(alignment: .leading, spacing: 0) {
				ZStack {
					Mango9VideoThumbnailView(
						videoURL: media.url,
						thumbnailURL: nil,
						maxPixelSize: 900
					)
					.frame(width: 230, height: 150)
					.clipShape(RoundedRectangle(cornerRadius: 10))
					Color.black.opacity(0.18)
						.clipShape(RoundedRectangle(cornerRadius: 10))
					Image(systemName: "play.fill")
						.font(.system(size: 26, weight: .bold))
						.foregroundStyle(Color.white)
						.frame(width: 56, height: 56)
						.background(Color.white.opacity(0.18))
						.clipShape(Circle())
				}
				.frame(width: 230, height: 150)
			}
		}
		.buttonStyle(.plain)
		.accessibilityLabel("Play video")
		.fullScreenCover(isPresented: $isPresentingPlayer) {
			Mango9VideoAttachmentViewer(
				sourceURL: media.url,
				name: media.name
			)
		}
	}
}

private struct Mango9PendingAttachmentStrip: View {
	@Binding var attachments: [Attachment]

	var body: some View {
		ScrollView(.horizontal, showsIndicators: false) {
			HStack(spacing: 8) {
				ForEach(attachments) { attachment in
					HStack(spacing: 7) {
						preview(for: attachment)
						.frame(width: 34, height: 34)
						.clipShape(RoundedRectangle(cornerRadius: 7))
						.background(Color.gray100)
					Text(attachment.name)
						.font(.system(size: 10, weight: .medium))
						.foregroundStyle(Color.grayMain2c700)
						.lineLimit(1)
						.frame(maxWidth: 105)
					Button {
						attachments.removeAll { $0.id == attachment.id }
					} label: {
						Image(systemName: "xmark.circle.fill")
							.foregroundStyle(Color.grayMain2c500)
					}
				}
				.padding(6)
				.background(Color.grayMain2c100)
				.clipShape(RoundedRectangle(cornerRadius: 10))
			}
			}
		}
	}

	@ViewBuilder
	private func preview(for attachment: Attachment) -> some View {
		if attachment.type == .image || attachment.type == .gif,
		   let image = UIImage(contentsOfFile: attachment.full.path) {
			Image(uiImage: image)
				.resizable()
				.scaledToFill()
		} else if attachment.type == .video,
				  let image = UIImage(contentsOfFile: attachment.thumbnail.path) {
			Image(uiImage: image)
				.resizable()
				.scaledToFill()
		} else {
			Image(systemName: attachment.type == .voiceRecording ? "waveform" : "doc.fill")
				.foregroundStyle(Color.orangeMain500)
		}
	}
}

@MainActor
private final class Mango9VoiceRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
	@Published private(set) var duration: TimeInterval = 0
	@Published private(set) var isRecording = false
	@Published private(set) var errorMessage: String?

	private var recorder: AVAudioRecorder?
	private var timer: Timer?
	private(set) var fileURL: URL?

	func start() {
		let session = AVAudioSession.sharedInstance()
		session.requestRecordPermission { [weak self] granted in
			Task { @MainActor in
				guard let self else {
					return
				}
				guard granted else {
					self.errorMessage = "Microphone access is required to record a voice message."
					return
				}
				do {
					try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker])
					try session.setActive(true)
					let url = FileManager.default.temporaryDirectory
						.appendingPathComponent("mango9-voice-\(UUID().uuidString.lowercased()).m4a")
					let settings: [String: Any] = [
						AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
						AVSampleRateKey: 44_100,
						AVNumberOfChannelsKey: 1,
						AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
					]
					let recorder = try AVAudioRecorder(url: url, settings: settings)
					recorder.delegate = self
					recorder.isMeteringEnabled = true
					guard recorder.record() else {
						throw Mango9ChatError.server("Voice recording could not be started.")
					}
					self.fileURL = url
					self.recorder = recorder
					self.duration = 0
					self.isRecording = true
					self.timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
						Task { @MainActor in
							self?.duration = self?.recorder?.currentTime ?? 0
						}
					}
				} catch {
					self.errorMessage = error.localizedDescription
				}
			}
		}
	}

	func finish() -> Attachment? {
		stop()
		guard let fileURL else {
			return nil
		}
		let size = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
		return Attachment(
			id: UUID().uuidString,
			name: fileURL.lastPathComponent,
			url: fileURL,
			type: .voiceRecording,
			duration: Int(duration * 1_000),
			size: size
		)
	}

	func cancel() {
		stop()
		if let fileURL {
			try? FileManager.default.removeItem(at: fileURL)
		}
		fileURL = nil
	}

	func stop() {
		recorder?.stop()
		recorder = nil
		timer?.invalidate()
		timer = nil
		isRecording = false
		try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
	}
}

private struct Mango9VoiceRecorderComposer: View {
	@StateObject private var recorder = Mango9VoiceRecorder()
	let onCancel: () -> Void
	let onComplete: (Attachment) -> Void

	var body: some View {
		HStack(spacing: 10) {
			Button {
				recorder.cancel()
				onCancel()
			} label: {
				Image(systemName: "xmark")
					.foregroundStyle(Color.redDanger500)
					.frame(width: 34, height: 34)
					.background(Color.gray100)
					.clipShape(Circle())
			}
			Image(systemName: "waveform")
				.foregroundStyle(recorder.isRecording ? Color.redDanger500 : Color.grayMain2c400)
			VStack(alignment: .leading, spacing: 2) {
				Text(recorder.errorMessage ?? (recorder.isRecording ? "Recording voice message" : "Starting microphone…"))
					.font(.system(size: 11, weight: .semibold))
					.foregroundStyle(Color.grayMain2c700)
				Text(Self.duration(recorder.duration))
					.font(.system(size: 10))
					.foregroundStyle(Color.grayMain2c500)
			}
			Spacer()
			Button {
				if let attachment = recorder.finish() {
					onComplete(attachment)
				}
			} label: {
				Image(systemName: "checkmark")
					.foregroundStyle(Color.white)
					.frame(width: 36, height: 36)
					.background(recorder.isRecording ? Color.orangeMain500 : Color.grayMain2c400)
					.clipShape(Circle())
			}
			.disabled(!recorder.isRecording)
			.accessibilityLabel("Attach voice recording")
		}
		.task {
			recorder.start()
		}
		.onDisappear {
			recorder.stop()
		}
	}

	private static func duration(_ value: TimeInterval) -> String {
		let seconds = max(0, Int(value))
		return String(format: "%d:%02d", seconds / 60, seconds % 60)
	}
}

private struct Mango9GroupMembersSheet: View {
	@Environment(\.dismiss) private var dismiss
	@ObservedObject private var store = Mango9ChatStore.shared
	let title: String
	let minimumSelection: Int
	let actionTitle: String
	let onSave: (Set<Int>) async -> Bool

	@State private var selection: Set<Int>
	@State private var isSaving = false

	init(
		title: String,
		initialSelection: Set<Int>,
		minimumSelection: Int,
		actionTitle: String,
		onSave: @escaping (Set<Int>) async -> Bool
	) {
		self.title = title
		self.minimumSelection = minimumSelection
		self.actionTitle = actionTitle
		self.onSave = onSave
		_selection = State(initialValue: initialSelection)
	}

	var body: some View {
		NavigationView {
			List(store.users) { user in
				Button {
					if selection.contains(user.id) {
						selection.remove(user.id)
					} else {
						selection.insert(user.id)
					}
				} label: {
					HStack {
						Mango9TeamChatRow(user: user)
						.allowsHitTesting(false)
						Spacer()
						Image(systemName: selection.contains(user.id) ? "checkmark.circle.fill" : "circle")
							.foregroundStyle(
								selection.contains(user.id)
									? Color.orangeMain500
									: Color.grayMain2c400
							)
					}
				}
				.buttonStyle(.plain)
			}
			.navigationTitle(title)
			.navigationBarTitleDisplayMode(.inline)
			.toolbar {
				ToolbarItem(placement: .cancellationAction) {
					Button("Cancel") {
						dismiss()
					}
				}
				ToolbarItem(placement: .confirmationAction) {
					Button(actionTitle) {
						isSaving = true
						Task {
							let saved = await onSave(selection)
							isSaving = false
							if saved {
								dismiss()
							}
						}
					}
					.disabled(selection.count < minimumSelection || isSaving)
				}
			}
		}
		.navigationViewStyle(.stack)
	}
}

struct Mango9ChatStandaloneFragment: View {
	@Binding var target: Mango9ChatTarget?
	@ObservedObject private var store = Mango9ChatStore.shared

	@ViewBuilder
	private var conversation: some View {
		if let roomId = target?.roomId,
		   let room = store.rooms.first(where: { $0.id == roomId }) {
			Mango9ChatFragment(
				room: room,
				onClose: close
			)
		} else if let target, target.roomId != nil, target.userId <= 0 {
			ProgressView("Opening conversation…")
				.task {
					await store.connectIfNeeded()
					await store.refreshDirectory()
				}
		} else if let target {
			Mango9ChatFragment(
				user: store.users.first(where: { $0.id == target.userId })
					?? Mango9ChatUser(
						id: target.userId,
						name: target.name,
						avatar: "",
						category: ""
					),
				onClose: close
			)
		}
	}

	var body: some View {
		NavigationView {
			conversation
		}
		.navigationViewStyle(.stack)
	}

	private func close() {
		withAnimation {
			target = nil
		}
	}
}

struct Mango9Lead: Decodable, Identifiable {
	let id: Int
	let ownerUserId: Int
	let ownerName: String
	let name: String
	let phone: String
	let email: String
	let status: String
	let source: String
	let createdAt: String
}

enum Mango9CRMRecordKind: Equatable {
	case lead
	case client

	var singular: String { self == .lead ? "Lead" : "Client" }
	var plural: String { self == .lead ? "Leads" : "Clients" }
	var emptyName: String { self == .lead ? "Unnamed lead" : "Unnamed client" }
	var changeNotification: Notification.Name {
		self == .lead ? .mango9LeadDidChange : .mango9ClientDidChange
	}
}

struct Mango9LeadGroup: Decodable, Identifiable {
	let id: String
	let name: String

	private enum CodingKeys: String, CodingKey {
		case id
		case name
	}

	init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		id = try container.decode(Mango9CRMFieldValue.self, forKey: .id).value
		name = try container.decode(String.self, forKey: .name)
	}
}

struct Mango9LeadGroupsPayload: Decodable {
	let groups: [Mango9LeadGroup]

	private enum CodingKeys: String, CodingKey {
		case groups
	}

	init(from decoder: Decoder) throws {
		if let container = try? decoder.singleValueContainer(),
		   let groups = try? container.decode([Mango9LeadGroup].self) {
			self.groups = groups
			return
		}
		let container = try decoder.container(keyedBy: CodingKeys.self)
		groups = try container.decodeIfPresent([Mango9LeadGroup].self, forKey: .groups) ?? []
	}
}

enum Mango9CRMDateFilter: String, CaseIterable, Identifiable {
	case all
	case today
	case last7Days
	case last30Days

	var id: String { rawValue }

	var title: String {
		switch self {
		case .all:
			return "All"
		case .today:
			return "Today"
		case .last7Days:
			return "Last 7 Days"
		case .last30Days:
			return "Last 30 Days"
		}
	}

	var compactTitle: String {
		switch self {
		case .all:
			return "All"
		case .today:
			return "Today"
		case .last7Days:
			return "7d"
		case .last30Days:
			return "30d"
		}
	}

	var apiValue: String? {
		switch self {
		case .all:
			return nil
		case .today:
			return "today"
		case .last7Days:
			return "last_7_days"
		case .last30Days:
			return "last_30_days"
		}
	}

	func includes(_ rawDate: String, now: Date = Date()) -> Bool {
		guard self != .all else { return true }
		guard let date = Self.parse(rawDate) else { return false }

		let calendar = Calendar.current
		switch self {
		case .all:
			return true
		case .today:
			return calendar.isDate(date, inSameDayAs: now)
		case .last7Days:
			let start = calendar.date(
				byAdding: .day,
				value: -6,
				to: calendar.startOfDay(for: now)
			) ?? now
			return date >= start && date <= now
		case .last30Days:
			let start = calendar.date(
				byAdding: .day,
				value: -29,
				to: calendar.startOfDay(for: now)
			) ?? now
			return date >= start && date <= now
		}
	}

	private static func parse(_ rawDate: String) -> Date? {
		let value = rawDate.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !value.isEmpty else { return nil }

		if let date = ISO8601DateFormatter().date(from: value) {
			return date
		}

		let formatter = DateFormatter()
		formatter.locale = Locale(identifier: "en_US_POSIX")
		for format in [
			"yyyy-MM-dd HH:mm:ss",
			"yyyy-MM-dd'T'HH:mm:ss",
			"yyyy-MM-dd",
			"MM/dd/yyyy HH:mm:ss",
			"MM/dd/yyyy"
		] {
			formatter.dateFormat = format
			if let date = formatter.date(from: value) {
				return date
			}
		}
		return nil
	}
}

struct Mango9LeadListPayload: Decodable {
	struct Pagination: Decodable {
		let total: Int
		let page: Int
		let limit: Int
		let pages: Int
	}

	let leads: [Mango9Lead]
	let pagination: Pagination
}

struct Mango9LeadDetailPayload: Decodable {
	let lead: Mango9Lead
	let schemaVersion: String
	let values: [String: String]

	private enum CodingKeys: String, CodingKey {
		case lead
		case client
		case schemaVersion
		case values
	}

	init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		if let decodedLead = try container.decodeIfPresent(Mango9Lead.self, forKey: .lead) {
			lead = decodedLead
		} else {
			lead = try container.decode(Mango9Lead.self, forKey: .client)
		}
		schemaVersion = (
			try container.decodeIfPresent(Mango9CRMFieldValue.self, forKey: .schemaVersion)
		)?.value ?? ""
		values = (
			try container.decodeIfPresent(
				[String: Mango9CRMFieldValue].self,
				forKey: .values
			)
		)?.mapValues(\.value) ?? [:]
	}
}

private struct Mango9EmptyPayload: Decodable {
	init(from decoder: Decoder) throws {}
}

private struct Mango9CRMFieldValue: Decodable {
	let value: String

	init(from decoder: Decoder) throws {
		let container = try decoder.singleValueContainer()
		if container.decodeNil() {
			value = ""
		} else if let string = try? container.decode(String.self) {
			value = string
		} else if let integer = try? container.decode(Int.self) {
			value = String(integer)
		} else if let decimal = try? container.decode(Double.self) {
			value = String(decimal)
		} else if let boolean = try? container.decode(Bool.self) {
			value = boolean ? "true" : "false"
		} else if let strings = try? container.decode([String].self) {
			value = strings.joined(separator: ", ")
		} else {
			// A new or unsupported CRM field type must not make the entire
			// lead unreadable. It remains blank until the app supports it.
			value = ""
		}
	}
}

struct Mango9TeamMember: Decodable, Identifiable {
	let userId: Int
	let loginId: String
	let name: String
	let email: String
	let mobile: String
	let role: String
	let `extension`: String
	let sipUri: String?

	var id: Int { userId }
	var displayName: String {
		let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
		return trimmedName.isEmpty ? loginId : trimmedName
	}
}

struct Mango9TeamMembersPayload: Decodable {
	let members: [Mango9TeamMember]
}

struct Mango9LineIdentity: Decodable {
	let extensionNumber: String
	let activeNumber: String?

	enum CodingKeys: String, CodingKey {
		case extensionNumber = "username"
		case activeNumber
	}
}

struct Mango9ProvisioningPayload: Decodable {
	let sip: Mango9LineIdentity
}

struct Mango9CallSettings: Decodable {
	struct Line: Decodable {
		let activeNumber: String
		let `extension`: String
	}

	struct Forwarding: Decodable {
		let enabled: Bool
		let destination: String
	}

	let line: Line
	let forwarding: Forwarding
}

private struct Mango9CallSettingsErrorResponse: Decodable {
	let message: String?
}

enum Mango9CallSettingsAPIError: LocalizedError {
	case message(String)

	var errorDescription: String? {
		switch self {
		case .message(let message):
			return message
		}
	}
}

enum Mango9LineIdentityStore {
	static let extensionKey = "mango9_active_extension"
	static let activeNumberKey = "mango9_active_number"
	private static let perAccountPrefix = "mango9_line_identity."
	private static let legacyMigrationKey =
		"mango9_line_identity_legacy_migrated_v1"

	static func migrateLegacyActiveValuesIfNeeded(sipIdentity: String) {
		let defaults = UserDefaults.standard
		guard !defaults.bool(forKey: legacyMigrationKey) else { return }
		defer { defaults.set(true, forKey: legacyMigrationKey) }

		guard let identity = Mango9SessionStore.normalizedIdentity(sipIdentity),
			  load(sipIdentity: identity) == nil else {
			return
		}
		let extensionNumber = defaults.string(forKey: extensionKey)?
			.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		let activeNumber = defaults.string(forKey: activeNumberKey)?
			.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		guard !extensionNumber.isEmpty else { return }

		defaults.set(
			[
				"extension": extensionNumber,
				"active_number": formatPhoneNumber(activeNumber)
			],
			forKey: storageKey(for: identity)
		)
		Log.info(
			"[Mango9] Migrated the legacy line identity cache to \(identity)"
		)
	}

	static func save(
		_ identity: Mango9LineIdentity,
		sipIdentity: String? = Mango9SessionStore.activeIdentity
	) {
		let defaults = UserDefaults.standard
		let extensionNumber = identity.extensionNumber
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let activeNumber = formatPhoneNumber(identity.activeNumber ?? "")
		if let sipIdentity = Mango9SessionStore.normalizedIdentity(sipIdentity) {
			defaults.set(
				[
					"extension": extensionNumber,
					"active_number": activeNumber
				],
				forKey: storageKey(for: sipIdentity)
			)
			if sipIdentity == Mango9SessionStore.activeIdentity {
				setActiveValues(
					extensionNumber: extensionNumber,
					activeNumber: activeNumber
				)
			}
		} else {
			setActiveValues(
				extensionNumber: extensionNumber,
				activeNumber: activeNumber
			)
		}
		notifyChanged(sipIdentity)
	}

	static func load(sipIdentity: String) -> Mango9LineIdentity? {
		guard let identity = Mango9SessionStore.normalizedIdentity(sipIdentity),
			  let stored = UserDefaults.standard.dictionary(
				forKey: storageKey(for: identity)
			  ),
			  let extensionNumber = stored["extension"] as? String else {
			return nil
		}
		return Mango9LineIdentity(
			extensionNumber: extensionNumber,
			activeNumber: stored["active_number"] as? String
		)
	}

	static func activate(sipIdentity: String) {
		if let identity = load(sipIdentity: sipIdentity) {
			setActiveValues(
				extensionNumber: identity.extensionNumber,
				activeNumber: identity.activeNumber ?? ""
			)
		} else {
			clearActiveValues()
		}
		notifyChanged(sipIdentity)
	}

	static func clear(sipIdentity: String? = Mango9SessionStore.activeIdentity) {
		if let identity = Mango9SessionStore.normalizedIdentity(sipIdentity) {
			UserDefaults.standard.removeObject(forKey: storageKey(for: identity))
			if identity == Mango9SessionStore.activeIdentity {
				clearActiveValues()
			}
		} else {
			clearActiveValues()
		}
		notifyChanged(sipIdentity)
	}

	private static func setActiveValues(
		extensionNumber: String,
		activeNumber: String
	) {
		let defaults = UserDefaults.standard
		defaults.set(extensionNumber, forKey: extensionKey)
		defaults.set(activeNumber, forKey: activeNumberKey)
	}

	private static func clearActiveValues() {
		UserDefaults.standard.removeObject(forKey: extensionKey)
		UserDefaults.standard.removeObject(forKey: activeNumberKey)
	}

	private static func storageKey(for identity: String) -> String {
		perAccountPrefix + identity
	}

	private static func notifyChanged(_ sipIdentity: String?) {
		DispatchQueue.main.async {
			NotificationCenter.default.post(
				name: .mango9LineIdentityChanged,
				object: sipIdentity
			)
		}
	}

	private static func formatPhoneNumber(_ raw: String) -> String {
		var digits = raw.filter(\.isNumber)
		if digits.count == 11, digits.first == "1" {
			digits.removeFirst()
		}
		guard digits.count == 10 else {
			return raw.trimmingCharacters(in: .whitespacesAndNewlines)
		}
		let areaEnd = digits.index(digits.startIndex, offsetBy: 3)
		let prefixEnd = digits.index(areaEnd, offsetBy: 3)
		return "\(digits[..<areaEnd])-\(digits[areaEnd..<prefixEnd])-\(digits[prefixEnd...])"
	}
}

struct Mango9ChatBootstrap: Decodable {
	let websocketUrl: String
	let token: String
	let expiresIn: Int
	let userId: Int
	let transport: String
}

private struct Mango9ChatUpload: Decodable {
	let putUrl: String
	let contentDisposition: String
	let getUrl: String
}

struct Mango9ChatUser: Identifiable, Equatable {
	let id: Int
	let name: String
	let avatar: String
	let category: String
}

struct Mango9ChatRoom: Identifiable, Equatable {
	let id: String
	let userIds: [Int]
	let latest: String
	let lastMessage: String
	let unread: Int
	let isDirect: Bool
}

struct Mango9ChatMessage: Identifiable, Equatable {
	let id: String
	let fromUserId: Int
	let roomId: String
	let text: String
	let time: String
	let status: Int
	let files: String
}

struct Mango9SMSParty: Identifiable, Equatable {
	let phone: String
	let latest: String
	let lastMessage: String
	let unread: Int
	let avatar: String

	var id: String { phone }
}

struct Mango9ServerSMSMessage: Identifiable, Equatable {
	let id: String
	let phone: String
	let text: String
	let time: String
	let senderID: String
	let status: Int
	let isIncoming: Bool
	let files: String

	func withStatus(_ newStatus: Int) -> Self {
		Self(
			id: id,
			phone: phone,
			text: text,
			time: time,
			senderID: senderID,
			status: newStatus,
			isIncoming: isIncoming,
			files: files
		)
	}
}

enum Mango9SMSDeliveryState: Equatable {
	case sent
	case delivered
	case failed
}

enum Mango9SMSDeliveryPolicy {
	static func state(for status: Int) -> Mango9SMSDeliveryState {
		if status == 99 { return .failed }
		if status >= 2 { return .delivered }
		// A record returned by the server is accepted/queued even when the carrier has not
		// published a receipt. Do not leave it visually "Sending" indefinitely.
		return .sent
	}

	static func newest(_ first: Int, _ second: Int) -> Int {
		if first == 99 || second == 99 { return 99 }
		return max(first, second)
	}
}

struct Mango9ServerSMSSender: Identifiable, Equatable {
	let id: Int
	let senderID: String
}

struct Mango9ChatTarget: Identifiable, Equatable {
	let userId: Int
	let name: String
	let roomId: String?

	init(userId: Int, name: String, roomId: String? = nil) {
		self.userId = userId
		self.name = name
		self.roomId = roomId
	}

	var id: String { roomId ?? "user:\(userId)" }
}

typealias Mango9Client = Mango9Lead

struct Mango9ClientListPayload: Decodable {
	struct Pagination: Decodable {
		let total: Int
		let page: Int
		let limit: Int
		let pages: Int
	}

	let clients: [Mango9Client]
	let pagination: Pagination
}

struct Mango9CommunicationTarget: Identifiable {
	let id = UUID()
	let name: String
	let phone: String
	let email: String

	var displayName: String {
		let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
		return trimmed.isEmpty ? "CRM contact" : trimmed
	}
}

@MainActor
enum Mango9CommunicationRouter {
	static func callWithMango9(_ target: Mango9CommunicationTarget) {
		let dialValue = sipDialValue(target.phone)
		let displayHandle = displayPhoneNumber(target.phone)
		guard !dialValue.isEmpty else { return }

		CoreContext.shared.doOnCoreQueue { core in
			guard let address = core.interpretUrl(url: dialValue, applyInternationalPrefix: false) else {
				DispatchQueue.main.async {
					ToastViewModel.shared.show("The phone number could not be called.")
				}
				return
			}

			try? address.setDisplayname(newValue: target.displayName)
			TelecomManager.shared.doCallOrJoinConf(
				address: address,
				displayName: target.displayName,
				displayHandle: displayHandle
			)
		}
	}

	static func callNative(_ phone: String) {
		openNativeURL(scheme: "tel", value: nativePhoneValue(phone))
	}

	static func smsNative(_ phone: String) {
		openNativeURL(scheme: "sms", value: nativePhoneValue(phone))
	}

	static func emailNative(_ email: String) {
		let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
		guard
			!address.isEmpty,
			let encoded = address.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
			let url = URL(string: "mailto:\(encoded)")
		else {
			return
		}
		UIApplication.shared.open(url)
	}

	static func copy(_ value: String, confirmation: String) {
		UIPasteboard.general.string = value
		ToastViewModel.shared.show(confirmation)
	}

	private static func nativePhoneValue(_ raw: String) -> String {
		let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
		let digits = trimmed.filter(\.isNumber)
		return trimmed.hasPrefix("+") ? "+\(digits)" : digits
	}

	private static func sipDialValue(_ raw: String) -> String {
		let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else { return "" }

		let digits = trimmed.filter(\.isNumber)
		if digits.count == 10 {
			return "1\(digits)"
		}
		if digits.count == 11, digits.first == "1" {
			return digits
		}
		if trimmed.hasPrefix("+"), !digits.isEmpty {
			return "+\(digits)"
		}
		return digits.isEmpty ? trimmed : digits
	}

	private static func displayPhoneNumber(_ raw: String) -> String {
		var digits = raw.filter(\.isNumber)
		if digits.count == 11, digits.first == "1" {
			digits.removeFirst()
		}
		guard digits.count == 10 else {
			return raw.trimmingCharacters(in: .whitespacesAndNewlines)
		}

		let areaEnd = digits.index(digits.startIndex, offsetBy: 3)
		let prefixEnd = digits.index(areaEnd, offsetBy: 3)
		return "\(digits[..<areaEnd])-\(digits[areaEnd..<prefixEnd])-\(digits[prefixEnd...])"
	}

	private static func openNativeURL(scheme: String, value: String) {
		guard !value.isEmpty, let url = URL(string: "\(scheme):\(value)") else {
			return
		}
		UIApplication.shared.open(url) { opened in
			if !opened {
				DispatchQueue.main.async {
					ToastViewModel.shared.show("This action is not available on this device.")
				}
			}
		}
	}
}

extension Mango9CRMAPI {
	static func lineIdentity(session: Mango9Session) async throws -> Mango9LineIdentity {
		let request = try authorizedRequest(
			session: session,
			path: ["provisioning"]
		)
		let envelope: Envelope<Mango9ProvisioningPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload.sip
	}

	static func chatBootstrap(session: Mango9Session) async throws -> Mango9ChatBootstrap {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "chat", "bootstrap"]
		)
		let envelope: Envelope<Mango9ChatBootstrap> = try await send(request)
		guard envelope.success, let bootstrap = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return bootstrap
	}

	static func teamMembers(session: Mango9Session) async throws -> [Mango9TeamMember] {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "team-members"]
		)
		let envelope: Envelope<Mango9TeamMembersPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload.members
	}

	static func callSettings(session: Mango9Session) async throws -> Mango9CallSettings {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "call-settings"]
		)
		let envelope: Envelope<Mango9CallSettings> = try await send(request)
		guard envelope.success, let settings = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return settings
	}

	static func updateCallForwarding(
		session: Mango9Session,
		enabled: Bool,
		destination: String
	) async throws -> Mango9CallSettings {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "call-settings", "forwarding"]
		)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(
			withJSONObject: [
				"enabled": enabled,
				"destination": destination
			]
		)
		let (data, response) = try await URLSession.shared.data(for: request)
		guard let httpResponse = response as? HTTPURLResponse else {
			throw Mango9CRMAPIError.server
		}
		if httpResponse.statusCode == 401 {
			throw Mango9CRMAPIError.unauthorized
		}
		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		guard (200..<300).contains(httpResponse.statusCode) else {
			let error = try? decoder.decode(Mango9CallSettingsErrorResponse.self, from: data)
			throw Mango9CallSettingsAPIError.message(
				error?.message ?? "The CRM could not update call forwarding."
			)
		}
		let envelope = try decoder.decode(
			Envelope<Mango9CallSettings>.self,
			from: data
		)
		guard envelope.success, let settings = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return settings
	}

	static func clients(
		session: Mango9Session,
		search: String,
		status: String = "",
		groupId: String = "",
		dateFilter: Mango9CRMDateFilter = .all,
		page: Int = 1
	) async throws -> Mango9ClientListPayload {
		var query = [
			URLQueryItem(name: "page", value: String(page)),
			URLQueryItem(name: "limit", value: "50")
		]
		if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			query.append(URLQueryItem(name: "search", value: search))
		}
		if !status.isEmpty {
			query.append(URLQueryItem(name: "status", value: status))
		}
		if !groupId.isEmpty {
			query.append(URLQueryItem(name: "group_id", value: groupId))
		}
		if let dateFilter = dateFilter.apiValue {
			query.append(URLQueryItem(name: "date_filter", value: dateFilter))
		}
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients"],
			query: query
		)
		let envelope: Envelope<Mango9ClientListPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func clientSchema(session: Mango9Session) async throws -> Mango9LeadSchema {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "schema", "clients"]
		)
		let envelope: Envelope<Mango9LeadSchema> = try await send(request)
		guard envelope.success, let schema = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return schema
	}

	static func clientGroups(session: Mango9Session) async throws -> [Mango9LeadGroup] {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients", "groups"]
		)
		let envelope: Envelope<Mango9LeadGroupsPayload> = try await send(request)
		guard envelope.success else {
			throw Mango9CRMAPIError.server
		}
		return envelope.data?.groups ?? []
	}

	static func client(
		session: Mango9Session,
		id: Int
	) async throws -> Mango9LeadDetailPayload {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients", String(id)]
		)
		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func createClient(
		session: Mango9Session,
		firstName: String,
		lastName: String,
		phone: String,
		email: String,
		groupId: String
	) async throws -> Mango9LeadDetailPayload {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients"]
		)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		let fullName = [firstName, lastName]
			.filter { !$0.isEmpty }
			.joined(separator: " ")
		var body: [String: String] = [
			"first_name": firstName,
			"last_name": lastName,
			"contact_name": fullName,
			"phone": phone,
			"email": email
		]
		if !groupId.isEmpty {
			body["group_id"] = groupId
		}
		request.httpBody = try JSONSerialization.data(withJSONObject: body)

		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func updateClient(
		session: Mango9Session,
		id: Int,
		values: [String: String]
	) async throws -> Mango9LeadDetailPayload {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients", String(id)]
		)
		request.httpMethod = "PATCH"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(withJSONObject: ["values": values])

		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func deleteClient(
		session: Mango9Session,
		id: Int
	) async throws {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "clients", String(id)]
		)
		request.httpMethod = "DELETE"
		let envelope: Envelope<Mango9EmptyPayload> = try await send(request)
		guard envelope.success else {
			throw Mango9CRMAPIError.server
		}
	}

	static func leadSchema(session: Mango9Session) async throws -> Mango9LeadSchema {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "schema", "leads"]
		)
		let envelope: Envelope<Mango9LeadSchema> = try await send(request)
		guard envelope.success, let schema = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return schema
	}

	static func leads(
		session: Mango9Session,
		search: String,
		status: String,
		groupId: String = "",
		dateFilter: Mango9CRMDateFilter = .all,
		page: Int = 1
	) async throws -> Mango9LeadListPayload {
		var query = [
			URLQueryItem(name: "page", value: String(page)),
			URLQueryItem(name: "limit", value: "50")
		]
		if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			query.append(URLQueryItem(name: "search", value: search))
		}
		if !status.isEmpty {
			query.append(URLQueryItem(name: "status", value: status))
		}
		if !groupId.isEmpty {
			query.append(URLQueryItem(name: "group_id", value: groupId))
		}
		if let dateFilter = dateFilter.apiValue {
			query.append(URLQueryItem(name: "date_filter", value: dateFilter))
		}
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads"],
			query: query
		)
		let envelope: Envelope<Mango9LeadListPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func leadGroups(session: Mango9Session) async throws -> [Mango9LeadGroup] {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads", "groups"]
		)
		let envelope: Envelope<Mango9LeadGroupsPayload> = try await send(request)
		guard envelope.success else {
			throw Mango9CRMAPIError.server
		}
		return envelope.data?.groups ?? []
	}

	static func lead(
		session: Mango9Session,
		id: Int
	) async throws -> Mango9LeadDetailPayload {
		let request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads", String(id)]
		)
		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func createLead(
		session: Mango9Session,
		firstName: String,
		lastName: String,
		phone: String,
		email: String,
		groupId: String
	) async throws -> Mango9LeadDetailPayload {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads"]
		)
		request.httpMethod = "POST"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		let fullName = [firstName, lastName]
			.filter { !$0.isEmpty }
			.joined(separator: " ")
		var body: [String: String] = [
			"first_name": firstName,
			"last_name": lastName,
			"contact_name": fullName,
			"phone": phone,
			"email": email
		]
		if !groupId.isEmpty {
			body["group_id"] = groupId
		}
		request.httpBody = try JSONSerialization.data(withJSONObject: body)

		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func updateLead(
		session: Mango9Session,
		id: Int,
		values: [String: String]
	) async throws -> Mango9LeadDetailPayload {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads", String(id)]
		)
		request.httpMethod = "PATCH"
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(withJSONObject: ["values": values])

		let envelope: Envelope<Mango9LeadDetailPayload> = try await send(request)
		guard envelope.success, let payload = envelope.data else {
			throw Mango9CRMAPIError.server
		}
		return payload
	}

	static func deleteLead(
		session: Mango9Session,
		id: Int
	) async throws {
		var request = try authorizedRequest(
			session: session,
			path: ["mobile", "leads", String(id)]
		)
		request.httpMethod = "DELETE"
		let envelope: Envelope<Mango9EmptyPayload> = try await send(request)
		guard envelope.success else {
			throw Mango9CRMAPIError.server
		}
	}

	private static func authorizedRequest(
		session: Mango9Session,
		path: [String],
		query: [URLQueryItem] = []
	) throws -> URLRequest {
		guard let baseURL = URL(string: session.crmApiBaseUrl) else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		let endpoint = path.reduce(baseURL) { partialURL, component in
			partialURL.appendingPathComponent(component)
		}
		guard var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		if !query.isEmpty {
			components.queryItems = query
		}
		guard let url = components.url else {
			throw Mango9CRMAPIError.invalidConfiguration
		}
		var request = URLRequest(url: url)
		request.httpMethod = "GET"
		request.setValue("application/json", forHTTPHeaderField: "Accept")
		request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
		return request
	}
}

private struct Mango9CommunicationButtons: View {
	let target: Mango9CommunicationTarget
	let onMango9SMS: (Mango9CommunicationTarget) -> Void

	var body: some View {
		Group {
			if !target.phone.isEmpty {
				Button {
					Mango9CommunicationRouter.callWithMango9(target)
				} label: {
					Label("Call with Mango9", systemImage: "phone.fill")
				}
				Button {
					onMango9SMS(target)
				} label: {
					Label("SMS with Mango9", systemImage: "message.fill")
				}
				Button {
					Mango9CommunicationRouter.callNative(target.phone)
				} label: {
					Label("Call Native", systemImage: "iphone")
				}
				Button {
					Mango9CommunicationRouter.smsNative(target.phone)
				} label: {
					Label("SMS Native", systemImage: "message")
				}
			}
			if !target.email.isEmpty {
				Button {
					Mango9CommunicationRouter.emailNative(target.email)
				} label: {
					Label("Email Native", systemImage: "envelope")
				}
			}
			Button {
				let value = target.phone.isEmpty ? target.email : target.phone
				Mango9CommunicationRouter.copy(
					value,
					confirmation: target.phone.isEmpty ? "Email copied" : "Phone number copied"
				)
			} label: {
				Label("Copy", systemImage: "doc.on.doc")
			}
		}
	}
}

@MainActor
final class Mango9CallSettingsViewModel: ObservableObject {
	@Published private(set) var settings: Mango9CallSettings?
	@Published var forwardingEnabled = false
	@Published var forwardingDestination = ""
	@Published private(set) var isLoading = false
	@Published private(set) var isSavingForwarding = false
	@Published private(set) var errorMessage: String?
	@Published private(set) var statusMessage: String?

	var formattedLine: String {
		guard let settings else { return "" }
		return "\(Self.formatPhoneNumber(settings.line.activeNumber)) · Ext \(settings.line.extension)"
	}

	func load(force: Bool = false) async {
		if isLoading || (!force && settings != nil) { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Sign in to Mango9 to manage call routing."
			return
		}

		isLoading = true
		errorMessage = nil
		statusMessage = nil
		defer { isLoading = false }

		do {
			do {
				try await load(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await load(session: session)
			}
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			errorMessage = error.localizedDescription
		}
	}

	func saveForwarding() async {
		guard !isSavingForwarding else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Sign in to Mango9 to manage call routing."
			return
		}

		isSavingForwarding = true
		errorMessage = nil
		statusMessage = nil
		defer { isSavingForwarding = false }

		do {
			let updated: Mango9CallSettings
			do {
				updated = try await Mango9CRMAPI.updateCallForwarding(
					session: session,
					enabled: forwardingEnabled,
					destination: forwardingDestination
				)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				updated = try await Mango9CRMAPI.updateCallForwarding(
					session: session,
					enabled: forwardingEnabled,
					destination: forwardingDestination
				)
			}
			guard Mango9SessionStore.isActive(session) else {
				return
			}
			apply(updated, sipIdentity: session.sipIdentity)
			statusMessage = forwardingEnabled
				? "Call forwarding was enabled successfully."
				: "Call forwarding was turned off successfully."
		} catch {
			errorMessage = error.localizedDescription
			if let current = settings {
				forwardingEnabled = current.forwarding.enabled
				forwardingDestination = current.forwarding.destination
			}
		}
	}

	private func load(session: Mango9Session) async throws {
		let loaded = try await Mango9CRMAPI.callSettings(session: session)
		guard Mango9SessionStore.isActive(session) else {
			return
		}
		apply(loaded, sipIdentity: session.sipIdentity)
	}

	private func apply(
		_ loadedSettings: Mango9CallSettings,
		sipIdentity: String?
	) {
		settings = loadedSettings
		forwardingEnabled = loadedSettings.forwarding.enabled
		forwardingDestination = loadedSettings.forwarding.destination
		Mango9LineIdentityStore.save(
			Mango9LineIdentity(
				extensionNumber: loadedSettings.line.extension,
				activeNumber: loadedSettings.line.activeNumber
			),
			sipIdentity: sipIdentity
		)
	}

	private static func normalizedDigits(_ value: String) -> String {
		let digits = value.filter(\.isNumber)
		return digits.count == 11 && digits.first == "1"
			? String(digits.dropFirst())
			: digits
	}

	private static func formatPhoneNumber(_ raw: String) -> String {
		let digits = normalizedDigits(raw)
		guard digits.count == 10 else { return raw }
		let areaEnd = digits.index(digits.startIndex, offsetBy: 3)
		let prefixEnd = digits.index(areaEnd, offsetBy: 3)
		return "\(digits[..<areaEnd])-\(digits[areaEnd..<prefixEnd])-\(digits[prefixEnd...])"
	}
}

typealias Mango9LeadListLoader = (
	Mango9Session,
	String,
	String,
	String,
	Mango9CRMDateFilter,
	Int
) async throws -> Mango9LeadListPayload

@MainActor
final class Mango9LeadsViewModel: ObservableObject {
	@Published private(set) var schema: Mango9LeadSchema?
	@Published private(set) var leads: [Mango9Lead] = []
	@Published private(set) var groups: [Mango9LeadGroup] = []
	@Published private(set) var total = 0
	@Published private(set) var isLoading = false
	@Published private(set) var errorMessage: String?
	@Published var searchText = ""
	@Published var selectedStatus = ""
	@Published var selectedGroupId = ""
	@Published var selectedDateFilter: Mango9CRMDateFilter = .all
	private let listLoader: Mango9LeadListLoader
	private var reloadRequested = false

	init(
		listLoader: @escaping Mango9LeadListLoader = {
			session,
			search,
			status,
			groupId,
			dateFilter,
			page in
			try await Mango9CRMAPI.leads(
				session: session,
				search: search,
				status: status,
				groupId: groupId,
				dateFilter: dateFilter,
				page: page
			)
		}
	) {
		self.listLoader = listLoader
	}

	var filteredLeads: [Mango9Lead] {
		// The server owns the date-filter definition and result count. Applying
		// a second device-side date parser can incorrectly hide valid records
		// from CRM installations that use another timestamp representation.
		leads
	}

	var selectedGroupName: String {
		groups.first(where: { $0.id == selectedGroupId })?.name ?? "All"
	}

	func load() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to load leads."
			return
		}

		isLoading = true
		errorMessage = nil
		var configurationLoaded = false

		do {
			do {
				try await loadConfiguration(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await loadConfiguration(session: session)
			}
			configurationLoaded = true
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch is CancellationError {
			// A newer refresh superseded this request. Keep the last successful list.
		} catch let error as URLError where error.code == .cancelled {
			// URLSession reports normal Swift task cancellation as NSURLErrorCancelled.
		} catch {
			errorMessage = "Leads could not be loaded. Check the connection and try again."
		}

		isLoading = false
		if configurationLoaded {
			await reloadList()
		} else {
			reloadRequested = false
		}
	}

	func setStatus(_ status: String) async {
		selectedStatus = selectedStatus == status ? "" : status
		await reloadList()
	}

	func setGroup(_ groupId: String) async {
		selectedGroupId = groupId
		await reloadList()
	}

	func reloadList() async {
		reloadRequested = true
		guard !isLoading else { return }

		while reloadRequested {
			reloadRequested = false
			guard var session = Mango9SessionStore.load() else {
				errorMessage = "Connect your Mango9 account to load leads."
				return
			}

			isLoading = true
			errorMessage = nil
			do {
				do {
					try await loadList(session: session)
				} catch Mango9CRMAPIError.unauthorized {
					session = try await Mango9CRMAPI.refresh(session: session)
					try Mango9SessionStore.save(session)
					try await loadList(session: session)
				}
			} catch Mango9CRMAPIError.unauthorized {
				errorMessage = "Your CRM session expired. Sign in again."
			} catch is CancellationError {
				// A newer refresh superseded this request. Keep the last successful list.
			} catch let error as URLError where error.code == .cancelled {
				// URLSession reports normal Swift task cancellation as NSURLErrorCancelled.
			} catch {
				errorMessage = "Leads could not be loaded. Check the connection and try again."
			}
			isLoading = false
		}
	}

	private func loadConfiguration(session: Mango9Session) async throws {
		schema = try await Mango9CRMAPI.leadSchema(session: session)
		do {
			groups = try await Mango9CRMAPI.leadGroups(session: session)
		} catch {
			// Group filtering is optional. Keep the core Leads module usable
			// while an older CRM finishes adopting the groups endpoint.
			Log.warn("[Mango9 CRM] Lead groups unavailable: \(String(reflecting: error))")
			groups = []
		}
		if !selectedGroupId.isEmpty && !groups.contains(where: { $0.id == selectedGroupId }) {
			selectedGroupId = ""
		}
	}

	private func loadList(session: Mango9Session) async throws {
		let payload = try await listLoader(
			session,
			searchText,
			selectedStatus,
			selectedGroupId,
			selectedDateFilter,
			1
		)
		guard !reloadRequested else { return }
		leads = payload.leads
		total = payload.pagination.total
	}
}

struct Mango9LeadsFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel = Mango9LeadsViewModel()
	@State private var isShowingCreateLead = false
	@State private var createdLeadPayload: Mango9LeadDetailPayload?
	@State private var isShowingCreatedLead = false

	var body: some View {
		ZStack {
			Color.gray100.ignoresSafeArea()

			VStack(spacing: 0) {
				header

				ScrollView {
					LazyVStack(spacing: 12) {
						searchCard

						if let schema = viewModel.schema {
							statusFilters(schema.statuses)
						}

						if let errorMessage = viewModel.errorMessage {
							errorCard(errorMessage)
						}

						if viewModel.isLoading && viewModel.filteredLeads.isEmpty {
							ProgressView()
								.tint(Color.orangeMain500)
								.padding(.top, 60)
						} else if viewModel.filteredLeads.isEmpty && viewModel.errorMessage == nil {
							emptyState
						} else {
							ForEach(viewModel.filteredLeads) { lead in
								NavigationLink(
									destination: Mango9LeadDetailFragment(
										leadId: lead.id,
										initialLead: lead,
										initialSchema: viewModel.schema
									)
								) {
									leadRow(lead)
								}
								.buttonStyle(.plain)
								.contextMenu {
									Mango9CommunicationButtons(
										target: communicationTarget(for: lead)
									) {
										Mango9SMSRouting.open(
											Mango9SMSTarget(phone: $0.phone, name: $0.displayName)
										)
									}
								}
							}
						}
					}
					.padding(16)
					.padding(.bottom, 28)
				}
				.refreshable {
					await viewModel.reloadList()
				}
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.background {
			if let createdLeadPayload {
				NavigationLink(
					destination: Mango9LeadDetailFragment(
						leadId: createdLeadPayload.lead.id,
						initialLead: createdLeadPayload.lead,
						initialSchema: viewModel.schema,
						initialValues: createdLeadPayload.values,
						startsInEditMode: true
					),
					isActive: $isShowingCreatedLead
				) {
					EmptyView()
				}
				.hidden()
			}
		}
		.task {
			await viewModel.load()
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9LeadDidChange)) { _ in
			Task { await viewModel.reloadList() }
		}
		.sheet(isPresented: $isShowingCreateLead) {
			Mango9CreateLeadFragment(
				groupId: viewModel.selectedGroupId,
				groupName: viewModel.selectedGroupName
			) { payload in
				createdLeadPayload = payload
				isShowingCreateLead = false
				NotificationCenter.default.post(name: .mango9LeadDidChange, object: nil)
				DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
					isShowingCreatedLead = true
				}
			}
		}
	}

	private var header: some View {
		HStack(spacing: 8) {
			Button {
				presentationMode.wrappedValue.dismiss()
			} label: {
				Image("caret-left")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 25, height: 25)
					.padding(10)
			}

			VStack(alignment: .leading, spacing: 1) {
				Text("Leads")
					.default_text_style_orange_800(styleSize: 18)
				Text("\(viewModel.total) in your CRM scope")
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
			}

			Spacer()

			Button {
				isShowingCreateLead = true
			} label: {
				Image("plus")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 21, height: 21)
					.padding(9)
			}
			.accessibilityLabel("Add lead")

			groupFilterMenu

			dateFilterMenu
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle().fill(Color.gray200).frame(height: 1)
		}
	}

	private var groupFilterMenu: some View {
		Menu {
			Button {
				Task { await viewModel.setGroup("") }
			} label: {
				if viewModel.selectedGroupId.isEmpty {
					Label("All", systemImage: "checkmark")
				} else {
					Text("All")
				}
			}

			ForEach(viewModel.groups) { group in
				Button {
					Task { await viewModel.setGroup(group.id) }
				} label: {
					if viewModel.selectedGroupId == group.id {
						Label(group.name, systemImage: "checkmark")
					} else {
						Text(group.name)
					}
				}
			}
		} label: {
			HStack(spacing: 4) {
				Image("users-three")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 18, height: 18)
				Text(viewModel.selectedGroupName)
					.font(.system(size: 11, weight: .semibold))
					.foregroundStyle(Color.orangeMain500)
					.lineLimit(1)
					.frame(maxWidth: 62)
				if !viewModel.groups.isEmpty {
					Image("caret-down")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.orangeMain500)
						.frame(width: 12, height: 12)
				}
			}
			.padding(.horizontal, 8)
			.frame(height: 36)
			.background(Color.orangeMain100)
			.cornerRadius(10)
		}
		.disabled(viewModel.groups.isEmpty)
		.accessibilityLabel("Filter leads by CRM group")
	}

	private var dateFilterMenu: some View {
		Menu {
			ForEach(Mango9CRMDateFilter.allCases) { filter in
				Button {
					Task {
						viewModel.selectedDateFilter = filter
						await viewModel.reloadList()
					}
				} label: {
					if viewModel.selectedDateFilter == filter {
						Label(filter.title, systemImage: "checkmark")
					} else {
						Text(filter.title)
					}
				}
			}
		} label: {
			HStack(spacing: 4) {
				Image("calendar")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 18, height: 18)
				Text(viewModel.selectedDateFilter.compactTitle)
					.font(.system(size: 11, weight: .semibold))
					.foregroundStyle(Color.orangeMain500)
				Image("caret-down")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 12, height: 12)
			}
			.padding(.horizontal, 8)
			.frame(height: 36)
			.background(Color.orangeMain100)
			.cornerRadius(10)
		}
		.accessibilityLabel("Filter leads by date")
	}

	private var searchCard: some View {
		HStack(spacing: 10) {
			Image("magnifying-glass")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.grayMain2c500)
				.frame(width: 21, height: 21)

			TextField("Search name, phone, or email", text: $viewModel.searchText)
				.default_text_style(styleSize: 14)
				.submitLabel(.search)
				.onSubmit {
					Task { await viewModel.reloadList() }
				}

			if !viewModel.searchText.isEmpty {
				Button {
					viewModel.searchText = ""
					Task { await viewModel.reloadList() }
				} label: {
					Image("x-circle")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.grayMain2c500)
						.frame(width: 20, height: 20)
				}
			}
		}
		.padding(.horizontal, 14)
		.frame(height: 48)
		.background(Color.white)
		.cornerRadius(14)
		.overlay {
			RoundedRectangle(cornerRadius: 14)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func statusFilters(_ statuses: [String]) -> some View {
		ScrollView(.horizontal, showsIndicators: false) {
			HStack(spacing: 8) {
				statusButton(label: "All", value: "")
				ForEach(statuses, id: \.self) { status in
					statusButton(label: status, value: status)
				}
			}
		}
	}

	private func statusButton(label: String, value: String) -> some View {
		let selected = viewModel.selectedStatus == value
		return Button {
			Task {
				if value.isEmpty {
					viewModel.selectedStatus = ""
					await viewModel.reloadList()
				} else {
					await viewModel.setStatus(value)
				}
			}
		} label: {
			Text(label)
				.font(.system(size: 11, weight: .semibold))
				.foregroundStyle(selected ? Color.white : Color.grayMain2c500)
				.padding(.horizontal, 12)
				.padding(.vertical, 8)
				.background(selected ? Color.orangeMain500 : Color.white)
				.cornerRadius(20)
				.overlay {
					Capsule()
						.stroke(selected ? Color.orangeMain500 : Color.gray200, lineWidth: 1)
				}
		}
	}

	private func leadRow(_ lead: Mango9Lead) -> some View {
		HStack(spacing: 13) {
			ZStack {
				Circle().fill(Color.orangeMain100)
				Text(initials(for: lead.name))
					.font(.system(size: 14, weight: .bold))
					.foregroundStyle(Color.orangeMain500)
			}
			.frame(width: 46, height: 46)

			VStack(alignment: .leading, spacing: 4) {
				Text(lead.name.isEmpty ? "Unnamed lead" : lead.name)
					.default_text_style_700(styleSize: 14)
					.lineLimit(1)

				Text(lead.phone.isEmpty ? (lead.email.isEmpty ? "No contact information" : lead.email) : lead.phone)
					.default_text_style(styleSize: 12)
					.foregroundStyle(Color.grayMain2c500)
					.lineLimit(1)

				if !lead.source.isEmpty {
					Text(lead.source)
						.default_text_style(styleSize: 10)
						.foregroundStyle(Color.grayMain2c500)
						.lineLimit(1)
				}
			}

			Spacer()

			VStack(alignment: .trailing, spacing: 7) {
				if !lead.status.isEmpty {
					Text(lead.status)
						.font(.system(size: 9, weight: .bold))
						.foregroundStyle(Color.orangeMain500)
						.padding(.horizontal, 8)
						.padding(.vertical, 5)
						.background(Color.orangeMain100)
						.cornerRadius(20)
						.lineLimit(1)
				}

				Image("caret-right")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.grayMain2c500)
					.frame(width: 18, height: 18)
			}
		}
		.padding(14)
		.background(Color.white)
		.cornerRadius(15)
		.overlay {
			RoundedRectangle(cornerRadius: 15)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func initials(for name: String) -> String {
		let parts = name.split(separator: " ").prefix(2)
		let value = parts.compactMap(\.first).map(String.init).joined()
		return value.isEmpty ? "L" : value.uppercased()
	}

	private func communicationTarget(for lead: Mango9Lead) -> Mango9CommunicationTarget {
		Mango9CommunicationTarget(
			name: lead.name,
			phone: lead.phone,
			email: lead.email
		)
	}

	private var emptyState: some View {
		VStack(spacing: 12) {
			Image("users-three-square")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.orangeMain500)
				.frame(width: 44, height: 44)
			Text("No leads found")
				.default_text_style_800(styleSize: 16)
			Text("Try another search, status, or date filter.")
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c500)
		}
		.frame(maxWidth: .infinity)
		.padding(.top, 56)
	}

	private func errorCard(_ message: String) -> some View {
		HStack(alignment: .top, spacing: 10) {
			Image("warning-circle")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.redDanger500)
				.frame(width: 21, height: 21)
			Text(message)
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c500)
			Spacer()
		}
		.padding(13)
		.background(Color.redDanger200.opacity(0.45))
		.cornerRadius(12)
	}
}

@MainActor
final class Mango9CreateLeadViewModel: ObservableObject {
	@Published var firstName = ""
	@Published var lastName = ""
	@Published var phone = ""
	@Published var email = ""
	@Published private(set) var isCreating = false
	@Published private(set) var errorMessage: String?

	let groupId: String
	let recordKind: Mango9CRMRecordKind

	init(groupId: String, recordKind: Mango9CRMRecordKind = .lead) {
		self.groupId = groupId
		self.recordKind = recordKind
	}

	func create() async -> Mango9LeadDetailPayload? {
		guard !isCreating else { return nil }
		let trimmedFirstName = firstName.trimmingCharacters(in: .whitespacesAndNewlines)
		let trimmedLastName = lastName.trimmingCharacters(in: .whitespacesAndNewlines)
		let trimmedPhone = phone.trimmingCharacters(in: .whitespacesAndNewlines)
		let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)

		guard !trimmedFirstName.isEmpty else {
			errorMessage = "First name is required."
			return nil
		}
		guard !trimmedPhone.isEmpty || !trimmedEmail.isEmpty else {
			errorMessage = "Add a phone number or email address."
			return nil
		}
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to add a \(recordKind.singular.lowercased())."
			return nil
		}

		isCreating = true
		errorMessage = nil
		defer { isCreating = false }

		do {
			do {
				return try await create(
					session: session,
					firstName: trimmedFirstName,
					lastName: trimmedLastName,
					phone: trimmedPhone,
					email: trimmedEmail
				)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				return try await create(
					session: session,
					firstName: trimmedFirstName,
					lastName: trimmedLastName,
					phone: trimmedPhone,
					email: trimmedEmail
				)
			}
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			Log.error("[Mango9 CRM] Unable to create \(recordKind.singular.lowercased()): \(String(reflecting: error))")
			errorMessage = "The \(recordKind.singular.lowercased()) could not be created. Try again."
		}
		return nil
	}

	private func create(
		session: Mango9Session,
		firstName: String,
		lastName: String,
		phone: String,
		email: String
	) async throws -> Mango9LeadDetailPayload {
		if recordKind == .client {
			return try await Mango9CRMAPI.createClient(
				session: session,
				firstName: firstName,
				lastName: lastName,
				phone: phone,
				email: email,
				groupId: groupId
			)
		}
		return try await Mango9CRMAPI.createLead(
			session: session,
			firstName: firstName,
			lastName: lastName,
			phone: phone,
			email: email,
			groupId: groupId
		)
	}
}

struct Mango9CreateLeadFragment: View {
	@Environment(\.dismiss) private var dismiss
	@StateObject private var viewModel: Mango9CreateLeadViewModel

	let groupName: String
	let onCreated: (Mango9LeadDetailPayload) -> Void

	init(
		groupId: String,
		groupName: String,
		recordKind: Mango9CRMRecordKind = .lead,
		onCreated: @escaping (Mango9LeadDetailPayload) -> Void
	) {
		self.groupName = groupName
		self.onCreated = onCreated
		_viewModel = StateObject(
			wrappedValue: Mango9CreateLeadViewModel(
				groupId: groupId,
				recordKind: recordKind
			)
		)
	}

	var body: some View {
		NavigationView {
			ZStack {
				Color.gray100.ignoresSafeArea()

				ScrollView {
					VStack(spacing: 16) {
						if groupName != "All" {
							HStack(spacing: 8) {
								Image("users-three")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.orangeMain500)
									.frame(width: 18, height: 18)
								Text("Adding to \(groupName)")
									.default_text_style_700(styleSize: 12)
								Spacer()
							}
							.padding(13)
							.background(Color.orangeMain100)
							.cornerRadius(12)
						}

						VStack(spacing: 18) {
							leadField(
								title: "First Name",
								placeholder: "First name",
								text: $viewModel.firstName,
								contentType: .givenName,
								keyboard: .default
							)
							leadField(
								title: "Last Name",
								placeholder: "Last name",
								text: $viewModel.lastName,
								contentType: .familyName,
								keyboard: .default
							)
							leadField(
								title: "Phone",
								placeholder: "Phone number",
								text: $viewModel.phone,
								contentType: .telephoneNumber,
								keyboard: .phonePad
							)
							leadField(
								title: "Email",
								placeholder: "Email address",
								text: $viewModel.email,
								contentType: .emailAddress,
								keyboard: .emailAddress
							)
						}
						.padding(16)
						.background(Color.white)
						.cornerRadius(16)
						.overlay {
							RoundedRectangle(cornerRadius: 16)
								.stroke(Color.gray200, lineWidth: 1)
						}

						if let errorMessage = viewModel.errorMessage {
							HStack(alignment: .top, spacing: 9) {
								Image("warning-circle")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.redDanger500)
									.frame(width: 20, height: 20)
								Text(errorMessage)
									.default_text_style(styleSize: 12)
									.foregroundStyle(Color.grayMain2c500)
								Spacer()
							}
							.padding(13)
							.background(Color.redDanger200.opacity(0.45))
							.cornerRadius(12)
						}
					}
					.padding(16)
				}
			}
			.navigationTitle("Add \(viewModel.recordKind.singular)")
			.navigationBarTitleDisplayMode(.inline)
			.toolbar {
				ToolbarItem(placement: .cancellationAction) {
					Button("Cancel") { dismiss() }
						.disabled(viewModel.isCreating)
				}
				ToolbarItem(placement: .confirmationAction) {
					Button {
						Task {
							if let payload = await viewModel.create() {
								onCreated(payload)
							}
						}
					} label: {
						if viewModel.isCreating {
							ProgressView().tint(Color.blueInfo500)
						} else {
							Image("check")
								.renderingMode(.template)
								.foregroundStyle(Color.blueInfo500)
						}
					}
					.disabled(viewModel.isCreating)
					.accessibilityLabel("Create \(viewModel.recordKind.singular.lowercased())")
				}
			}
		}
		.navigationViewStyle(.stack)
	}

	private func leadField(
		title: String,
		placeholder: String,
		text: Binding<String>,
		contentType: UITextContentType?,
		keyboard: UIKeyboardType
	) -> some View {
		VStack(alignment: .leading, spacing: 7) {
			Text(title)
				.default_text_style_700(styleSize: 12)
			TextField(placeholder, text: text)
				.textContentType(contentType)
				.keyboardType(keyboard)
				.autocapitalization(keyboard == .emailAddress ? .none : .sentences)
				.disableAutocorrection(keyboard == .emailAddress)
				.default_text_style(styleSize: 14)
				.padding(.horizontal, 13)
				.frame(height: 46)
				.background(Color.gray100)
				.cornerRadius(11)
		}
	}
}

@MainActor
final class Mango9ClientsViewModel: ObservableObject {
	@Published private(set) var schema: Mango9LeadSchema?
	@Published private(set) var clients: [Mango9Client] = []
	@Published private(set) var groups: [Mango9LeadGroup] = []
	@Published private(set) var total = 0
	@Published private(set) var isLoading = false
	@Published private(set) var errorMessage: String?
	@Published var searchText = ""
	@Published var selectedStatus = ""
	@Published var selectedGroupId = ""
	@Published var selectedDateFilter: Mango9CRMDateFilter = .all

	var filteredClients: [Mango9Client] {
		clients.filter { selectedDateFilter.includes($0.createdAt) }
	}

	var selectedGroupName: String {
		groups.first(where: { $0.id == selectedGroupId })?.name ?? "All"
	}

	func load() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to load clients."
			return
		}

		isLoading = true
		errorMessage = nil
		defer { isLoading = false }

		do {
			do {
				try await load(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await load(session: session)
			}
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch is CancellationError {
			// Keep the last successful list when navigation supersedes this task.
		} catch let error as URLError where error.code == .cancelled {
			// URLSession reports normal task cancellation as NSURLErrorCancelled.
		} catch {
			Log.error("[Mango9 CRM] Unable to load clients: \(String(reflecting: error))")
			errorMessage = "Clients could not be loaded. Check the connection and try again."
		}
	}

	func setStatus(_ status: String) async {
		selectedStatus = selectedStatus == status ? "" : status
		await reloadList()
	}

	func setGroup(_ groupId: String) async {
		selectedGroupId = groupId
		await reloadList()
	}

	func reloadList() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to load clients."
			return
		}

		isLoading = true
		errorMessage = nil
		defer { isLoading = false }

		do {
			do {
				try await loadList(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await loadList(session: session)
			}
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch is CancellationError {
			// A newer refresh superseded this request. Keep the last successful list.
		} catch let error as URLError where error.code == .cancelled {
			// URLSession reports normal Swift task cancellation as NSURLErrorCancelled.
		} catch {
			Log.error("[Mango9 CRM] Unable to reload clients: \(String(reflecting: error))")
			errorMessage = "Clients could not be loaded. Check the connection and try again."
		}
	}

	private func load(session: Mango9Session) async throws {
		schema = try await Mango9CRMAPI.clientSchema(session: session)
		do {
			groups = try await Mango9CRMAPI.clientGroups(session: session)
		} catch {
			Log.warn("[Mango9 CRM] Client groups unavailable: \(String(reflecting: error))")
			groups = []
		}
		if !selectedGroupId.isEmpty && !groups.contains(where: { $0.id == selectedGroupId }) {
			selectedGroupId = ""
		}
		try await loadList(session: session)
	}

	private func loadList(session: Mango9Session) async throws {
		let payload = try await Mango9CRMAPI.clients(
			session: session,
			search: searchText,
			status: selectedStatus,
			groupId: selectedGroupId,
			dateFilter: selectedDateFilter
		)
		clients = payload.clients
		total = payload.pagination.total
	}
}

struct Mango9ClientsFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel = Mango9ClientsViewModel()
	@State private var isShowingCreateClient = false
	@State private var createdClientPayload: Mango9LeadDetailPayload?
	@State private var isShowingCreatedClient = false

	var body: some View {
		ZStack {
			Color.gray100.ignoresSafeArea()

			VStack(spacing: 0) {
				header

				ScrollView {
					LazyVStack(spacing: 12) {
						searchCard

						if let schema = viewModel.schema {
							statusFilters(schema.statuses)
						}

						if let errorMessage = viewModel.errorMessage {
							errorCard(errorMessage)
						}

						if viewModel.isLoading && viewModel.filteredClients.isEmpty {
							ProgressView()
								.tint(Color.orangeMain500)
								.padding(.top, 60)
						} else if viewModel.filteredClients.isEmpty && viewModel.errorMessage == nil {
							emptyState
						} else {
							ForEach(viewModel.filteredClients) { client in
								NavigationLink(
									destination: Mango9LeadDetailFragment(
										leadId: client.id,
										recordKind: .client,
										initialLead: client,
										initialSchema: viewModel.schema
									)
								) {
									clientRow(client)
								}
								.buttonStyle(.plain)
								.contextMenu {
									Mango9CommunicationButtons(
										target: communicationTarget(for: client)
									) {
										Mango9SMSRouting.open(
											Mango9SMSTarget(phone: $0.phone, name: $0.displayName)
										)
									}
								}
							}
						}
					}
					.padding(16)
					.padding(.bottom, 28)
				}
				.refreshable {
					await viewModel.reloadList()
				}
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.background {
			if let createdClientPayload {
				NavigationLink(
					destination: Mango9LeadDetailFragment(
						leadId: createdClientPayload.lead.id,
						recordKind: .client,
						initialLead: createdClientPayload.lead,
						initialSchema: viewModel.schema,
						initialValues: createdClientPayload.values,
						startsInEditMode: true
					),
					isActive: $isShowingCreatedClient
				) {
					EmptyView()
				}
				.hidden()
			}
		}
		.task {
			await viewModel.load()
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9ClientDidChange)) { _ in
			Task { await viewModel.reloadList() }
		}
		.sheet(isPresented: $isShowingCreateClient) {
			Mango9CreateLeadFragment(
				groupId: viewModel.selectedGroupId,
				groupName: viewModel.selectedGroupName,
				recordKind: .client
			) { payload in
				createdClientPayload = payload
				isShowingCreateClient = false
				NotificationCenter.default.post(name: .mango9ClientDidChange, object: nil)
				DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
					isShowingCreatedClient = true
				}
			}
		}
	}

	private var header: some View {
		HStack(spacing: 8) {
			Button {
				presentationMode.wrappedValue.dismiss()
			} label: {
				Image("caret-left")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 25, height: 25)
					.padding(10)
			}

			VStack(alignment: .leading, spacing: 1) {
				Text("Clients")
					.default_text_style_orange_800(styleSize: 18)
				Text("\(viewModel.total) in your CRM scope")
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
			}

			Spacer()

			Button {
				isShowingCreateClient = true
			} label: {
				Image("plus")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 21, height: 21)
					.padding(9)
			}
			.accessibilityLabel("Add client")

			groupFilterMenu

			dateFilterMenu
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle().fill(Color.gray200).frame(height: 1)
		}
	}

	private var groupFilterMenu: some View {
		Menu {
			Button {
				Task { await viewModel.setGroup("") }
			} label: {
				if viewModel.selectedGroupId.isEmpty {
					Label("All", systemImage: "checkmark")
				} else {
					Text("All")
				}
			}

			ForEach(viewModel.groups) { group in
				Button {
					Task { await viewModel.setGroup(group.id) }
				} label: {
					if viewModel.selectedGroupId == group.id {
						Label(group.name, systemImage: "checkmark")
					} else {
						Text(group.name)
					}
				}
			}
		} label: {
			HStack(spacing: 4) {
				Image("users-three")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 18, height: 18)
				Text(viewModel.selectedGroupName)
					.font(.system(size: 11, weight: .semibold))
					.foregroundStyle(Color.orangeMain500)
					.lineLimit(1)
					.frame(maxWidth: 62)
				if !viewModel.groups.isEmpty {
					Image("caret-down")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.orangeMain500)
						.frame(width: 12, height: 12)
				}
			}
			.padding(.horizontal, 8)
			.frame(height: 36)
			.background(Color.orangeMain100)
			.cornerRadius(10)
		}
		.disabled(viewModel.groups.isEmpty)
		.accessibilityLabel("Filter clients by CRM group")
	}

	private var dateFilterMenu: some View {
		Menu {
			ForEach(Mango9CRMDateFilter.allCases) { filter in
				Button {
					Task {
						viewModel.selectedDateFilter = filter
						await viewModel.reloadList()
					}
				} label: {
					if viewModel.selectedDateFilter == filter {
						Label(filter.title, systemImage: "checkmark")
					} else {
						Text(filter.title)
					}
				}
			}
		} label: {
			HStack(spacing: 4) {
				Image("calendar")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 18, height: 18)
				Text(viewModel.selectedDateFilter.compactTitle)
					.font(.system(size: 11, weight: .semibold))
					.foregroundStyle(Color.orangeMain500)
				Image("caret-down")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 12, height: 12)
			}
			.padding(.horizontal, 8)
			.frame(height: 36)
			.background(Color.orangeMain100)
			.cornerRadius(10)
		}
		.accessibilityLabel("Filter clients by date")
	}

	private var searchCard: some View {
		HStack(spacing: 10) {
			Image("magnifying-glass")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.grayMain2c500)
				.frame(width: 21, height: 21)

			TextField("Search client name, phone, or email", text: $viewModel.searchText)
				.default_text_style(styleSize: 14)
				.submitLabel(.search)
				.onSubmit {
					Task { await viewModel.reloadList() }
				}

			if !viewModel.searchText.isEmpty {
				Button {
					viewModel.searchText = ""
					Task { await viewModel.reloadList() }
				} label: {
					Image("x-circle")
						.renderingMode(.template)
						.resizable()
						.foregroundStyle(Color.grayMain2c500)
						.frame(width: 20, height: 20)
				}
			}
		}
		.padding(.horizontal, 14)
		.frame(height: 48)
		.background(Color.white)
		.cornerRadius(14)
		.overlay {
			RoundedRectangle(cornerRadius: 14)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func statusFilters(_ statuses: [String]) -> some View {
		ScrollView(.horizontal, showsIndicators: false) {
			HStack(spacing: 8) {
				statusButton(label: "All", value: "")
				ForEach(statuses, id: \.self) { status in
					statusButton(label: status, value: status)
				}
			}
		}
	}

	private func statusButton(label: String, value: String) -> some View {
		let selected = viewModel.selectedStatus == value
		return Button {
			Task {
				if value.isEmpty {
					viewModel.selectedStatus = ""
					await viewModel.reloadList()
				} else {
					await viewModel.setStatus(value)
				}
			}
		} label: {
			Text(label)
				.font(.system(size: 11, weight: .semibold))
				.foregroundStyle(selected ? Color.white : Color.grayMain2c500)
				.padding(.horizontal, 12)
				.padding(.vertical, 8)
				.background(selected ? Color.orangeMain500 : Color.white)
				.cornerRadius(20)
				.overlay {
					Capsule()
						.stroke(selected ? Color.orangeMain500 : Color.gray200, lineWidth: 1)
				}
		}
	}

	private func clientRow(_ client: Mango9Client) -> some View {
		HStack(spacing: 13) {
			ZStack {
				Circle().fill(Color.orangeMain100)
				Text(initials(for: client.name))
					.font(.system(size: 14, weight: .bold))
					.foregroundStyle(Color.orangeMain500)
			}
			.frame(width: 46, height: 46)

			VStack(alignment: .leading, spacing: 4) {
				Text(client.name.isEmpty ? "Unnamed client" : client.name)
					.default_text_style_700(styleSize: 14)
					.lineLimit(1)

				Text(
					client.phone.isEmpty
					? (client.email.isEmpty ? "No contact information" : client.email)
					: client.phone
				)
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c500)
				.lineLimit(1)

				if !client.ownerName.isEmpty {
					Text("Owner: \(client.ownerName)")
						.default_text_style(styleSize: 10)
						.foregroundStyle(Color.grayMain2c500)
						.lineLimit(1)
				}
			}

			Spacer()

			VStack(alignment: .trailing, spacing: 7) {
				Text(client.status.isEmpty ? "CLIENT" : client.status)
					.font(.system(size: 9, weight: .bold))
					.foregroundStyle(Color.orangeMain500)
					.padding(.horizontal, 8)
					.padding(.vertical, 5)
					.background(Color.orangeMain100)
					.cornerRadius(20)
					.lineLimit(1)

				Image("caret-right")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.grayMain2c500)
					.frame(width: 18, height: 18)
			}
		}
		.padding(14)
		.background(Color.white)
		.cornerRadius(15)
		.overlay {
			RoundedRectangle(cornerRadius: 15)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func communicationTarget(for client: Mango9Client) -> Mango9CommunicationTarget {
		Mango9CommunicationTarget(
			name: client.name,
			phone: client.phone,
			email: client.email
		)
	}

	private func initials(for name: String) -> String {
		let parts = name.split(separator: " ").prefix(2)
		let value = parts.compactMap(\.first).map(String.init).joined()
		return value.isEmpty ? "C" : value.uppercased()
	}

	private var emptyState: some View {
		VStack(spacing: 12) {
			Image("address-book")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.orangeMain500)
				.frame(width: 44, height: 44)
			Text("No clients found")
				.default_text_style_800(styleSize: 16)
			Text("Try another search or date filter.")
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c500)
		}
		.frame(maxWidth: .infinity)
		.padding(.top, 56)
	}

	private func errorCard(_ message: String) -> some View {
		HStack(alignment: .top, spacing: 10) {
			Image("warning-circle")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.redDanger500)
				.frame(width: 21, height: 21)
			Text(message)
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c500)
			Spacer()
		}
		.padding(13)
		.background(Color.redDanger200.opacity(0.45))
		.cornerRadius(12)
	}
}

@MainActor
final class Mango9LeadDetailViewModel: ObservableObject {
	let leadId: Int
	let recordKind: Mango9CRMRecordKind

	@Published private(set) var schema: Mango9LeadSchema?
	@Published private(set) var lead: Mango9Lead?
	@Published var values: [String: String] = [:]
	@Published private(set) var isLoading = false
	@Published private(set) var isSaving = false
	@Published private(set) var isDeleting = false
	@Published var isEditing = false
	@Published private(set) var errorMessage: String?
	@Published private(set) var savedMessage: String?

	private var originalValues: [String: String] = [:]

	init(
		leadId: Int,
		recordKind: Mango9CRMRecordKind = .lead,
		initialLead: Mango9Lead? = nil,
		initialSchema: Mango9LeadSchema? = nil,
		initialValues: [String: String] = [:],
		startsInEditMode: Bool = false
	) {
		self.leadId = leadId
		self.recordKind = recordKind
		lead = initialLead
		schema = initialSchema
		values = initialValues
		originalValues = initialValues
		isEditing = startsInEditMode
	}

	func load() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to open this \(recordKind.singular.lowercased())."
			return
		}

		isLoading = true
		errorMessage = nil
		defer { isLoading = false }
		do {
			do {
				try await load(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await load(session: session)
			}
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch is CancellationError {
			// Normal when a navigation transition supersedes a view-bound task.
		} catch let error as URLError where error.code == .cancelled {
			// Normal when URLSession is superseded during a navigation transition.
		} catch {
			Log.error("[Mango9 CRM] Unable to load \(recordKind.singular.lowercased()) \(leadId): \(String(reflecting: error))")
			errorMessage = "This \(recordKind.singular.lowercased()) could not be loaded."
		}
	}

	func cancelEditing() {
		values = originalValues
		errorMessage = nil
		savedMessage = nil
		isEditing = false
	}

	func valueBinding(for key: String) -> Binding<String> {
		Binding(
			get: { self.values[key] ?? "" },
			set: {
				self.values[key] = $0
				self.savedMessage = nil
			}
		)
	}

	func save() async {
		guard !isSaving, !isDeleting, let schema else { return }
		for field in schema.fields where field.required && field.editable && field.isVisible {
			if (values[field.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				errorMessage = "\(field.label) is required."
				return
			}
		}

		var changes: [String: String] = [:]
		for field in schema.fields where field.editable {
			let value = values[field.key] ?? ""
			if value != (originalValues[field.key] ?? "") {
				changes[field.key] = value
			}
		}
		if changes.isEmpty {
			isEditing = false
			return
		}

		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to save this \(recordKind.singular.lowercased())."
			return
		}
		isSaving = true
		errorMessage = nil
		savedMessage = nil
		defer { isSaving = false }

		do {
			let payload: Mango9LeadDetailPayload
			do {
				payload = try await update(
					session: session,
					values: changes
				)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				payload = try await update(
					session: session,
					values: changes
				)
			}
			lead = payload.lead
			values = payload.values
			originalValues = payload.values
			isEditing = false
			savedMessage = "\(recordKind.singular) saved"
			NotificationCenter.default.post(name: recordKind.changeNotification, object: nil)
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			errorMessage = "The \(recordKind.singular.lowercased()) could not be saved. Try again."
		}
	}

	func delete() async -> Bool {
		guard !isSaving, !isDeleting else { return false }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to delete this \(recordKind.singular.lowercased())."
			return false
		}

		isDeleting = true
		errorMessage = nil
		savedMessage = nil
		defer { isDeleting = false }

		do {
			do {
				try await delete(session: session)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				try await delete(session: session)
			}
			NotificationCenter.default.post(name: recordKind.changeNotification, object: nil)
			return true
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			Log.error("[Mango9 CRM] Unable to delete \(recordKind.singular.lowercased()) \(leadId): \(String(reflecting: error))")
			errorMessage = "The \(recordKind.singular.lowercased()) could not be deleted. Try again."
		}
		return false
	}

	private func load(session: Mango9Session) async throws {
		let payload: Mango9LeadDetailPayload
		if recordKind == .client {
			payload = try await Mango9CRMAPI.client(session: session, id: leadId)
		} else {
			payload = try await Mango9CRMAPI.lead(session: session, id: leadId)
		}
		lead = payload.lead
		values = payload.values
		originalValues = payload.values

		if schema == nil {
			if recordKind == .client {
				schema = try await Mango9CRMAPI.clientSchema(session: session)
			} else {
				schema = try await Mango9CRMAPI.leadSchema(session: session)
			}
		}
	}

	private func update(
		session: Mango9Session,
		values: [String: String]
	) async throws -> Mango9LeadDetailPayload {
		if recordKind == .client {
			return try await Mango9CRMAPI.updateClient(
				session: session,
				id: leadId,
				values: values
			)
		}
		return try await Mango9CRMAPI.updateLead(
			session: session,
			id: leadId,
			values: values
		)
	}

	private func delete(session: Mango9Session) async throws {
		if recordKind == .client {
			try await Mango9CRMAPI.deleteClient(session: session, id: leadId)
		} else {
			try await Mango9CRMAPI.deleteLead(session: session, id: leadId)
		}
	}
}

struct Mango9LeadDetailFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel: Mango9LeadDetailViewModel
	@State private var communicationTarget: Mango9CommunicationTarget?
	@State private var isShowingCommunicationActions = false
	@State private var isShowingDeleteConfirmation = false

	init(
		leadId: Int,
		recordKind: Mango9CRMRecordKind = .lead,
		initialLead: Mango9Lead? = nil,
		initialSchema: Mango9LeadSchema? = nil,
		initialValues: [String: String] = [:],
		startsInEditMode: Bool = false
	) {
		_viewModel = StateObject(
			wrappedValue: Mango9LeadDetailViewModel(
				leadId: leadId,
				recordKind: recordKind,
				initialLead: initialLead,
				initialSchema: initialSchema,
				initialValues: initialValues,
				startsInEditMode: startsInEditMode
			)
		)
	}

	var body: some View {
		ZStack {
			Color.gray100.ignoresSafeArea()

			VStack(spacing: 0) {
				header

				if viewModel.isLoading && viewModel.lead == nil {
					Spacer()
					ProgressView().tint(Color.orangeMain500)
					Spacer()
				} else {
					ScrollView {
						VStack(spacing: 14) {
							if let lead = viewModel.lead {
								leadHeader(lead)
							}

							if let error = viewModel.errorMessage {
								messageCard(error, isError: true)
							}
							if let saved = viewModel.savedMessage {
								messageCard(saved, isError: false)
							}

							if let schema = viewModel.schema {
								ForEach(schema.sections) { section in
									let fields = visibleFields(
										for: section,
										schemaFields: schema.fields
									)
									if !fields.isEmpty {
										fieldSection(section: section, fields: fields)
									}
								}
							}
						}
						.padding(16)
						.padding(.bottom, 30)
					}
					.refreshable {
						if !viewModel.isEditing {
							await viewModel.load()
						}
					}
				}
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.task {
			await viewModel.load()
		}
		.confirmationDialog(
			communicationTarget?.displayName ?? "Contact options",
			isPresented: $isShowingCommunicationActions,
			titleVisibility: .visible
		) {
			if let communicationTarget {
				Mango9CommunicationButtons(target: communicationTarget) {
					Mango9SMSRouting.open(
						Mango9SMSTarget(phone: $0.phone, name: $0.displayName)
					)
				}
			}
		} message: {
			if let communicationTarget {
				Text(
					communicationTarget.phone.isEmpty
					? communicationTarget.email
					: communicationTarget.phone
				)
			}
		}
		.confirmationDialog(
			"Delete this \(viewModel.recordKind.singular.lowercased())?",
			isPresented: $isShowingDeleteConfirmation,
			titleVisibility: .visible
		) {
			Button("Delete \(viewModel.recordKind.singular)", role: .destructive) {
				Task {
					if await viewModel.delete() {
						presentationMode.wrappedValue.dismiss()
					}
				}
			}
			Button("Cancel", role: .cancel) {}
		} message: {
			Text("This removes the \(viewModel.recordKind.singular.lowercased()) from the CRM and cannot be undone.")
		}
	}

	private var header: some View {
		HStack(spacing: 8) {
			Button {
				if viewModel.isEditing {
					viewModel.cancelEditing()
				} else {
					presentationMode.wrappedValue.dismiss()
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
				Text(viewModel.isEditing ? "Edit \(viewModel.recordKind.singular)" : viewModel.recordKind.singular)
					.default_text_style_orange_800(styleSize: 18)
				Text(viewModel.lead?.name.isEmpty == false ? viewModel.lead?.name ?? "" : "CRM record")
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
					.lineLimit(1)
			}

			Spacer()

			if viewModel.lead != nil {
				if viewModel.isEditing {
					HStack(spacing: 2) {
						Button {
							isShowingDeleteConfirmation = true
						} label: {
							if viewModel.isDeleting {
								ProgressView()
									.tint(Color.redDanger500)
									.frame(width: 21, height: 21)
									.padding(10)
							} else {
								Image("trash-simple")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.redDanger500)
									.frame(width: 21, height: 21)
									.padding(10)
							}
						}
						.disabled(viewModel.isSaving || viewModel.isDeleting)
						.accessibilityLabel("Delete \(viewModel.recordKind.singular.lowercased())")

						Button {
							Task { await viewModel.save() }
						} label: {
							if viewModel.isSaving {
								ProgressView()
									.tint(Color.blueInfo500)
									.frame(width: 21, height: 21)
									.padding(10)
							} else {
								Image("check")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.blueInfo500)
									.frame(width: 21, height: 21)
									.padding(10)
							}
						}
						.disabled(viewModel.isSaving || viewModel.isDeleting)
						.accessibilityLabel("Save \(viewModel.recordKind.singular.lowercased())")
					}
				} else {
					Button {
						viewModel.isEditing = true
					} label: {
						Image("pencil-simple")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeMain500)
							.frame(width: 21, height: 21)
							.padding(10)
					}
				}
			}
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle().fill(Color.gray200).frame(height: 1)
		}
	}

	private func leadHeader(_ lead: Mango9Lead) -> some View {
		HStack(spacing: 14) {
			ZStack {
				Circle().fill(Color.orangeMain500)
				Image("user-circle")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.white)
					.frame(width: 30, height: 30)
			}
			.frame(width: 54, height: 54)

			VStack(alignment: .leading, spacing: 4) {
				Text(lead.name.isEmpty ? viewModel.recordKind.emptyName : lead.name)
					.default_text_style_800(styleSize: 16)
					.lineLimit(1)
				if !lead.phone.isEmpty {
					Button {
						presentCommunicationActions(
							phone: lead.phone,
							email: lead.email
						)
					} label: {
						HStack(spacing: 5) {
							Image(systemName: "phone.fill")
								.font(.system(size: 10, weight: .semibold))
							Text(lead.phone)
								.default_text_style_700(styleSize: 12)
						}
						.foregroundStyle(Color.orangeMain500)
					}
					.buttonStyle(.plain)
				}
				if !lead.email.isEmpty {
					Button {
						presentCommunicationActions(
							phone: lead.phone,
							email: lead.email
						)
					} label: {
						HStack(spacing: 5) {
							Image(systemName: "envelope.fill")
								.font(.system(size: 10, weight: .semibold))
							Text(lead.email)
								.default_text_style_700(styleSize: 12)
								.lineLimit(1)
						}
						.foregroundStyle(Color.orangeMain500)
					}
					.buttonStyle(.plain)
				}
			}

			Spacer()

			if !lead.status.isEmpty {
				Text(lead.status)
					.font(.system(size: 9, weight: .bold))
					.foregroundStyle(Color.orangeMain500)
					.padding(.horizontal, 8)
					.padding(.vertical, 5)
					.background(Color.orangeMain100)
					.cornerRadius(20)
			}
		}
		.padding(16)
		.background(Color.white)
		.cornerRadius(16)
		.overlay {
			RoundedRectangle(cornerRadius: 16)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func fieldSection(
		section: Mango9LeadSchema.Section,
		fields: [Mango9LeadSchema.Field]
	) -> some View {
		VStack(alignment: .leading, spacing: 0) {
			Text(section.label)
				.default_text_style_800(styleSize: 15)
				.padding(.horizontal, 14)
				.padding(.vertical, 12)

			Divider()

			ForEach(Array(fields.enumerated()), id: \.element.id) { index, field in
				if viewModel.isEditing && field.editable {
					editor(field)
						.padding(.horizontal, 14)
						.padding(.vertical, 10)
				} else {
					valueRow(field)
						.padding(.horizontal, 14)
						.padding(.vertical, 11)
				}
				if index < fields.count - 1 {
					Divider().padding(.leading, 14)
				}
			}

			if section.id == "location" {
				Divider().padding(.leading, 14)
				ZStack(alignment: .bottomTrailing) {
					Mango9LocationMapView(address: appleMapsAddress)

					Button {
						openAppleMaps()
					} label: {
						Image(systemName: "arrow.up.right")
							.font(.system(size: 16, weight: .bold))
							.foregroundStyle(Color.white)
							.frame(width: 42, height: 42)
							.background(Color.blueInfo500)
							.clipShape(Circle())
							.shadow(color: Color.black.opacity(0.18), radius: 5, x: 0, y: 2)
					}
					.accessibilityLabel(
						appleMapsAddress.isEmpty
							? "Open Apple Maps"
							: "Open \(appleMapsAddress) in Apple Maps"
					)
					.padding(12)
				}
				.frame(height: 360)
				.clipShape(RoundedRectangle(cornerRadius: 12))
				.padding(14)
			}
		}
		.background(Color.white)
		.cornerRadius(15)
		.overlay {
			RoundedRectangle(cornerRadius: 15)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func visibleFields(
		for section: Mango9LeadSchema.Section,
		schemaFields: [Mango9LeadSchema.Field]
	) -> [Mango9LeadSchema.Field] {
		let hiddenMobileFields: Set<String> = [
			"utm_source",
			"utm_medium",
			"utm_campaign"
		]
		var fields = schemaFields.filter {
			$0.section == section.id
				&& $0.isVisible
				&& !hiddenMobileFields.contains($0.key)
		}

		if section.id == "personal",
		   fields.contains(where: { $0.key == "first_name" }),
		   fields.contains(where: { $0.key == "last_name" }) {
			fields.removeAll(where: { $0.key == "contact_name" })
		}
		return fields
	}

	private var appleMapsAddress: String {
		["address", "city", "state", "zip_code", "country"]
			.compactMap { key in
				let value = (viewModel.values[key] ?? "")
					.trimmingCharacters(in: .whitespacesAndNewlines)
				return value.isEmpty ? nil : value
			}
			.joined(separator: ", ")
	}

	private func openAppleMaps() {
		if appleMapsAddress.isEmpty {
			guard let mapsURL = URL(string: "maps://") else { return }
			UIApplication.shared.open(mapsURL)
			return
		}

		var components = URLComponents()
		components.scheme = "https"
		components.host = "maps.apple.com"
		components.queryItems = [
			URLQueryItem(name: "q", value: appleMapsAddress)
		]
		guard let mapsURL = components.url else { return }
		UIApplication.shared.open(mapsURL)
	}

	@ViewBuilder
	private func valueRow(_ field: Mango9LeadSchema.Field) -> some View {
		let rawValue = viewModel.values[field.key] ?? ""
		VStack(alignment: .leading, spacing: 4) {
			Text(field.label)
				.default_text_style_700(styleSize: 11)
				.foregroundStyle(Color.grayMain2c500)
			if !rawValue.isEmpty && (field.type == "phone" || field.type == "email") {
				Button {
					presentCommunicationActions(
						phone: field.type == "phone" ? rawValue : nil,
						email: field.type == "email" ? rawValue : nil
					)
				} label: {
					HStack(spacing: 7) {
						Image(systemName: field.type == "phone" ? "phone.fill" : "envelope.fill")
							.font(.system(size: 12, weight: .semibold))
						Text(displayValue(rawValue, type: field.type))
							.default_text_style_700(styleSize: 14)
							.frame(maxWidth: .infinity, alignment: .leading)
					}
					.foregroundStyle(Color.orangeMain500)
				}
				.buttonStyle(.plain)
			} else {
				Text(displayValue(rawValue, type: field.type))
					.default_text_style(styleSize: 14)
					.foregroundStyle(rawValue.isEmpty ? Color.grayMain2c500 : Color.black)
					.frame(maxWidth: .infinity, alignment: .leading)
			}
		}
	}

	private func presentCommunicationActions(phone: String?, email: String?) {
		guard let lead = viewModel.lead else { return }
		let selectedPhone = phone ?? lead.phone
		let selectedEmail = email ?? lead.email
		guard !selectedPhone.isEmpty || !selectedEmail.isEmpty else { return }
		communicationTarget = Mango9CommunicationTarget(
			name: lead.name,
			phone: selectedPhone,
			email: selectedEmail
		)
		isShowingCommunicationActions = true
	}

	@ViewBuilder
	private func editor(_ field: Mango9LeadSchema.Field) -> some View {
		VStack(alignment: .leading, spacing: 7) {
			HStack(spacing: 3) {
				Text(field.label)
					.default_text_style_700(styleSize: 12)
				if field.required {
					Text("*")
						.foregroundStyle(Color.redDanger500)
				}
			}

			if field.type == "select" {
				Menu {
					Button("No status") {
						viewModel.values[field.key] = ""
					}
					ForEach(field.options ?? [], id: \.self) { option in
						Button(option) {
							viewModel.values[field.key] = option
						}
					}
				} label: {
					HStack {
						Text((viewModel.values[field.key] ?? "").isEmpty ? "Select" : viewModel.values[field.key] ?? "")
							.default_text_style(styleSize: 14)
							.foregroundStyle((viewModel.values[field.key] ?? "").isEmpty ? Color.grayMain2c500 : Color.black)
						Spacer()
						Image("caret-down")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeMain500)
							.frame(width: 18, height: 18)
					}
					.padding(.horizontal, 12)
					.frame(height: 44)
					.background(Color.gray100)
					.cornerRadius(10)
				}
			} else if field.type == "textarea" {
				TextEditor(text: viewModel.valueBinding(for: field.key))
					.default_text_style(styleSize: 14)
					.frame(minHeight: 84)
					.padding(7)
					.background(Color.gray100)
					.cornerRadius(10)
			} else {
				TextField(placeholder(for: field), text: viewModel.valueBinding(for: field.key))
					.default_text_style(styleSize: 14)
					.keyboardType(keyboardType(for: field.type))
					.textContentType(textContentType(for: field.type))
					.padding(.horizontal, 12)
					.frame(height: 44)
					.background(Color.gray100)
					.cornerRadius(10)
			}
		}
	}

	private func displayValue(_ value: String, type: String) -> String {
		if value.isEmpty { return "Not set" }
		if type == "datetime" {
			return value.replacingOccurrences(of: "T", with: " ")
		}
		return value
	}

	private func placeholder(for field: Mango9LeadSchema.Field) -> String {
		switch field.type {
		case "date": return "YYYY-MM-DD"
		case "email": return "name@example.com"
		case "phone": return "Phone number"
		case "url": return "https://"
		default: return field.label
		}
	}

	private func keyboardType(for type: String) -> UIKeyboardType {
		switch type {
		case "email": return .emailAddress
		case "phone": return .phonePad
		case "url": return .URL
		default: return .default
		}
	}

	private func textContentType(for type: String) -> UITextContentType? {
		switch type {
		case "email": return .emailAddress
		case "phone": return .telephoneNumber
		case "url": return .URL
		default: return nil
		}
	}

	private func messageCard(_ message: String, isError: Bool) -> some View {
		HStack(spacing: 9) {
			Image(isError ? "warning-circle" : "check")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(isError ? Color.redDanger500 : Color.greenSuccess500)
				.frame(width: 20, height: 20)
			Text(message)
				.default_text_style(styleSize: 12)
			Spacer()
		}
		.padding(12)
		.background(isError ? Color.redDanger200.opacity(0.45) : Color.greenSuccess200.opacity(0.45))
		.cornerRadius(12)
	}
}

private struct Mango9LocationMapView: View {
	private struct Marker: Identifiable {
		let id = UUID()
		let coordinate: CLLocationCoordinate2D
	}

	let address: String

	@State private var region = MKCoordinateRegion(
		center: CLLocationCoordinate2D(latitude: 39.8283, longitude: -98.5795),
		span: MKCoordinateSpan(latitudeDelta: 45, longitudeDelta: 55)
	)
	@State private var markers: [Marker] = []

	var body: some View {
		Map(
			coordinateRegion: $region,
			annotationItems: markers
		) { marker in
			MapMarker(
				coordinate: marker.coordinate,
				tint: Color.blueInfo500
			)
		}
		.task(id: address) {
			await resolveAddress()
		}
		.accessibilityLabel(
			address.isEmpty ? "Apple Maps preview" : "Map of \(address)"
		)
	}

	@MainActor
	private func resolveAddress() async {
		let normalized = address.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !normalized.isEmpty else {
			markers = []
			region = MKCoordinateRegion(
				center: CLLocationCoordinate2D(latitude: 39.8283, longitude: -98.5795),
				span: MKCoordinateSpan(latitudeDelta: 45, longitudeDelta: 55)
			)
			return
		}

		do {
			guard let coordinate = try await CLGeocoder()
				.geocodeAddressString(normalized)
				.first?
				.location?
				.coordinate else {
				return
			}
			markers = [Marker(coordinate: coordinate)]
			region = MKCoordinateRegion(
				center: coordinate,
				span: MKCoordinateSpan(latitudeDelta: 0.012, longitudeDelta: 0.012)
			)
		} catch {
			Log.warn("[Mango9 CRM] Apple Maps could not resolve \(normalized)")
		}
	}
}
