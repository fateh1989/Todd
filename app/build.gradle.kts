plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.todd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.todd"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
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

    // Room persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Firebase AI Logic and App Check
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck)

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

