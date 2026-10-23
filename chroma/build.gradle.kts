plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kunzisoft.androidclearchroma"
    compileSdk = 36

    defaultConfig {
        minSdk = 19
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("androidx.legacy:legacy-preference-v14:1.0.0")
}
