import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val syncGroup = libs.versions.publish.group.get()
val syncVersion = libs.versions.publish.version.get()

group = syncGroup
version = syncVersion

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ghost) apply false
    alias(libs.plugins.nexus.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    tasks.withType<AbstractTestTask>().configureEach {
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/content/repositories/snapshots/"))
            username.set(project.findProperty("sonatypeUsername") as String?)
            password.set(project.findProperty("sonatypePassword") as String?)
            packageGroup.set(syncGroup)
        }
    }
}

tasks.register("ciTestJvm") {
    group = "verification"
    description = "JVM test modules (no emulador / sin macOS requerido)"
    dependsOn(":ghost-sync:jvmTest")
}
