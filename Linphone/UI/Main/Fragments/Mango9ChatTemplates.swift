// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

struct Mango9ChatTemplate: Decodable, Identifiable {
    struct Permissions: Decodable { let canUse: Bool; let canUpdate: Bool; let canDelete: Bool }
    let id: Int
    let title: String
    let preview: String?
    let content: String?
    let revision: String?
    let permissions: Permissions
}

struct Mango9TemplateLibrary: Decodable {
    struct Permissions: Decodable { let canList: Bool; let canUse: Bool; let canCreate: Bool }
    struct Limits: Decodable {
        struct TextLimit: Decodable { let maxUtf8Bytes: Int }
        let title: TextLimit; let content: TextLimit
    }
    struct Pagination: Decodable { let page: Int; let limit: Int; let total: Int }
    let permissions: Permissions
    let limits: Limits
    let templates: [Mango9ChatTemplate]?
    let template: Mango9ChatTemplate?
    let pagination: Pagination?
}

struct Mango9TemplatePresentation: Identifiable {
    let id = UUID()
    let session: Mango9Session
}

enum Mango9TemplateText {
    static func inserting(_ template: String, into draft: String) -> String {
        guard !draft.isEmpty else { return template }
        return draft + (draft.last?.isWhitespace == true ? "" : "\n") + template
    }
    static func valid(title: String, content: String, titleBytes: Int, contentBytes: Int) -> Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        title.utf8.count <= titleBytes && content.utf8.count <= contentBytes
    }
}

@MainActor final class Mango9TemplateAPI {
    let session: Mango9Session
    private let transport: URLSession
    init(session: Mango9Session, transport: URLSession? = nil) {
        self.session = session
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
        self.transport = transport ?? URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
    }
    var active: Bool {
        guard let current = Mango9SessionStore.load() else { return false }
        return Mango9SessionStore.isActive(session) &&
            Mango9CalendarAPI.accountKey(current) == Mango9CalendarAPI.accountKey(session) &&
            current.enrollmentExpiresAt == session.enrollmentExpiresAt
    }
    static func wire(session: Mango9Session, path: String = "", method: String = "GET", query: [URLQueryItem] = [], body: [String: Any]? = nil) throws -> URLRequest {
        guard let base = URL(string: session.crmApiBaseUrl), base.scheme == "https", base.host != nil,
            base.user == nil, base.password == nil, base.query == nil,
            var parts = URLComponents(url: base.appendingPathComponent("mobile/messaging/templates" + (path.isEmpty ? "" : "/" + path)), resolvingAgainstBaseURL: false)
        else { throw Mango9MessagingError.invalidResponse }
        parts.queryItems = query.isEmpty ? nil : query
        guard let url = parts.url else { throw Mango9MessagingError.invalidResponse }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        request.httpMethod = method; request.httpShouldHandleCookies = false
        request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return request
    }
    func request(path: String = "", method: String = "GET", query: [URLQueryItem] = [], body: [String: Any]? = nil) async throws -> Mango9TemplateLibrary {
        for attempt in 0...1 {
            guard active, !Task.isCancelled, let current = Mango9SessionStore.load() else { throw Mango9MessagingError.scopeChanged }
            let request = try Self.wire(session: current, path: path, method: method, query: query, body: body)
            let (data, response) = try await transport.data(for: request)
            guard active, !Task.isCancelled else { throw Mango9MessagingError.scopeChanged }
            guard let http = response as? HTTPURLResponse, data.count <= 2_097_152 else { throw Mango9MessagingError.invalidResponse }
            if http.statusCode == 401 && attempt == 0 {
                let refreshed = try await Mango9CRMAPI.refresh(session: current, transport: transport)
                guard active else { throw Mango9MessagingError.scopeChanged }
                try Mango9SessionStore.save(refreshed); continue
            }
            guard (200..<300).contains(http.statusCode) else {
                let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
                throw Mango9MessagingError.rejected(http.statusCode, (json?["error"] as? [String: Any])?["code"] as? String ?? "template_service_unavailable")
            }
            let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
            let envelope = try decoder.decode(Mango9MessagingAPI.Envelope<Mango9TemplateLibrary>.self, from: data)
            guard envelope.success, let value = envelope.data else { throw Mango9MessagingError.invalidResponse }
            return value
        }
        throw Mango9MessagingError.invalidResponse
    }
}

@MainActor final class Mango9TemplatePickerModel: ObservableObject {
    enum Page { case list, detail, editor }
    @Published var page = Page.list
    @Published var search = ""
    @Published var rows: [Mango9ChatTemplate] = []
    @Published var selected: Mango9ChatTemplate?
    @Published var title = ""
    @Published var content = ""
    @Published var busy = false
    @Published var loaded = false
    @Published var error: String?
    @Published var metadata: Mango9TemplateLibrary?
    @Published var unconfirmedSave = false
    private var listPage = 0
    private var total = 0
    private var generation = 0
    private var command: (path: String, method: String, body: [String: Any])?
    private var editing: Mango9ChatTemplate?
    let api: Mango9TemplateAPI
    init(session: Mango9Session) { api = Mango9TemplateAPI(session: session) }
    var more: Bool { rows.count < total }
    var canCreate: Bool { metadata?.permissions.canCreate == true && api.active }
    var titleLimit: Int { metadata?.limits.title.maxUtf8Bytes ?? 255 }
    var contentLimit: Int { metadata?.limits.content.maxUtf8Bytes ?? 10000 }
    var valid: Bool { Mango9TemplateText.valid(title: title, content: content, titleBytes: titleLimit, contentBytes: contentLimit) }
    var editingSaved: Bool { editing != nil }
    var canInsert: Bool { api.active && selected?.permissions.canUse == true && selected?.content != nil && !busy }
    func notice(_ e: Error) -> String {
        if !api.active { return "Your account changed. Close Templates and open it again in the current chat." }
        if case Mango9MessagingError.rejected(let code, _) = e {
            switch code {
            case 403: return "Templates are unavailable for this account. Ask your administrator to check template permissions."
            case 404: return "This template is no longer available. Return to the list and refresh."
            case 409: return "This template changed on another device. Your text is kept here. Go back and reload before saving changes."
            case 400, 413: return "Check the title and message length, then try again."
            default: break
            }
        }
        return "Couldn’t load or save templates. Check your connection and try again."
    }
    func load(more: Bool = false) async {
        generation += 1; let stamp = generation
        let next = more ? listPage + 1 : 1
        if !more { loaded = false; rows = [] }
        busy = true; error = nil
        do {
            let value = try await api.request(query: [.init(name: "page", value: String(next)), .init(name: "search", value: search)])
            guard stamp == generation, api.active else { return }
            metadata = value; rows = more ? rows + (value.templates ?? []) : value.templates ?? []
            listPage = next; total = value.pagination?.total ?? rows.count; loaded = true
        } catch { if stamp == generation && !(error is CancellationError) { self.error = notice(error) } }
        if stamp == generation { busy = false }
    }
    func select(_ row: Mango9ChatTemplate) async {
        page = .detail; selected = nil; busy = true; error = nil
        do { let result = try await api.request(path: String(row.id)); metadata = result; selected = result.template }
        catch { self.error = notice(error) }
        busy = false
    }
    func edit(_ row: Mango9ChatTemplate? = nil) {
        editing = row; title = row?.title ?? ""; content = row?.content ?? ""
        command = nil; unconfirmedSave = false; error = nil; page = .editor
    }
    func back() {
        error = nil
        if page == .editor && editing != nil { page = .detail } else { page = .list }
    }
    func save() async {
        guard api.active, !busy, valid else { return }
        if command == nil {
            var body: [String: Any] = ["requestId": UUID().uuidString.lowercased(), "values": ["title": title, "content": content]]
            if let editing { body["revision"] = editing.revision }
            command = (editing.map { String($0.id) } ?? "", editing == nil ? "POST" : "PATCH", body)
        }
        guard let command else { return }
        busy = true; error = nil
        do {
            let result = try await api.request(path: command.path, method: command.method, body: command.body)
            metadata = result; selected = result.template; self.command = nil; unconfirmedSave = false; page = .detail
            loaded = false
        } catch {
            self.error = notice(error)
            if case Mango9MessagingError.rejected(let status, _) = error, (400..<500).contains(status) {
                self.command = nil; unconfirmedSave = false
            } else { unconfirmedSave = true }
        }
        busy = false
    }
}

struct Mango9TemplatePicker: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    private var compact: Bool { verticalSizeClass == .compact }
    @StateObject private var model: Mango9TemplatePickerModel
    private let insert: (String) -> Void
    @FocusState private var editorFocused: Bool
    @State private var confirmDiscard = false
    init(session: Mango9Session, insert: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: Mango9TemplatePickerModel(session: session)); self.insert = insert
    }
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                if let error = model.error {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error).font(.callout)
                        if model.page == .list { Button("Retry") { Task { await model.load() } } }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(Color(uiColor: .secondarySystemBackground))
                }
                if model.page == .list { library }
                else if model.page == .detail { detail }
                else { editor }
            }
            .navigationTitle(model.page == .list ? "Templates" : model.page == .detail ? "Template" : model.editingSaved ? "Edit template" : "Create template")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(model.page == .list ? "Done" : "Back") {
                        if model.page == .editor && (!model.content.isEmpty || !model.title.isEmpty || model.unconfirmedSave) { confirmDiscard = true }
                        else if model.page == .list { dismiss() } else { model.back() }
                    }.disabled(model.busy)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if model.page == .editor {
                        Button(model.unconfirmedSave ? "Retry save" : "Save") { editorFocused = false; Task { await model.save() } }
                            .disabled(!model.valid || model.busy).accessibilityIdentifier("templates.save")
                    } else if model.page == .list {
                        Button { model.edit() } label: { Image(systemName: "plus") }.disabled(!model.canCreate || model.busy).accessibilityLabel("Create template")
                    } else if model.selected?.permissions.canUpdate == true {
                        Button("Edit") { model.edit(model.selected) }.disabled(model.busy).accessibilityIdentifier("templates.edit")
                    }
                }
            }
        }.navigationViewStyle(.stack).tint(Color.orangeMain500)
            .interactiveDismissDisabled(model.busy || model.unconfirmedSave)
            .task(id: model.search) {
                guard model.page == .list else { return }
                do { if !model.search.isEmpty { try await Task.sleep(nanoseconds: 300_000_000) }; try Task.checkCancellation(); await model.load() } catch {}
            }
            .onChange(of: model.page) { page in if page == .list && !model.loaded { Task { await model.load() } } }
            .confirmationDialog(model.unconfirmedSave ? "This save is not confirmed. Retry it before closing to avoid creating a duplicate." : "Discard unsaved edits?", isPresented: $confirmDiscard, titleVisibility: .visible) {
                if !model.unconfirmedSave { Button("Discard edits", role: .destructive) { model.back() } }
                Button("Keep editing", role: .cancel) {}
            }
    }
    private var library: some View {
        VStack(spacing: 0) {
            HStack { Image(systemName: "magnifyingglass"); TextField("Search templates", text: $model.search).textInputAutocapitalization(.never).autocorrectionDisabled() }
                .padding(12).background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 12)).padding()
            List {
                Section {
                    Button { model.edit() } label: { Label("Create template", systemImage: "plus.circle") }.disabled(!model.canCreate || model.busy)
                    ForEach(model.rows) { row in
                        Button { Task { await model.select(row) } } label: {
                            HStack { VStack(alignment: .leading, spacing: 5) { Text(row.title).font(.headline).foregroundStyle(.primary); Text(row.preview ?? "").font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }; Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) }.padding(.vertical, 5)
                        }.disabled(model.busy).accessibilityIdentifier("templates.row.\(row.id)")
                    }
                    if model.busy { ProgressView("Loading templates…") }
                    else if model.loaded && model.rows.isEmpty { Text(model.search.isEmpty ? "No templates yet. Create your first template." : "No matching templates.").foregroundStyle(.secondary) }
                    if model.more && !model.busy { Button("Load more") { Task { await model.load(more: true) } } }
                } header: { Text("My templates") } footer: { Text("Saved to your account. Choose a template, then Insert or Edit.") }
            }.listStyle(.insetGrouped).refreshable { await model.load() }
        }
    }
    private var detail: some View {
        VStack(alignment: .leading, spacing: 16) {
            if model.busy { ProgressView("Loading template…") }
            if let row = model.selected {
                Text(row.title).font(.title2.bold())
                ScrollView { Text(row.content ?? "").textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding().background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 14)) }
                Text("\((row.content ?? "").count) characters").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .trailing)
                Button { if model.canInsert, let text = row.content { insert(text); dismiss() } } label: {
                    Label("Insert", systemImage: "text.badge.plus").frame(maxWidth: .infinity).padding(8)
                }.buttonStyle(.borderedProminent).disabled(!model.canInsert).accessibilityIdentifier("templates.insert")
                Text("You can edit the message in the composer before sending.").font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }.padding()
    }
    private var editor: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 12) {
            if !compact {
                Label("Saves to your account", systemImage: "person.crop.circle").font(.caption).foregroundStyle(.secondary)
                Text("Title").font(.headline)
            }
            HStack {
                if compact { Text("Title").font(.caption) }
                TextField("Template name", text: $model.title).textFieldStyle(.roundedBorder).accessibilityIdentifier("templates.title")
            }
            if !compact { Text("Message").font(.headline) }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $model.content).focused($editorFocused).accessibilityIdentifier("templates.content")
                if model.content.isEmpty { Text("Type your template…").foregroundStyle(.secondary).padding(.horizontal, 5).padding(.vertical, 8).allowsHitTesting(false) }
            }.padding(compact ? 4 : 8).overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.25))).frame(minHeight: compact ? 44 : 100)
            HStack { Spacer(); Text("\(model.content.count) characters").font(.caption).foregroundStyle(.secondary) }
            if model.content.utf8.count > model.contentLimit || model.title.utf8.count > model.titleLimit { Text("This template is too long. Shorten the title or message to save it.").font(.footnote).foregroundStyle(.red) }
            if model.unconfirmedSave { Text("Save not confirmed. Retry the same save; your text is preserved.").font(.footnote) }
            if model.busy { ProgressView("Saving…") }
        }.padding(.horizontal, 16).padding(.vertical, compact ? 6 : 16).disabled(model.busy || model.unconfirmedSave)
    }
}

private struct Mango9ComposerMenuOrder: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 16.0, *) { content.menuOrder(.fixed) } else { content }
    }
}
extension View {
    func mango9FixedComposerMenuOrder() -> some View { modifier(Mango9ComposerMenuOrder()) }
}
