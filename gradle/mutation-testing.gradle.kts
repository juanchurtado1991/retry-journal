// Mutation testing (Pitest) for the core disk-queue/replay-engine classes — not wired into
// ciTestJvm since a full mutation run is much slower than the regular suite; run manually via
// `./gradlew :retry-journal:pitestCore` when you want to know whether the test suite's assertions
// are actually strong enough to catch a bug, not just whether they execute the line.
val pitest: Configuration by configurations.creating

dependencies {
    pitest("org.pitest:pitest-command-line:1.16.1")
}

tasks.register<JavaExec>("pitestCore") {
    group = "verification"
    description = "Mutation testing (Pitest) on DiskQueue/RetryJournalEngine/DeadLetterQueue/RecordCodec"

    dependsOn(":retry-journal:compileTestKotlinJvm")

    val mainClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    val testClasses = layout.buildDirectory.dir("classes/kotlin/jvm/test")
    val reportDir = layout.buildDirectory.dir("reports/pitest")

    inputs.dir(mainClasses)
    inputs.dir(testClasses)
    outputs.dir(reportDir)

    classpath = pitest +
        files(mainClasses, testClasses) +
        configurations.getByName("jvmRuntimeClasspath") +
        configurations.getByName("jvmTestRuntimeClasspath")
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")

    doFirst {
        args = listOf(
            "--reportDir", reportDir.get().asFile.path,
            "--sourceDirs", "src/commonMain/kotlin,src/jvmMain/kotlin",
            "--classPath", (pitest + files(mainClasses, testClasses)).asPath,
            "--targetClasses",
            listOf(
                "com.retryjournal.queue.disk.*",
                "com.retryjournal.engine.*",
                "com.retryjournal.deadletter.*",
                "com.retryjournal.queue.record.*",
                "com.retryjournal.queue.ReplayClaim",
                "com.retryjournal.queue.DeliveryJournal*",
            ).joinToString(","),
            "--targetTests", "com.retryjournal.*",
            "--outputFormats", "HTML",
            "--threads", "4",
            "--timeoutConst", "10000",
        )
    }
}
