plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "africa.saritunnel"
    compileSdk = 35

    defaultConfig {
        applicationId = "africa.saritunnel"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    // Real in-app WireGuard userspace tunnel engine.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // Built by scripts/build-xray-aar.sh from xray-go during preBuild / GitHub Actions.
    implementation(files("libs/xray.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
}

val xrayAarFile = layout.projectDirectory.file("libs/xray.aar").asFile
val buildXrayAar by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds the embedded Xray core for VLESS through gomobile. Requires Go and Android NDK."
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-xray-aar.sh")
    inputs.file(rootProject.file("xray-go/go.mod"))
    inputs.file(rootProject.file("xray-go/xray_bridge.go"))
    inputs.file(rootProject.file("scripts/build-xray-aar.sh"))
    outputs.file(xrayAarFile)
}

tasks.named("preBuild") {
    dependsOn(buildXrayAar)
}

