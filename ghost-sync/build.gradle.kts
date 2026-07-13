import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ghost)
    alias(libs.plugins.dokka)
    // Not needed by :ghost-sync's own code (FrozenHttpRequestMeta is @GhostSerialization) — only
    // by GhostSyncSerializerAgnosticTest, which proves the payload layer works with
    // kotlinx.serialization content negotiation too, not just Ghost's.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral()
    signAllPublications()
    coordinates(project.group.toString(), "ghost-sync", project.version.toString())

    pom {
        name.set("ghost-sync")
        description.set("Offline-first HTTP sync engine for Kotlin Multiplatform")
        url.set("https://github.com/ghostserializer/ghost-sync-kmp")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("ghostserializer")
                name.set("ghostserializer")
            }
        }
        scm {
            url.set("https://github.com/ghostserializer/ghost-sync-kmp")
            connection.set("scm:git:git://github.com/ghostserializer/ghost-sync-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/ghostserializer/ghost-sync-kmp.git")
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaHtml")))
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    jvm {
        withSourcesJar()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.ghost.api)
                api(libs.ghost.serialization)
                api(libs.ghost.ktor)
                implementation(libs.okio)
                implementation(libs.kotlinx.coroutines.core)
                // api: GhostSync's public API surfaces HttpClient/HttpClientEngineFactory/HttpClientConfig directly.
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}

ksp { arg("ghost.moduleName", "ghost_sync") }

android {
    namespace = "com.ghostserializer.sync"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
}
