plugins {
    id("com.android.application") version "9.0.1"
}

android {
    namespace = "com.microwifi.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.microwifi.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Reproduce el aviso BSD también en la distribución binaria, sin duplicar el archivo fuente.
    implementation(files(tasks.register<Jar>("rnnoiseLicense") {
        archiveFileName = "rnnoise-license.jar"
        destinationDirectory = layout.buildDirectory.dir("generated/rnnoiseLicense")
        from("src/main/cpp/rnnoise/COPYING") {
            into("META-INF")
            rename { "RNNOISE-LICENSE.txt" }
        }
    }))
    testImplementation("junit:junit:4.13.2")
}

// Las anotaciones transitivas de Kotlin solo se necesitan durante la compilación.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains", module = "annotations")
}
