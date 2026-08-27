import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsPlugin
import com.google.gms.googleservices.GoogleServicesPlugin
import com.google.gms.googleservices.GoogleServicesTask
import java.io.BufferedReader
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kapt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.navigation)
}

val packageName = "com.mango9.phone"
val useDifferentPackageNameForDebugBuild = false
val mango9VersionName = "6.2.6"
val mango9VersionCode = 602010
val mango9ReleaseTag = "android-$mango9VersionName-build-$mango9VersionCode"

val sdkPath = providers.gradleProperty("LinphoneSdkBuildDir").get()
// The upstream repository includes credentials for Linphone's Firebase project.
// They must never be linked into Mango9 builds. Push can be enabled after a
// Mango9-owned google-services.json and matching release process are provided.
val googleServices = File(projectDir.absolutePath + "/mango9-google-services.json")
val linphoneLibs = File("$sdkPath/libs/")
val linphoneDebugLibs = File("$sdkPath/libs-debug/")
val firebaseCloudMessagingAvailable = googleServices.exists()
val crashlyticsAvailable = googleServices.exists() && linphoneLibs.exists() && linphoneDebugLibs.exists()
val defaultMango9SourceCodeUrl =
    "https://github.com/8189164300/Linphone_mango9/tree/$mango9ReleaseTag"
val mango9SourceCodeUrl = providers.gradleProperty("Mango9SourceCodeUrl")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: defaultMango9SourceCodeUrl
val escapedMango9SourceCodeUrl = mango9SourceCodeUrl.replace("\\", "\\\\").replace("\"", "\\\"")

if (firebaseCloudMessagingAvailable) {
    println("google-services.json found, enabling Firebase CloudMessaging feature")
    apply<GoogleServicesPlugin>()
    afterEvaluate {
        tasks.withType<GoogleServicesTask>().configureEach {
            googleServicesJsonFiles.set(listOf(googleServices))
        }
    }
} else {
    println("google-services.json not found, disabling Firebase CloudMessaging feature")
}
if (crashlyticsAvailable) {
    println("google-services.json found and Linphone SDK libs-debug folder found, enabling Crashlytics feature")
    apply<CrashlyticsPlugin>()
} else {
    println("Crashlytics has been disabled because either google-services.json file wasn't found or local Linphone SDK build folder isn't configured")
}

val gitVersion = mango9VersionName
var gitBranch = "unknown"
try {
    val gitCommitHash = ProcessBuilder()
        .command("git", "rev-parse", "--short", "HEAD")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()

    gitBranch = ProcessBuilder()
        .command("git", "name-rev", "--name-only", "HEAD")
        .directory(project.rootDir)
        .start()
        .inputStream.bufferedReader().use(BufferedReader::readText)
        .trim()
    println("Git commit: $gitCommitHash, branch: $gitBranch")
} catch (e: Exception) {
    println("Git metadata unavailable [$e]")
}
println("Mango9 version: $gitVersion ($mango9VersionCode), source tag: $mango9ReleaseTag")

configurations {
    implementation { isCanBeResolved = true }
}

tasks.register("linphoneSdkSource") {
    doLast {
        configurations.implementation.get().incoming.resolutionResult.allComponents.forEach {
            if (it.id.displayName.contains("linphone-sdk-android")) {
                println("Linphone SDK used is ${it.moduleVersion?.version}")
            }
        }
    }
}
project.tasks.preBuild.dependsOn("linphoneSdkSource")

// AGP 9 does not currently merge the namespaced assets from the published
// Linphone SDK AAR into this application. The native SDK requires those exact
// grammars, CA data, images, and sounds at runtime and aborts if they are absent.
// Extract them from the resolved SDK artifact so they are packaged without
// keeping a second, manually copied, version in this repository.
val linphoneSdkAssetsDir = layout.buildDirectory.dir("generated/linphoneSdkAssets")
val extractLinphoneSdkAssets = tasks.register<Sync>("extractLinphoneSdkAssets") {
    val linphoneAars = providers.provider {
        configurations.implementation.get().filter {
            it.name.startsWith("linphone-sdk-android") && it.extension == "aar"
        }
    }
    from({ linphoneAars.get().map(::zipTree) }) {
        include("assets/**")
        includeEmptyDirs = false
        eachFile {
            path = path.removePrefix("assets/")
        }
    }
    into(linphoneSdkAssetsDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
project.tasks.preBuild.dependsOn(extractLinphoneSdkAssets)

android {
    namespace = "org.linphone"
    compileSdk = 37

    defaultConfig {
        applicationId = packageName
        minSdk = 28
        targetSdk = 37
        versionCode = mango9VersionCode
        versionName = mango9VersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
        buildConfigField("String", "MANGO9_SOURCE_CODE_URL", "\"$escapedMango9SourceCodeUrl\"")
        buildConfigField("Boolean", "MANGO9_FCM_ENABLED", firebaseCloudMessagingAvailable.toString())

        manifestPlaceholders["appAuthRedirectScheme"] = packageName
        manifestPlaceholders["firebaseMessagingEnabled"] = firebaseCloudMessagingAvailable.toString()

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    sourceSets.named("main") {
        // preBuild depends on extractLinphoneSdkAssets above; use the concrete
        // directory here because AGP 9 rejects Provider values in SourceSet.
        assets.srcDir(file("build/generated/linphoneSdkAssets"))
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "mango9-android-${variant.buildType.name}-$gitVersion.apk"
            }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))

    signingConfigs {
        create("release") {
            val keyStorePath = keystoreProperties["storeFile"] as String
            val keyStore = project.file(keyStorePath)
            if (keyStore.exists()) {
                storeFile = keyStore
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                println("Signing config release is using keystore [$storeFile]")
            } else {
                println("Keystore [$storeFile] doesn't exists!")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (useDifferentPackageNameForDebugBuild) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            isJniDebuggable = true

            val appVersion = gitVersion
            val appBranch = gitBranch
            println("Debug flavor app version is [$appVersion], app branch is [$appBranch]")
            resValue("string", "linphone_app_version", appVersion)
            resValue("string", "linphone_app_branch", appBranch)
            if (useDifferentPackageNameForDebugBuild) {
                resValue("string", "file_provider", "$packageName.debug.fileprovider")
            } else {
                resValue("string", "file_provider", "$packageName.fileprovider")
            }
            resValue("string", "linphone_openid_callback_scheme", packageName)

            if (crashlyticsAvailable) {
                val path = File("$sdkPath/libs-debug/").toString()
                configure<CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                    unstrippedNativeLibsDir = path
                }
            }
            buildConfigField("Boolean", "CRASHLYTICS_ENABLED", crashlyticsAvailable.toString())
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")

            val appVersion = gitVersion
            val appBranch = gitBranch
            println("Release flavor app version is [$appVersion], app branch is [$appBranch]")
            resValue("string", "linphone_app_version", appVersion)
            resValue("string", "linphone_app_branch", appBranch)
            resValue("string", "file_provider", "$packageName.fileprovider")
            resValue("string", "linphone_openid_callback_scheme", packageName)

            if (crashlyticsAvailable) {
                val path = File("$sdkPath/libs-debug/").toString()
                configure<CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                    unstrippedNativeLibsDir = path
                }
            }
            buildConfigField("Boolean", "CRASHLYTICS_ENABLED", crashlyticsAvailable.toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        dataBinding = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = true
        // Android lint 9.2.1 crashes inside the AndroidX fragment detector
        // while resolving upstream DebugFragment.kt. Keep the rest of lint
        // active and disable only the three checks named by lint's workaround.
        disable += setOf(
            "FragmentAddMenuProvider",
            "FragmentBackPressedCallback",
            "FragmentLiveDataObserve",
            // Upstream ships partial community translations and intentionally
            // falls back to the complete English resource set.
            "MissingQuantity",
            "MissingTranslation",
        )
    }
}

dependencies {
    implementation(libs.androidx.annotations)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraint.layout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.telecom)
    implementation(libs.androidx.media)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.slidingpanelayout)
    implementation(libs.androidx.window)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.security.crypto.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.car)

    // https://github.com/google/flexbox-layout/blob/main/LICENSE Apache v2.0
    implementation(libs.google.flexbox)
    // https://github.com/material-components/material-components-android/blob/master/LICENSE Apache v2.0
    implementation(libs.google.material)
    // To be able to parse native crash tombstone and print them with SDK logs the next time the app will start
    implementation(libs.google.protobuf)

    if (firebaseCloudMessagingAvailable) {
        implementation(platform(libs.google.firebase.bom))
        implementation(libs.google.firebase.messaging)
    } else {
        // Linphone SDK and dormant upstream diagnostics types reference Firebase
        // APIs at compile time. Keep those references resolvable without
        // packaging the Firebase runtime in a credential-free Mango9 build.
        compileOnly(platform(libs.google.firebase.bom))
        compileOnly(libs.google.firebase.messaging)
    }
    if (crashlyticsAvailable) {
        implementation(libs.google.firebase.crashlytics)
    } else {
        compileOnly(libs.google.firebase.crashlytics)
    }

    // https://github.com/coil-kt/coil/blob/main/LICENSE.txt Apache v2.0
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    // https://github.com/tommybuonomo/dotsindicator/blob/master/LICENSE Apache v2.0
    implementation(libs.dots.indicator)
    // https://github.com/Baseflow/PhotoView/blob/master/LICENSE Apache v2.0
    implementation(libs.photoview)
    // https://github.com/openid/AppAuth-Android/blob/master/LICENSE Apache v2.0
    implementation(libs.openid.appauth)
    // https://square.github.io/okhttp/ Apache v2.0; Mango9 JSON-RPC WebSocket transport.
    implementation(libs.okhttp)

    implementation(libs.linphone)

    testImplementation(libs.junit4)
    testImplementation(libs.json.java)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

val verifyMango9StaticPolicy = tasks.register("verifyMango9StaticPolicy") {
    group = "verification"
    description = "Rejects Mango9 identity, transport, credential, and upstream-service regressions."

    val manifest = file("src/main/AndroidManifest.xml")
    val mainActivity = file("src/main/java/org/linphone/ui/main/MainActivity.kt")
    val helpFragment = file("src/main/java/org/linphone/ui/main/help/fragment/HelpFragment.kt")
    val loginLayout = file("src/main/res/layout/assistant_landing_fragment.xml")
    val drawerLayout = file("src/main/res/layout/drawer_menu.xml")
    val bottomNavLayout = file("src/main/res/layout/bottom_nav_bar.xml")
    val loginBrandBackground = file("src/main/res/drawable/mango9_brand_card_background.xml")
    val splashThemes = files(
        "src/main/res/values/themes.xml",
        "src/main/res/values-night/themes.xml",
        "src/main/res/values-v31/themes.xml",
        "src/main/res/values-night-v31/themes.xml",
        "src/main/res/values-v33/themes.xml",
        "src/main/res/values-night-v33/themes.xml",
    )
    val mango9ApiClient = file("src/main/java/org/linphone/mango9/Mango9ApiClient.kt")
    val forwardingPolicy = file("src/main/java/org/linphone/mango9/Mango9CallForwardingPolicy.kt")
    val callSettingsLayout = file("src/main/res/layout/mango9_call_settings.xml")
    val accountCompany = file("src/main/java/org/linphone/mango9/Mango9AccountCompany.kt")
    val accountModel = file("src/main/java/org/linphone/ui/main/model/AccountModel.kt")
    val accountListCell = file("src/main/res/layout/account_list_cell.xml")
    val coreContext = file("src/main/java/org/linphone/core/CoreContext.kt")
    val suggestionModel = file("src/main/java/org/linphone/ui/main/model/ConversationContactOrSuggestionModel.kt")
    val contactsManager = file("src/main/java/org/linphone/contacts/ContactsManager.kt")
    val notificationsManager = file("src/main/java/org/linphone/notifications/NotificationsManager.kt")
    val linphoneUtils = file("src/main/java/org/linphone/utils/LinphoneUtils.kt")
    val mainViewModel = file("src/main/java/org/linphone/ui/main/viewmodel/MainViewModel.kt")
    val loginRepository = file("src/main/java/org/linphone/mango9/Mango9LoginRepository.kt")
    val crmViewModel = file("src/main/java/org/linphone/ui/main/crm/viewmodel/Mango9CrmViewModel.kt")
    val chatStore = file("src/main/java/org/linphone/mango9/Mango9ChatStore.kt")
    val messagePush = file("src/main/java/org/linphone/mango9/Mango9MessagePush.kt")
    val accountProfile = file("src/main/java/org/linphone/ui/main/settings/viewmodel/AccountProfileViewModel.kt")
    val backupRules = file("src/main/res/xml/backup_rules.xml")
    val dataExtractionRules = file("src/main/res/xml/data_extraction_rules.xml")
    val assistantNavigation = file("src/main/res/navigation/assistant_nav_graph.xml")
    val versionCatalog = rootProject.file("gradle/libs.versions.toml")
    val readme = rootProject.file("README.md")
    val openSourceNotices = rootProject.file("OPEN_SOURCE_NOTICES.md")
    val runtimeAssets = fileTree("src/main/assets")
    val runtimeResources = fileTree("src/main/res") {
        include("**/*.xml")
    }
    val upstreamGoogleServices = file("google-services.json")
    val mango9GoogleServices = file("mango9-google-services.json")
    inputs.files(
        manifest,
        mainActivity,
        helpFragment,
        loginLayout,
        drawerLayout,
        bottomNavLayout,
        loginBrandBackground,
        splashThemes,
        mango9ApiClient,
        forwardingPolicy,
        callSettingsLayout,
        accountCompany,
        accountModel,
        accountListCell,
        coreContext,
        suggestionModel,
        contactsManager,
        notificationsManager,
        linphoneUtils,
        mainViewModel,
        loginRepository,
        crmViewModel,
        chatStore,
        messagePush,
        accountProfile,
        backupRules,
        dataExtractionRules,
        assistantNavigation,
        versionCatalog,
        readme,
        openSourceNotices,
        runtimeAssets,
        runtimeResources,
    )
    inputs.file(file("build.gradle.kts"))
    if (mango9GoogleServices.exists()) inputs.file(mango9GoogleServices)

    doLast {
        val failures = mutableListOf<String>()
        val manifestText = manifest.readText()
        val mainActivityText = mainActivity.readText()
        val helpFragmentText = helpFragment.readText()
        val loginLayoutText = loginLayout.readText()
        val drawerLayoutText = drawerLayout.readText()
        val bottomNavLayoutText = bottomNavLayout.readText()
        val loginBrandBackgroundText = loginBrandBackground.readText()
        val splashThemeText = splashThemes.joinToString("\n") { it.readText() }
        val mango9ApiClientText = mango9ApiClient.readText()
        val forwardingPolicyText = forwardingPolicy.readText()
        val callSettingsLayoutText = callSettingsLayout.readText()
        val accountCompanyText = accountCompany.readText()
        val accountModelText = accountModel.readText()
        val accountListCellText = accountListCell.readText()
        val coreContextText = coreContext.readText()
        val suggestionModelText = suggestionModel.readText()
        val contactsManagerText = contactsManager.readText()
        val notificationsManagerText = notificationsManager.readText()
        val linphoneUtilsText = linphoneUtils.readText()
        val mainViewModelText = mainViewModel.readText()
        val loginRepositoryText = loginRepository.readText()
        val crmViewModelText = crmViewModel.readText()
        val chatStoreText = chatStore.readText()
        val messagePushText = messagePush.readText()
        val accountProfileText = accountProfile.readText()
        val backupRulesText = backupRules.readText()
        val dataExtractionRulesText = dataExtractionRules.readText()
        val assistantNavigationText = assistantNavigation.readText()
        val versionCatalogText = versionCatalog.readText()
        val readmeText = readme.readText()
        val openSourceNoticesText = openSourceNotices.readText()
        val buildText = file("build.gradle.kts").readText()

        fun requirePolicy(condition: Boolean, message: String) {
            if (!condition) failures += message
        }

        requirePolicy(
            buildText.contains("val packageName = \"com.mango9.phone\""),
            "application ID must remain com.mango9.phone",
        )
        requirePolicy(
            manifestText.contains("android:usesCleartextTraffic=\"false\""),
            "cleartext traffic must remain disabled",
        )
        requirePolicy(
            manifestText.contains("android:name=\"android.content.APP_RESTRICTIONS\"") &&
                manifestText.contains("android:resource=\"@xml/app_restrictions\""),
            "managed app restrictions metadata is missing",
        )
        requirePolicy(
            manifestText.contains("android:enabled=\"\${firebaseMessagingEnabled}\"") &&
                manifestText.contains(".mango9.Mango9FirebaseMessagingService") &&
                buildText.contains("if (firebaseCloudMessagingAvailable)"),
            "Firebase Messaging must remain conditional on Mango9-owned credentials",
        )
        requirePolicy(
            !manifestText.contains(".ui.welcome.WelcomeActivity") &&
                !mainActivityText.contains("WelcomeActivity"),
            "the upstream Linphone welcome carousel must not be reachable from Mango9",
        )
        requirePolicy(
            !helpFragmentText.contains("DebugFragment") &&
                !helpFragmentText.contains("checkForUpdate") &&
                !helpFragmentText.contains("website_translate_weblate_url"),
            "Help must not expose upstream debug, update, or translation routes",
        )
        requirePolicy(
            loginLayoutText.contains("android:background=\"@color/gray_100\"") &&
                loginLayoutText.contains("@drawable/mango9_login_field_background") &&
                loginLayoutText.contains("@drawable/mango9_login_primary_button_background") &&
                loginLayoutText.contains("@drawable/mango9_login_secondary_button_background") &&
                !loginLayoutText.contains("?attr/") &&
                loginBrandBackgroundText.contains("@color/orange_main_500") &&
                !loginBrandBackgroundText.contains("<gradient"),
            "Mango9 login must retain the fixed iOS parity palette in light and dark device modes",
        )
        requirePolicy(
            !splashThemeText.contains("linphone_splashscreen") &&
                !splashThemeText.contains("windowSplashScreenBrandingImage") &&
                splashThemes.all {
                    val text = it.readText()
                    text.contains("@color/orange_main_500") &&
                        text.contains("@drawable/mango9_splashscreen_wordmark")
                } &&
                loginLayoutText.contains("android:id=\"@+id/mango9_use_email_code\"") &&
                !loginLayoutText.contains("app:drawableStartCompat=\"@drawable/envelope_simple\""),
            "Splash branding and the centered email-code label must remain Mango9-specific",
        )
        requirePolicy(
            drawerLayoutText.contains("@drawable/mango9_wordmark_white") &&
                !drawerLayoutText.contains("@drawable/linphone_notification"),
            "Mango9 drawer must use the Mango9 wordmark instead of the Linphone mark",
        )
        requirePolicy(
            bottomNavLayoutText.contains("@drawable/mango9_crm_footer") &&
                bottomNavLayoutText.contains("viewModel.navigateToCrm()") &&
                !drawerLayoutText.contains("@+id/crm"),
            "CRM must remain in the main footer instead of the drawer",
        )
        requirePolicy(
            forwardingPolicyText.contains("validatedDestination(enabled: Boolean") &&
                mango9ApiClientText.contains("Mango9CallForwardingPolicy.validatedDestination") &&
                callSettingsLayoutText.contains("@string/mango9_call_forwarding_number_required"),
            "Call forwarding must require a destination number before it can be enabled",
        )
        requirePolicy(
            coreContextText.contains("only_display_sip_uri_username\", true") &&
                suggestionModelText.contains("Mango9SipDisplay.friendlyUsername") &&
                contactsManagerText.contains("Mango9SipDisplay.friendlyUsername"),
            "Mango9 suggestions and contact rows must show the SIP username instead of a raw URI",
        )
        requirePolicy(
            accountCompanyText.contains("normalizedCompanyToken") &&
                accountModelText.contains("Mango9AccountCompany.displayName") &&
                accountListCellText.contains("@+id/company_name") &&
                accountListCellText.contains("model.companyName"),
            "Mango9 hamburger account rows must show the PBX company under the line number",
        )
        requirePolicy(
            mango9ApiClientText.contains("application/xml, text/xml;q=0.9, */*;q=0.1") &&
                mango9ApiClientText.contains("purpose = RequestPurpose.Enrollment") &&
                mango9ApiClientText.contains("Mango9ApiException.EnrollmentUnavailable"),
            "One-time SIP enrollment must use XML negotiation and stage-specific error mapping",
        )
        requirePolicy(
            !assistantNavigationText.contains("action_landingFragment_to_registerFragment") &&
                !assistantNavigationText.contains("action_landingFragment_to_recoverAccountFragment") &&
                !assistantNavigationText.contains("action_landingFragment_to_thirdPartySipAccountWarningFragment"),
            "Mango9 landing must not expose upstream Linphone account creation or recovery",
        )
        requirePolicy(
            versionCatalogText.contains("linphone = \"5.5.17-pre.1+3896ec0681\""),
            "Linphone SDK must remain pinned to the audited exact artifact",
        )
        requirePolicy(
            buildText.contains("MANGO9_SOURCE_CODE_URL"),
            "the configurable Mango9 corresponding-source field is missing",
        )
        requirePolicy(
            mango9SourceCodeUrl.startsWith(
                "https://github.com/8189164300/Linphone_mango9/tree/android-",
            ) &&
                readmeText.contains(mango9SourceCodeUrl) &&
                openSourceNoticesText.contains(mango9SourceCodeUrl),
            "Licensing, README, and open-source notices must use the same immutable Android source tag",
        )
        requirePolicy(
            coreContextText.indexOf("enforceMango9RuntimeDefaults()") in
                0 until coreContextText.indexOf("managedConfigurationManager.applyBeforeCoreStart(core)"),
            "runtime sanitation must happen before managed configuration is applied",
        )
        requirePolicy(
            coreContextText.contains("onPushNotificationReceived") &&
                coreContextText.contains("Mango9PushCallerIdentityCache.cache") &&
                coreContextText.contains("Call push received, refreshing SIP registrations") &&
                notificationsManagerText.contains("Call.State.PushIncomingReceived") &&
                linphoneUtilsText.contains("Call.State.PushIncomingReceived"),
            "Call-ID-scoped Mango9 push caller identity, registration refresh, or incoming-call handling is missing",
        )
        requirePolicy(
            mainViewModelText.contains("Mango9RegistrationFailure(message)") &&
                mainViewModelText.contains("REGISTRATION_FAILURE_DELAY_MS"),
            "actionable delayed Mango9 registration failures are missing",
        )
        requirePolicy(
            loginRepositoryText.contains("Mango9LineIdentityStore") &&
                loginRepositoryText.contains("Mango9LineIdentity(provisioning.username, provisioning.activeNumber)"),
            "per-account Mango9 DID/extension persistence is missing",
        )
        requirePolicy(
            crmViewModelText.contains("Mango9LatestRequestGate") &&
                crmViewModelText.contains("recordsRequests.isLatest(request)") &&
                crmViewModelText.contains("detailRequests.isLatest(request)"),
            "CRM stale-response protection is missing",
        )
        requirePolicy(
            messagePushText.contains("chat.message") &&
                messagePushText.contains("sms.received") &&
                messagePushText.contains("lead.assigned") &&
                mainActivityText.contains("Mango9MessagePushCoordinator.activateForOpen"),
            "account-scoped Mango9 message-push parsing and deep-link routing are missing",
        )
        requirePolicy(
            chatStoreText.contains("registerRemotePushTokenIfAvailable") &&
                chatStoreText.contains("unregisterRemotePushToken") &&
                accountProfileText.contains("unregisterRemotePushToken(identity, session)"),
            "Mango9 message-push registration lifecycle is missing",
        )
        val privatePreferenceFiles = listOf(
            "encrypted.pref.xml",
            "mango9_session_metadata.xml",
            "mango9_session_secrets.xml",
            "mango9_device.xml",
            "mango9_managed_configuration_secrets.xml",
            "mango9_line_identity.xml",
            "mango9_chat_moderation.xml",
            "mango9_message_push.xml",
        )
        privatePreferenceFiles.forEach { preferenceFile ->
            requirePolicy(
                backupRulesText.contains("path=\"$preferenceFile\""),
                "$preferenceFile must be excluded from legacy Android backup",
            )
            requirePolicy(
                dataExtractionRulesText.split("path=\"$preferenceFile\"").size == 3,
                "$preferenceFile must be excluded from cloud backup and device transfer",
            )
        }
        requirePolicy(!upstreamGoogleServices.exists(), "upstream app/google-services.json must not exist")

        val forbiddenRuntimeValues = listOf(
            "sip.linphone.org",
            "stun.linphone.org",
            "lime.linphone.org",
            "files.linphone.org",
            "subscribe.linphone.org",
            "download.linphone.org",
            "linphone.org/contact",
            "linphone.org/en/docs",
            "linphone.org/en/privacy-policy",
            "linphone.org/en/terms-of-use",
            "weblate.linphone.org",
        )
        (runtimeAssets.files + runtimeResources.files).forEach { candidate ->
            val text = candidate.readText()
            forbiddenRuntimeValues.forEach { forbidden ->
                if (text.contains(forbidden, ignoreCase = true)) {
                    failures += "${candidate.relativeTo(projectDir)} contains forbidden runtime endpoint [$forbidden]"
                }
            }
        }

        val defaults = file("src/main/assets/linphonerc_default").readText()
        requirePolicy(
            defaults.contains("default_domain=proxy.mango9.com"),
            "Mango9 default SIP domain is missing",
        )
        requirePolicy(
            defaults.contains("log_collection_upload_server_url=\n") &&
                defaults.contains("file_transfer_server_url=\n") &&
                defaults.contains("version_check_url_root=\n"),
            "hosted update/file/log services must be blank by default",
        )
        val assistantDefaults = file("src/main/assets/assistant_linphone_default_values").readText()
        requirePolicy(
            assistantDefaults.contains("sip:proxy.mango9.com;transport=tls"),
            "managed account defaults must route through proxy.mango9.com over TLS",
        )

        if (mango9GoogleServices.exists()) {
            val googleText = mango9GoogleServices.readText()
            requirePolicy(
                googleText.contains("\"package_name\": \"com.mango9.phone\""),
                "Mango9 Firebase configuration must target com.mango9.phone",
            )
            requirePolicy(
                !googleText.contains("linphone", ignoreCase = true),
                "Mango9 Firebase configuration must not reference an upstream Linphone project",
            )
        }

        if (failures.isNotEmpty()) {
            throw GradleException("Mango9 static policy failed:\n- ${failures.joinToString("\n- ")}")
        }
        logger.lifecycle("Mango9 static policy passed")
    }
}

val verifyMango9ReleasePolicy = tasks.register("verifyMango9ReleasePolicy") {
    group = "verification"
    description = "Rejects release builds without a public Mango9 corresponding-source URL."
    dependsOn(verifyMango9StaticPolicy)
    doLast {
        if (mango9SourceCodeUrl.isEmpty()) {
            throw GradleException(
                "Release requires -PMango9SourceCodeUrl=https://... for GPL corresponding source",
            )
        }
        if (!firebaseCloudMessagingAvailable) {
            throw GradleException(
                "Release requires app/mango9-google-services.json for Mango9 push notifications",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyMango9ReleasePolicy)
}

tasks.named("preBuild").configure {
    dependsOn(verifyMango9StaticPolicy)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    android.set(true)
    ignoreFailures.set(true)
    additionalEditorconfig.set(
        mapOf(
            "max_line_length" to "120",
            "ktlint_standard_max-line-length" to "disabled",
            "ktlint_standard_function-signature" to "disabled",
            "ktlint_standard_no-blank-line-before-rbrace" to "disabled",
            "ktlint_standard_no-empty-class-body" to "disabled",
            "ktlint_standard_annotation-spacing" to "disabled",
            "ktlint_standard_class-signature" to "disabled",
            "ktlint_standard_function-expression-body" to "disabled",
            "ktlint_standard_function-type-modifier-spacing" to "disabled",
            "ktlint_standard_if-else-wrapping" to "disabled",
            "ktlint_standard_argument-list-wrapping" to "disabled",
            "ktlint_standard_trailing-comma-on-call-site" to "disabled",
            "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
            "ktlint_standard_no-empty-first-line-in-class-body" to "disabled",
            "ktlint_standard_no-empty-first-line-in-method-block" to "disabled",
            "ktlint_standard_no-trailing-spaces" to "disabled",
            "ktlint_standard_no-blank-line-in-list" to "disabled",
            "ktlint_standard_no-multi-spaces" to "disabled",
            "ktlint_standard_try-catch-finally-spacing" to "disabled",
            "ktlint_standard_block-comment-initial-star-alignment" to "disabled",
            "ktlint_standard_spacing-between-declarations-with-comments" to "disabled",
            "ktlint_standard_no-consecutive-comments" to "disabled",
            "ktlint_standard_multiline-expression-wrapping" to "disabled",
            "ktlint_standard_parameter-list-wrapping" to "disabled",
            "ktlint_standard_comment-wrapping" to "disabled",
            "ktlint_standard_discouraged-comment-location" to "disabled",
            "ktlint_standard_string-template-indent" to "disabled",
            "ktlint_standard_parameter-list-spacing" to "disabled",
            "ktlint_standard_statement-wrapping" to "disabled",
            "ktlint_standard_import-ordering" to "disabled",
            "ktlint_standard_paren-spacing" to "disabled",
            "ktlint_standard_curly-spacing" to "disabled",
            "ktlint_standard_indent" to "disabled",
        )
    )
}
project.tasks.preBuild.dependsOn("ktlintFormat")

if (crashlyticsAvailable) {
    afterEvaluate {
        tasks.getByName("assembleDebug").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileDebug"),
        )
        tasks.getByName("packageDebug").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileDebug"),
        )
        tasks.getByName("assembleRelease").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileRelease"),
        )
        tasks.getByName("packageRelease").finalizedBy(
            tasks.getByName("uploadCrashlyticsSymbolFileRelease"),
        )
    }
}
