package com.retryjournal

import com.ghost.serialization.Ghost
import com.ghost.serialization.generated.GhostModuleRegistry_retry_journal
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.native.EagerInitialization
import kotlin.random.Random

// Some test classes (e.g. RecordCodecTest) call Ghost.encodeToBytes in a property initializer,
// which runs at instance construction — before any @BeforeTest gets a chance to register the
// module. On JVM/Android this registry is populated automatically; Kotlin/Native has no such
// discovery mechanism, so @EagerInitialization forces this to run at binary startup instead,
// before the test runner instantiates anything.
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val ghostRegistryRegistered = Ghost.addRegistry(GhostModuleRegistry_retry_journal.INSTANCE)

actual fun freshTestDir(prefix: String): Path {
    val dir = "/tmp/$prefix-${Random.nextLong().toString(36).removePrefix("-")}".toPath()
    FileSystem.SYSTEM.createDirectories(dir, mustCreate = true)
    return dir
}
