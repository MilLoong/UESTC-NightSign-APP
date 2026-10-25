plugins { alias(libs.plugins.android.application) }

android {
    namespace = "edu.uestc.nightsign.bridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.milloong.uestc.nightsign.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
