plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "ru.radiationx.data"

    compileSdk = libs.versions.app.compile.sdk.version.get().toInt()

    defaultConfig {
        minSdk = libs.versions.tv.min.sdk.version.get().toInt()
        // Публичный client_id приложения AniList (implicit grant, не секрет); client secret не используется.
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"52291\"")
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
    }
    testOptions {
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.version.get().toInt())
}

dependencies {
    implementation(project(":shared-android-ktx"))
    implementation(project(":quill-di"))

    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    api(libs.okhttp.logging.interceptor)
    api(libs.okhttp.urlconnection)

    implementation(libs.chucker)

    api(libs.kotlin.coroutines.core)

    implementation(libs.kotlin.stdlib)

    api(libs.minitemplator)

    api(libs.timber)

    api(libs.conscrypt.android)

    api(libs.moshi)
    api(libs.moshi.adapters)
    ksp(libs.moshi.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.cronet)

    compileOnly(libs.toothpick)
    ksp(libs.toothpick.compiler)

    implementation(libs.androidnetworktools)

    testImplementation(libs.junit)
}

