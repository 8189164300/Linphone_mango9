import Contacts
import SwiftUI

/// Debounces address-book notifications and serializes automatic reads. A change
/// during a read gets one follow-up; background events wait until the app returns.
@MainActor final class Mango9ContactsAutoRefresh {
	private let readAccess: () -> Mango9ContactAccess
	private let ready: () -> Bool
	private let reload: (@escaping @MainActor () -> Void) -> Void
	private let delay: UInt64
	private var task: Task<Void, Never>?
	private var pending = false
	private var running = false
	private var active = false
	private var lastAccess: Mango9ContactAccess?

	init(readAccess: @escaping () -> Mango9ContactAccess, ready: @escaping () -> Bool,
		 delay: UInt64 = 600_000_000, reload: @escaping (@escaping @MainActor () -> Void) -> Void) {
		self.readAccess = readAccess; self.ready = ready; self.delay = delay; self.reload = reload
	}
	func becameActive() {
		let access = readAccess()
		let changed = lastAccess != access
		lastAccess = access
		let returned = !active
		active = true
		if returned || changed { requestRefresh() }
	}
	func enteredBackground() { active = false; task?.cancel(); task = nil }
	func requestRefresh() {
		pending = true
		task?.cancel()
		task = Task { [weak self] in
			guard let self else { return }
			do { try await Task.sleep(nanoseconds: self.delay) } catch { return }
			self.flush()
		}
	}
	private func flush() {
		guard active, ready(), pending, !running else { return }
		pending = false
		// Automatic refresh never presents a permission prompt. A revoked grant
		// still refreshes the native cache to discard no-longer-readable contacts.
		guard readAccess() != .notRequested else { return }
		running = true
		var finished = false
		reload { [weak self] in
			guard !finished, let self else { return }
			finished = true; self.running = false
			if self.pending { self.requestRefresh() }
		}
	}
}

enum Mango9ContactAccess: CaseIterable {
	case notRequested, full, limited, denied, restricted, unavailable

	static var current: Self { from(CNContactStore.authorizationStatus(for: .contacts)) }
	static func from(_ status: CNAuthorizationStatus) -> Self {
		if #available(iOS 18.0, *), status == .limited { return .limited }
		switch status {
		case .notDetermined: return .notRequested
		case .authorized: return .full
		case .denied: return .denied
		case .restricted: return .restricted
		default: return .unavailable
		}
	}
	var canRead: Bool { self == .full || self == .limited }
	var title: String {
		switch self {
		case .notRequested: return "Access not requested"
		case .full: return "Full access"
		case .limited: return "Selected contacts only"
		case .denied: return "Contact access is off"
		case .restricted: return "Access restricted"
		case .unavailable: return "Access unavailable"
		}
	}
	var explanation: String {
		switch self {
		case .notRequested: return "Allow access to see your iPhone contacts in Mango9 and recognize people who call or message you."
		case .full: return "Mango9 can read your iPhone contacts. You can change access in iPhone Settings."
		case .limited: return "Only contacts you selected are available. Use iPhone Settings to change your selection or allow full access."
		case .denied: return "To use your iPhone contacts, open Settings, choose Contacts for Mango9, and allow access."
		case .restricted: return "Screen Time or device-management restrictions prevent contact access. Mango9 cannot override these restrictions."
		case .unavailable: return "Contact access is currently unavailable. Check Mango9's permissions in iPhone Settings."
		}
	}
}

/// Uses the live iOS permission, not the onboarding checkbox. No contact writes,
/// CRM preference changes, or new directory/synchronization backend.
@MainActor final class Mango9ContactsSettingsModel: ObservableObject {
	@Published private(set) var access: Mango9ContactAccess
	@Published private(set) var requesting = false
	@Published private(set) var reloading = false
	@Published private(set) var message: String?
	@Published private(set) var failed = false
	var busy: Bool { requesting || reloading }
	var accessActionTitle: String { access == .notRequested ? "Allow Contacts Access" : "Open iPhone Settings" }
	private let readStatus: () -> Mango9ContactAccess
	private let request: (@escaping @MainActor (Error?) -> Void) -> Void
	private let reload: (@escaping @MainActor (Bool) -> Void) -> Void
	private let openSettings: () -> Void
	private var returningFromSettings = false
	private var reloadAfterCurrent = false
	private var operation: UUID?
	private var timeout: Task<Void, Never>?

	convenience init() {
		self.init(readStatus: { .current }, request: { completion in
			CNContactStore().requestAccess(for: .contacts) { _, error in
				Task { @MainActor in completion(error) }
			}
		}, reload: { completion in
			guard CoreContext.shared.coreIsStarted else { completion(false); return }
			ContactsManager.shared.fetchContacts(requireFreshSnapshot: true) { success in
				Task { @MainActor in completion(success) }
			}
		}, openSettings: {
			if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
		})
	}

	init(readStatus: @escaping () -> Mango9ContactAccess,
		 request: @escaping (@escaping @MainActor (Error?) -> Void) -> Void,
		 reload: @escaping (@escaping @MainActor (Bool) -> Void) -> Void,
		 openSettings: @escaping () -> Void) {
		self.readStatus = readStatus; self.request = request; self.reload = reload; self.openSettings = openSettings
		access = readStatus()
	}

	func refreshAccess() {
		let previous = access
		access = readStatus()
		PermissionManager.shared.contactsPermissionGranted = access.canRead
		let shouldReload = returningFromSettings || previous != access
		returningFromSettings = false
		if previous != access { message = nil; failed = false }
		// A revoked permission also refreshes the native cache, removing contacts
		// that can no longer be read. CRM and saved Mango9 contacts are untouched.
		if shouldReload && access != .notRequested && !requesting {
			if reloading { reloadAfterCurrent = true } else { beginReload() }
		}
	}

	func manageAccess() {
		guard !busy else { return }
		access = readStatus()
		guard access == .notRequested else {
			returningFromSettings = true; openSettings(); return
		}
		requesting = true; message = nil; failed = false
		request { [weak self] error in
			guard let self, self.requesting else { return }
			self.requesting = false
			self.access = self.readStatus()
			PermissionManager.shared.contactsPermissionGranted = self.access.canRead
			if error != nil {
				self.failed = true; self.message = "Unable to request contact access. Please try again."
			} else if self.access.canRead { self.beginReload() }
		}
	}

	func reloadContacts() {
		guard !busy else { return }
		guard readStatus().canRead else { refreshAccess(); return }
		access = readStatus()
		beginReload()
	}

	private func beginReload() {
		guard !busy else { return }
		let id = UUID(); operation = id
		reloading = true; message = nil; failed = false
		timeout?.cancel()
		timeout = Task { [weak self] in
			do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
			self?.finishReload(id: id, success: false)
		}
		reload { [weak self] success in self?.finishReload(id: id, success: success) }
	}

	private func finishReload(id: UUID, success: Bool) {
		guard operation == id else { return }
		operation = nil; timeout?.cancel(); timeout = nil; reloading = false
		access = readStatus()
		PermissionManager.shared.contactsPermissionGranted = access.canRead
		if reloadAfterCurrent {
			reloadAfterCurrent = false
			if access != .notRequested { beginReload(); return }
		}
		guard access.canRead else { message = nil; failed = false; return }
		failed = !success
		message = success ? "Contacts reloaded." : "Contacts couldn't be reloaded. Please try again when Mango9 is ready."
	}
}

struct Mango9ContactsAccessSettings: View {
	@ObservedObject var model: Mango9ContactsSettingsModel
	var body: some View {
		VStack(alignment: .leading, spacing: 16) {
			Label("iPhone contacts", systemImage: "person.crop.circle.badge.checkmark")
				.font(.headline).foregroundColor(.mango9Primary)
			Text(model.access.title).font(.subheadline.weight(.semibold))
				.accessibilityIdentifier("contacts.accessStatus")
			Text(model.access.explanation).font(.footnote).foregroundColor(.secondary)
				.fixedSize(horizontal: false, vertical: true)
			Button(action: model.manageAccess) {
				Label(model.accessActionTitle, systemImage: model.access == .notRequested ? "lock.open" : "gearshape")
					.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
			}.foregroundColor(.mango9Primary).disabled(model.busy).accessibilityIdentifier("contacts.manageAccess")
			Button(action: model.reloadContacts) {
				HStack {
					Label("Reload Contacts", systemImage: "arrow.clockwise")
					Spacer()
					ProgressView().frame(width: 22, height: 22).opacity(model.reloading ? 1 : 0)
				}.frame(minHeight: 44)
			}.foregroundColor(.mango9Primary).disabled(model.busy || !model.access.canRead).accessibilityIdentifier("contacts.reload")
			Text(model.message ?? "Contacts refresh automatically when they change and when you return to Mango9. You can also reload them here. Refreshing doesn't change contacts on your iPhone or in your CRM.")
				.font(.footnote).foregroundColor(model.failed ? .red : .secondary)
				.fixedSize(horizontal: false, vertical: true)
		}
		.buttonStyle(.plain).tint(.mango9Primary)
		.onAppear { model.refreshAccess() }
		.onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in model.refreshAccess() }
	}
}
