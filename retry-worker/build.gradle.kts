import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

ext.set(
    "pomDescription",
    "Out-of-the-box background scheduling (WorkManager / BGTaskScheduler) for :retry-journal",
)
apply(from = "../gradle/publishing.gradle")

mavenPublishing {
    // See :retry-journal/build.gradle.kts's identical comment — Dokka V1's dokkaHtml task no
    // longer runs under Dokka 2.2.0's default V2 mode, and an empty javadoc jar avoids the extra
    // upload weight against Sonatype's monthly limit while still satisfying Maven Central's
    // requirement that a `-javadoc.jar` exist.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}

apiValidation {
    klib {
        enabled = true
    }
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
                api(project(":retry-journal"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.work.runtime.ktx)
        }
    }
}

android {
    namespace = "com.retryjournal.worker"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // WorkManager logs via android.util.Log (e.g. clamping an interval below its minimum) —
        // the stub android.jar throws on unmocked calls unless this is set, even though nothing
        // here needs a real Android runtime otherwise.
        unitTests.isReturnDefaultValues = true
    }
}
