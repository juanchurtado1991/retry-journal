import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            // Ktorfit's KSP processor generates the MutationApi implementation once for the
            // common metadata compilation (kspCommonMainMetadata below) — per-target ksp<Target>
            // configurations alone only see that target's own sources, never commonMain's.
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        commonMain.dependencies {
            implementation(project(":retry-journal"))
            implementation(project(":retry-worker"))
            implementation(project(":retry-sample:shared"))
            implementation(libs.ghost.ktor)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktorfit.lib.light)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }

        androidMain {
            kotlin.srcDir("build/generated/ksp/android/androidDebug/kotlin")
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        val iosArm64Main by getting {
            kotlin.srcDir("build/generated/ksp/iosArm64/iosArm64Main/kotlin")
        }
        val iosSimulatorArm64Main by getting {
            kotlin.srcDir("build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin")
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(compose.desktop.currentOs)

            // Lets the desktop build run the chaos server in-process (MockServerController) so
            // testing needs one command and one window instead of a second terminal.
            implementation(project(":retry-sample:server"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.ktorfit.ksp)
    add("kspAndroid", libs.ktorfit.ksp)
    add("kspIosArm64", libs.ktorfit.ksp)
    add("kspIosSimulatorArm64", libs.ktorfit.ksp)
}

// KSP metadata processing must run before anything compiles against its output.
tasks.configureEach {
    val isSourcesJar = name.contains("sourcesJar", ignoreCase = true)
    if ((name.startsWith("compile") || name.startsWith("ksp") || isSourcesJar) && name != "kspCommonMainKotlinMetadata") {
        dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
    }
}

android {
    namespace = "com.retryjournal.sample.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.retryjournal.sample.app"
        // :retry-sample:shared's own minimum (unrelated to :retry-worker, whose minSdk is 21).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.retryjournal.sample.app.resources"
}

compose.desktop {
    application {
        mainClass = "com.retryjournal.sample.app.MainKt"
    }
}
