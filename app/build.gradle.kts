import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val toddCiVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val toddSigningStoreFile = System.getenv("TODD_SIGNING_STORE_FILE")
val toddSigningStorePassword = System.getenv("TODD_SIGNING_STORE_PASSWORD")
val toddSigningKeyAlias = System.getenv("TODD_SIGNING_KEY_ALIAS")
val toddSigningKeyPassword = System.getenv("TODD_SIGNING_KEY_PASSWORD")
val hasToddStableSigning =
    !toddSigningStoreFile.isNullOrBlank() &&
        !toddSigningStorePassword.isNullOrBlank() &&
        !toddSigningKeyAlias.isNullOrBlank() &&
        !toddSigningKeyPassword.isNullOrBlank()

android {
    namespace = "com.todd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.todd"
        minSdk = 26
        targetSdk = 35
        versionCode = toddCiVersionCode ?: 1
        versionName = if (toddCiVersionCode != null) {
            "1.0.${toddCiVersionCode}"
        } else {
            "1.0.0"
        }

        val gitSha = System.getenv("GITHUB_SHA") ?: "local"
        val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: ""
        val buildTimestamp = Instant.now().toString()

        buildConfigField("String", "GIT_COMMIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GITHUB_RUN_NUMBER", "\"$runNumber\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val toddStableSigningConfig = if (hasToddStableSigning) {
        signingConfigs.create("toddStable") {
            storeFile = file(toddSigningStoreFile!!)
            storePassword = toddSigningStorePassword
            keyAlias = toddSigningKeyAlias
            keyPassword = toddSigningKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            isDebuggable = true
            toddStableSigningConfig?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            toddStableSigningConfig?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    // Room persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Direct Gemini Live WebSocket transport.
    implementation(libs.okhttp)

    // Firebase AI Logic and App Check
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.ai.ondevice)
    implementation(libs.firebase.appcheck)

    // Local OCR fallback for text rendered inside images/custom UI.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Ensure a valid google-services.json configuration exists so CI and local builds compile cleanly without requiring secrets in git
val ensureGoogleServicesJsonTask = tasks.register("ensureGoogleServicesJson") {
    val googleServicesFile = file("google-services.json")
    outputs.file(googleServicesFile)
    doLast {
        if (!googleServicesFile.exists()) {
            googleServicesFile.writeText(
                """
                {
                  "project_info": {
                    "project_number": "000000000000",
                    "project_id": "todd-ci-build",
                    "storage_bucket": "todd-ci-build.appspot.com"
                  },
                  "client": [
                    {
                      "client_info": {
                        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
                        "android_client_info": {
                          "package_name": "com.todd"
                        }
                      },
                      "oauth_client": [],
                      "api_key": [
                        {
                          "current_key": "AIzaSyCiBuildDummyKey0000000000000000000"
                        }
                      ],
                      "services": {
                        "appinvite_service": {
                          "other_platform_oauth_client": []
                        }
                      }
                    }
                  ],
                  "configuration_version": "1"
                }
                """.trimIndent()
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("process") && name.endsWith("GoogleServices")) {
        dependsOn(ensureGoogleServicesJsonTask)
    }
}

