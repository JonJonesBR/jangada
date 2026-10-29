// Biblioteca do emulador de terminal do Termux (Apache 2.0), copiada sem alterações
// no código-fonte. Origem: github.com/termux/termux-app (pasta terminal-emulator).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 35

    // No GitHub Actions o NDK já vem instalado; usar o que está lá evita download.
    // A versão declarada precisa ser a mesma da pasta, senão o Gradle recusa.
    System.getenv("ANDROID_NDK_HOME")?.let { file(it) }?.takeIf { it.isDirectory }?.let { ndkDir ->
        ndkPath = ndkDir.absolutePath
        ndkVersion = ndkDir.resolve("source.properties").takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("Pkg.Revision") }
            ?.substringAfter("=")?.trim()
            ?: ndkDir.name
    }

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
