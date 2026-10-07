import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Firebase is optional at build time: the google-services plugin is applied only when
// app/google-services.json exists (locally, or written from a CI secret). Without it the
// app runs in a fully functional local-only mode.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()

// Imported repos start their Actions counter at 1: keep updates newer than original builds.
val ciRunNumber = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)

android {
    namespace = "com.arnav.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arnav.music"
        minSdk = 26
        targetSdk = 37
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Instrumented UI tests run under the Test Orchestrator: every test gets a fresh process
        // and `pm clear` (fresh onboarding, settings and database), so tests are order-independent.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "YOUTUBE_API_KEY", "\"${secret("YOUTUBE_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasGoogleServices.toString())
        // Self-update source: this repository's GitHub Releases (forks update from themselves).
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: "Arnav-Dugad/Arnav-Music-Background"}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = secret("ARNAV_KEYSTORE_PATH")
            if (storePath.isNotEmpty() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = secret("ARNAV_KEYSTORE_PASSWORD")
                keyAlias = secret("ARNAV_KEY_ALIAS")
                keyPassword = secret("ARNAV_KEY_PASSWORD")
            } else {
                // Public community-build key. It is intentionally not secret: it only lets
                // GitHub release APKs update each other. Production stores use a private key.
                storeFile = rootProject.file("keystore/arnav-public.jks")
                storePassword = "arnavmusic-public"
                keyAlias = "arnavmusic"
                keyPassword = "arnavmusic-public"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones are ARM: ship only ARM native code (ML Kit's translation engine is large per ABI).
            // Debug builds keep x86/x86_64 for emulators and CI UI tests.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // No applicationIdSuffix: google-services.json registers only com.arnav.music.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.play.services.auth)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.config)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.playintegrity)
    // Release builds too: lets the owner's sideloaded phone attest with a registered debug token.
    implementation(libs.firebase.appcheck.debug)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.youtube.player)
    // On-device lyrics translation (models downloaded on demand) and language detection.
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}
