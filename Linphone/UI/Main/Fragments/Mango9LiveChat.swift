import SwiftUI
import ImageIO
import AVFoundation
import UniformTypeIdentifiers
import QuickLook

@MainActor final class Mango9LiveChatStore: ObservableObject {
	let session: Mango9Session?
	@Published var sites: [Mango9LiveSite] = []
	@Published var conversations: [Mango9LiveConversation] = []
	@Published var error: String?
	@Published var busy = false
	@Published var site = ""
	@Published var status = ""
	@Published var assigned = false
	@Published var unread = false
	private(set) var next: String?
	private var nextID: String?
	private var generation = 0
	private var loadedFilter: String?
	private(set) var expanded = false
	init(session: Mango9Session? = Mango9SessionStore.load()) { self.session = session }
	func filter(_ search: String) -> String { [site, status, String(assigned), String(unread), search].joined(separator: "|") }
	func hasLoaded(_ search: String) -> Bool { loadedFilter == filter(search) }
	func loadSites() async {
		guard let session else { return }
		do {
			let data = try await Mango9LiveChatAPI.shared.request("sites", session: session)
			sites = (data["sites"] as? [[String: Any]] ?? []).compactMap(Mango9LiveSite.init)
		} catch { fail(error) }
	}
	func load(_ search: String, more: Bool = false) async {
		guard let session, !more || (!busy && next != nil && nextID != nil) else { return }
		let requested = filter(search)
		if requested != loadedFilter { conversations = []; next = nil; nextID = nil; expanded = false }
		generation += 1; let current = generation
		busy = true; defer { if generation == current { busy = false } }
		do {
			var query = [URLQueryItem(name: "limit", value: "100")]
			for (key, value) in [("siteId", site), ("status", status), ("search", search)] where !value.isEmpty { query.append(.init(name: key, value: value)) }
			if assigned { query.append(.init(name: "assignedToMe", value: "1")) }
			if unread { query.append(.init(name: "unread", value: "1")) }
			if more, let next, let nextID { query += [.init(name: "before", value: next), .init(name: "beforeId", value: nextID)] }
			let data = try await Mango9LiveChatAPI.shared.request("list", session: session, query: query)
			guard current == generation, !Task.isCancelled else { return }
			guard let rows = data["conversations"] as? [[String: Any]] else { throw Mango9LiveChatFailure(status: 0) }
			let parsed = rows.compactMap(Mango9LiveConversation.init)
			guard parsed.count == rows.count else { throw Mango9LiveChatFailure(status: 0) }
			var seen = Set(more ? conversations.map(\.id) : [])
			let unique = parsed.filter { seen.insert($0.id).inserted }
			conversations = more ? conversations + unique : unique
			next = data["next"] as? String; nextID = data["nextId"] as? String
			expanded = more; loadedFilter = requested; error = nil
		} catch { if current == generation { fail(error) } }
	}
	private func fail(_ failure: Error) {
		guard !Task.isCancelled else { return }
		if case Mango9MessagingError.scopeChanged = failure { conversations = []; sites = [] }
		if let failure = failure as? Mango9LiveChatFailure, [401, 403, 404].contains(failure.status) { conversations = []; sites = [] }
		error = Mango9LiveChatAPI.message(failure)
	}
}

@MainActor final class Mango9LivePresence: ObservableObject {
	static let shared = Mango9LivePresence()
	@Published private(set) var available = false
	@Published private(set) var busy = false
	@Published var error: String?
	private var lastHeartbeat = Date.distantPast
	private var account = ""
	private var device: String {
		if let value = UserDefaults.standard.string(forKey: "mango9_live_device") { return value }
		let value = UUID().uuidString.lowercased(); UserDefaults.standard.set(value, forKey: "mango9_live_device"); return value
	}
	func update(session: Mango9Session, enabled: Bool) async {
		guard !busy else { return }
		busy = true; defer { busy = false }
		do {
			_ = try await Mango9LiveChatAPI.shared.request("presence", session: session, body: ["status": enabled ? "available" : "away", "deviceId": device])
			available = enabled; account = Mango9ReactionTarget.accountKey(session); lastHeartbeat = Date(); error = nil
		} catch { self.error = Mango9LiveChatAPI.message(error) }
	}
	func heartbeat(_ session: Mango9Session) async {
		if account != Mango9ReactionTarget.accountKey(session) { available = false; account = Mango9ReactionTarget.accountKey(session); error = nil }
		if available && Date().timeIntervalSince(lastHeartbeat) >= 30 { await update(session: session, enabled: true) }
	}
}

struct Mango9LiveChatList: View {
	let searchText: String
	@StateObject private var store = Mango9LiveChatStore()
	@ObservedObject private var presence = Mango9LivePresence.shared
	@Environment(\.scenePhase) private var phase
	@State private var visible = false
	var body: some View {
		VStack(spacing: 0) {
			HStack(spacing: 8) {
				Menu {
					Button("All websites") { store.site = "" }
					ForEach(store.sites) { site in Button(site.name) { store.site = site.id } }
				} label: { dropdown(store.sites.first(where: { $0.id == store.site })?.name ?? "All websites", icon: "globe") }
				.accessibilityIdentifier("livechat.filter.website")
				Menu {
					Button("All conversations") { store.status = "" }
					Button("Open") { store.status = "open" }
					Button("Resolved") { store.status = "resolved" }
				} label: { dropdown(store.status.isEmpty ? "All conversations" : store.status.capitalized, icon: "line.3.horizontal.decrease") }
				.accessibilityIdentifier("livechat.filter.status")
			}.font(.system(size: 13)).padding(.horizontal, 16).padding(.top, 4)
			HStack(spacing: 12) {
				filter("Assigned to me", selected: $store.assigned)
				filter("Unread", selected: $store.unread)
				Spacer(minLength: 0)
				if let session = store.session {
					Button { Task { await presence.update(session: session, enabled: !presence.available) } } label: {
						Image(systemName: presence.available ? "circle.fill" : "moon.fill")
							.font(.system(size: 11)).foregroundStyle(presence.available ? Color.green : Color.secondary)
							.frame(width: 44, height: 44).contentShape(Rectangle())
					}.accessibilityLabel(presence.available ? "Set away" : "Set available")
						.accessibilityValue(presence.available ? "Available on this phone" : "Away on this phone")
						.accessibilityIdentifier("livechat.presence").disabled(presence.busy)
					NavigationLink(destination: Mango9LiveChatSettings(session: session)) {
						Image(systemName: "slider.horizontal.3").font(.system(size: 13)).frame(width: 44, height: 44)
					}.accessibilityLabel("Live Chat settings")
				}
			}.padding(.leading, 16).padding(.trailing, 4)

			Divider()
			List {
				if let error = store.error ?? presence.error {
					Section { Text(error).font(.subheadline); Button("Try again") { Task { await store.loadSites(); await store.load(searchText) } } }
				}
				ForEach(store.conversations) { conversation in
					if let session = store.session {
						NavigationLink(destination: Mango9LiveChatConversation(conversation: conversation, session: session).id(conversation.id)) {
							HStack(spacing: 12) {
								Image(systemName: "globe").font(.system(size: 30)).foregroundStyle(Color.orangeMain500).frame(width: 46, height: 46)
								VStack(alignment: .leading, spacing: 4) {
									HStack { Text(conversation.title).font(.custom(conversation.unread ? "NotoSans-Bold" : "NotoSans-SemiBold", size: 14)).lineLimit(1); Spacer(minLength: 4); if conversation.unread { Circle().fill(Color.orangeMain500).frame(width: 7, height: 7) } }
									Text(conversation.summary.isEmpty ? "No messages yet" : conversation.summary).font(.custom("NotoSans-Regular", size: 13)).foregroundStyle(.secondary).lineLimit(2).frame(height: 38, alignment: .topLeading)
									HStack(alignment: .lastTextBaseline, spacing: 8) {
										Text(conversation.siteName).lineLimit(1)
										Spacer(minLength: 0)
										Text(Mango9ChatDateText.display(conversation.updated)).fixedSize()
											.accessibilityIdentifier("livechat.date." + conversation.id)
									}.font(.custom("NotoSans-Regular", size: 11)).foregroundStyle(.secondary)
								}.frame(maxWidth: .infinity, alignment: .leading)
							}.frame(minHeight: 56).contentShape(Rectangle()).accessibilityElement(children: .combine)
						}.accessibilityIdentifier("livechat.thread." + conversation.id)
						.listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 12))
					}
				}
				if store.conversations.isEmpty && store.error == nil && (store.busy || !store.hasLoaded(searchText)) {
					ProgressView("Loading conversations…").accessibilityIdentifier("livechat.loading")
				}
				if !store.busy && store.hasLoaded(searchText) && store.conversations.isEmpty && store.error == nil {
					Text(searchText.isEmpty ? "No Live Chat conversations yet. Conversations from your connected websites will appear here." : "No matching conversations.").foregroundStyle(.secondary)
						.accessibilityIdentifier("livechat.empty")
				}
				if store.next != nil { Button("Load more") { Task { await store.load(searchText, more: true) } }.disabled(store.busy) }
			}.listStyle(.plain).refreshable { await store.loadSites(); await store.load(searchText) }
		}.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).tint(Color.orangeMain500)
		.onAppear { visible = true }.onDisappear { visible = false }
		.onReceive(NotificationCenter.default.publisher(for: .mango9LiveInboxDidChange)) { note in
			guard visible, let target = note.object as? Mango9LivePushTarget,
				let session = store.session, target.matches(session) else { return }
			Task { await store.load(searchText) }
		}
		.task(id: store.filter(searchText) + String(phase == .active && visible)) {
			guard phase == .active && visible else { return }
			do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
			await store.loadSites(); await store.load(searchText)
			while !Task.isCancelled {
				if let session = store.session { await presence.heartbeat(session) }
				do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
				if !store.expanded { await store.load(searchText) }
			}
		}
	}
	private func dropdown(_ title: String, icon: String) -> some View {
		HStack(spacing: 5) {
			Image(systemName: icon)
			Text(title).lineLimit(1)
			Spacer(minLength: 0)
			Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
		}.padding(.horizontal, 9).frame(maxWidth: .infinity, minHeight: 36)
		.background(Color(UIColor.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 8))
		.frame(minHeight: 44).contentShape(Rectangle())
	}
	private func filter(_ title: String, selected: Binding<Bool>) -> some View {
		Button { selected.wrappedValue.toggle() } label: { Label(title, systemImage: selected.wrappedValue ? "checkmark.square.fill" : "square") }
			.font(.system(size: 12)).frame(minHeight: 44).contentShape(Rectangle())
			.accessibilityValue(selected.wrappedValue ? "Selected" : "Not selected")
	}
}

@MainActor final class Mango9LiveThreadStore: ObservableObject {
	let session: Mango9Session
	@Published var conversation: Mango9LiveConversation
	@Published var messages: [Mango9LiveMessage] = []
	@Published var site: Mango9LiveSite?
	@Published var agents: [[String: Any]] = []
	@Published var online: Bool?
	@Published var error: String?
	@Published var busy = false
	@Published var sending = false
	@Published var pending: Mango9LivePendingSend?
	@Published var pendingReaction: Mango9LiveReactionCommand?
	@Published var reacting = false
	@Published var reactionNotice: String?
	@Published var hasMore = false
	@Published var visitorTyping = false
	@Published var authorized = false
	private var before: Int?
	private var readID = 0
 private var revision = 0
	private var lastTyping = Date.distantPast
	private var account: String { Mango9ReactionTarget.accountKey(session) }
	init(conversation: Mango9LiveConversation, session: Mango9Session) {
		self.conversation = conversation; self.session = session
		pending = Mango9LiveOutbox.load(account: Mango9ReactionTarget.accountKey(session), conversation: conversation.id)
		pendingReaction = Mango9LiveReactionJournal.load(account: Mango9ReactionTarget.accountKey(session), conversation: conversation.id)
	}
	func load(more: Bool = false) async {
		guard !busy, !more || hasMore else { return }
		busy = true; defer { busy = false }
  let loadRevision = revision
		do {
			var query = [URLQueryItem(name: "id", value: conversation.id)]
			if more, let before { query.append(.init(name: "before", value: String(before))) }
			let data = try await Mango9LiveChatAPI.shared.request("messages", session: session, query: query)
			guard loadRevision == revision else { return }
			guard let raw = data["conversation"] as? [String: Any], let current = Mango9LiveConversation(raw), current.id == conversation.id,
				let rows = data["messages"] as? [[String: Any]] else { throw Mango9LiveChatFailure(status: 0) }
			let parsed = rows.compactMap(Mango9LiveMessage.init)
			guard parsed.count == rows.count else { throw Mango9LiveChatFailure(status: 0) }
			conversation = current
			authorized = true
			if let rawSite = raw["site"] as? [String: Any] { site = Mango9LiveSite(rawSite) }
			let typing = raw["visitor_typing_until"] as? String ?? ""
			visitorTyping = (Mango9LiveChatTime.date(typing) ?? .distantPast) > Date()
			var merged = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
			for var message in parsed {
				if let saved = merged[message.id], saved.reactions.version > message.reactions.version { message.reactions = saved.reactions }
				merged[message.id] = message
			}
			if let hidden = data["hiddenMessageIds"] as? [Int] {
    let ids = Set(hidden)
    for id in Array(merged.keys) { merged[id]?.hiddenForMe = ids.contains(id) }
   }
   messages = merged.values.sorted { $0.id < $1.id }
			if more || before == nil { hasMore = data["hasMore"] as? Bool ?? false; before = data["nextBefore"] as? Int }
			error = nil
			// Staff inbox read state is independent of the website's visitor read-receipt setting.
			if let latest = parsed.last, latest.id > readID {
				_ = try await Mango9LiveChatAPI.shared.request("read", session: session, body: ["id": conversation.id, "messageId": latest.id]); readID = latest.id
			}
		} catch { fail(error) }
	}
	func availability() async {
		do {
			let data = try await Mango9LiveChatAPI.shared.request("availability", session: session, query: [.init(name: "siteId", value: conversation.siteID)])
			agents = data["agents"] as? [[String: Any]] ?? []; online = data["online"] as? Bool
		} catch { if case Mango9MessagingError.scopeChanged = error { fail(error) } }
	}
	func send(_ text: String, attachment: Mango9LivePendingSend? = nil) async -> Bool {
		guard !sending, authorized else { return false }
		sending = true; defer { sending = false }
		do {
			let command = pending ?? attachment ?? Mango9LivePendingSend(text: text)
			try Mango9LiveOutbox.save(command, account: account, conversation: conversation.id)
			pending = command
			var body = command.body; body["id"] = conversation.id
			let data = try await Mango9LiveChatAPI.shared.request("send", session: session, body: body)
			guard let row = data["message"] as? [String: Any], row["conversation_id"] as? String == conversation.id,
				let message = Mango9LiveMessage(row), message.direction == "outgoing" else { throw Mango9LiveChatFailure(status: 0) }
			try Mango9LiveOutbox.save(nil, account: account, conversation: conversation.id); pending = nil
			messages.removeAll { $0.id == message.id }; messages.append(message); messages.sort { $0.id < $1.id }; error = nil
			await load(); return true
		} catch {
			// Explicit client rejection is definitive. A transport failure must keep the same command.
			if let failure = error as? Mango9LiveChatFailure, [400, 401, 403, 404, 409, 413].contains(failure.status) {
				try? Mango9LiveOutbox.save(nil, account: account, conversation: conversation.id); pending = nil
			}
			fail(error); return false
		}
	}
	func action(_ operation: String, fields: [String: Any]) async {
		guard !sending, authorized else { return }
		sending = true; defer { sending = false }
		do {
			var body = fields; body["id"] = conversation.id
			let result = try await Mango9LiveChatAPI.shared.request(operation, session: session, body: body)
   revision += 1
   if operation == "assign" { conversation.assignee = result["assigned"] as? Int ?? Int(result["assigned"] as? String ?? "") }
   error = nil; await load(); await availability()
		} catch { fail(error) }
	}
 func hide(_ ids: Set<Int>) async -> Bool {
  guard authorized, !sending, !ids.isEmpty else { return false }
  sending = true; defer { sending = false }
  do {
   let result = try await Mango9LiveChatAPI.shared.request("visibility", session: session,
    body: ["id": conversation.id, "messageIds": Array(ids), "hidden": true])
   guard result["id"] as? String == conversation.id, result["hidden"] as? Bool == true,
    Set(result["messageIds"] as? [Int] ?? []) == ids else { throw Mango9LiveChatFailure(status: 0) }
   revision += 1
   for index in messages.indices where ids.contains(messages[index].id) { messages[index].hiddenForMe = true }
   error = nil; return true
  } catch { fail(error); return false }
 }

	func react(to message: Mango9LiveMessage, emoji: String?) async {
		guard authorized, !reacting, pendingReaction == nil, message.reactions.supported,
			emoji == nil || message.reactions.allowed.contains(emoji!) else { return }
		let command = Mango9LiveReactionCommand(messageId: message.id, version: message.reactions.version, emoji: emoji)
		do {
			try Mango9LiveReactionJournal.save(command, account: account, conversation: conversation.id)
			pendingReaction = command; await retryReaction()
		} catch { reactionNotice = "Couldn’t save the reaction request. Please try again." }
	}
	func retryReaction() async {
		guard authorized, !reacting, let command = pendingReaction else { return }
		reacting = true; reactionNotice = nil; defer { reacting = false }
		do {
			var body = command.body; body["id"] = conversation.id
			let result = try await Mango9LiveChatAPI.shared.request("reaction", session: session, body: body)
			guard result["id"] as? String == conversation.id, result["messageId"] as? Int == command.messageId,
				result["requestId"] as? String == command.requestId,
				let snapshot = Mango9LiveReactions.parse(result["reactions"]) else { throw Mango9LiveChatFailure(status: 0) }
			if let index = messages.firstIndex(where: { $0.id == command.messageId }), snapshot.version >= messages[index].reactions.version { messages[index].reactions = snapshot }
			try Mango9LiveReactionJournal.save(nil, account: account, conversation: conversation.id); pendingReaction = nil
		} catch {
			if let failure = error as? Mango9LiveChatFailure, [400, 401, 403, 404, 409].contains(failure.status) {
				try? Mango9LiveReactionJournal.save(nil, account: account, conversation: conversation.id); pendingReaction = nil
				reactionNotice = failure.status == 409 ? "Reactions changed on another device. Review the refreshed message and tap again." : Mango9LiveChatAPI.message(error)
				await load()
			} else { reactionNotice = "Reaction not confirmed. Retry the saved request to check its result." }
		}
	}
	func typing(_ on: Bool) async {
		guard site?.capabilities["typing"] as? Bool == true, !on || Date().timeIntervalSince(lastTyping) > 3 else { return }
		lastTyping = Date()
		_ = try? await Mango9LiveChatAPI.shared.request("typing", session: session, body: ["id": conversation.id, "status": on ? "on" : "off"])
	}
	private func fail(_ failure: Error) {
		guard !Task.isCancelled else { return }
		if case Mango9MessagingError.scopeChanged = failure { messages = []; site = nil; agents = []; authorized = false }
		if let failure = failure as? Mango9LiveChatFailure, [401, 403, 404].contains(failure.status) { messages = []; site = nil; agents = []; authorized = false }
		error = Mango9LiveChatAPI.message(failure)
	}
}

struct Mango9LiveChatConversation: View {
	@StateObject private var store: Mango9LiveThreadStore
	@Environment(\.scenePhase) private var phase
	@Environment(\.dismiss) private var dismiss
	@FocusState private var focused: Bool
	@State private var draft = ""
	@State private var attachmentRequest: Mango9AttachmentRequest?
 @State private var selectedMedia: [Attachment] = []
 @State private var recordingVoice = false
 @State private var confirmingDelete = false
 @State private var forwardPayload: Mango9LiveForwardPayload?
 private var visibleMessages: [Mango9LiveMessage] { store.messages.filter { !$0.hiddenForMe } }
 private var canAttach: Bool { (store.site?.capabilities["attachments"] as? [String])?.isEmpty == false }
 private var canRecord: Bool { (store.site?.capabilities["attachments"] as? [String])?.contains("audio/mp4") == true }
	@State private var details: Mango9LiveVisitorContext?
	@State private var showDetails = false
	@State private var localError: String?
	@State private var previewURL: URL?
	@State private var voicePreviewOwner = UUID()
	@State private var preparingFile = false
	@State private var downloadedDirectories: [URL] = []
	@State private var downloadedFiles: [String: URL] = [:]
	@State private var selectionMode = false
	@State private var selectedMessages = Set<Int>()
	@State private var copiedMessage: Int?
	@State private var atBottom = true
	@State private var scrolledInitially = false
	init(conversation: Mango9LiveConversation, session: Mango9Session, initialText: String = "", initialAttachments: [Attachment] = []) {
		_draft = State(initialValue: initialText); _selectedMedia = State(initialValue: initialAttachments)
		_store = StateObject(wrappedValue: Mango9LiveThreadStore(conversation: conversation, session: session))
	}
	var body: some View {
		VStack(spacing: 0) {
			header
			if let error = localError ?? store.error {
				HStack { Text(error).font(.caption); Spacer(); Button("Retry") { Task { await store.load() } } }.padding(12).background(Color(UIColor.secondarySystemBackground))
			}
			ScrollViewReader { proxy in
				ScrollView {
					VStack(spacing: 0) {
					LazyVStack(spacing: 12) {
						if store.hasMore { Button("Earlier messages") { Task { await store.load(more: true) } }.disabled(store.busy) }
						if store.busy && store.messages.isEmpty { ProgressView().padding() }
						ForEach(visibleMessages) { message in bubble(message).id(message.id) }
						if store.visitorTyping { Text("Visitor is typing…").font(.caption).foregroundStyle(.secondary) }
					}.padding(16)
					Mango9ChatBottomMarker().id("bottom")
					}.mango9ChatContentSize()
				}.background(Color(UIColor.systemGroupedBackground))
				.accessibilityIdentifier("live.messages")
				.mango9LatestMessages(proxy: proxy, bottomID: "bottom", following: $atBottom)
				.onChange(of: store.messages.last?.id) { _ in
					if !scrolledInitially || atBottom { proxy.scrollTo("bottom", anchor: .bottom); scrolledInitially = true }
				}
			}
  }.frame(maxWidth: .infinity, maxHeight: .infinity)
  .safeAreaInset(edge: .bottom, spacing: 0) {
   VStack(spacing: 0) {
    Divider()
    if let notice = store.reactionNotice { Text(notice).font(.caption).foregroundStyle(.secondary).padding(.horizontal) }
    if store.pendingReaction != nil {
     Button("Retry saved reaction") { Task { await store.retryReaction() } }.font(.caption).disabled(store.reacting || !store.authorized)
    }
    if selectionMode {
     Mango9MessageSelectionBar(count: selectedMessages.count, delete: { confirmingDelete = true }, forward: {
      Task { await prepareForward(visibleMessages.filter { selectedMessages.contains($0.id) }) }
     }, cancel: { selectionMode = false; selectedMessages = [] })
     Button("Copy selected messages") { copySelected() }.font(.caption).disabled(selectedMessages.isEmpty)
    } else { composer }
   }.background(Color(uiColor: .systemBackground).ignoresSafeArea(.container, edges: .bottom))
  }.background(Color.white).tint(Color.orangeMain500)
		.navigationTitle(store.conversation.title).navigationBarTitleDisplayMode(.inline)
		.mango9ConversationNavigationHidden()
		.toolbar { ToolbarItem(placement: .navigationBarTrailing) {
			Menu {
				Button { details = store.conversation.context; showDetails = true } label: { Label("Visitor details", systemImage: "person.text.rectangle") }
				Button { Task { await store.action("status", fields: ["status": store.conversation.status == "resolved" ? "open" : "resolved"]) } } label: {
					Label(store.conversation.status == "resolved" ? "Reopen" : "Resolve", systemImage: "checkmark.circle")
				}
				Menu("Transfer") {
					Button("Unassigned") { Task { await store.action("assign", fields: ["actorId": NSNull()]) } }
					ForEach(store.agents.indices, id: \.self) { index in
						if let id = store.agents[index]["id"] as? Int {
							Button(store.agents[index]["name"] as? String ?? "Team member") { Task { await store.action("assign", fields: ["actorId": id]) } }.accessibilityIdentifier("livechat.transfer.agent." + String(id))
						}
					}
				}
			} label: { Image(systemName: "ellipsis.circle") }.disabled(store.sending || !store.authorized).accessibilityLabel("Conversation actions")
		} }
		.sheet(isPresented: $showDetails) { NavigationView { Mango9LiveVisitorDetails(context: details ?? store.conversation.context, name: store.conversation.title, email: store.conversation.email) } }
		.mango9Attachments(request: $attachmentRequest, selected: $selectedMedia, limit: 1, photosOnly: true, error: { localError = $0 })
  .sheet(item: $forwardPayload) { payload in Mango9LiveForwardSheet(session: store.session, payload: payload) }
  .confirmationDialog("Delete for me on all my devices? The visitor and other staff keep their copies.", isPresented: $confirmingDelete, titleVisibility: .visible) {
   Button("Delete for me", role: .destructive) {
    Task { if await store.hide(selectedMessages) { selectedMessages = []; selectionMode = false } }
   }
  }
  .onChange(of: selectedMedia) { _ in
   if let item = selectedMedia.first {
    do { _ = try pendingAttachment(item, text: ""); localError = nil }
    catch { selectedMedia = []; localError = error.localizedDescription }
   }
  }
		.quickLookPreview($previewURL)
		.modifier(Mango9VoicePreviewLifecycle(owner: voicePreviewOwner, url: $previewURL))
		.task(id: phase == .active) {
			guard phase == .active else { return }
			await store.load(); await store.availability()
			while !Task.isCancelled {
				await Mango9LivePresence.shared.heartbeat(store.session)
				do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
				await store.load(); await store.availability()
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
		.onChange(of: draft) { _ in Task { await store.typing(focused && !draft.isEmpty) } }
		.onChange(of: focused) { value in Task { await store.typing(value && !draft.isEmpty) } }
		.onChange(of: store.authorized) { allowed in if !allowed { showDetails = false; previewURL = nil; selectedMedia = [] } }
		
		.onDisappear { if forwardPayload == nil && previewURL == nil { cleanDownloads() } }
		.onChange(of: previewURL) { value in if value == nil && forwardPayload == nil { cleanDownloads() } }
	}
	private var header: some View {
		VStack(spacing: 0) {
			if let assignee = store.conversation.assignee {
				HStack(spacing: 6) {
					Label("Assigned to " + (store.agents.first { ($0["id"] as? Int ?? Int($0["id"] as? String ?? "")) == assignee }?["name"] as? String ?? "Team member"), systemImage: "person.crop.circle.badge.checkmark")
						.lineLimit(1).accessibilityIdentifier("livechat.assignee")
					Spacer(minLength: 4)
					if let online = store.online {
						Circle().fill(online ? Color.green : Color.gray).frame(width: 7, height: 7)
							.accessibilityLabel(online ? "Team available" : "Team away")
					}
				}.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.vertical, 4)
			}
			if store.site?.enabled == false {
				Text("This website’s chat is disabled. Saved conversations remain available.").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16)
			}
		}.background(Color.white).accessibilityIdentifier("livechat.detailHeader")
	}
 private var composer: some View {
  VStack(spacing: 6) {
   if store.pending != nil {
    Text("Delivery is unconfirmed. Retry checks the original message.").font(.caption).foregroundStyle(.secondary).padding(.horizontal)
    Button("Retry original message") { Task { if await store.send("") { draft = ""; selectedMedia = [] } } }.disabled(store.sending)
   } else if recordingVoice {
    Mango9VoiceRecorderComposer(onCancel: { recordingVoice = false }, onComplete: { selectedMedia = [$0]; recordingVoice = false }).padding(12)
   } else {
    if !selectedMedia.isEmpty { Mango9PendingAttachmentStrip(attachments: $selectedMedia) }
    Mango9MessageComposer(templateSession: store.session, text: $draft, focus: $focused, placeholder: "Reply to this visitor…",
     enabled: store.authorized && store.site != nil, sending: store.sending, sendEnabled: draft.count <= 10000,
     hasAttachments: !selectedMedia.isEmpty, attach: canAttach ? { focused = false; attachmentRequest = $0 } : nil,
     record: canRecord ? { focused = false; recordingVoice = true } : nil, send: sendDraft)
   }
  }.disabled(!store.authorized)
 }
 private func pendingAttachment(_ item: Attachment, text: String) throws -> Mango9LivePendingSend {
  try Mango9LiveAttachmentPayload.prepare(item, text: text, allowedMIMEs: store.site?.capabilities["attachments"] as? [String] ?? [])
 }
 private func sendDraft() {
  focused = false
  let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
  do {
   let attachment = try selectedMedia.first.map { try pendingAttachment($0, text: text) }
   Task { if await store.send(text, attachment: attachment) { draft = ""; selectedMedia = []; atBottom = true } }
  } catch { localError = error.localizedDescription }
 }

	private func bubble(_ message: Mango9LiveMessage) -> some View {
		let context = message.context
		return HStack {
			if message.direction == "outgoing" { Spacer(minLength: 52) }
			VStack(alignment: message.direction == "outgoing" ? .trailing : .leading, spacing: 4) {
				Text(message.sender + (message.direction == "note" ? " · Private note" : "")).font(.caption).foregroundStyle(.secondary)
				if !message.text.isEmpty {
					Text(message.text).font(Mango9InboxTypography.message)
						.fixedSize(horizontal: false, vertical: true).padding(.horizontal, 13).padding(.vertical, 9)
						.foregroundStyle(message.direction == "outgoing" ? Color.white : Color.primary)
						.background(message.direction == "outgoing" ? Color.orangeMain500 : Color(uiColor: .secondarySystemGroupedBackground))
						.clipShape(RoundedRectangle(cornerRadius: 20))
						
				}
				ForEach(message.attachments.indices, id: \.self) { index in
					Mango9LiveAttachmentPreview(file: message.attachments[index],
      load: { try await downloadFile(message.attachments[index]) },
      open: { Task { await openFile(message.attachments[index]) } }).disabled(preparingFile)
				}
				if !context.rows.isEmpty {
					HStack(spacing: 5) {
						Text(context.compact).font(.custom("NotoSans-Regular", size: 10)).foregroundStyle(.secondary).lineLimit(1)
							.accessibilityIdentifier("livechat.message.metadata.\(message.id)")
						Button { details = context; showDetails = true } label: { Image(systemName: "info.circle").font(.system(size: 12)).padding(5).contentShape(Rectangle()) }
							.accessibilityLabel("Message visitor details").accessibilityIdentifier("livechat.message.details.\(message.id)")
					}.frame(maxWidth: .infinity, alignment: message.direction == "outgoing" ? .trailing : .leading)
				}
				HStack(spacing: 7) {
					Text(Mango9ChatDateText.display(Mango9CalendarAPI.timestamp(message.created), inConversation: true)).lineLimit(1).minimumScaleFactor(0.8)
					if message.direction == "outgoing", store.site?.capabilities["readState"] as? Bool == true { Text(message.status.capitalized) }
					if !selectionMode {
						if !message.text.isEmpty {
							Button { copy(message) } label: { Image(systemName: copiedMessage == message.id ? "checkmark" : "doc.on.doc").padding(5) }
								.accessibilityLabel("Copy message").accessibilityIdentifier("livechat.message.copy.\(message.id)")
								.accessibilityValue(copiedMessage == message.id ? "Copied" : "")
						}
						if message.reactions.supported {
							Mango9MessageActionsButton { liveActions(message) }
							.accessibilityLabel("React to message").accessibilityIdentifier("livechat.message.react.\(message.id)")
							.disabled(store.reacting || store.pendingReaction != nil || !store.authorized)
						}
						Button { selectionMode = true; selectedMessages = [message.id]; focused = false } label: { Image(systemName: "checkmark.circle").padding(5) }
							.accessibilityLabel("Select message").accessibilityIdentifier("livechat.message.select.\(message.id)")
					}
				}.font(.custom("NotoSans-Regular", size: 10)).foregroundStyle(.secondary)
				if !message.reactions.items.isEmpty {
					LazyVGrid(columns: [GridItem(.adaptive(minimum: 48), spacing: 4)], alignment: .leading, spacing: 4) {
						ForEach(message.reactions.items) { item in
							Button { Task { await store.react(to: message, emoji: item.mine ? nil : item.emoji) } } label: {
								Text("\(item.emoji) \(item.count)").font(.system(size: 12)).padding(.horizontal, 7).padding(.vertical, 4)
									.background(item.mine ? Color.orangeMain500.opacity(0.15) : Color(UIColor.secondarySystemBackground))
									.clipShape(Capsule())
							}.accessibilityLabel("\(item.emoji), \(item.count) reactions\(item.mine ? ", including yours" : "")")
								.disabled(selectionMode || store.reacting || store.pendingReaction != nil || !store.authorized)
						}
					}
				}
			}.contentShape(Rectangle()).mango9MessageActions(enabled: !selectionMode) { liveActions(message) }
			if message.direction != "outgoing" { Spacer(minLength: 52) }
		}.mango9MessageSelection(active: selectionMode, selected: selectedMessages.contains(message.id)) {
			if !selectedMessages.insert(message.id).inserted { selectedMessages.remove(message.id) }
		}
	}
	private func copy(_ message: Mango9LiveMessage) {
		UIPasteboard.general.string = message.selectedText; copiedMessage = message.id
	}
	private func copySelected() {
		UIPasteboard.general.string = visibleMessages.filter { selectedMessages.contains($0.id) }.map(\.selectedText).filter { !$0.isEmpty }.joined(separator: "\n\n")
	}
 private func liveActions(_ message: Mango9LiveMessage) -> Mango9MessageActionSet {
  var actions = Mango9MessageActionSet()
  if message.reactions.supported, store.authorized, !store.reacting, store.pendingReaction == nil {
   actions.reactions = message.reactions.allowed; actions.allowsMoreReactions = false
   actions.selectedReactions = Set(message.reactions.items.filter(\.mine).map(\.emoji))
   let mine = actions.selectedReactions
   actions.react = { emoji in Task { await store.react(to: message, emoji: mine.contains(emoji) ? nil : emoji) } }
  }
  actions.actions.append(.init(title: "Copy", icon: "doc.on.doc", perform: { copy(message) }))
  actions.actions.append(.init(title: "Select", icon: "checkmark.circle", perform: { selectionMode = true; selectedMessages = [message.id]; focused = false }))
  actions.actions.append(.init(title: "Forward", icon: "arrowshape.turn.up.right", perform: { Task { await prepareForward([message]) } }))
  actions.actions.append(.init(title: "Delete for me", icon: "trash", destructive: true, perform: { selectedMessages = [message.id]; confirmingDelete = true }))
  return actions
 }
 private func prepareForward(_ messages: [Mango9LiveMessage]) async {
  guard !messages.isEmpty, !preparingFile else { return }
  let files = messages.flatMap(\.attachments)
  guard files.count <= 1 else { localError = "Forward attachments one message at a time."; return }
  preparingFile = true; defer { preparingFile = false }
  do {
   var attachments: [Attachment] = []
   if let file = files.first {
    let url = try await downloadFile(file)
    attachments = [Attachment(id: UUID().uuidString, name: url.lastPathComponent, url: url,
     type: (file["mime"] as? String ?? "").hasPrefix("audio/") ? .voiceRecording : .fileTransfer)]
   }
   let text = Mango9MessageText.forward(messages.map(\.selectedText))
   guard text.count <= 10000 else { throw Mango9LiveInputFailure(message: "Choose fewer messages to forward at once.") }
   forwardPayload = Mango9LiveForwardPayload(text: text, attachments: attachments)
  } catch { localError = error.localizedDescription }
 }

 private func openFile(_ file: [String: Any]) async {
  guard !preparingFile else { return }; preparingFile = true; defer { preparingFile = false }
  do {
   let url = try await downloadFile(file)
   if (file["mime"] as? String ?? "").hasPrefix("audio/") {
    try Mango9VoicePlaybackSession.shared.acquire(voicePreviewOwner) { previewURL = nil }
   }
   previewURL = url
  } catch { localError = error.localizedDescription }
 }
 private func downloadFile(_ file: [String: Any]) async throws -> URL {
   let cacheKey = String(describing: file["id"] ?? file["data_url"] ?? "")
   if let cached = downloadedFiles[cacheKey], Mango9SessionStore.isActive(store.session), FileManager.default.fileExists(atPath: cached.path) { return cached }
			guard let base = URL(string: store.session.smsChatApi), let path = file["data_url"] as? String,
				let url = URL(string: path, relativeTo: base)?.absoluteURL, url.scheme == "https", url.host == base.host, url.port == base.port,
				url.user == nil, url.password == nil, url.path.hasPrefix("/sms_chat_api/livechat/file/") else { throw Mango9LiveChatFailure(status: 400) }
			// A short-lived signed file URL carries its own authorization; no CRM bearer or cookies.
			let config = URLSessionConfiguration.ephemeral; config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
			let transport = URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
			defer { transport.invalidateAndCancel() }
			let (temporary, response) = try await transport.download(from: url)
			defer { try? FileManager.default.removeItem(at: temporary) }
			guard Mango9SessionStore.isActive(store.session), let http = response as? HTTPURLResponse, http.statusCode == 200,
				(try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 5 * 1024 * 1024 else { throw Mango9LiveChatFailure(status: 0) }
			let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
			try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
			let rawName = (file["name"] as? String ?? "Attachment").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
			let safeName = ["", ".", ".."].contains(rawName) ? "Attachment" : String(rawName.prefix(180))
			let target = directory.appendingPathComponent(safeName)
			try FileManager.default.moveItem(at: temporary, to: target); localError = nil
			downloadedDirectories.append(directory); downloadedFiles[cacheKey] = target
			return target
 }

	private func cleanDownloads() {
		for directory in downloadedDirectories { try? FileManager.default.removeItem(at: directory) }
		downloadedDirectories = []; downloadedFiles = [:]
	}
}

struct Mango9LiveVisitorDetails: View {
	let context: Mango9LiveVisitorContext
	let name: String
	let email: String
	@Environment(\.dismiss) private var dismiss
	private var visible: Mango9LiveVisitorContext { context }
	var body: some View {
		List {
			Section("Visitor") { Text(name); if !email.isEmpty { Text(email).textSelection(.enabled) }; Text("Visitor-provided identity · Not verified").font(.caption).foregroundStyle(.secondary) }
			Section("Visit details") {
				if visible.rows.isEmpty { Text("No visitor details are available with the current settings.").foregroundStyle(.secondary) }
				ForEach(visible.rows.indices, id: \.self) { index in
					VStack(alignment: .leading, spacing: 5) { Text(visible.rows[index].0).font(.caption).foregroundStyle(.secondary); Text(visible.rows[index].1).textSelection(.enabled) }.padding(.vertical, 3)
				}
			}
			Section { Text("Details follow this website’s privacy settings. Location is approximate; device details are browser-reported.").font(.caption).foregroundStyle(.secondary)
				if context.country["source"] as? String == "DB-IP" { Link("Location data by DB-IP", destination: URL(string: "https://db-ip.com")!) }
			}
		}.navigationTitle("Visitor details").navigationBarTitleDisplayMode(.inline)
		.toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
	}
}

struct Mango9LiveChatSettings: View {
	let session: Mango9Session
	@State private var sites: [Mango9LiveSite] = []
	@State private var preferences: [[String: Any]] = []
	@State private var error: String?
	@State private var busy = false
	@Environment(\.dismiss) private var dismiss
	var body: some View {
		List {
			if let error { Text(error); Button("Try again") { Task { await load() } } }
			if busy && sites.isEmpty { ProgressView() }
			if !busy && sites.isEmpty && error == nil { Text("No websites are connected to this account yet.").foregroundStyle(.secondary) }
			ForEach(sites) { site in
				Section(site.name) {
					LabeledContentCompat(label: "Website chat", value: site.enabled ? "Enabled" : "Disabled")
					Toggle("Push notifications", isOn: binding(site, field: "push")).disabled(busy)
					Toggle("Email notifications", isOn: binding(site, field: "email")).disabled(busy)
					ForEach([("typingIndicators", "Typing indicators"), ("readReceipts", "Read receipts"), ("attachmentsEnabled", "Attachments"),
						("trackCountry", "Approximate location"), ("collectIp", "IP address"), ("collectDevice", "Device details"), ("collectReferrer", "Referrer")], id: \.0) { key, title in
						LabeledContentCompat(label: title, value: site.settings[key] as? Bool == true ? "Enabled" : "Disabled")
					}
				}
			}
			Section { Text("Website settings and staff access are managed in Connections on the web. Notification preferences apply to your account for each website.").font(.footnote).foregroundStyle(.secondary) }
		}.navigationTitle("Live Chat settings").navigationBarTitleDisplayMode(.inline).tint(Color.orangeMain500)
		.task { await load() }.refreshable { await load() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in sites = []; preferences = []; dismiss() }
	}
	private func preference(_ site: Mango9LiveSite, field: String) -> Bool {
		// With no staff override both channels are allowed; website policy still gates delivery.
		preferences.first { $0["siteId"] as? String == site.id }?[field] as? Bool ?? true
	}
	private func binding(_ site: Mango9LiveSite, field: String) -> Binding<Bool> {
		Binding(get: { preference(site, field: field) }, set: { value in
			Task {
				busy = true; defer { busy = false }
				do {
					_ = try await Mango9LiveChatAPI.shared.request("preferences", session: session, body: ["siteId": site.id,
						"push": field == "push" ? value : preference(site, field: "push"), "email": field == "email" ? value : preference(site, field: "email")])
					await load()
				} catch { self.error = Mango9LiveChatAPI.message(error) }
			}
		})
	}
	private func load() async {
		busy = true; defer { busy = false }
		do {
			let data = try await Mango9LiveChatAPI.shared.request("sites", session: session)
			sites = (data["sites"] as? [[String: Any]] ?? []).compactMap(Mango9LiveSite.init); preferences = data["preferences"] as? [[String: Any]] ?? []; error = nil
		} catch { self.error = Mango9LiveChatAPI.message(error) }
	}
}

private struct LabeledContentCompat: View {
	let label: String
	let value: String
	var body: some View { HStack { Text(label); Spacer(); Text(value).foregroundStyle(.secondary) } }
}

private struct Mango9LiveForwardPayload: Identifiable {
 let id = UUID()
 let text: String
 let attachments: [Attachment]
}
private struct Mango9LiveForwardSheet: View {
 let session: Mango9Session
 let payload: Mango9LiveForwardPayload
 @StateObject private var inbox = Mango9LiveChatStore()
 @Environment(\.dismiss) private var dismiss
 @State private var search = ""
 var body: some View {
  NavigationView {
   List {
    Text("Choose a conversation, then review the message and visitor details before sending.").font(.footnote)
    if let error = inbox.error { Text(error); Button("Retry") { Task { await inbox.load(search) } } }
    ForEach(inbox.conversations) { conversation in
     NavigationLink(conversation.title + " · " + conversation.siteName) {
      Mango9LiveChatConversation(conversation: conversation, session: session, initialText: payload.text, initialAttachments: payload.attachments)
     }
    }
   }.searchable(text: $search).navigationTitle("Forward message").navigationBarTitleDisplayMode(.inline)
    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
    .task(id: search) { try? await Task.sleep(nanoseconds: 250_000_000); guard !Task.isCancelled else { return }; await inbox.load(search) }
  }.navigationViewStyle(.stack)
   .onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
 }
}

/// Reuses the native file preview/export flow; images have the same thumbnail
/// size as Team Chat. Downloads still go through the account-scoped signed URL.
private struct Mango9LiveAttachmentPreview: View {
 let file: [String: Any]
 let load: () async throws -> URL
 let open: () -> Void
 @State private var image: UIImage?
 private var name: String { file["name"] as? String ?? "Attachment" }
 private var mime: String { file["mime"] as? String ?? "" }
 var body: some View {
  Button(action: open) {
   if let image {
    Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: 230, maxHeight: 240)
     .clipShape(RoundedRectangle(cornerRadius: 10))
   } else {
    Label(name, systemImage: mime.hasPrefix("audio/") ? "waveform" : (mime.hasPrefix("image/") ? "photo" : "doc.fill"))
     .font(.system(size: 14)).lineLimit(2).padding(10)
     .background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12))
   }
  }.buttonStyle(.plain).accessibilityLabel(name)
   .accessibilityIdentifier("livechat.attachment." + name)
   .task {
    guard mime.hasPrefix("image/"), let url = try? await load(),
     let source = CGImageSourceCreateWithURL(url as CFURL, nil),
     let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceThumbnailMaxPixelSize: 700
     ] as CFDictionary) else { return }
    image = UIImage(cgImage: thumbnail)
   }
 }
}
