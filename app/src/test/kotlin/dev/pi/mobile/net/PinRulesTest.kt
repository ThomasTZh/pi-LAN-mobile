package dev.pi.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinRulesTest {
    @Test
    fun acceptsSixDigitsWithSpaces() {
        assertEquals("123456", PinRules.strip("123 456"))
        assertEquals("123456", PinRules.strip("12 34 56"))
        assertEquals("123456", PinRules.normalize("123 456"))
        assertEquals("123456", PinRules.normalize(" 123456 "))
        assertNull(PinRules.validate("123 456"))
    }

    @Test
    fun rejectsWrongLengthOrLetters() {
        assertNull(PinRules.normalize(""))
        assertNull(PinRules.normalize("87146"))
        assertNull(PinRules.normalize("1234568"))
        assertNull(PinRules.normalize("87a467"))
        assertNull(PinRules.normalize("871-467"))
    }

    @Test
    fun validationMessages() {
        assertEquals("Enter the 6-digit PIN.", PinRules.validate(""))
        assertEquals("Enter the 6-digit PIN.", PinRules.validate("   "))
        assertEquals("The PIN is six digits.", PinRules.validate("12345"))
        assertEquals("The PIN is six digits.", PinRules.validate("1234567"))
        assertEquals("The PIN is six digits.", PinRules.validate("12a456"))
        assertEquals("The PIN contains unsupported characters.", PinRules.validate("12\n3456"))
    }

    @Test
    fun formatsForDisplay() {
        assertEquals("123 456", PinRules.format("123456"))
        assertEquals("12345", PinRules.format("12345"))
    }

    @Test
    fun masksForDiagnostics() {
        assertEquals("", PinRules.masked(""))
        assertEquals("••••••", PinRules.masked("123456"))
    }

    @Test
    fun maskDisplayKeepsTheGroupedLayout() {
        // Review F16: the field is masked by default, but the "123 456"
        // grouping (space) has to survive so the field does not jump.
        assertEquals("", PinRules.maskDisplay(""))
        assertEquals("••• •••", PinRules.maskDisplay("123 456"))
        assertEquals("••", PinRules.maskDisplay("70"))
        assertEquals("••••", PinRules.maskDisplay("1234"))
    }
}
