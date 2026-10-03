package com.orangetiger.otstudio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class LocalModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun catalogEntriesArePinnedAndDistinct() {
        val entries = LocalModelCatalog.entries
        assertEquals(7, entries.size)
        assertEquals("Huihui-Qwen3-4B-Instruct-2507-abliterated.i1-Q4_0.gguf", LocalModelCatalog.default.file)
        entries.forEach {
            assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.bytes > 1_000_000_000)
            assertTrue(it.file.endsWith(".gguf"))
            assertEquals(it.localName, HfLink.parse(it.url).localName)
        }
        assertEquals(entries.size, entries.map { it.localName }.toSet().size)
    }

    @Test fun huggingFaceLinksAreParsedStrictly() {
        val m = HfLink.parse("https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true")
        assertEquals("unsloth/Qwen3-4B-Instruct-2507-GGUF", m.repo)
        assertEquals("Qwen3-4B-Instruct-2507-Q4_K_M.gguf", m.file)
        assertEquals("https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf", m.url)
        // A blob page link becomes the matching download link.
        assertEquals(m.url, HfLink.parse("https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/blob/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf").url)
        assertEquals("sub/x.gguf", HfLink.parse("https://huggingface.co/a/b/resolve/main/sub/x.gguf").file)
        listOf(
            "http://huggingface.co/a/b/resolve/main/x.gguf",
            "https://huggingface.co.evil.com/a/b/resolve/main/x.gguf",
            "https://user@huggingface.co/a/b/resolve/main/x.gguf",
            "https://huggingface.co/a/b/resolve/main/x.bin",
            "https://huggingface.co/a/b/tree/main/x.gguf",
            "https://huggingface.co/a/b/resolve/main/../x.gguf",
            "https://huggingface.co/a/b/resolve/main/%2e%2e/x.gguf",
            "https://huggingface.co/a/b/resolve/main/x.gguf?token=1",
            "https://huggingface.co/a/b/resolve/main/x-00001-of-00002.gguf",
            "https://huggingface.co/a/b/resolve/main/mmproj-F16.gguf",
            "https://huggingface.co/a/b/resolve/main/x.gguf#f",
            "not a link"
        ).forEach { link -> assertThrows(link, UserError::class.java) { HfLink.parse(link) } }
    }

    @Test fun thinkingIsHidden() {
        assertEquals("Answer", ReplyText.visible("<think>\nplan\n</think>\n\nAnswer"))
        assertEquals("", ReplyText.visible("<think>still going"))
        assertEquals("Plain", ReplyText.visible("Plain"))
        assertEquals("plan", ReplyText.thinking("<think>\nplan\n</think>\n\nAnswer"))
        assertEquals("still going", ReplyText.thinking("<think>still going"))
        assertEquals("", ReplyText.thinking("Plain"))
    }

    @Test fun chatWindowKeepsTheNewestTurnsThatFit() {
        val count = { s: String -> s.length }
        val history = listOf(Turn("user", "a".repeat(40)), Turn("assistant", "b".repeat(40)), Turn("user", "c".repeat(10)), Turn("assistant", "d".repeat(10)))
        val all = ChatWindow.fit("sys", history, "now", 1000, 100, count)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), all.map { it.role })
        assertEquals("now", all.last().content)
        // Budget: 120 - 20 reply - (3+8) system - (3+8) prompt = 78, so only the last two short turns fit.
        val trimmed = ChatWindow.fit("sys", history, "now", 120, 20, count)
        assertEquals(listOf("c".repeat(10), "d".repeat(10), "now"), trimmed.drop(1).map { it.content })
        // A window never opens on an assistant reply without its question.
        val odd = ChatWindow.fit("", history, "now", 61, 20, count)
        assertEquals(listOf("user"), odd.map { it.role })
        assertThrows(UserError::class.java) { ChatWindow.fit("", emptyList(), "x".repeat(500), 100, 20, count) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A fake Hugging Face: resolve redirects to a storage host, which serves ranges. */
    private class FakeHub(val data: ByteArray, val sha: String, val storage: String = "https://cas-bridge.xethub.hf.co/x?sig=1") : Fetcher {
        val calls = mutableListOf<Pair<String, Long>>()
        var failAt = -1L
        override fun get(url: String, rangeFrom: Long): FetchReply {
            calls += url to rangeFrom
            if (url.startsWith("https://huggingface.co/")) return FetchReply(302, mapOf("location" to storage, "x-linked-size" to "${data.size}", "x-linked-etag" to "\"$sha\""), null) { }
            if (rangeFrom >= data.size) return FetchReply(416, emptyMap(), null) { }
            val end = if (failAt in (rangeFrom + 1)..data.size) failAt.toInt() else data.size
            val body = ByteArrayInputStream(data, rangeFrom.toInt(), end - rangeFrom.toInt())
            return if (rangeFrom > 0) FetchReply(206, mapOf("content-range" to "bytes $rangeFrom-${data.size - 1}/${data.size}"), body) { }
            else FetchReply(200, mapOf("content-length" to "${data.size}"), body) { }
        }
    }

    @Test fun downloadResumesWithRangeAndVerifiesTheChecksum() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val hub = FakeHub(data, sha(data)).apply { failAt = 100_000 }
        val m = LocalModelInfo("a/b", "x.gguf", bytes = data.size.toLong(), sha256 = sha(data))
        val folder = tmp.newFolder("models")
        val d = ModelDownloader(folder, hub)
        assertThrows(UserError::class.java) { d.download(m, { Long.MAX_VALUE }) { } }
        assertEquals(100_000L, d.partFile(m).length())
        hub.failAt = -1
        val file = d.download(m, { Long.MAX_VALUE }) { }
        assertArrayEquals(data, file.readBytes())
        assertFalse(d.partFile(m).exists())
        assertEquals(100_000L, hub.calls.last().second)
        assertEquals("https://cas-bridge.xethub.hf.co/x?sig=1", hub.calls.last().first)
    }

    @Test fun downloadRefusesBadChecksumsHostsSizesAndLowSpace() {
        val data = ByteArray(50_000) { 7 }
        val folder = tmp.newFolder("m2")
        // The published checksum disagrees with the bytes: the partial file is deleted.
        val wrong = "0".repeat(64)
        val custom = LocalModelInfo("a/b", "y.gguf")
        assertThrows(UserError::class.java) { ModelDownloader(folder, FakeHub(data, wrong)).download(custom, { Long.MAX_VALUE }) { } }
        assertFalse(File(folder, custom.localName + ".part").exists())
        // A redirect away from Hugging Face stops before any byte is fetched there.
        val evil = FakeHub(data, sha(data), storage = "https://evil.example/x")
        assertThrows(UserError::class.java) { ModelDownloader(folder, evil).download(custom, { Long.MAX_VALUE }) { } }
        assertTrue(evil.calls.none { it.first.startsWith("https://evil") })
        // The catalog pins the size, so a changed file is refused.
        val pinned = LocalModelInfo("a/b", "z.gguf", bytes = 49_999)
        assertThrows(UserError::class.java) { ModelDownloader(folder, FakeHub(data, sha(data))).download(pinned, { Long.MAX_VALUE }) { } }
        // A pinned checksum that differs from the published one is refused before downloading.
        val pinnedSha = LocalModelInfo("a/b", "w.gguf", sha256 = "1".repeat(64))
        assertThrows(UserError::class.java) { ModelDownloader(folder, FakeHub(data, sha(data))).download(pinnedSha, { Long.MAX_VALUE }) { } }
        // Too little free space.
        assertThrows(UserError::class.java) { ModelDownloader(folder, FakeHub(data, sha(data))).download(custom, { 1000L }) { } }
    }

    @Test fun pausingKeepsThePartialFile() {
        val data = ByteArray(1_000_000) { 3 }
        val folder = tmp.newFolder("m3")
        val m = LocalModelInfo("a/b", "p.gguf")
        lateinit var d: ModelDownloader
        val hub = object : Fetcher {
            val inner = FakeHub(data, sha(data))
            override fun get(url: String, rangeFrom: Long): FetchReply {
                val r = inner.get(url, rangeFrom)
                if (r.code != 200) return r
                // Ask for a pause after the first block arrives.
                val body = object : java.io.InputStream() {
                    var n = 0
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int { if (n++ == 1) d.requestStop(); return r.body!!.read(b, off, len) }
                }
                return FetchReply(200, mapOf("content-length" to "${data.size}"), body) { }
            }
        }
        d = ModelDownloader(folder, hub)
        assertThrows(DownloadPaused::class.java) { d.download(m, { Long.MAX_VALUE }) { } }
        assertTrue(d.partFile(m).length() in 1 until data.size.toLong())
        assertFalse(d.finalFile(m).exists())
    }
}
