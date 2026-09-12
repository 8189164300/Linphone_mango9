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

import SwiftUI
import linphonesw
import Contacts
import ImageIO

/// Only visible native contacts load thumbnails. The cache is memory bounded and
/// automatically discarded on address-book changes or memory pressure.
final class Mango9ContactPhotoCache {
	static let shared = Mango9ContactPhotoCache()
	private final class Entry { let image: UIImage?; init(_ image: UIImage?) { self.image = image } }
	private let cache = NSCache<NSString, Entry>()
	private let queue = DispatchQueue(label: "mango9.contacts.thumbnails", qos: .utility)
	private var observers: [NSObjectProtocol] = []
	init() {
		cache.countLimit = 128; cache.totalCostLimit = 8 * 1024 * 1024
		for name in [Notification.Name.CNContactStoreDidChange, UIApplication.didReceiveMemoryWarningNotification] {
			observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
				self?.queue.async { self?.cache.removeAllObjects() }
			})
		}
	}
	deinit { observers.forEach(NotificationCenter.default.removeObserver) }
	func image(identifier: String) async -> UIImage? {
		guard !identifier.isEmpty, !Task.isCancelled else { return nil }
		return await withCheckedContinuation { continuation in
			queue.async {
				let image: UIImage? = autoreleasepool {
					guard Mango9ContactAccess.current.canRead else { self.cache.removeAllObjects(); return nil }
					if let entry = self.cache.object(forKey: identifier as NSString) { return entry.image }
					let contact = try? CNContactStore().unifiedContact(withIdentifier: identifier,
						keysToFetch: [CNContactThumbnailImageDataKey as CNKeyDescriptor])
					var image: UIImage?
					if let data = contact?.thumbnailImageData, data.count <= 4 * 1024 * 1024,
						let source = CGImageSourceCreateWithData(data as CFData, nil),
						let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
							kCGImageSourceCreateThumbnailFromImageAlways: true,
							kCGImageSourceCreateThumbnailWithTransform: true,
							kCGImageSourceThumbnailMaxPixelSize: 160,
							kCGImageSourceShouldCacheImmediately: true] as CFDictionary) {
						image = UIImage(cgImage: thumbnail)
					}
					self.cache.setObject(Entry(image), forKey: identifier as NSString,
						cost: image?.cgImage.map { $0.bytesPerRow * $0.height } ?? 1)
					return image
				}
				continuation.resume(returning: image)
			}
		}
	}
}

struct Mango9ContactInitials: View {
	let name: String
	let size: CGFloat
	static func initials(_ name: String) -> String {
		let parts = name.split(whereSeparator: \.isWhitespace)
		return parts.prefix(2).compactMap { $0.first(where: \.isLetter).map { String($0).uppercased() } }.joined()
	}
	var body: some View {
		ZStack {
			Circle().fill(Color.grayMain2c200)
			let letters = Self.initials(name)
			if letters.isEmpty { Image(systemName: "person.fill").font(.system(size: size * 0.45)) }
			else { Text(letters).font(.system(size: size * 0.35, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6) }
		}.foregroundStyle(Color.grayMain2c600).frame(width: size, height: size)
	}
}

private struct Mango9NativeContactPhoto: View {
	let identifier: String
	let name: String
	let size: CGFloat
	@State private var image: UIImage?
	@State private var revision = 0
	var body: some View {
		Group {
			if let image { Image(uiImage: image).resizable().scaledToFill().frame(width: size, height: size).clipShape(Circle()) }
			else { Mango9ContactInitials(name: name, size: size) }
		}
		.task(id: "\(identifier):\(revision)") {
			image = nil
			let loaded = await Mango9ContactPhotoCache.shared.image(identifier: identifier)
			guard !Task.isCancelled else { return }
			image = loaded
		}
		.onReceive(NotificationCenter.default.publisher(for: .CNContactStoreDidChange).receive(on: DispatchQueue.main)) { _ in revision &+= 1 }
		.onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification).receive(on: DispatchQueue.main)) { _ in
			if !Mango9ContactAccess.current.canRead { image = nil }; revision &+= 1
		}
	}
}

struct Avatar: View {
	
	private var contactsManager = ContactsManager.shared
	
	@ObservedObject var contactAvatarModel: ContactAvatarModel
	
	let avatarSize: CGFloat
	let hidePresence: Bool
	
	init(contactAvatarModel: ContactAvatarModel, avatarSize: CGFloat, hidePresence: Bool = false) {
		self.contactAvatarModel = contactAvatarModel
		self.avatarSize = avatarSize
		self.hidePresence = hidePresence
	}
	
	var body: some View {
		ZStack {
			if contactAvatarModel.removalSource == .iPhone, !contactAvatarModel.nativeUri.isEmpty {
				Mango9NativeContactPhoto(identifier: contactAvatarModel.nativeUri, name: contactAvatarModel.name, size: avatarSize)
			} else if !contactAvatarModel.photo.isEmpty {
				let uniqueUrl = ContactsManager.shared.getImagePath(friendPhotoPath: contactAvatarModel.photo)
				//let finalUrl = uniqueUrl.appendingQueryItem("v", value: UUID().uuidString)
				
				AsyncImage(url: uniqueUrl) { image in
					switch image {
					case .empty:
						ProgressView()
							.frame(width: avatarSize, height: avatarSize)
					case .success(let image):
						ZStack {
							image
								.resizable()
								.aspectRatio(contentMode: .fill)
								.frame(width: avatarSize, height: avatarSize)
								.clipShape(Circle())
						}
					case .failure:
						Image("profil-picture-default")
							.resizable()
							.frame(width: avatarSize, height: avatarSize)
							.clipShape(Circle())
					@unknown default:
						EmptyView()
					}
				}
			} else if !contactAvatarModel.name.isEmpty {
				Mango9ContactInitials(name: contactAvatarModel.name, size: avatarSize)
			} else {
				Image("profil-picture-default")
					.resizable()
					.frame(width: avatarSize, height: avatarSize)
					.clipShape(Circle())
			}
			
			if contactAvatarModel.friend != nil && !hidePresence {
				if contactAvatarModel.unsafeFriend || contactAvatarModel.trustedFriend {
					Circle()
						.stroke(contactAvatarModel.trustedFriend ? Color.blueInfo500 : Color.redDanger500, lineWidth: 2)
						.frame(width: avatarSize, height: avatarSize)
					
					HStack {
						VStack {
							Spacer()
							Image(contactAvatarModel.trustedFriend ? "trusted" : "not-trusted")
								.resizable()
								.frame(width: avatarSize/4, height: avatarSize/4)
								.padding(.trailing, avatarSize == 50 || avatarSize == 35 ? 1 : 3)
								.padding(.bottom, avatarSize == 50 || avatarSize == 35 ? 1 : 3)
						}
						Spacer()
					}
					.frame(width: avatarSize, height: avatarSize)
				}
				
				HStack {
					Spacer()
					VStack {
						Spacer()
						if !hidePresence && (contactAvatarModel.presenceStatus == .Online || contactAvatarModel.presenceStatus == .Busy) {
							Image(contactAvatarModel.presenceStatus == .Online ? "presence-online" : "presence-busy")
								.resizable()
								.frame(width: avatarSize/4, height: avatarSize/4)
								.padding(.trailing, avatarSize == 50 || avatarSize == 35 ? 1 : 3)
								.padding(.bottom, avatarSize == 50 || avatarSize == 35 ? 1 : 3)
						}
					}
				}
				.frame(width: avatarSize, height: avatarSize)
			}
		}
	}
}
