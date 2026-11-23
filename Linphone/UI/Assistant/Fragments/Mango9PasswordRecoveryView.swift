import SwiftUI

struct Mango9PasswordRecoveryView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var email: String
    @State private var isSending = false
    @State private var didRequest = false
    @State private var errorMessage: String?
    @State private var retryAt: Date?
    @State private var requestTask: Task<Void, Never>?
    @FocusState private var emailFocused: Bool

    init(email: String) {
        _email = State(initialValue: Mango9PasswordRecoveryService.normalizedEmail(email) ?? "")
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                }.accessibilityLabel("Back").accessibilityIdentifier("passwordRecovery.back")
                Spacer()
                Text("Reset password").font(.headline).foregroundStyle(Color.white)
                Spacer()
                Color.clear.frame(width: 44, height: 44).accessibilityHidden(true)
            }.foregroundStyle(Color.white).padding(.horizontal, 8).padding(.vertical, 6)
                .background(Color.mango9Primary.ignoresSafeArea(edges: .top))

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Image(systemName: didRequest ? "envelope.badge" : "lock.rotation")
                        .font(.system(size: 32)).foregroundStyle(Color.white)
                        .accessibilityHidden(true)
                    Text(didRequest ? "Check your email" : "Forgot your password?")
                        .font(.title2.weight(.bold)).foregroundStyle(Color.white)
                    if didRequest {
                        Text("If an account matches this email, you’ll receive a link to reset your Mango9 password. Check your inbox and junk folder, then return here to sign in.")
                            .font(.body).foregroundStyle(Color.white.opacity(0.85))
                            .accessibilityIdentifier("passwordRecovery.confirmation")
                        actionButton("Back to sign in") { dismiss() }
                    } else {
                        Text("Enter the email address for your Mango9 account. We’ll email you a password reset link.")
                            .font(.body).foregroundStyle(Color.white.opacity(0.85))
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Email address").font(.subheadline.weight(.semibold)).foregroundStyle(Color.white)
                            TextField("Email address", text: $email, prompt: Text("Email address").foregroundColor(.grayMain2c500))
                                .foregroundStyle(Color.grayMain2c800).tint(.mango9Primary)
                                .textContentType(.emailAddress).keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never).disableAutocorrection(true)
                                .submitLabel(.send).focused($emailFocused)
                                .padding(16).background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.mango9Primary.opacity(0.25)))
                                .accessibilityIdentifier("passwordRecovery.email")
                                .disabled(isSending).onSubmit(send)
                        }
                        if let errorMessage {
                            Text(errorMessage).font(.callout).foregroundStyle(Color(red: 1, green: 0.84, blue: 0.87))
                                .accessibilityIdentifier("passwordRecovery.error")
                        }
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            let waiting = retryAt.map { $0 > context.date } ?? false
                            actionButton(isSending ? "Sending…" : "Send reset link", busy: isSending, action: send)
                                .disabled(isSending || waiting || Mango9PasswordRecoveryService.normalizedEmail(email) == nil)
                                .accessibilityIdentifier("passwordRecovery.send")
                        }
                    }
                }.frame(maxWidth: 520, alignment: .leading).padding(24)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .background(Color.mango9Primary.ignoresSafeArea())
        }
        .background(Color.mango9Primary.ignoresSafeArea())
        .tint(.mango9Primary)
        .navigationBarHidden(true)
        .preferredColorScheme(.dark)
        .onDisappear { requestTask?.cancel(); requestTask = nil; isSending = false }
    }

    private func actionButton(_ title: String, busy: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if busy { ProgressView().tint(.mango9Primary) }
                Text(title).font(.headline)
            }.foregroundStyle(Color.mango9Primary).frame(maxWidth: .infinity, minHeight: 52)
                .background(Color.white).clipShape(Capsule())
        }.buttonStyle(.plain)
    }

    @MainActor private func send() {
        guard !isSending, !(retryAt.map { $0 > Date() } ?? false),
              let normalized = Mango9PasswordRecoveryService.normalizedEmail(email) else { return }
        emailFocused = false
        isSending = true
        errorMessage = nil
        requestTask = Task { @MainActor in
            defer { isSending = false }
            do {
                try await Mango9PasswordRecoveryService.request(email: normalized)
                try Task.checkCancellation()
                didRequest = true
            } catch {
                guard !Task.isCancelled else { return }
                if case Mango9PasswordRecoveryError.rateLimited(let seconds) = error {
                    retryAt = Date().addingTimeInterval(TimeInterval(seconds))
                    let minutes = max(1, Int(ceil(Double(seconds) / 60)))
                    errorMessage = "Too many requests. Please wait \(minutes) \(minutes == 1 ? "minute" : "minutes") before trying again."
                } else if let urlError = error as? URLError,
                          [.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotConnectToHost, .cannotFindHost].contains(urlError.code) {
                    errorMessage = "Couldn’t connect. Check your internet connection and try again."
                } else {
                    errorMessage = "Password recovery is temporarily unavailable. Please try again."
                }
            }
        }
    }
}
