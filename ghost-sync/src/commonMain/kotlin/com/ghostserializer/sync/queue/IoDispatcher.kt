package com.ghostserializer.sync.queue

import kotlinx.coroutines.CoroutineDispatcher

/** `Dispatchers.IO` isn't declared in `commonMain` (JVM/Native only — see [DiskQueue]'s own doc),
 * so each platform hands back its own. [DiskQueue] dispatches its blocking file I/O onto this
 * internally — callers never need to remember to do it themselves. */
internal expect val ioDispatcher: CoroutineDispatcher
