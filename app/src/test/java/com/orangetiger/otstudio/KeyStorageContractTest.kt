package com.orangetiger.otstudio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Saved API keys must survive app updates. These names are stored on users' phones; changing any of them
 * would silently make every saved key unreadable after an update. Do not edit this test to make a change pass.
 */
class KeyStorageContractTest {
    @Test fun savedKeyNamesNeverChange() {
        assertEquals("credentials", SettingsStore.KEY_FILE)
        assertEquals("otstudio-keys-v1", SettingsStore.KEY_ALIAS)
        assertEquals("SEARCH", SettingsStore.SEARCH_SLOT)
        assertEquals("SPICY", SettingsStore.SPICY_SLOT)
        assertEquals(listOf("CHAT", "IMAGE", "VIDEO"), Mode.entries.map(SettingsStore::slot))
        assertEquals("IMAGE|https://api.replicate.com:443", SettingsStore.aad("IMAGE", "https://api.replicate.com:443"))
    }

    @Test fun providerOriginsOfSavedKeysNeverChange() {
        // A key is bound to the origin it was saved for, in this exact form; a moved default would orphan it.
        assertEquals("https://openrouter.ai:443", Rules.origin(Rules.defaults(Mode.CHAT).baseUrl))
        assertEquals("https://api.replicate.com:443", Rules.origin(Rules.defaults(Mode.IMAGE).baseUrl))
        assertEquals("https://api.replicate.com:443", Rules.origin(Rules.defaults(Mode.VIDEO).baseUrl))
        assertEquals("https://api.tavily.com:443", Rules.origin(WebSearch.TAVILY_ORIGIN))
        assertEquals("https://api.spicyapi.com:443", Rules.origin(Rules.SPICY_BASE))
    }
}
