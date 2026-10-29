plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A chave de assinatura vem dos "secrets" do GitHub (nunca do repositório).
// Sem ela (forks, pull requests), o APK sai assinado com a chave de debug.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "io.github.jonjonesbr.jangada"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jonjonesbr.jangada"
        minSdk = 26
        // 28 de propósito, a mesma escolha do Termux: permite executar os programas
        // do ambiente Linux (Etapa 2) a partir da pasta do app. Ver README.
        targetSdk = 28
        versionCode = 20
        versionName = "0.7.4"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (signingStoreFile != null) {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingStoreFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
