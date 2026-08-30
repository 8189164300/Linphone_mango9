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
import PhotosUI
import AVFoundation

// swiftlint:disable line_length
struct PhotoPicker: UIViewControllerRepresentable {
	typealias UIViewControllerType = PHPickerViewController
	
	let filter: PHPickerFilter?
	var limit: Int = 0
	let onComplete: ([PHPickerResult]) -> Void
	
	func makeUIViewController(context: Context) -> PHPickerViewController {
		
		var configuration = PHPickerConfiguration()
		if filter != nil {
			configuration.filter = filter
		}
		configuration.selectionLimit = limit
		
		let controller = PHPickerViewController(configuration: configuration)
		
		controller.delegate = context.coordinator
		return controller
	}
	
	static func convertToUIImageArray(fromResults results: [PHPickerResult], onComplete: @escaping ([UIImage]?, Error?) -> Void) {
		var images = [UIImage]()
		
		let dispatchGroup = DispatchGroup()
		for result in results {
			dispatchGroup.enter()
			let itemProvider = result.itemProvider
			if itemProvider.canLoadObject(ofClass: UIImage.self) {
				itemProvider.loadObject(ofClass: UIImage.self) { (imageOrNil, errorOrNil) in
					if let error = errorOrNil {
						onComplete(nil, error)
					}
					if let image = imageOrNil as? UIImage {
						images.append(image)
					}
					dispatchGroup.leave()
				}
			}
		}
		dispatchGroup.notify(queue: .main) {
			onComplete(images, nil)
		}
	}
	
	static func convertToAttachmentArray(fromResults results: [PHPickerResult], onComplete: @escaping ([Attachment]?, Error?) -> Void) {
		var medias = [Attachment]()
		
		let dispatchGroup = DispatchGroup()
		for result in results {
			dispatchGroup.enter()
			let itemProvider = result.itemProvider
			if itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
				itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { urlFile, error in
					if urlFile != nil {
						do {
							let dataResult = try Data(contentsOf: urlFile!)
							let urlImage = self.saveMedia(name: urlFile!.lastPathComponent, data: dataResult, type: .image)
							if urlImage != nil {
								let attachment = Attachment(id: UUID().uuidString, name: urlFile!.lastPathComponent, url: urlImage!, type: .image)
								medias.append(attachment)
							}
						} catch {
							
						}
					} else {
						Log.error("Could not load file representation: \(error?.localizedDescription ?? "unknown error")")
					}
					
					dispatchGroup.leave()
				}
			} else if itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
				itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { urlFile, error in
					guard let urlFile else {
						Log.error("Could not load file representation: \(error?.localizedDescription ?? "unknown error")")
						dispatchGroup.leave()
						return
					}
					prepareVideoForMessaging(
						sourceURL: urlFile,
						suggestedName: urlFile.lastPathComponent
					) { result in
						switch result {
						case .success(let attachment):
							medias.append(attachment)
						case .failure(let error):
							Log.error("Could not prepare video attachment: \(error.localizedDescription)")
						}
						dispatchGroup.leave()
					}
				}
			}
		}
		
		dispatchGroup.notify(queue: .main) {
			onComplete(medias, nil)
		}
	}
	
	static func saveMedia(name: String, data: Data, type: AttachmentType) -> URL? {
		do {
			let path = FileManager.default.temporaryDirectory.appendingPathComponent(name)
			_ = try data.write(to: path)
			
			if type == .video {
				let asset = AVURLAsset(url: path, options: nil)
				let imgGenerator = AVAssetImageGenerator(asset: asset)
				imgGenerator.appliesPreferredTrackTransform = true
				let cgImage = try imgGenerator.copyCGImage(at: CMTimeMake(value: 0, timescale: 1), actualTime: nil)
				let thumbnail = UIImage(cgImage: cgImage)
				
				guard let data = thumbnail.jpegData(compressionQuality: 1) ?? thumbnail.pngData() else {
					return nil
				}
				
				let urlName = FileManager.default.temporaryDirectory.appendingPathComponent("preview_" + (name.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed) ?? "") + ".png")
				
				_ = try data.write(to: urlName)
			}
			
			return path
		} catch let error {
			print("*** Error generating thumbnail: \(error.localizedDescription)")
			return nil
		}
	}
	
	static func getURLThumbnail(name: String) -> URL {
		return FileManager.default.temporaryDirectory.appendingPathComponent("preview_" + (name.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed) ?? "") + ".png")
	}

	static func prepareVideoForMessaging(
		sourceURL: URL,
		suggestedName: String,
		completion: @escaping (Result<Attachment, Error>) -> Void
	) {
		let temporaryDirectory = FileManager.default.temporaryDirectory
		let sourceExtension = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
		let sourceCopy = temporaryDirectory
			.appendingPathComponent("mango9-video-source-\(UUID().uuidString.lowercased())")
			.appendingPathExtension(sourceExtension)
		let outputURL = temporaryDirectory
			.appendingPathComponent("mango9-video-\(UUID().uuidString.lowercased())")
			.appendingPathExtension("mp4")

		do {
			try FileManager.default.copyItem(at: sourceURL, to: sourceCopy)
		} catch {
			completion(.failure(error))
			return
		}

		let asset = AVURLAsset(url: sourceCopy)
		guard let exporter = AVAssetExportSession(
			asset: asset,
			presetName: AVAssetExportPresetMediumQuality
		), exporter.supportedFileTypes.contains(.mp4) else {
			try? FileManager.default.removeItem(at: sourceCopy)
			completion(.failure(PhotoPickerVideoError.unsupported))
			return
		}

		exporter.outputURL = outputURL
		exporter.outputFileType = .mp4
		exporter.shouldOptimizeForNetworkUse = true
		exporter.exportAsynchronously {
			defer {
				try? FileManager.default.removeItem(at: sourceCopy)
			}
			guard exporter.status == .completed else {
				try? FileManager.default.removeItem(at: outputURL)
				completion(.failure(exporter.error ?? PhotoPickerVideoError.exportFailed))
				return
			}

			do {
				let thumbnailURL = try createVideoThumbnail(for: outputURL)
				let baseName = (suggestedName as NSString).deletingPathExtension
				let attachmentName = (baseName.isEmpty ? "video" : baseName) + ".mp4"
				let size = try outputURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
				completion(.success(Attachment(
					id: UUID().uuidString,
					name: attachmentName,
					thumbnail: thumbnailURL,
					full: outputURL,
					type: .video,
					size: size
				)))
			} catch {
				try? FileManager.default.removeItem(at: outputURL)
				completion(.failure(error))
			}
		}
	}

	private static func createVideoThumbnail(for videoURL: URL) throws -> URL {
		let generator = AVAssetImageGenerator(asset: AVURLAsset(url: videoURL))
		generator.appliesPreferredTrackTransform = true
		generator.maximumSize = CGSize(width: 1_280, height: 1_280)
		generator.requestedTimeToleranceBefore = .positiveInfinity
		generator.requestedTimeToleranceAfter = .positiveInfinity
		let image = try generator.copyCGImage(
			at: CMTime(seconds: 0.1, preferredTimescale: 600),
			actualTime: nil
		)
		guard let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.82) else {
			throw PhotoPickerVideoError.thumbnailFailed
		}
		let thumbnailURL = FileManager.default.temporaryDirectory
			.appendingPathComponent("mango9-video-thumbnail-\(UUID().uuidString.lowercased())")
			.appendingPathExtension("jpg")
		try data.write(to: thumbnailURL, options: .atomic)
		return thumbnailURL
	}
	
	func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
	
	func makeCoordinator() -> Coordinator {
		return Coordinator(self)
	}
	
	class Coordinator: PHPickerViewControllerDelegate {
		
		private let parent: PhotoPicker
		
		init(_ parent: PhotoPicker) {
			self.parent = parent
		}
		
		func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
			picker.dismiss(animated: true)
			parent.onComplete(results)
		}
	}
}

private enum PhotoPickerVideoError: LocalizedError {
	case unsupported
	case exportFailed
	case thumbnailFailed

	var errorDescription: String? {
		switch self {
		case .unsupported:
			return "This video format cannot be prepared for messaging."
		case .exportFailed:
			return "The video could not be prepared for messaging."
		case .thumbnailFailed:
			return "The video preview could not be created."
		}
	}
}

// swiftlint:enable line_length
