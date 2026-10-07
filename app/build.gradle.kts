plugins { id("com.android.application") }
android {
    namespace = "dev.repro.nativegles"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.repro.nativegles"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
