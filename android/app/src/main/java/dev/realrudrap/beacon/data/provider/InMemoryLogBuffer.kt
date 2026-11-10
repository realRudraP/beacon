package dev.realrudrap.beacon.data.provider

import android.util.Log
import timber.log.Timber
import java.time.Instant
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryLogBuffer @Inject constructor() {
    private val lines = ArrayDeque<String>()
    private var characterCount = 0

    @Synchronized
    fun add(priority: Int, tag: String?, message: String?, throwable: Throwable?) {
        val line = buildString {
            append(Instant.now())
            append(' ')
            append(priorityLabel(priority))
            append('/')
            append(tag ?: "-")
            append(": ")
            append(message ?: "")
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable))
            }
        }

        lines.addLast(line)
        characterCount += line.length + 1

        while (lines.size > MAX_LINES) {
            characterCount -= lines.removeFirst().length + 1
        }
        while (characterCount > MAX_CHARACTERS && lines.isNotEmpty()) {
            characterCount -= lines.removeFirst().length + 1
        }
    }

    @Synchronized
    fun snapshot(): String {
        if (lines.isEmpty()) return ""

        var total = 0
        val selected = ArrayList<String>()
        val iterator = lines.descendingIterator()
        while (iterator.hasNext()) {
            val line = iterator.next()
            if (selected.isNotEmpty() && total + line.length > MAX_CHARACTERS) break
            selected.add(line)
            total += line.length + 1
        }
        return selected.asReversed().joinToString("\n")
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    private companion object {
        const val MAX_LINES = 250
        const val MAX_CHARACTERS = 32_000
    }
}

class BufferedLogTree(
    private val buffer: InMemoryLogBuffer
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        buffer.add(priority, tag, message, t)
    }
}
