import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing credentials live in keystore.properties at the repo root,
// which is NOT committed (see .gitignore). Loading is conditional so a fresh
// clone without the file still configures: assembleDebug works, and
// assembleRelease just yields an unsigned APK instead of failing the build.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.galaxy.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galaxy.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "2.14"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                // storeFile is relative to the repo root, so the properties
                // file stays free of machine-specific paths.
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 26 only strictly needs v2, but some ROM security
                // scanners and sideload installers still look for v1/v3.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // debug builds are signed with the public "Android Debug" key and
            // get android:debuggable="true"; both read as high risk to device
            // security scanners, so distribution builds must be release-signed.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    testImplementation("junit:junit:4.13.2")

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
