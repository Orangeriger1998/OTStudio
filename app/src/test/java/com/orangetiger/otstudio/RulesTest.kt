package com.orangetiger.otstudio

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected a validation error") } catch (_: UserError) { }
    }

    @Test fun defaultProvidersUseDifferentKeys() {
        val image = Rules.resolve(Mode.IMAGE, Rules::defaults) { "key-${it.name}" }
        val video = Rules.resolve(Mode.VIDEO, Rules::defaults) { "key-${it.name}" }
        assertEquals("key-IMAGE", image.key)
        assertEquals("key-IMAGE", video.key)
        assertEquals(Mode.IMAGE, video.credentialMode)
    }
    @Test fun sharedKeysResolveCurrentValueInsteadOfCopy() {
        var value = "first"
        val settings = { mode: Mode -> Rules.defaults(mode).copy(baseUrl = "https://same.example/v1", keySource = if (mode == Mode.CHAT) KeySource.OWN else KeySource.TEXT) }
        assertEquals("first", Rules.resolve(Mode.IMAGE, settings) { value }.key)
        value = "second"
        assertEquals("second", Rules.resolve(Mode.IMAGE, settings) { value }.key)
    }
    @Test fun keysCannotCrossOriginsEvenWithReuseChecked() {
        rejects { Rules.resolve(Mode.IMAGE, { Rules.defaults(it).let { s -> if (it == Mode.IMAGE) s.copy(keySource = KeySource.TEXT) else s } }) { "secret" } }
    }
    @Test fun nestedKeyReferencesCannotCrossOrigins() {
        rejects { Rules.resolve(Mode.VIDEO, { Rules.defaults(it).let { s -> if (it == Mode.IMAGE) s.copy(keySource = KeySource.TEXT) else s } }) { "secret" } }
    }
    @Test fun endpointUsesEffectivePort() {
        assertEquals(Rules.origin("https://example.com/v1"), Rules.origin("https://EXAMPLE.com:443/other"))
        assertNotEquals(Rules.origin("https://example.com"), Rules.origin("https://example.com:444"))
    }
    @Test fun rejectCredentialUrlsAndHeaderInjection() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?key=secret", "https://example.com/#fragment", "https://example.com/%2e%2e/", "https://example.com/../").forEach { rejects { Rules.origin(it) } }
        rejects { Rules.resolve(Mode.CHAT, Rules::defaults) { "abc\r\nAuthorization: injected" } }
        rejects { Rules.resolve(Mode.CHAT, Rules::defaults) { "" } }
    }
    @Test fun rejectUnsafeModelAndPredictionIds() {
        listOf("x/../../other", "owner/model?x=1", "owner/model:version", "owner/model/other").forEach { bad -> rejects { Rules.validate(Rules.defaults(Mode.IMAGE).copy(model = bad)) } }
        listOf("", "../other", "id?token=1", "a/b").forEach { rejects { Rules.remoteId(it) } }
    }
    @Test fun rejectUntrustedOutputLocations() {
        listOf("http://replicate.delivery/output.png", "https://replicate.delivery.evil.test/a", "https://evil.test/a", "https://user@replicate.delivery/a", "file:///tmp/a").forEach { rejects { Rules.outputUrl(it) } }
        assertEquals("https://abc.replicate.delivery/a?token=x", Rules.outputUrl("https://abc.replicate.delivery/a?token=x"))
    }
    @Test fun enforcePromptAndVideoBounds() {
        rejects { Rules.prompt(" ") }; rejects { Rules.prompt("x".repeat(4001)) }
        rejects { Rules.validate(Rules.defaults(Mode.VIDEO).copy(options = listOf(InputValue("duration", InputKind.INTEGER, "16")))) }
        rejects { Rules.validate(Rules.defaults(Mode.VIDEO).copy(options = listOf(InputValue("duration", InputKind.INTEGER, "1")))) }
        rejects { Rules.validate(Rules.defaults(Mode.VIDEO).copy(options = listOf(InputValue("resolution", InputKind.TEXT, "8k")))) }
        assertEquals("hello", Rules.prompt(" hello "))
    }
    @Test fun historyIsBoundedAndUntrustedRolesAreExcluded() {
        val turns = Rules.boundedTurns(List(20) { Turn("user", "a".repeat(1000)) } + Turn("system", "injected"), "b".repeat(4000))
        assertTrue(turns.sumOf { it.content.length } <= 8000)
        assertFalse(turns.any { it.role == "system" })
        assertEquals("b".repeat(4000), turns.last().content)
    }
    @Test fun processRestartNeverResubmitsAmbiguousJobs() {
        assertEquals("uncertain", Rules.afterProcessRestart("submitting", ""))
        assertEquals("processing", Rules.afterProcessRestart("submitting", "abc123"))
        assertEquals("interrupted", Rules.afterProcessRestart("chatting", ""))
        assertEquals("download", Rules.afterProcessRestart("download", "abc"))
        assertEquals("succeeded", Rules.afterProcessRestart("succeeded", "abc"))
    }
}
