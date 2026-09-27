import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun getDateTime(): String {
    val df = SimpleDateFormat("dd MMMMM yyyy")
    return "${df.format(Date())} г."
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}
val releaseSigningProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
// Бета ставится рядом с основным модом: свой applicationId и название (-PtvBeta=true).
val isTvBeta = (findProperty("tvBeta") as String?).toBoolean()

val hasReleaseSigningConfig = releaseSigningProperties.all {
    !localProperties.getProperty(it).isNullOrBlank()
}

android {
    namespace = "ru.radiationx.anilibria"

    compileSdk = libs.versions.app.compile.sdk.version.get().toInt()

    defaultConfig {
        applicationId = "ru.radiationx.anilibria.app.tv.mod"
        minSdk = libs.versions.tv.min.sdk.version.get().toInt()
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
        versionCode = libs.versions.tv.version.code.get().toInt()
        versionName = libs.versions.tv.version.name.get()
        buildConfigField("String", "BUILD_DATE", "\"${getDateTime()}\"")
        buildConfigField("boolean", "IS_BETA", "$isTvBeta")
        if (isTvBeta) {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "AniLiberty TV Beta"
        } else {
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += listOf("type")
    productFlavors {
        create("app") {
            dimension = "type"
            buildConfigField("boolean", "FOR_RUSTORE", "false")
        }

        create("rustore") {
            dimension = "type"
            buildConfigField("boolean", "FOR_RUSTORE", "true")
            versionName = "${libs.versions.tv.version.name.get()}-rustore"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val inputApkName = buildString {
            append("${project.name}-")
            variant.productFlavors.forEach { (dimension, flavor) ->
                append("${flavor}-")
            }
            variant.buildType?.also { append(it) }
            append(".apk")
        }
        val inputApkPath = buildString {
            append("outputs/apk/")
            variant.flavorName?.also { append("$it/") }
            variant.buildType?.also { append("$it/") }
            append(inputApkName)
        }
        val inputPath = project.layout.buildDirectory.file(inputApkPath).get().asFile

        val appName = if (isTvBeta) "AniLiberty_TV_Beta" else "AniLiberty_TV_Mod"
        val versionName = variant.outputs[0].versionName.get()
        val buildDateTime = SimpleDateFormat("yyyy-MM-dd_HH-mm").format(Date())
        val outputApkName = "${appName}_v${versionName}_${buildDateTime}.apk"

        val buildName = variant.name.capitalize()
        tasks.register<Copy>("copy${buildName}Apk") {
            description = "Copies $buildName APK to another folder"
            group = "custom"
            from(inputPath) {
                rename { outputApkName }
            }
            into("${rootProject.rootDir}/release-apks/")
            dependsOn(tasks.named("assemble$buildName"))
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.version.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)

    implementation(project(":data"))
    implementation(project(":shared-android-ktx"))
    implementation(project(":shared-app"))
    implementation(project(":quill-di"))
    // Трансформация размытия постера (ui/util/Blur.kt) для API < 31.
    implementation(libs.coil)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.androidx.tvprovider)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.cicerone)

    implementation(libs.androidx.tv.material)

    compileOnly(libs.toothpick)
    ksp(libs.toothpick.compiler)

    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.leanback)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)

    implementation(libs.mintpermissions)
    implementation(libs.mintpermissions.flows)

    implementation(libs.viewbindingpropertydelegate)
}
