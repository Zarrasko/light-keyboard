package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationCleanupTest {
    @Test fun standaloneI_getsCapitalized() {
        assertEquals("I think so", DictationCleanup.applyCasing("i think so", false))
        assertEquals("I think so", DictationCleanup.applyCasing("i think so", true))
        assertEquals("yeah I think so", DictationCleanup.applyCasing("yeah i think so", false))
        assertEquals("I", DictationCleanup.applyCasing("i", false))
        assertEquals("I'm going", DictationCleanup.applyCasing("i'm going", false))
        assertEquals("hi there", DictationCleanup.applyCasing("hi there", false))
        assertEquals("ibm made this", DictationCleanup.applyCasing("ibm made this", false))
    }
}
