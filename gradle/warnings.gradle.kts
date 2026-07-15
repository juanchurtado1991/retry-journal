val suppressGhostKspWarnings = tasks.register("suppressGhostKspWarnings") {
    dependsOn(tasks.matching { it.name.startsWith("kspKotlin") })
    doLast {
        val kspDir = layout.buildDirectory.dir("generated/ksp").get().asFile
        if (kspDir.exists()) {
            kspDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.startsWith("GhostModuleRegistry_") && file.extension == "kt") {
                    val content = file.readText()
                    val suppression = "@file:Suppress(\"OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE\")\n"
                    if (!content.startsWith(suppression)) {
                        file.writeText(suppression + content)
                    }
                }
            }
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(suppressGhostKspWarnings)
}
