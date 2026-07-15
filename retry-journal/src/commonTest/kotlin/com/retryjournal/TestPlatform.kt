package com.retryjournal

import okio.Path

/** Creates a fresh, isolated directory for a single test run. */
expect fun freshTestDir(prefix: String = "retry-journal-test"): Path
