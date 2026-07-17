plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":retry-sample:shared"))
    implementation(libs.ghost.ktor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.retryjournal.sample.server.MainKt")
}

kotlin {
    jvmToolchain(17)
}
