plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasspn.icon.classic"
    compileSdk = 36

    defaultConfig {
        minSdk = 19
    }

    resourcePrefix = "classic_"
}
