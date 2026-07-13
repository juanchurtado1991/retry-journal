package com.ghostserializer.sync.queue

import com.ghost.serialization.annotations.GhostSerialization

/**
 * Flat, map-free HTTP header bundle stored inside [FrozenHttpRequestMeta]. Two parallel [List]s
 * indexed together — no [Map] nodes, no hash buckets. Ghost serializes [List] of [String]; the
 * capture path fills reusable scratch arrays and only materializes these lists once per enqueue.
 *
 * Replay uses [HeaderDispatch][com.ghostserializer.sync.engine.HeaderDispatch] in the engine
 * (compare chain, not map lookup) over the two lists.
 */
@GhostSerialization
data class FrozenHttpHeaders(
    val names: List<String>,
    val values: List<String>,
) {
    val size: Int
        get() = names.size

    init {
        require(names.size == values.size) { SIZE_MISMATCH_MESSAGE }
    }

    inline fun forEach(action: (name: String, value: String) -> Unit) {
        for (index in names.indices) {
            action(names[index], values[index])
        }
    }

    fun findValue(name: String): String? {
        for (index in names.indices) {
            if (names[index].equals(name, ignoreCase = true)) {
                return values[index]
            }
        }
        return null
    }

    companion object {
        private const val SIZE_MISMATCH_MESSAGE: String = "FrozenHttpHeaders names/values size mismatch"

        val EMPTY: FrozenHttpHeaders = FrozenHttpHeaders(emptyList(), emptyList())

        fun of(vararg pairs: Pair<String, String>): FrozenHttpHeaders {
            val names = ArrayList<String>(pairs.size)
            val values = ArrayList<String>(pairs.size)
            for ((name, value) in pairs) {
                names.add(name)
                values.add(value)
            }
            return FrozenHttpHeaders(names, values)
        }

        fun fromScratch(names: Array<String>, values: Array<String>, count: Int): FrozenHttpHeaders {
            val nameList = ArrayList<String>(count)
            val valueList = ArrayList<String>(count)
            for (index in 0 until count) {
                nameList.add(names[index])
                valueList.add(values[index])
            }
            return FrozenHttpHeaders(nameList, valueList)
        }
    }
}
