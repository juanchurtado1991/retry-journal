import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ghost)
    alias(libs.plugins.dokka)
    // Not needed by :retry-journal's own code (FrozenHttpRequestMeta is @GhostSerialization) — only
    // by RetryJournalSerializerAgnosticTest, which proves the payload layer works with
    // kotlinx.serialization content negotiation too, not just Ghost's.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

apply(from = "../gradle/publishing.gradle")

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaHtml")))
}

kotlin {
    compilerOptions {
        // TestCounter (expect/actual) is a deliberate multiplatform test helper, not an accident.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

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
                // api: RetryJournal's public API surfaces HttpClient/HttpClientEngineFactory/HttpClientConfig directly.
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

ksp { arg("ghost.moduleName", "retry_journal") }

android {
    namespace = "com.retryjournal"
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

apply(from = "../gradle/kover.gradle")

kover {
    reports {
        total {
            verify {
                rule("jvm-line-coverage") {
                    groupBy.set(GroupingEntityType.APPLICATION)
                    minBound(90)
                }
            }
        }
    }
}

apply(from = "../gradle/warnings.gradle.kts")
