plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.keepasspn"
    compileSdk = 36

    val gmsPackage = "com.google.android.gms"

    defaultConfig {
        // Application ID convention: io.github.<username>.<appname>, used by many
        // F-Droid apps that do not own a domain name.
        // applicationId is what actually gets installed on the device /
        // listed on stores; it is independent from "namespace" above.
        applicationId = "io.github.hidefbi.keepasspn"
        minSdk = 19
        targetSdk = 36
        // TODO: bump this version scheme with every release you make;
        // it must always increase for Play Store / F-Droid updates to install.
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true

        testApplicationId = "io.github.hidefbi.keepasspn.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_PLAY_SERVICES_PACKAGE", "\"$gmsPackage\"")
        buildConfigField("String[]", "ICON_PACKS", "{\"classic\",\"material\"}")
        
        manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "version"
    productFlavors {
        create("libre") {
            dimension = "version"
            applicationIdSuffix = ".libre"
            buildConfigField("String", "BUILD_VERSION", "\"libre\"")
            buildConfigField("boolean", "CLOSED_STORE", "false")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeePassPNStyle_Red\"," +
                    "\"KeePassPNStyle_Red_Night\"," +
                    "\"KeePassPNStyle_Reply\"," +
                    "\"KeePassPNStyle_Reply_Night\"," +
                    "\"KeePassPNStyle_Purple\"," +
                    "\"KeePassPNStyle_Purple_Dark\"," +
                    "\"KeePassPNStyle_Dynamic_Light\"," +
                    "\"KeePassPNStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
        }
        create("free") {
            dimension = "version"
            applicationIdSuffix = ".free"
            buildConfigField("String", "BUILD_VERSION", "\"free\"")
            buildConfigField("boolean", "CLOSED_STORE", "true")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeePassPNStyle_Blue\"," +
                    "\"KeePassPNStyle_Blue_Night\"," +
                    "\"KeePassPNStyle_Red\"," +
                    "\"KeePassPNStyle_Red_Night\"," +
                    "\"KeePassPNStyle_Reply\"," +
                    "\"KeePassPNStyle_Reply_Night\"," +
                    "\"KeePassPNStyle_Purple\"," +
                    "\"KeePassPNStyle_Purple_Dark\"," +
                    "\"KeePassPNStyle_Dynamic_Light\"," +
                    "\"KeePassPNStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
            manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
        }
    }

    sourceSets {
        getByName("libre") {
            res.srcDirs("src/libre/res")
        }
        getByName("free") {
            res.srcDirs("src/free/res")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media)
    // Lifecycle - ViewModel - Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    // Token auto complete
    // From sources until https://github.com/splitwise/TokenAutoComplete/pull/422 fixed
    implementation(libs.tokenautocomplete)
    // Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Utilities
    implementation(libs.androidx.autofill)
    implementation(libs.joda.time)
    implementation(libs.chroma)
    implementation(libs.taptargetview)
    implementation(libs.commons.io)
    // Credentials
    implementation(libs.nbvcxz)
    implementation(libs.androidx.credentials)
    // Modules import
    implementation(project(":database"))
    implementation(project(":icon-pack"))
    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
