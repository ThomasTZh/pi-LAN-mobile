package dev.pi.mobile.core

/**
 * The app renders plain text, but extensions and tools sometimes emit terminal
 * colour codes. Without this they show up as literal fragments such as
 * "[39m[2mReady[22;39m" in status chips, widgets, notices and tool output.
 *
 * Handles CSI (SGR colours, cursor movement), OSC (window titles) and the
 * single-character ESC forms, keeping every other character untouched.
 */
private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
private val CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
private val C1_CSI = Regex("\u009B\\[[0-9;?]*[ -/]*[@-~]")
private val SINGLE_ESC = Regex("\u001B[@-_]")

fun String.stripAnsi(): String {
    if (indexOf('\u001B') < 0 && indexOf('\u009B') < 0) return this
    return replace(OSC, "")
        .replace(CSI, "")
        .replace(C1_CSI, "")
        .replace(SINGLE_ESC, "")
}
