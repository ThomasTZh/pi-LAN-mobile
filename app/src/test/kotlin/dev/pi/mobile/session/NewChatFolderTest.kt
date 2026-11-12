package dev.pi.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatFolderTest {
    @Test
    fun acceptsAnAbsoluteFolder() {
        assertNull(NewChatFolder.validate("/home/user/projects/pi"))
        assertNull(NewChatFolder.validate("  /srv/work  "))
    }

    @Test
    fun rejectsEmptyAndRelativePaths() {
        assertNotNull(NewChatFolder.validate(""))
        assertNotNull(NewChatFolder.validate("   "))
        assertNotNull(NewChatFolder.validate("projects/pi"))
        assertNotNull(NewChatFolder.validate("~/projects/pi"))
    }

    @Test
    fun rejectsDoubleSlashesAndControlCharacters() {
        assertNotNull(NewChatFolder.validate("/home//user"))
        assertNotNull(NewChatFolder.validate("/home/user\nrm -rf"))
    }

    @Test
    fun normalizesTrailingSlashesButKeepsRoot() {
        assertEquals("/home/user/projects", NewChatFolder.normalize("/home/user/projects/"))
        assertEquals("/home/user/projects", NewChatFolder.normalize(" /home/user/projects "))
        assertEquals("/", NewChatFolder.normalize("/"))
        assertEquals("/", NewChatFolder.normalize("//"))
    }
}
