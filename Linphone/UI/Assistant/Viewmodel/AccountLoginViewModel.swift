/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of Linphone
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

import linphonesw
import SwiftUI

class AccountLoginViewModel: ObservableObject {
	
	private var coreContext = CoreContext.shared
	
	@Published var username: String = ""
	@Published var passwd: String = ""
	@Published var domain: String = ""
	@Published var displayName: String = ""
	@Published var phoneNumber: String = ""
	@Published var registrationError: String?
	@Published var transportType: String = "TLS"
	@Published var authId: String = ""
	@Published var sipProxyUrl: String = ""
	@Published var outboundProxy: String = ""
	
	private var mCoreDelegate: CoreDelegate!
	
	init() {}

	deinit {
		if let delegate = mCoreDelegate {
			coreContext.doOnCoreQueue { $0.removeDelegate(delegate: delegate) }
		}
	}
	
	func login() {
		// Snapshot the form before entering the core queue. A qualified SIP
		// username supplies the account domain even when the server is a proxy.
		let enteredUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
		let parts = enteredUsername.split(separator: "@", maxSplits: 1)
		let sipUsername = parts.count == 2 ? String(parts[0]) : enteredUsername
		let sipDomain = parts.count == 2
			? String(parts[1])
			: domain.trimmingCharacters(in: .whitespacesAndNewlines)
		let sipPassword = passwd
		let authenticationID = authId.trimmingCharacters(in: .whitespacesAndNewlines)
		let selectedTransport = transportType
		let enteredDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
		let enteredPhoneNumber = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
		registrationError = nil
		guard sipDomain.lowercased() != "proxy.mango9.com" else {
			registrationError = "Enter the full SIP username supplied by your administrator, including its account suffix."
			return
		}
		coreContext.doOnCoreQueue { core in
			guard self.coreContext.networkStatusIsConnected else {
				DispatchQueue.main.async {
					self.coreContext.loggingInProgress = false
					ToastViewModel.shared.show("Unavailable_network")
				}
				return
			}
			do {
				let identity = try Factory.Instance.createAddress(addr: "sip:" + sipUsername + "@" + sipDomain)
				let sipIdentity = identity.asStringUriOnly()
				let existingAccount = core.accountList.first {
					Mango9SessionStore.normalizedIdentity($0.params?.identityAddress?.asStringUriOnly()) ==
						Mango9SessionStore.normalizedIdentity(sipIdentity)
				}
				if existingAccount == nil,
				   let assistantLinphone = Bundle.main.path(forResource: "assistant_linphone_default_values", ofType: nil) {
					core.loadConfigFromXml(xmlUri: assistantLinphone)
				}
				
				// Get the transport protocol to use.
				// TLS is strongly recommended
				// Only use UDP if you don't have the choice
				var transport: TransportType
				if selectedTransport == "TLS" {
					transport = TransportType.Tls
				} else if selectedTransport == "TCP" {
					transport = TransportType.Tcp
				} else { transport = TransportType.Udp }
				
				// To configure a SIP account, we need an Account object and an AuthInfo object
				// The first one is how to connect to the proxy server, the second one stores the credentials
				
				// The auth info can be created from the Factory as it's only a data class
				// userID is set to null as it's the same as the username in our case
				// ha1 is set to null as we are using the clear text password. Upon first register, the hash will be computed automatically.
				// The realm will be determined automatically from the first register, as well as the algorithm
				let authInfo = try Factory.Instance.createAuthInfo(
					username: sipUsername,
					userid: authenticationID,
					passwd: sipPassword,
					ha1: "",
					realm: "",
					domain: sipDomain
				)
				
				// Account object replaces deprecated ProxyConfig object
				// Account object is configured through an AccountParams object that we can obtain from the Core
				
				let accountParams = try existingAccount?.params?.clone() ?? core.createAccountParams()
				
				// A SIP account is identified by an identity address that we can construct from the username and domain
				let previousParams = existingAccount?.params?.clone()
				let savedAuth = existingAccount?.findAuthInfo()
				let previousAuth = savedAuth?.clone()
				let previousDefault = core.defaultAccount
				let savedName = existingAccount?.params?.identityAddress?.displayName ?? ""
				let resolvedName = enteredDisplayName.isEmpty ? savedName : enteredDisplayName
				if !resolvedName.isEmpty { try identity.setDisplayname(newValue: resolvedName) }
				try accountParams.setIdentityaddress(newValue: identity)
				
				// We also need to configure where the proxy server is located
				var serverAddress: Address
				serverAddress = try Factory.Instance.createAddress(addr: Mango9Configuration.sipProxyURI)
				
				// We use the Address object to easily set the transport protocol
				try serverAddress.setTransport(newValue: transport)
				try accountParams.setServeraddress(newValue: serverAddress)
				
				let routeAddress = try Factory.Instance.createAddress(addr: Mango9Configuration.sipProxyURI)
				try routeAddress.setTransport(newValue: .Tls)
				try accountParams.setRoutesaddresses(newValue: [routeAddress])
				
				// And we ensure the account will start the registration process
				accountParams.registerEnabled = true
				Mango9Configuration.configurePush(on: accountParams)
				
				let targetAccount: Account
				if let existingAccount { targetAccount = existingAccount }
				else { targetAccount = try core.createAccount(params: accountParams) }
				if let delegate = self.mCoreDelegate { core.removeDelegate(delegate: delegate) }
				self.mCoreDelegate = CoreDelegateStub(onAccountRegistrationStateChanged: { [weak self] core, account, state, message in
					guard account == targetAccount, state == .Ok || state == .Failed else { return }
					if let delegate = self?.mCoreDelegate {
						core.removeDelegate(delegate: delegate)
						self?.mCoreDelegate = nil
					}
					if state == .Ok {
						core.defaultAccount = targetAccount
						DispatchQueue.main.async {
							if !enteredPhoneNumber.isEmpty {
								Mango9LineIdentityStore.save(
									Mango9LineIdentity(extensionNumber: sipUsername, activeNumber: enteredPhoneNumber),
									sipIdentity: sipIdentity)
							}
							self?.registrationError = nil
						}
					} else {
						// A failed attempt must never delete another saved calling line.
						if let savedAuth, let previousAuth {
							savedAuth.password = previousAuth.password
							savedAuth.ha1 = previousAuth.ha1
							savedAuth.userid = previousAuth.userid
							savedAuth.algorithm = previousAuth.algorithm
						} else {
							core.removeAuthInfo(info: authInfo)
						}
						if let previousParams {
							targetAccount.params = previousParams
							targetAccount.refreshRegister()
						} else { core.removeAccount(account: targetAccount) }
						if let previousDefault,
						   !(core.defaultAccount.map { $0 == previousDefault } ?? false) {
							core.defaultAccount = previousDefault
						}
						DispatchQueue.main.async {
							self?.registrationError = "Could not register this line. Check the extension, SIP password and domain."
						}
					}
				})
				core.addDelegate(delegate: self.mCoreDelegate)
				if let savedAuth {
					// Update this line's existing authentication object and start a fresh
					// registration. Re-adding it retries all pending SDK authentications,
					// including operations that have not received a challenge yet.
					savedAuth.password = sipPassword
					savedAuth.ha1 = nil
					savedAuth.userid = authenticationID.isEmpty ? nil : authenticationID
					// Let the next challenge select the algorithm and persist the new hash.
					savedAuth.algorithm = nil
				} else {
					core.addAuthInfo(info: authInfo)
				}
				if existingAccount != nil { targetAccount.params = accountParams }
				else { try core.addAccount(account: targetAccount) }
				targetAccount.refreshRegister()
			} catch {
				DispatchQueue.main.async {
					self.registrationError = "Could not set up this line. Check the extension and server domain."
				}
			}
		}
	}
	
	func unregister() {
		coreContext.doOnCoreQueue { core in
			// Here we will disable the registration of our Account
			if let account = core.defaultAccount {
				
				let params = account.params
				// Returned params object is const, so to make changes we first need to clone it
				let clonedParams = params?.clone()
				
				// Now let's make our changes
				clonedParams?.registerEnabled = false
				
				// And apply them
				account.params = clonedParams
			}
		}
	}
	
	func delete() {
		HelpView.logout()
	}
}
