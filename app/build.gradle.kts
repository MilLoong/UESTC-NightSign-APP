import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val uploadKeystorePropertiesFile = rootProject.file("keystore.properties")
val uploadKeystoreProperties = Properties().apply {
    if (uploadKeystorePropertiesFile.exists()) uploadKeystorePropertiesFile.inputStream().use { load(it) }
}

fun uploadSigningReady(): Boolean {
    if (!uploadKeystorePropertiesFile.exists()) return false
    val path = uploadKeystoreProperties.getProperty("storeFile")?.trim().orEmpty()
    if (path.isEmpty()) return false
    return rootProject.file(path).isFile
}

android {
    namespace = "edu.uestc.nightsign"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.milloong.uestc.nightsign"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val tencentMapKey = localProperties.getProperty("tencent.map.key").orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "TENCENT_MAP_KEY", "\"$tencentMapKey\"")
    }
    signingConfigs {
        create("upload") {
            if (uploadSigningReady()) {
                val storePass = uploadKeystoreProperties.getProperty("storePassword")
                storeFile = rootProject.file(uploadKeystoreProperties.getProperty("storeFile")!!.trim())
                storePassword = storePass
                keyAlias = uploadKeystoreProperties.getProperty("keyAlias")
                keyPassword = storePass
            }
        }
    }
    buildTypes {
        debug {
            if (uploadSigningReady()) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

afterEvaluate {
    tasks.named("assembleDebug").configure {
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
            val source = apkDir.resolve("app-debug.apk")
            if (!source.isFile) return@doLast
            source.copyTo(apkDir.resolve("UESTC-NightSign.apk"), overwrite = true)
        }
    }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
