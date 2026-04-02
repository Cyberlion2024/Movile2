plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movile2.bot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.movile2.bot"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // In CI le variabili d'ambiente vengono iniettate dal workflow GitHub Actions.
    // In locale, se non ci sono env var, la release usa il debug keystore standard.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPass = System.getenv("KEY_STORE_PASS")
            val kAlias = System.getenv("KEY_ALIAS")
            val kPass  = System.getenv("KEY_PASS")
            if (ksPath != null && ksPass != null && kAlias != null && kPass != null) {
                storeFile     = file(ksPath)
                storePassword = ksPass
                keyAlias      = kAlias
                keyPassword   = kPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
