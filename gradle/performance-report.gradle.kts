// Manual, complete performance report for DiskQueue — speed of every operation plus memory used
// by the in-memory live-entry index. Not a test, not wired into any CI task. Run via
// `./gradlew :retry-journal:performanceReport`. See
// retry-journal/src/jvmTest/kotlin/com/retryjournal/tools/PerformanceReport.kt and
// docs/development.md's Contributing section for the reference numbers this produces.
tasks.register<JavaExec>("performanceReport") {
    group = "verification"
    description = "Real speed (per-operation timing) and memory (JOL) numbers for DiskQueue"

    dependsOn(":retry-journal:compileTestKotlinJvm")

    val mainClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val testClasses = layout.buildDirectory.dir("classes/kotlin/jvm/test")

    classpath = files(mainClasses, testClasses) +
        configurations.getByName("jvmRuntimeClasspath") +
        configurations.getByName("jvmTestRuntimeClasspath")
    mainClass.set("com.retryjournal.tools.PerformanceReportKt")

    // JOL tries (and fails, harmlessly) to self-attach a Java agent for the most precise
    // measurement mode; this just quiets the warning and falls back to its next-best estimator.
    jvmArgs = listOf("-Djdk.attach.allowAttachSelf=true")
}
