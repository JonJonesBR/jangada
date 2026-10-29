// Biblioteca da tela de terminal do Termux (Apache 2.0), copiada sem alterações
// no código-fonte. Origem: github.com/termux/termux-app (pasta terminal-view).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api(project(":terminal-emulator"))
}
