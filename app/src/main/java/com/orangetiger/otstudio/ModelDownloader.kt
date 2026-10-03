package com.orangetiger.otstudio

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

// Portable model download: plain java.net and java.io, so a desktop build can reuse it.

class FetchReply(val code: Int, private val headers: Map<String, String>, val body: InputStream?, private val onClose: () -> Unit) : AutoCloseable {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun close() { try { body?.close() } catch (_: Exception) { } ; onClose() }
}
/** Opens one GET without following redirects. [rangeFrom] > 0 asks for the rest of the file. */
fun interface Fetcher { fun get(url: String, rangeFrom: Long): FetchReply }

class HttpFetcher : Fetcher {
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { active?.disconnect() }
    override fun get(url: String, rangeFrom: Long): FetchReply {
        val c = URL(url).openConnection() as HttpURLConnection
        active = c
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "OTStudio/1.0 (github.com/Orangeriger1998/OTStudio)")
        // Identity encoding keeps byte counts and ranges exact.
        c.setRequestProperty("Accept-Encoding", "identity")
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        val code = c.responseCode
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
        val body = if (code in 200..299) c.inputStream else null
        return FetchReply(code, headers, body) { c.disconnect(); if (active === c) active = null }
    }
}

data class DownloadProgress(val done: Long, val total: Long, val verifying: Boolean = false)

/**
 * Downloads one GGUF file into [folder] with resume. A partial file stays as {name}.part until its size and
 * SHA-256 check out. No key is ever sent: catalog and custom models are public Hugging Face files.
 */
class ModelDownloader(private val folder: File, private val fetcher: Fetcher) {
    @Volatile private var stopRequested = false
    val stopping get() = stopRequested
    fun requestStop() { stopRequested = true }

    fun partFile(m: LocalModelInfo) = File(folder, m.localName + ".part")
    fun finalFile(m: LocalModelInfo) = File(folder, m.localName)

    /** Returns the verified file, or throws [UserError]. A pause throws [DownloadPaused] and keeps the partial file. */
    fun download(m: LocalModelInfo, freeBytes: () -> Long, progress: (DownloadProgress) -> Unit): File {
        stopRequested = false
        folder.mkdirs()
        val part = partFile(m)
        var have = if (part.exists()) part.length() else 0L
        if (m.bytes > 0 && have > m.bytes) { part.delete(); have = 0 }
        val (reply, expectedSize, expectedSha) = open(m, have)
        reply.use {
            when {
                reply.code == 206 -> {
                    val start = Regex("bytes (\\d+)-").find(reply.header("content-range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) throw UserError("The download server sent the wrong part of the file. Delete the partial download and start again.")
                }
                reply.code == 200 -> { if (have > 0) { part.delete(); have = 0 } }
                reply.code == 416 && have > 0 && have == expectedSize -> { }
                else -> throw UserError("Hugging Face refused the download (HTTP ${reply.code}).")
            }
            if (expectedSize <= 0) throw UserError("Hugging Face did not report the file size, so the download cannot be checked.")
            if (freeBytes() < expectedSize - have + RESERVE_BYTES) throw UserError("Not enough free space. This model needs ${gb(expectedSize - have + RESERVE_BYTES)} free.")
            if (reply.code != 416) RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                val buf = ByteArray(1 shl 16)
                var last = 0L
                val input = reply.body ?: throw UserError("Hugging Face sent an empty reply.")
                while (true) {
                    if (stopRequested) throw DownloadPaused()
                    val n = input.read(buf)
                    if (n < 0) break
                    have += n
                    if (have > expectedSize) throw UserError("The download is larger than Hugging Face reported. It was stopped.")
                    out.write(buf, 0, n)
                    if (have - last >= 4L shl 20) { last = have; progress(DownloadProgress(have, expectedSize)) }
                }
            }
            if (have != expectedSize) throw UserError("The download ended early. Tap Resume to continue it.")
            progress(DownloadProgress(have, expectedSize, verifying = true))
            val sha = sha256(part) { if (stopRequested) throw DownloadPaused() }
            if (expectedSha.isNotEmpty() && sha != expectedSha) {
                part.delete()
                throw UserError("The downloaded file does not match its published checksum, so it was deleted. Try again.")
            }
            val target = finalFile(m)
            if (!part.renameTo(target)) throw UserError("The finished model could not be saved. Check free space.")
            return target
        }
    }

    /** Follows Hugging Face's redirect to its file storage, collecting the size and SHA-256 it publishes. */
    private fun open(m: LocalModelInfo, from: Long): Triple<FetchReply, Long, String> {
        var url = m.url
        var size = m.bytes
        var sha = m.sha256
        for (hop in 0..5) {
            val reply = fetcher.get(url, from)
            reply.header("x-linked-size")?.toLongOrNull()?.let {
                if (size <= 0) size = it else if (size != it) { reply.close(); throw UserError("The file on Hugging Face changed size since OT Studio listed it. The download was not started.") }
            }
            // For files stored with Git LFS or Xet, the linked ETag is the file's SHA-256.
            reply.header("x-linked-etag")?.trim('"', ' ')?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let {
                if (sha.isEmpty()) sha = it else if (sha != it) { reply.close(); throw UserError("The file on Hugging Face changed since OT Studio listed it. The download was not started.") }
            }
            if (reply.code in 300..399) {
                val location = reply.header("location")
                reply.close()
                if (location.isNullOrBlank() || hop == 5) throw UserError("Hugging Face redirected the download too many times.")
                url = URI(url).resolve(location).toString()
                allowedHost(url)
                continue
            }
            if (size <= 0 && reply.code == 200) size = reply.header("content-length")?.toLongOrNull() ?: 0
            if (size <= 0 && reply.code == 206) size = reply.header("content-range")?.substringAfter('/')?.toLongOrNull() ?: 0
            return Triple(reply, size, sha)
        }
        throw UserError("Hugging Face redirected the download too many times.")
    }

    companion object {
        const val RESERVE_BYTES = 512L * 1024 * 1024
        fun gb(bytes: Long) = if (bytes < 1_000_000_000) "${bytes / 1_000_000} MB" else "%.1f GB".format(bytes / 1e9)

        /** Downloads may only travel between Hugging Face hosts over HTTPS. */
        fun allowedHost(url: String) {
            val uri = URI(url)
            val host = uri.host?.lowercase().orEmpty()
            val ok = host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co")
            if (uri.scheme != "https" || !ok || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
                throw UserError("The download was redirected away from Hugging Face (${uri.scheme}://${host.take(80)}). It was stopped.")
            }
        }

        fun sha256(file: File, check: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { check(); val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class DownloadPaused : Exception("Paused")
