package dev.pi.mobile.net

/**
 * The Pi LAN host prints its PIN as six digits with a space ("123 456").
 * Accepted input is exactly six digits, optionally split by any spaces.
 */
object PinRules {
    const val LENGTH = 6

    /** Digits with every space removed, ready for the login form. */
    fun strip(raw: String): String = raw.filterNot { it == ' ' || it == '\t' }

    /** Six digits or null. */
    fun normalize(raw: String): String? {
        val digits = strip(raw)
        if (digits.length != LENGTH) return null
        if (!digits.all { it in '0'..'9' }) return null
        return digits
    }

    /** User-facing error, or null when the PIN can be submitted. */
    fun validate(raw: String): String? {
        if (strip(raw).isEmpty()) return "Enter the 6-digit PIN."
        if (raw.any { it.isISOControl() }) return "The PIN contains unsupported characters."
        val digits = strip(raw)
        if (!digits.all { it in '0'..'9' }) return "The PIN is six digits."
        if (digits.length != LENGTH) return "The PIN is six digits."
        return null
    }

    /** Display form: "123 456" for a complete PIN, otherwise the raw input. */
    fun format(raw: String): String {
        val digits = strip(raw)
        if (digits.length != LENGTH || !digits.all { it in '0'..'9' }) return raw
        return "${digits.substring(0, 3)} ${digits.substring(3)}"
    }

    /** Never log a PIN; this form is safe for diagnostics. */
    fun masked(raw: String): String = if (strip(raw).isEmpty()) "" else "•".repeat(LENGTH)

    /**
     * Display mask for the PIN field: every digit becomes a bullet, the layout
     * (including the "123 456" group space) is preserved so the field width and
     * caret positions do not jump while typing. Review F16.
     */
    fun maskDisplay(text: String): String = buildString(text.length) {
        for (char in text) append(if (char.isDigit()) '\u2022' else char)
    }
}
