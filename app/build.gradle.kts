import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("jacoco")
}

// Room schema export directory — versioned for audit trail
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.register("jacocoTestReport", JacocoReport::class) {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val fileFilter = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/di/**",
        "**/model/**",
        "**/data/api/**",
        "**/data/local/**",
        "**/UScroogeApplication*"
    )
    val debugTree = fileTree("$buildDir/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    val mainSrc = "$projectDir/src/main/java"
    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree("$buildDir") {
        include("**/*.exec", "**/*.ec")
    })
}

val appVersionMajor: String by project
val appVersionMinor: String by project
val appVersionPatch: String by project
val appVersionCode: String by project

android {
    val keystoreProperties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
    }

    namespace = "com.uscrooge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uscrooge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode.toInt()
        versionName = "${appVersionMajor}.${appVersionMinor}.${appVersionPatch}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }

        val githubRepo = providers.environmentVariable("GITHUB_REPOSITORY")
            .orElse("andreaschiona/uscrooge-trading-app")
            .get()
        val githubToken = providers.environmentVariable("REPORTING_GH_TOKEN")
            .orElse(localProps.getProperty("reportingGhToken", ""))
            .get()
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("String", "GITHUB_TOKEN", "\"$githubToken\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
                    .filter { keystoreProperties.getProperty(it).isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    error(
                        "keystore.properties is present but missing required keys: " +
                            "${missing.joinToString()}. See keystore.properties.example."
                    )
                }
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(enforcedPlatform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLite for migrations
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Hilt - Dependency Injection
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Charts
    implementation("com.patrykandpatrick.vico:compose:2.0.0")
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0")
    implementation("com.patrykandpatrick.vico:core:2.0.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Security - Crypto for API keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(enforcedPlatform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("io.mockk:mockk:1.13.9")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("app.cash.turbine:turbine:1.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
