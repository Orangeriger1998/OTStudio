package com.orangetiger.otstudio

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Make it longer, on Android: takes a clip's last frame for the next part, and joins clips into one MP4.
 * Each part has its own decoder setup, so copying frames across would break playback of later parts. Joining therefore
 * decodes every part straight into one encoder, which writes a single video with one setup, at a high bitrate.
 */
object VideoJoiner {
    /** The clip's last frame as a JPEG, for the next part's starting frame. */
    fun lastFrame(video: File): ByteArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw UserError("The clip's length could not be read.")
            // A little before the very end, where some files have no decodable frame.
            val frame: Bitmap = retriever.getFrameAtTime(maxOf(0, durationMs - 60) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw UserError("The clip's last frame could not be read.")
            return ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray().also { frame.recycle() }
        } finally { retriever.release() }
    }

    private const val TIMEOUT_US = 10_000L

    /** Joins [clips] in order into [out]. All clips must have the same picture size. */
    fun join(clips: List<File>, out: File) {
        require(clips.size >= 2)
        fun videoTrack(ex: MediaExtractor) = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw UserError("A clip has no video track.")
        val sizes = clips.map { file ->
            val ex = MediaExtractor().apply { setDataSource(file.path) }
            try { ex.getTrackFormat(videoTrack(ex)).let { Triple(it.getInteger(MediaFormat.KEY_WIDTH), it.getInteger(MediaFormat.KEY_HEIGHT), if (it.containsKey(MediaFormat.KEY_FRAME_RATE)) it.getInteger(MediaFormat.KEY_FRAME_RATE) else 16) } }
            finally { ex.release() }
        }
        if (sizes.map { it.first to it.second }.distinct().size != 1) throw UserError("The parts have different sizes, so they cannot be joined. Keep the same resolution for every part.")
        val (width, height, fps) = sizes.first()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // A generous bitrate, so the second encoding is hard to tell from the original.
            setInteger(MediaFormat.KEY_BIT_RATE, maxOf(8_000_000, width * height * fps.coerceAtLeast(16) / 2))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val encoded = MediaCodec.BufferInfo()
        /** Moves finished frames from the encoder into the file. Returns true at the end of the stream. */
        fun drainEncoder(untilEnd: Boolean): Boolean {
            while (true) {
                val index = encoder.dequeueOutputBuffer(encoded, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return false
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxing = true }
                    index >= 0 -> {
                        val data = encoder.getOutputBuffer(index) ?: throw UserError("The video encoder stopped.")
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoded.size = 0
                        if (encoded.size > 0 && muxing) { data.position(encoded.offset); data.limit(encoded.offset + encoded.size); muxer.writeSampleData(track, data, encoded) }
                        encoder.releaseOutputBuffer(index, false)
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            var offsetUs = 0L
            clips.forEach { file ->
                val ex = MediaExtractor().apply { setDataSource(file.path) }
                val t = videoTrack(ex)
                ex.selectTrack(t)
                val inFormat = ex.getTrackFormat(t)
                val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
                var lastUs = 0L
                var stepUs = 1_000_000L / fps.coerceAtLeast(1)
                try {
                    decoder.configure(inFormat, surface, null, 0)
                    decoder.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val buffer = decoder.getInputBuffer(inIndex)!!
                                val size = ex.readSampleData(buffer, 0)
                                if (size < 0) { decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                                else { decoder.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0); ex.advance() }
                            }
                        }
                        val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outIndex >= 0) {
                            val render = info.size > 0
                            if (render) {
                                if (info.presentationTimeUs > lastUs) { stepUs = info.presentationTimeUs - lastUs; lastUs = info.presentationTimeUs }
                                // The frame goes to the encoder with its place on the joined timeline.
                                decoder.releaseOutputBuffer(outIndex, (offsetUs + info.presentationTimeUs) * 1000)
                            } else decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        drainEncoder(false)
                    }
                } finally { decoder.stop(); decoder.release(); ex.release() }
                offsetUs += lastUs + stepUs
            }
            encoder.signalEndOfInputStream()
            drainEncoder(true)
            surface.release()
        } finally {
            try { encoder.stop() } catch (_: Exception) { }
            encoder.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) { }
            muxer.release()
        }
    }
}
