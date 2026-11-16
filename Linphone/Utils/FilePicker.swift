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
import UniformTypeIdentifiers

struct FilePicker: UIViewControllerRepresentable {
	var onDocumentsPicked: ([URL]) -> Void

	func makeCoordinator() -> Coordinator {
		Coordinator(onDocumentsPicked: onDocumentsPicked)
	}

	func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
		let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.item], asCopy: true)
		picker.allowsMultipleSelection = true
		picker.delegate = context.coordinator
		return picker
	}

	func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

	class Coordinator: NSObject, UIDocumentPickerDelegate {
		let onDocumentsPicked: ([URL]) -> Void

		init(onDocumentsPicked: @escaping ([URL]) -> Void) {
			self.onDocumentsPicked = onDocumentsPicked
		}

		func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
			onDocumentsPicked(urls)
		}

		func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
			onDocumentsPicked([])
		}
	}
	
	static func convertToAttachmentArray(fromResults results: [URL], onComplete: @escaping ([Attachment]?, Error?) -> Void) {
        let lock = NSLock()
        var files: [Int: Attachment] = [:]
        var failure: Error?
        func record(_ index: Int, _ result: Result<Attachment, Error>) {
            lock.lock(); defer { lock.unlock() }
            switch result { case .success(let file): files[index] = file; case .failure(let error): failure = error }
        }
        let group = DispatchGroup()
        for (index, source) in results.enumerated() {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                let access = source.startAccessingSecurityScopedResource()
                let mime = source.mimeType(), category = mime.components(separatedBy: "/").first ?? ""
                if category == "video" {
                    PhotoPicker.prepareVideoForMessaging(sourceURL: source, suggestedName: source.lastPathComponent) { result in
                        record(index, result)
                        if access { source.stopAccessingSecurityScopedResource() }
                        group.leave()
                    }
                    return
                }
                defer { if access { source.stopAccessingSecurityScopedResource() }; group.leave() }
                let id = UUID().uuidString
                let target = FileManager.default.temporaryDirectory.appendingPathComponent(id).appendingPathExtension(source.pathExtension)
                do {
                    // Independent snapshots prevent same-name files from replacing each other.
                    try FileManager.default.copyItem(at: source, to: target)
                    let type: AttachmentType
                    switch category {
                    case "image": type = source.pathExtension.lowercased() == "gif" ? .gif : .image
                    case "audio": type = .audio
                    case "text": type = .text
                    case "application": type = mime == "application/pdf" ? .pdf : .other
                    default: type = .other
                    }
                    let size = try target.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    record(index, .success(Attachment(id: id, name: source.lastPathComponent, url: target, type: type, size: size)))
                } catch { try? FileManager.default.removeItem(at: target); record(index, .failure(error)) }
            }
        }
        group.notify(queue: .main) {
            lock.lock(); let ordered = files.keys.sorted().compactMap { files[$0] }; let error = failure; lock.unlock()
            if let error {
                for item in ordered { for url in Set([item.full, item.thumbnail]) { try? FileManager.default.removeItem(at: url) } }
                onComplete(nil, error)
            } else { onComplete(ordered, nil) }
        }
    }
}
