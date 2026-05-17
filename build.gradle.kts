import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {

        // Android Gradle Plugin
        classpath(
            "com.android.tools.build:gradle:8.7.3"
        )

        // CloudStream Gradle Plugin
        classpath(
            "com.github.recloudstream:gradle:master-SNAPSHOT"
        )

        // Kotlin
        classpath(
            "org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0"
        )
    }
}

allprojects {

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(
    configuration: CloudstreamExtension.() -> Unit
) = extensions
    .getByName<CloudstreamExtension>("cloudstream")
    .configuration()

fun Project.android(
    configuration: BaseExtension.() -> Unit
) = extensions
    .getByName<BaseExtension>("android")
    .configuration()

subprojects {

    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {

        setRepo(
            System.getenv("GITHUB_REPOSITORY")
                ?: "https://github.com/durotun/cloudstream-extensions"
        )
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "11"
            // DIUBAH JADI FALSE: Mematikan kuncian ketat secara global agar warning Cinemax21, Dramacool, dll tidak merusak build Drakor
            allWarningsAsErrors = false
        }
    }

    android {

        compileSdkVersion(35)

        defaultConfig {
            minSdkVersion(21)
            targetSdkVersion(35)
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    dependencies {

        val stringFormat =
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.0"

        implementation(stringFormat)

        // =========================
        // NETWORK
        // =========================

        implementation(
            "com.github.Blatzar:NiceHttp:0.4.13"
        )

        implementation(
            "com.squareup.okhttp3:okhttp:4.12.0"
        )

        // =========================
        // HTML PARSER
        // =========================

        implementation(
            "org.jsoup:jsoup:1.18.3"
        )

        // =========================
        // JSON
        // =========================

        implementation(
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0"
        )

        implementation(
            "com.fasterxml.jackson.core:jackson-databind:2.16.0"
        )

        implementation(
            "com.google.code.gson:gson:2.11.0"
        )

        // =========================
        // JAVASCRIPT ENGINE
        // =========================

        implementation(
            "com.faendir.rhino:rhino-android:1.6.0"
        )

        implementation(
            "app.cash.quickjs:quickjs-android:0.9.2"
        )

        // =========================
        // UTILS
        // =========================

        implementation(
            "me.xdrop:fuzzywuzzy:1.4.0"
        )

        implementation(
            "androidx.core:core-ktx:1.16.0"
        )
    }
}

// =========================
// CLEAN
// =========================

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
