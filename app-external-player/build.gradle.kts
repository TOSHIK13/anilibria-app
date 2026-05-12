import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.radiationx.externalplayer"

    compileSdk = libs.versions.app.compile.sdk.version.get().toInt()

    defaultConfig {
        applicationId = "ru.radiationx.externalplayer"
        minSdk = 23
        targetSdk = libs.versions.app.target.sdk.version.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
}

androidComponents {
    onVariants { variant ->
        val inputApkName = "${project.name}-${variant.name}.apk"
        val inputApkPath = "outputs/apk/${variant.name}/$inputApkName"
        val inputPath = project.layout.buildDirectory.file(inputApkPath).get().asFile
        val versionName = variant.outputs[0].versionName.get()
        val buildDateTime = SimpleDateFormat("yyyy-MM-dd_HH-mm").format(Date())
        val outputApkName = "AniLiberty_External_Player_Debug_v${versionName}_${buildDateTime}.apk"
        val buildName = variant.name.replaceFirstChar { it.uppercase() }

        tasks.register<Copy>("copy${buildName}Apk") {
            description = "Copies $buildName APK to release-apks"
            group = "custom"
            from(inputPath) {
                rename { outputApkName }
            }
            into("${rootProject.rootDir}/release-apks/")
            dependsOn(tasks.named("assemble$buildName"))
        }
    }
}

dependencies {
    implementation(project(":player-tv"))
}
