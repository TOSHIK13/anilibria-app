plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.radiationx.player.tv"

    compileSdk = libs.versions.app.compile.sdk.version.get().toInt()

    defaultConfig {
        minSdk = 23
    }

    buildFeatures {
        compose = true
    }

    lint {
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
    }
    testOptions {
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
    }
}

dependencies {
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.tv.material)
    api(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
}
