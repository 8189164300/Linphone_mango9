import Contacts
import CallKit
import Combine
import SwiftUI
import XCTest
import linphonesw
@testable import LinphoneApp

@MainActor final class Mango9ContactsSettingsTests: XCTestCase {
	func testContactLabelsPreserveCustomUnicodeAndLocalizeSystemTokens() {
		for custom in ["", "mobile", "Work direct", "Մոբայլ", "мобильный", "للهاتف", "A"] {
			XCTAssertEqual(Mango9ContactLabel.localized(custom), custom)
		}
		for system in [CNLabelPhoneNumberMobile, CNLabelHome, CNLabelWork, CNLabelPhoneNumberiPhone] {
			XCTAssertEqual(Mango9ContactLabel.localized(system), CNLabeledValue<NSString>.localizedString(forLabel: system))
			XCTAssertFalse(Mango9ContactLabel.localized(system).contains("_$!"))
		}
	}

	func testOutgoingCallKitKeepsNumbersInHandlesNotNameOverrides() {
		for (input, expected) in [
			("202-555-0142", "2025550142"), ("+1 (202) 555-0142", "+12025550142"),
			("+971 55 555 0142", "+971555550142"), ("+44 20 7946 0018", "+442079460018"),
			("+49 123 456789", "+49123456789"), ("700", "700")
		] {
			let handle = Mango9OutgoingCallPresentation.handle(input)
			let update = Mango9OutgoingCallPresentation.update(handle: handle, displayName: input)
			XCTAssertEqual(handle.type, .phoneNumber)
			XCTAssertEqual(handle.value, expected, "Presentation must not guess a country code or change the number")
			XCTAssertNil(update.localizedCallerName, "Allow iOS to format phone numbers and resolve its own contacts")
			XCTAssertEqual(update.remoteHandle?.value, expected)
		}
		for placeholder in ["Unknown", "Anonymous", "sip:alice@example.invalid", "sips:alice@example.invalid", "alice@example.invalid", "tel:+12025550142"] {
			XCTAssertNil(Mango9OutgoingCallPresentation.callerName(placeholder))
		}
		for name in ["Morgan Taylor", "Studio 54", "Արամ", "مريم"] {
			XCTAssertEqual(Mango9OutgoingCallPresentation.callerName(name), name)
		}
		let sip = Mango9OutgoingCallPresentation.handle("sip:alice@example.invalid")
		XCTAssertEqual(sip.type, .generic)
		XCTAssertEqual(sip.value, "sip:alice@example.invalid")
		XCTAssertEqual(Mango9OutgoingCallPresentation.update(handle: sip, displayName: "Morgan Taylor").localizedCallerName, "Morgan Taylor")
		for uri in ["sip:700@example.invalid", "sips:700@example.invalid", "123@example.invalid", "sip:+12025550142@example.invalid;user=phone"] {
			XCTAssertEqual(Mango9OutgoingCallPresentation.handle(uri).type, .generic)
			XCTAssertEqual(Mango9OutgoingCallPresentation.handle(uri).value, uri, "Preserve the full destination for SIP redial")
		}
	}

	func testNativeStyleContactPageRendersAtSmallAndAccessibleSizes() async throws {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		let original = SharedMainViewModel.shared.displayedFriend
		defer { window.isHidden = true; SharedMainViewModel.shared.displayedFriend = original }
		let model = ContactAvatarModel(friend: nil, name: "Morgan Taylor", address: "", withPresence: false)
		model.phoneNumbersWithLabel = [(label: "mobile", phoneNumber: "+1 (202) 555-0142")]
		model.emails = ["morgan.taylor@example.invalid"]
		model.removalSource = .iPhone
		model.nativeUri = "native-display-fixture"
		model.editable = false
		SharedMainViewModel.shared.displayedFriend = model
		let actions = ContactsListViewModel()
		for (width, height, size, scheme) in [
			(393.0, 852.0, DynamicTypeSize.large, ColorScheme.light),
			(320.0, 900.0, .accessibility3, .light),
			(393.0, 852.0, .large, .dark)
		] {
			window.frame = CGRect(x: 0, y: 0, width: width, height: height)
			window.rootViewController = UIHostingController(rootView:
				ContactInnerFragment(isShowDeletePopup: .constant(false), showingSheet: .constant(false),
					showShareSheet: .constant(false), isShowDismissPopup: .constant(false),
					isShowSipAddressesPopup: .constant(false), isShowSipAddressesPopupType: .constant(0),
					isShowEditContactFragmentInContactDetails: .constant(false))
				.environmentObject(model).environmentObject(actions).dynamicTypeSize(size).environment(\.colorScheme, scheme))
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 500_000_000)
			XCTAssertNotNil(window.rootViewController?.view)
			let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
			let attachment = XCTAttachment(image: image)
			attachment.name = "Native style contact \(width) \(size) \(scheme)"
			attachment.lifetime = .keepAlways; add(attachment)
		}
	}

	func testSDKSearchRefreshesAfterNativeImportAndDeletion() async throws {
		try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
			coreQueue.async {
				do {
					let config = try XCTUnwrap(Config.newFromBuffer(buffer: "[storage]\nuri=null\nfriends_db_uri=null\n"))
					let core = try Factory.Instance.createCoreWithConfig(config: config, systemContext: nil)
					core.autoIterateEnabled = false
					let list = try core.createFriendList(); list.displayName = "Native address-book"
					list.databaseStorageEnabled = false; core.addFriendList(list: list)
					let search = try core.createMagicSearch(); search.limitedSearch = false
					func results() -> [SearchResult] {
						search.getContactsList(filter: "", domain: "", sourceFlags: MagicSearch.Source.Friends.rawValue, aggregation: .Friend)
					}
					func add(_ index: Int) throws {
						let friend = try core.createFriend()
						try friend.setName(newValue: "Fixture \(index)")
						friend.nativeUri = "fixture-\(index)"
						friend.addPhoneNumber(phoneNumber: String(format: "+1202555%04d", index))
						XCTAssertEqual(list.addLocalFriend(linphoneFriend: friend), .OK)
					}
					for index in 0..<3 { try add(index) }
					XCTAssertEqual(results().count, 3)
					for index in 3..<5_000 { try add(index) }
					XCTAssertEqual(list.friends.count, 5_000)
					XCTAssertEqual(results().count, 5_000, "A same-query search must reflect the completed native import")
					let imported = results()
					let visible = MagicSearchSingleton.uniqueFriendResults(imported + imported)
					XCTAssertEqual(visible.count, 5_000, "Deduplication must use SDK identity, not reused temporary Swift wrapper addresses")
					XCTAssertEqual(Set(visible.compactMap { $0.friend?.nativeUri }).count, 5_000)
					for friend in list.friends.prefix(3) { XCTAssertEqual(list.removeFriend(linphoneFriend: friend), .OK) }
					XCTAssertEqual(MagicSearchSingleton.uniqueFriendResults(results()).count, 4_997, "Deleted contacts must not remain in the search cache")
					continuation.resume()
				} catch { continuation.resume(throwing: error) }
			}
		}
	}

	func testLargeImportFetchesTextOnlyAndUsesBoundedBatches() {
		let keys = ContactsManager.nativeFetchKeys.compactMap { $0 as? String }
		XCTAssertTrue(keys.contains(CNContactPhoneNumbersKey))
		XCTAssertTrue(keys.contains(CNContactEmailAddressesKey))
		XCTAssertFalse(keys.contains(CNContactThumbnailImageDataKey))
		XCTAssertFalse(keys.contains(CNContactImageDataKey))
		XCTAssertFalse(keys.contains(CNContactPostalAddressesKey))
		XCTAssertEqual(ContactsManager.nativeImportBatchSize, 64)
	}
	func testTwentyThousandContactImportHasOneQueuedBatchAndPreservesOrder() {
		var jobs: [() -> Void] = []; var imported: [Int] = []; var completions: [Bool] = []; var largestBatch = 0
		Mango9ContactBatchImport.run(Array(0..<20_000), batchSize: ContactsManager.nativeImportBatchSize,
			schedule: { jobs.append($0) }, apply: { values in
				largestBatch = max(largestBatch, values.count); imported.append(contentsOf: values); return true
			}, completion: { completions.append($0) })
		while !jobs.isEmpty { XCTAssertEqual(jobs.count, 1); jobs.removeFirst()() }
		XCTAssertEqual(largestBatch, 64); XCTAssertEqual(imported, Array(0..<20_000)); XCTAssertEqual(completions, [true])
	}
	func testImportStopsBeforeNextBatchAfterPermissionOrCoreLoss() {
		var jobs: [() -> Void] = []; var calls = 0; var completions: [Bool] = []
		Mango9ContactBatchImport.run(Array(0..<20_000), batchSize: 64, schedule: { jobs.append($0) },
			apply: { _ in calls += 1; return calls < 3 }, completion: { completions.append($0) })
		while !jobs.isEmpty { jobs.removeFirst()() }
		XCTAssertEqual(calls, 3); XCTAssertEqual(completions, [false])
	}

	func testInitialsDoNotUseNamesAsFilesystemPaths() {
		XCTAssertEqual(Mango9ContactInitials.initials("Taylor Reed"), "TR")
		XCTAssertEqual(Mango9ContactInitials.initials("Music/Studio ../Contact"), "MC")
		XCTAssertEqual(Mango9ContactInitials.initials("+1 (202) 555-0100"), "")
		XCTAssertEqual(Mango9ContactInitials.initials(""), "")
	}

	func testLargeContactSnapshotIsStableAndDoesNotRepublishEveryFavorite() async throws {
		let manager = ContactsManager.shared
		let original = manager.avatarListModel
		defer { manager.avatarListModel = original }
		let contacts = (0..<20_000).map { ContactAvatarModel(friend: nil, name: String(format: "Person %05d", $0), address: "", withPresence: false) }
		var notifications = 0
		let subscription = manager.$starredChangeTrigger.dropFirst().sink { _ in notifications += 1 }
		defer { subscription.cancel() }
		manager.avatarListModel = contacts
		XCTAssertEqual(notifications, 0, "Installing 20,000 contacts must not emit 20,000 initial favorite changes")
		let rows = ContactsListFragment.rows(contacts)
		XCTAssertEqual(rows.count, 20_000)
		XCTAssertEqual(rows.first?.heading, "P")
		XCTAssertTrue(rows.dropFirst().allSatisfy { $0.heading.isEmpty })
		XCTAssertEqual(Set(rows.map(\.id)).count, 20_000)
		// An already-rendered row never indexes into the newly shortened list.
		manager.avatarListModel = Array(contacts.prefix(1))
		XCTAssertEqual(rows.last?.contact.name, "Person 19999")
		contacts[0].starred = true
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertEqual(notifications, 1)
	}

	func testContactsTabRendersTenThousandRowsAndRefreshesWithoutLosingIdentity() async throws {
		let manager = ContactsManager.shared
		let original = manager.avatarListModel
		let contacts = (0..<10_000).map { ContactAvatarModel(friend: nil, name: String(format: "Contact %05d", $0), address: "", withPresence: false) }
		manager.avatarListModel = contacts
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true; window.rootViewController = nil; manager.avatarListModel = original }
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		window.rootViewController = UIHostingController(rootView:
			ContactsInnerFragment(showingSheet: .constant(false), text: .constant(""))
				.environmentObject(ContactsListViewModel()))
		window.makeKeyAndVisible()
		try await Task.sleep(nanoseconds: 500_000_000)
		let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
		let attachment = XCTAttachment(image: image); attachment.name = "Contacts with 10,000 synthetic records"; attachment.lifetime = .keepAlways; add(attachment)
		for _ in 0..<10 {
			manager.avatarListModel = Array(contacts.prefix(50))
			await Task.yield()
			manager.avatarListModel = contacts
			await Task.yield()
		}
		XCTAssertEqual(manager.avatarListModel.first?.id, contacts.first?.id)
	}

	func testAutomaticRefreshCoalescesAndFollowsChangesDuringRead() async throws {
		var callbacks: [@MainActor () -> Void] = []
		let refresh = Mango9ContactsAutoRefresh(readAccess: { .full }, ready: { true }, delay: 1_000_000) { callbacks.append($0) }
		refresh.becameActive()
		for _ in 0..<20 { refresh.requestRefresh() }
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertEqual(callbacks.count, 1)
		for _ in 0..<20 { refresh.requestRefresh() }
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertEqual(callbacks.count, 1, "No overlapping imports")
		callbacks[0](); callbacks[0]()
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertEqual(callbacks.count, 2, "Exactly one follow-up for edits during import")
		callbacks[1]()
		refresh.becameActive()
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertEqual(callbacks.count, 2, "A system menu is not a return from background")
	}

	func testAutomaticRefreshWaitsForCoreAndForegroundWithoutPrompting() async throws {
		var ready = false; var access = Mango9ContactAccess.notRequested; var reads = 0
		let refresh = Mango9ContactsAutoRefresh(readAccess: { access }, ready: { ready }, delay: 1_000_000) { reads += 1; $0() }
		refresh.becameActive()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 0)
		ready = true; refresh.requestRefresh()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 0, "Not-requested access must not prompt/read automatically")
		access = .limited; refresh.becameActive()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 1)
		refresh.enteredBackground(); refresh.requestRefresh()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 1)
		refresh.becameActive()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 2)
		access = .denied; refresh.becameActive()
		try await Task.sleep(nanoseconds: 60_000_000)
		XCTAssertEqual(reads, 3, "Revocation must clear the no-longer-readable native cache")
	}

	func testContactRemovalCopyExplainsTheActualAddressBookScope() {
		XCTAssertTrue(Mango9ContactRemovalSource.iPhone.message.contains("not from your iPhone"))
		XCTAssertTrue(Mango9ContactRemovalSource.iPhone.message.contains("reappear"))
		XCTAssertTrue(Mango9ContactRemovalSource.directory.message.contains("synced address book"))
		XCTAssertTrue(Mango9ContactRemovalSource.mango9.message.contains("from Mango9"))
	}

	func testNativeSnapshotComparisonDetectsContentEditsWithTheSameIdentifier() throws {
		let mutable = CNMutableContact(); mutable.givenName = "Taylor"
		mutable.phoneNumbers = [CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: "+12025550142"))]
		let original = try XCTUnwrap(mutable.copy() as? CNContact)
		let unchanged = try XCTUnwrap(original.copy() as? CNContact)
		XCTAssertEqual([original], [unchanged])
		let edited = try XCTUnwrap(original.mutableCopy() as? CNMutableContact)
		edited.givenName = "Taylor Reed"
		XCTAssertEqual(original.identifier, edited.identifier)
		XCTAssertNotEqual([original], [edited as CNContact], "Content changes must invalidate the cached import")
	}

	func testRefreshingPhoneOnlyContactsDoesNotMatchEmptySIPAddresses() async throws {
		let first = ContactAvatarModel(friend: nil, name: "First", address: "", withPresence: false)
		let second = ContactAvatarModel(friend: nil, name: "Second", address: "", withPresence: false)
		try await Task.sleep(nanoseconds: 100_000_000)
		XCTAssertFalse(first.isSameContact(as: second))
		first.sourceName = "Native address-book"; second.sourceName = "Native address-book"
		first.nativeUri = "native-1"; second.nativeUri = "native-2"
		XCTAssertFalse(first.isSameContact(as: second))
		second.nativeUri = "native-1"
		XCTAssertTrue(first.isSameContact(as: second))
		second.sourceName = "Other directory"
		XCTAssertFalse(first.isSameContact(as: second), "Do not cross directory identities")
	}

	func testContactDetailActionsRenderWithoutTrustControls() async throws {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true }
		let model = ContactAvatarModel(friend: nil, name: "Morgan Taylor", address: "", withPresence: false)
		let actions = ContactsListViewModel()
		try await Task.sleep(nanoseconds: 150_000_000)
		model.phoneNumbersWithLabel = [(label: "Mobile", phoneNumber: "+1 (202) 555-0142")]
		model.emails = ["morgan.taylor@example.invalid"]
		model.removalSource = .iPhone
		model.nativeUri = "native-actions-fixture"
		for (width, size) in [(393.0, DynamicTypeSize.large), (320.0, .accessibility3)] {
			window.frame = CGRect(x: 0, y: 0, width: width, height: 1100)
			window.rootViewController = UIHostingController(rootView: ScrollView {
				VStack(spacing: 0) {
					ContactInnerActionsFragment(showingSheet: .constant(false), showShareSheet: .constant(false),
						isShowDeletePopup: .constant(false), isShowDismissPopup: .constant(false),
						isShowMediaFilesFragment: .constant(false), isShowDocumentsFilesFragment: .constant(false),
						isShowEditContactFragmentInContactDetails: .constant(false), actionEditButton: {})
				}.environmentObject(model).environmentObject(actions)
			}.background(Color.gray100).dynamicTypeSize(size))
			window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 400_000_000)
			let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
			let attachment = XCTAttachment(image: image)
			attachment.name = "Contact actions without Trust \(width) \(size)"; attachment.lifetime = .keepAlways; add(attachment)
		}
	}
	func testLivePermissionMappingIncludesLimitedAccessOnNewerIOS() {
		XCTAssertEqual(Mango9ContactAccess.from(.notDetermined), .notRequested)
		XCTAssertEqual(Mango9ContactAccess.from(.denied), .denied)
		XCTAssertEqual(Mango9ContactAccess.from(.restricted), .restricted)
		XCTAssertEqual(Mango9ContactAccess.from(.authorized), .full)
		if #available(iOS 18, *) { XCTAssertEqual(Mango9ContactAccess.from(.limited), .limited) }
		XCTAssertTrue(Mango9ContactAccess.full.canRead)
		XCTAssertTrue(Mango9ContactAccess.limited.canRead)
		for value in [Mango9ContactAccess.notRequested, .denied, .restricted, .unavailable] { XCTAssertFalse(value.canRead) }
	}

	func testInitialDisplayDoesNotPromptOrReadContacts() {
		for access in Mango9ContactAccess.allCases {
			let model = Mango9ContactsSettingsModel(readStatus: { access },
				request: { _ in XCTFail("Opening Settings must not prompt") },
				reload: { _ in XCTFail("Opening Settings must not import") },
				openSettings: { XCTFail("User action required") })
			model.refreshAccess()
			XCTAssertEqual(model.access, access)
			XCTAssertFalse(model.busy)
		}
	}

	func testFirstGrantPromptsOnceThenReloadsAllowedContacts() {
		for granted in [Mango9ContactAccess.full, .limited] {
			var access = Mango9ContactAccess.notRequested
			var reply: (@MainActor (Error?) -> Void)?
			var finish: (@MainActor (Bool) -> Void)?
			var prompts = 0; var reloads = 0
			let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { prompts += 1; reply = $0 },
				reload: { reloads += 1; finish = $0 }, openSettings: { XCTFail("First request should be native") })
			model.manageAccess(); model.manageAccess(); model.reloadContacts()
			XCTAssertEqual(prompts, 1); XCTAssertEqual(reloads, 0)
			access = granted; reply?(nil); reply?(nil)
			XCTAssertEqual(model.access, granted); XCTAssertEqual(reloads, 1)
			XCTAssertTrue(model.reloading); XCTAssertNil(model.message)
			finish?(true)
			XCTAssertFalse(model.busy); XCTAssertEqual(model.message, "Contacts reloaded.")
		}
	}

	func testDenyingFirstRequestNeverReadsContactsAndThenOffersSettings() {
		var access = Mango9ContactAccess.notRequested
		var reply: (@MainActor (Error?) -> Void)?
		var opened = 0
		let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { reply = $0 },
			reload: { _ in XCTFail("No read after denial") }, openSettings: { opened += 1 })
		model.manageAccess(); access = .denied; reply?(nil)
		XCTAssertFalse(model.busy); XCTAssertFalse(model.failed)
		XCTAssertEqual(model.accessActionTitle, "Open iPhone Settings")
		model.reloadContacts(); model.manageAccess()
		XCTAssertEqual(opened, 1)
	}

	func testDeniedAndRestrictedDoNotRepromptOrPermitManualReload() {
		for access in [Mango9ContactAccess.denied, .restricted] {
			var opened = 0
			let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { _ in XCTFail("Cannot reprompt") },
				reload: { _ in XCTFail("Cannot read") }, openSettings: { opened += 1 })
			model.reloadContacts(); model.manageAccess()
			XCTAssertEqual(opened, 1)
		}
	}

	func testReturnFromSettingsRefreshesStatusAndReloadsSelectedContacts() {
		var access = Mango9ContactAccess.denied; var reads = 0
		let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { _ in XCTFail("Already requested") },
			reload: { reads += 1; $0(true) }, openSettings: {})
		model.manageAccess(); access = .limited; model.refreshAccess()
		XCTAssertEqual(model.access, .limited); XCTAssertEqual(reads, 1)
		model.refreshAccess(); XCTAssertEqual(reads, 1, "Unrelated foreground events must not re-import")
		// A different selected-contact set leaves the limited authorization unchanged.
		model.manageAccess(); model.refreshAccess(); XCTAssertEqual(reads, 2)
	}

	func testManualReloadIsSingleFlightAndReportsCompletionNotStart() {
		var callbacks: [@MainActor (Bool) -> Void] = []
		let model = Mango9ContactsSettingsModel(readStatus: { .full }, request: { _ in XCTFail() },
			reload: { callbacks.append($0) }, openSettings: { XCTFail("Disabled during reload") })
		model.reloadContacts(); model.reloadContacts(); model.manageAccess()
		XCTAssertEqual(callbacks.count, 1); XCTAssertTrue(model.reloading); XCTAssertNil(model.message)
		callbacks[0](false)
		XCTAssertFalse(model.reloading); XCTAssertTrue(model.failed)
		model.reloadContacts(); XCTAssertEqual(callbacks.count, 2)
		callbacks[0](true); XCTAssertTrue(model.reloading, "A stale completion cannot finish the retry")
		callbacks[1](true); XCTAssertFalse(model.reloading); XCTAssertFalse(model.failed)
		XCTAssertEqual(model.message, "Contacts reloaded.")
	}

	func testPermissionRevokedDuringReloadSchedulesNativeCacheRefresh() {
		var access = Mango9ContactAccess.full
		var callbacks: [@MainActor (Bool) -> Void] = []
		let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { _ in XCTFail() },
			reload: { callbacks.append($0) }, openSettings: {})
		model.reloadContacts(); access = .denied; model.refreshAccess()
		XCTAssertEqual(callbacks.count, 1)
		callbacks[0](true); XCTAssertEqual(callbacks.count, 2)
		callbacks[1](false)
		XCTAssertFalse(model.busy); XCTAssertEqual(model.access, .denied)
		XCTAssertNil(model.message, "Must not claim a successful read after permission was revoked")
	}

	func testPermissionRequestErrorIsFriendlyAndRetryable() {
		let model = Mango9ContactsSettingsModel(readStatus: { .notRequested }, request: { $0(URLError(.unknown)) },
			reload: { _ in XCTFail() }, openSettings: {})
		model.manageAccess()
		XCTAssertFalse(model.busy); XCTAssertTrue(model.failed)
		XCTAssertEqual(model.message, "Unable to request contact access. Please try again.")
	}

	func testContactAccessControlsRenderAtNormalAndLargeTextSizes() async throws {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let previous = scene.windows.first { $0.isKeyWindow }
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true; window.rootViewController = nil; previous?.makeKey() }
		for (access, width, size) in [(Mango9ContactAccess.denied, CGFloat(393), DynamicTypeSize.large), (.full, 393, .large), (.limited, 320, .accessibility3)] {
			let model = Mango9ContactsSettingsModel(readStatus: { access }, request: { _ in XCTFail() },
				reload: { _ in XCTFail() }, openSettings: { XCTFail() })
			window.frame = CGRect(x: 0, y: 0, width: width, height: 852)
			window.rootViewController = UIHostingController(rootView: ScrollView {
				VStack(alignment: .leading, spacing: 20) {
					Text("Contacts").font(.title2.bold())
					Mango9ContactsAccessSettings(model: model)
				}.padding(20)
			}.dynamicTypeSize(size))
			window.makeKeyAndVisible(); try await Task.sleep(nanoseconds: 500_000_000)
			let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
				window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
			}
			let attachment = XCTAttachment(image: image)
			attachment.name = "Contact access \(access) width \(width) \(size)"; attachment.lifetime = .keepAlways
			add(attachment)
			XCTAssertFalse(model.busy)
		}
	}
}
