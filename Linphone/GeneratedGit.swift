import Foundation

public enum AppGitInfo {
	public static let branch = "public-source"

	public static var commit: String {
		Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
			?? "unknown-build"
	}

	public static var tag: String {
		Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
			?? "unknown-version"
	}
}
