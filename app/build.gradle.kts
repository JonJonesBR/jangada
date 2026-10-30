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
        versionCode = 107
        versionName = "1.3.0"
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
    testOptions {
        // Testes rodam na JVM do CI; chamadas ao Android (Log etc.) devolvem o padrão.
        unitTests.isReturnDefaultValues = true
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

    testImplementation("junit:junit:4.13.2")
    // O org.json do Android não funciona fora do celular; nos testes, usa a biblioteca original.
    testImplementation("org.json:json:20260814")
}

// Mostra no log do CI o motivo de cada teste que falhar.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
