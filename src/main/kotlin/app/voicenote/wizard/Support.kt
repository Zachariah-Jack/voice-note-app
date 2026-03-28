package app.voicenote.wizard

import java.util.UUID

interface EpochClock {
    fun nowEpochMillis(): Long
}

object SystemEpochClock : EpochClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

interface IdGenerator {
    fun nextId(prefix: String): String
}

object UuidIdGenerator : IdGenerator {
    override fun nextId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
