package dev.pi.mobile.net

/**
 * JSONL framing. The host speaks one JSON object per WebSocket text frame, but
 * fixtures, logs and any future streaming transport are line based, so the
 * reader is chunk tolerant and the writer emits exactly one line per record.
 */
object Jsonl {
    /** Splits already-buffered text into non-blank records, tolerating CRLF. */
    fun lines(raw: String): List<String> = raw.split('\n')
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }

    fun encode(record: String): String = if (record.endsWith("\n")) record else "$record\n"

    fun isRecord(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }
}

/**
 * Incremental line splitter for a byte/char stream. Feed arbitrary chunks;
 * complete lines come back in order and a trailing partial stays buffered.
 */
class JsonlBuffer(private val maxRecordChars: Int = 8 * 1024 * 1024) {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        if (buffer.length > maxRecordChars) {
            // Never grow without bound on a broken peer: drop the partial record.
            buffer.setLength(0)
            return emptyList()
        }
        val out = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < buffer.length) {
            if (buffer[index] == '\n') {
                appendLine(out, buffer.substring(start, index))
                start = index + 1
            }
            index++
        }
        if (start > 0) buffer.delete(0, start)
        return out
    }

    /** Trailing record without a newline terminator. */
    fun flush(): String? {
        if (buffer.isEmpty()) return null
        val rest = buffer.toString()
        buffer.setLength(0)
        return rest.trimEnd('\r').takeIf { it.isNotBlank() }
    }

    fun reset() {
        buffer.setLength(0)
    }

    private fun appendLine(out: MutableList<String>, raw: String) {
        val line = raw.trimEnd('\r')
        if (line.isNotBlank()) out.add(line)
    }
}
