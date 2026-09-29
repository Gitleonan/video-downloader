import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    id("com.android.application")
}

android {
    namespace = "com.galaxy.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galaxy.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Ship the APK under the app's real name instead of app-debug.apk.
// outputFileName lives on the impl type, not on the public VariantOutput.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set("Video Downloader.apk")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    constraints {
        // appcompat pulls in old split kotlin-stdlib artifacts that
        // duplicate classes inside kotlin-stdlib 1.8+; pin them to the
        // merged versions per the official migration guidance.
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22") {
            because("aligns kotlin-stdlib-jdk7 with the merged kotlin-stdlib 1.8.22")
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22") {
            because("aligns kotlin-stdlib-jdk8 with the merged kotlin-stdlib 1.8.22")
        }
    }
}
