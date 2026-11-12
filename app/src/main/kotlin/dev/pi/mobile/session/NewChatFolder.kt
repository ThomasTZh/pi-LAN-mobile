package dev.pi.mobile.session

/**
 * Pure rules for the new-chat workspace folder. The host validates the folder
 * again with `meta project.check`; these checks only keep obvious mistakes out
 * of the round trip and make the error message immediate.
 */
object NewChatFolder {
    const val MAX_LENGTH = 4096

    /** Returns a user-facing problem, or null when the path is plausible. */
    fun validate(raw: String): String? {
        val path = raw.trim()
        if (path.isEmpty()) return "Enter a folder path."
        if (path.length > MAX_LENGTH) return "That path is too long."
        if (!path.startsWith("/")) return "Use an absolute path, starting with /."
        if (path.contains("//")) return "Use one slash between folders."
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return "That path contains invalid characters."
        }
        return null
    }

    /** Trailing slashes are dropped; the root folder stays "/". */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }
}
