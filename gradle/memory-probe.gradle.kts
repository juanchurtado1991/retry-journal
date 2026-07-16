// Manual memory diagnostic for DiskQueue's in-memory live-entry index — not a test, not wired
// into any CI task. Run via `./gradlew :retry-journal:memoryProbe` when you want real (JOL-measured,
// not guessed) numbers on how liveOffsetsBySequence scales with backlog size. See
// retry-journal/src/jvmTest/kotlin/com/retryjournal/tools/MemoryProbe.kt and docs/development.md's
// Contributing section for the reference table this produces.
tasks.register<JavaExec>("memoryProbe") {
    group = "verification"
    description = "Measures DiskQueue's liveOffsetsBySequence memory footprint at increasing backlog sizes"

    dependsOn(":retry-journal:compileTestKotlinJvm")

    val mainClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val testClasses = layout.buildDirectory.dir("classes/kotlin/jvm/test")

    classpath = files(mainClasses, testClasses) +
        configurations.getByName("jvmRuntimeClasspath") +
        configurations.getByName("jvmTestRuntimeClasspath")
    mainClass.set("com.retryjournal.tools.MemoryProbeKt")

    // JOL tries (and fails, harmlessly) to self-attach a Java agent for the most precise
    // measurement mode; this just quiets the warning and falls back to its next-best estimator.
    jvmArgs = listOf("-Djdk.attach.allowAttachSelf=true")
}
