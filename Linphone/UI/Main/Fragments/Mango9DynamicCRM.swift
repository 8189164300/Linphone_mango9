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
final class Mango9ChatStore: ObservableObject {
	static let shared = Mango9ChatStore()

	@Published private(set) var users: [Mango9ChatUser] = []
	@Published private(set) var rooms: [Mango9ChatRoom] = []
	@Published private(set) var messages: [Mango9ChatMessage] = []
	@Published private(set) var onlineUserIds: Set<Int> = []
	@Published private(set) var typingUserIds: Set<Int> = []
	@Published private(set) var currentUserId: Int?
	@Published private(set) var activeRoomId: String?
	@Published private(set) var isConnecting = false
	@Published private(set) var isConnected = false
	@Published private(set) var errorMessage: String?

	private var socket: URLSessionWebSocketTask?
	private var pendingCalls: [String: CheckedContinuation<Any, Error>] = [:]
	private var reconnectTask: Task<Void, Never>?
	private var intentionallyDisconnected = false
	private var openingUserId: Int?
	private var chatToken: String?
	private var chatTokenExpiresAt: Date?
	private var uploadURL: URL?

	private init() {}

	func connectIfNeeded(force: Bool = false) async {
		if isConnected && !force {
			return
		}
		if isConnecting {
			return
		}

		guard var session = Mango9SessionStore.load() else {
			errorMessage = Mango9ChatError.noSession.localizedDescription
			return
		}

		isConnecting = true
		errorMessage = nil
		intentionallyDisconnected = false
		defer {
			isConnecting = false
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

			guard let websocketURL = URL(string: bootstrap.websocketUrl) else {
				throw Mango9ChatError.invalidEndpoint
			}

			disconnect(clearData: false)
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
			receiveNext()

			try await loadDirectory()
		} catch {
			isConnected = false
			errorMessage = error.localizedDescription
			scheduleReconnect()
		}
	}

	func disconnect(clearData: Bool = true) {
		intentionallyDisconnected = true
		reconnectTask?.cancel()
		reconnectTask = nil
		socket?.cancel(with: .normalClosure, reason: nil)
		socket = nil
		isConnected = false
		activeRoomId = nil
		openingUserId = nil
		chatToken = nil
		chatTokenExpiresAt = nil
		uploadURL = nil
		for continuation in pendingCalls.values {
			continuation.resume(throwing: Mango9ChatError.disconnected)
		}
		pendingCalls.removeAll()
		if clearData {
			users = []
			rooms = []
			messages = []
			onlineUserIds = []
			typingUserIds = []
		}
	}

	func refreshAfterForeground() async {
		guard Mango9SessionStore.load() != nil else {
			return
		}

		if activeRoomId == nil {
			await connectIfNeeded(force: true)
			return
		}

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

	func closeConversation(roomId: String?) {
		guard let roomId, activeRoomId == roomId else {
			return
		}
		activeRoomId = nil
		openingUserId = nil
		messages = []
	}

	func openConversation(with userId: Int, fallbackName: String = "") async {
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
			return true
		} catch {
			errorMessage = error.localizedDescription
			return false
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
		directRoom(with: userId)
	}

	var unreadCount: Int {
		rooms.reduce(0) { total, room in
			total + max(0, room.unread)
		}
	}

	var inboxPreviewRoom: Mango9ChatRoom? {
		rooms
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
		let rawPresence = try await rpcCall("getPresence", params: [])

		users = Self.array(from: rawUsers).compactMap(Self.user(from:))
			.filter { $0.id != currentUserId }
			.sorted {
				$0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
			}
		rooms = Self.array(from: rawRooms).compactMap(Self.room(from:))
			.sorted { $0.latest > $1.latest }
		applyPresence(Self.array(from: rawPresence))
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
	}

	private func directRoom(with userId: Int) -> Mango9ChatRoom? {
		rooms.first { room in
			room.isDirect && room.userIds.contains(userId)
		}
	}

	private func rpcCall(_ method: String, params: [Any]) async throws -> Any {
		guard let socket, isConnected else {
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
			pendingCalls[id] = continuation
			socket.send(.string(text)) { [weak self] error in
				guard let error else {
					return
				}
				Task { @MainActor in
					guard let self,
						  let pending = self.pendingCalls.removeValue(forKey: id) else {
						return
					}
					pending.resume(throwing: error)
				}
			}
		}
	}

	private func receiveNext() {
		guard let socket else {
			return
		}
		socket.receive { [weak self] result in
			Task { @MainActor in
				guard let self else {
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
					self.receiveNext()
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
		   let continuation = pendingCalls.removeValue(forKey: id) {
			if let error = json["error"] as? [String: Any] {
				continuation.resume(
					throwing: Mango9ChatError.server(
						(error["message"] as? String) ?? "Chat request failed."
					)
				)
			} else {
				continuation.resume(returning: json["result"] ?? "")
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
		default:
			break
		}
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

struct Mango9TeamChatListFragment: View {
	@ObservedObject private var store = Mango9ChatStore.shared
	@State private var isShowingCreateGroup = false
	@State private var createdRoom: Mango9ChatRoom?

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
						ForEach(store.users) { user in
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
		.navigationTitle("Team Chat")
		.navigationBarTitleDisplayMode(.inline)
		.toolbar {
			ToolbarItem(placement: .navigationBarTrailing) {
				Button {
					isShowingCreateGroup = true
				} label: {
					Image(systemName: "person.2.badge.plus")
						.foregroundStyle(Color.orangeMain500)
				}
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
		store.rooms.filter { !$0.isDirect }
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
				Button {
					onClose?()
				} label: {
					Image("caret-left")
						.renderingMode(.template)
						.foregroundStyle(Color.orangeMain500)
				}
				.opacity(onClose == nil ? 0 : 1)
				.disabled(onClose == nil),
			trailing:
				Button {
					isManagingMembers = true
				} label: {
					Image(systemName: "person.2")
						.foregroundStyle(Color.orangeMain500)
				}
				.opacity(room?.isDirect == false ? 1 : 0)
				.disabled(room?.isDirect != false)
				.accessibilityLabel("Group members")
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
					if store.messages.isEmpty && store.activeRoomId != nil {
						Text(emptyPrompt)
							.default_text_style(styleSize: 12)
							.foregroundStyle(Color.grayMain2c500)
							.padding(.top, 36)
					}
					ForEach(store.messages) { message in
						Mango9ChatBubble(
							message: message,
							isOutgoing: message.fromUserId == store.currentUserId,
							senderName: room?.isDirect == false
								? store.userName(message.fromUserId)
								: nil
						)
						.id(message.id)
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
			if isRecordingVoice {
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

	private var statusColor: Color {
		if let user {
			return store.isOnline(user.id) ? Color.greenSuccess500 : Color.grayMain2c400
		}
		return room?.userIds.contains(where: store.isOnline) == true
			? Color.greenSuccess500
			: Color.grayMain2c400
	}

	private var statusText: String {
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
						Mango9ChatMediaView(media: media)
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

private struct Mango9ChatMedia: Identifiable {
	enum Kind {
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
			Mango9AudioAttachmentView(media: media)
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
	@State private var player: AVPlayer

	init(media: Mango9ChatMedia) {
		self.media = media
		_player = State(initialValue: AVPlayer(url: media.url))
	}

	var body: some View {
		VStack(alignment: .leading, spacing: 5) {
			VideoPlayer(player: player)
				.frame(width: 230, height: 150)
				.clipShape(RoundedRectangle(cornerRadius: 10))
			Text(media.name)
				.font(.system(size: 10, weight: .medium))
				.foregroundStyle(Color.white.opacity(0.75))
				.lineLimit(1)
		}
	}
}

private struct Mango9AudioAttachmentView: View {
	let media: Mango9ChatMedia
	@State private var player: AVPlayer
	@State private var isPlaying = false

	init(media: Mango9ChatMedia) {
		self.media = media
		_player = State(initialValue: AVPlayer(url: media.url))
	}

	var body: some View {
		HStack(spacing: 10) {
			Button {
				if isPlaying {
					player.pause()
				} else {
					player.play()
				}
				isPlaying.toggle()
			} label: {
				Image(systemName: isPlaying ? "pause.fill" : "play.fill")
					.foregroundStyle(Color.white)
					.frame(width: 34, height: 34)
					.background(Color.orangeMain500)
					.clipShape(Circle())
			}
			VStack(alignment: .leading, spacing: 2) {
				Text(media.name)
					.font(.system(size: 12, weight: .semibold))
					.foregroundStyle(Color.white)
					.lineLimit(1)
				Text("Voice or audio message")
					.font(.system(size: 9))
					.foregroundStyle(Color.white.opacity(0.72))
			}
		}
		.frame(width: 220, alignment: .leading)
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

	var body: some View {
		NavigationView {
			if let target {
				Mango9ChatFragment(
					user: store.users.first(where: { $0.id == target.userId })
						?? Mango9ChatUser(
							id: target.userId,
							name: target.name,
							avatar: "",
							category: ""
						),
					onClose: {
						withAnimation {
							self.target = nil
						}
					}
				)
			}
		}
		.navigationViewStyle(.stack)
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

	static func save(_ identity: Mango9LineIdentity) {
		let defaults = UserDefaults.standard
		defaults.set(
			identity.extensionNumber.trimmingCharacters(in: .whitespacesAndNewlines),
			forKey: extensionKey
		)
		defaults.set(
			formatPhoneNumber(identity.activeNumber ?? ""),
			forKey: activeNumberKey
		)
	}

	static func clear() {
		let defaults = UserDefaults.standard
		defaults.removeObject(forKey: extensionKey)
		defaults.removeObject(forKey: activeNumberKey)
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

struct Mango9ChatTarget: Identifiable, Equatable {
	let userId: Int
	let name: String

	var id: Int { userId }
}

struct Mango9Client: Decodable, Identifiable {
	let id: Int
	let ownerUserId: Int
	let ownerName: String
	let name: String
	let phone: String
	let email: String
	let source: String
	let createdAt: String
}

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
		page: Int = 1
	) async throws -> Mango9ClientListPayload {
		var query = [
			URLQueryItem(name: "page", value: String(page)),
			URLQueryItem(name: "limit", value: "50")
		]
		if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			query.append(URLQueryItem(name: "search", value: search))
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
			apply(updated)
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
		apply(try await Mango9CRMAPI.callSettings(session: session))
	}

	private func apply(_ loadedSettings: Mango9CallSettings) {
		settings = loadedSettings
		forwardingEnabled = loadedSettings.forwarding.enabled
		forwardingDestination = loadedSettings.forwarding.destination
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

@MainActor
final class Mango9LeadsViewModel: ObservableObject {
	@Published private(set) var schema: Mango9LeadSchema?
	@Published private(set) var leads: [Mango9Lead] = []
	@Published private(set) var total = 0
	@Published private(set) var isLoading = false
	@Published private(set) var errorMessage: String?
	@Published var searchText = ""
	@Published var selectedStatus = ""

	func load() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to load leads."
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
		} catch {
			errorMessage = "Leads could not be loaded. Check the connection and try again."
		}
	}

	func setStatus(_ status: String) async {
		selectedStatus = selectedStatus == status ? "" : status
		await reloadList()
	}

	func reloadList() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to load leads."
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
		} catch {
			errorMessage = "Leads could not be loaded. Check the connection and try again."
		}
	}

	private func load(session: Mango9Session) async throws {
		schema = try await Mango9CRMAPI.leadSchema(session: session)
		try await loadList(session: session)
	}

	private func loadList(session: Mango9Session) async throws {
		let payload = try await Mango9CRMAPI.leads(
			session: session,
			search: searchText,
			status: selectedStatus
		)
		leads = payload.leads
		total = payload.pagination.total
	}
}

struct Mango9LeadsFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel = Mango9LeadsViewModel()

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

						if viewModel.isLoading && viewModel.leads.isEmpty {
							ProgressView()
								.tint(Color.orangeMain500)
								.padding(.top, 60)
						} else if viewModel.leads.isEmpty && viewModel.errorMessage == nil {
							emptyState
						} else {
							ForEach(viewModel.leads) { lead in
								NavigationLink(destination: Mango9LeadDetailFragment(leadId: lead.id)) {
									leadRow(lead)
								}
								.buttonStyle(.plain)
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
		.task {
			await viewModel.load()
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9LeadDidChange)) { _ in
			Task { await viewModel.reloadList() }
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
				Task { await viewModel.reloadList() }
			} label: {
				Image("arrow-clockwise")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 22, height: 22)
					.padding(10)
			}
			.disabled(viewModel.isLoading)
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle().fill(Color.gray200).frame(height: 1)
		}
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

	private var emptyState: some View {
		VStack(spacing: 12) {
			Image("users-three-square")
				.renderingMode(.template)
				.resizable()
				.foregroundStyle(Color.orangeMain500)
				.frame(width: 44, height: 44)
			Text("No leads found")
				.default_text_style_800(styleSize: 16)
			Text("Try another search or status filter.")
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
final class Mango9ClientsViewModel: ObservableObject {
	@Published private(set) var clients: [Mango9Client] = []
	@Published private(set) var total = 0
	@Published private(set) var isLoading = false
	@Published private(set) var errorMessage: String?
	@Published var searchText = ""

	func load() async {
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
			let payload: Mango9ClientListPayload
			do {
				payload = try await Mango9CRMAPI.clients(
					session: session,
					search: searchText
				)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				payload = try await Mango9CRMAPI.clients(
					session: session,
					search: searchText
				)
			}
			clients = payload.clients
			total = payload.pagination.total
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			errorMessage = "Clients could not be loaded. Check the connection and try again."
		}
	}
}

struct Mango9ClientsFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel = Mango9ClientsViewModel()

	var body: some View {
		ZStack {
			Color.gray100.ignoresSafeArea()

			VStack(spacing: 0) {
				header

				ScrollView {
					LazyVStack(spacing: 12) {
						searchCard

						if let errorMessage = viewModel.errorMessage {
							errorCard(errorMessage)
						}

						if viewModel.isLoading && viewModel.clients.isEmpty {
							ProgressView()
								.tint(Color.orangeMain500)
								.padding(.top, 60)
						} else if viewModel.clients.isEmpty && viewModel.errorMessage == nil {
							emptyState
						} else {
							ForEach(viewModel.clients) { client in
								clientRow(client)
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
		.task {
			await viewModel.load()
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
				Task { await viewModel.reloadList() }
			} label: {
				Image("arrow-clockwise")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 22, height: 22)
					.padding(10)
			}
			.disabled(viewModel.isLoading)
		}
		.frame(height: 58)
		.padding(.horizontal, 6)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle().fill(Color.gray200).frame(height: 1)
		}
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

			Text("CLIENT")
				.font(.system(size: 9, weight: .bold))
				.foregroundStyle(Color.orangeMain500)
				.padding(.horizontal, 8)
				.padding(.vertical, 5)
				.background(Color.orangeMain100)
				.cornerRadius(20)
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
			Text("Try another name, phone number, or email.")
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

	@Published private(set) var schema: Mango9LeadSchema?
	@Published private(set) var lead: Mango9Lead?
	@Published var values: [String: String] = [:]
	@Published private(set) var isLoading = false
	@Published private(set) var isSaving = false
	@Published var isEditing = false
	@Published private(set) var errorMessage: String?
	@Published private(set) var savedMessage: String?

	private var originalValues: [String: String] = [:]

	init(leadId: Int) {
		self.leadId = leadId
	}

	func load() async {
		guard !isLoading else { return }
		guard var session = Mango9SessionStore.load() else {
			errorMessage = "Connect your Mango9 account to open this lead."
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
		} catch {
			errorMessage = "This lead could not be loaded."
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
		guard !isSaving, let schema else { return }
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
			errorMessage = "Connect your Mango9 account to save this lead."
			return
		}
		isSaving = true
		errorMessage = nil
		savedMessage = nil
		defer { isSaving = false }

		do {
			let payload: Mango9LeadDetailPayload
			do {
				payload = try await Mango9CRMAPI.updateLead(
					session: session,
					id: leadId,
					values: changes
				)
			} catch Mango9CRMAPIError.unauthorized {
				session = try await Mango9CRMAPI.refresh(session: session)
				try Mango9SessionStore.save(session)
				payload = try await Mango9CRMAPI.updateLead(
					session: session,
					id: leadId,
					values: changes
				)
			}
			lead = payload.lead
			values = payload.values
			originalValues = payload.values
			isEditing = false
			savedMessage = "Lead saved"
			NotificationCenter.default.post(name: .mango9LeadDidChange, object: nil)
		} catch Mango9CRMAPIError.unauthorized {
			errorMessage = "Your CRM session expired. Sign in again."
		} catch {
			errorMessage = "The lead could not be saved. Try again."
		}
	}

	private func load(session: Mango9Session) async throws {
		let loadedSchema = try await Mango9CRMAPI.leadSchema(session: session)
		let payload = try await Mango9CRMAPI.lead(session: session, id: leadId)
		schema = loadedSchema
		lead = payload.lead
		values = payload.values
		originalValues = payload.values
	}
}

struct Mango9LeadDetailFragment: View {
	@Environment(\.presentationMode) private var presentationMode
	@StateObject private var viewModel: Mango9LeadDetailViewModel

	init(leadId: Int) {
		_viewModel = StateObject(wrappedValue: Mango9LeadDetailViewModel(leadId: leadId))
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
									let fields = schema.fields.filter {
										$0.section == section.id && $0.isVisible
									}
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
				Text(viewModel.isEditing ? "Edit Lead" : "Lead")
					.default_text_style_orange_800(styleSize: 18)
				Text(viewModel.lead?.name.isEmpty == false ? viewModel.lead?.name ?? "" : "CRM record")
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c500)
					.lineLimit(1)
			}

			Spacer()

			if viewModel.lead != nil {
				if viewModel.isEditing {
					Button {
						Task { await viewModel.save() }
					} label: {
						if viewModel.isSaving {
							ProgressView()
								.tint(Color.white)
								.frame(width: 58, height: 34)
								.background(Color.orangeMain500)
								.cornerRadius(18)
						} else {
							Text("Save")
								.font(.system(size: 12, weight: .bold))
								.foregroundStyle(Color.white)
								.frame(width: 58, height: 34)
								.background(Color.orangeMain500)
								.cornerRadius(18)
						}
					}
					.disabled(viewModel.isSaving)
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
				Text(lead.name.isEmpty ? "Unnamed lead" : lead.name)
					.default_text_style_800(styleSize: 16)
					.lineLimit(1)
				if !lead.phone.isEmpty {
					Text(lead.phone)
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
				}
				if !lead.email.isEmpty {
					Text(lead.email)
						.default_text_style(styleSize: 12)
						.foregroundStyle(Color.grayMain2c500)
						.lineLimit(1)
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
		}
		.background(Color.white)
		.cornerRadius(15)
		.overlay {
			RoundedRectangle(cornerRadius: 15)
				.stroke(Color.gray200, lineWidth: 1)
		}
	}

	private func valueRow(_ field: Mango9LeadSchema.Field) -> some View {
		VStack(alignment: .leading, spacing: 4) {
			Text(field.label)
				.default_text_style_700(styleSize: 11)
				.foregroundStyle(Color.grayMain2c500)
			Text(displayValue(viewModel.values[field.key] ?? "", type: field.type))
				.default_text_style(styleSize: 14)
				.foregroundStyle((viewModel.values[field.key] ?? "").isEmpty ? Color.grayMain2c500 : Color.black)
				.frame(maxWidth: .infinity, alignment: .leading)
		}
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
