plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.tdttv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "es.tdttv.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.0"

        val listBase = (project.findProperty("tdttv.listBaseUrl") as String?) ?: ""
        buildConfigField("String", "LIST_BASE_URL", "\"$listBase\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        // Keystore de depuracion estandar de Android guardado en el repo (ver README): asi cualquier maquina firma
        // igual y la APK se instala encima de la anterior sin desinstalar.
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json real: el de android.jar en tests unitarios son stubs que no hacen nada
    testImplementation("org.json:json:20231013")
}
