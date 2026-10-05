package com.orangetiger.otstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Expand on Android: builds the canvas and mask sent to the fill model, and puts the full-size result together
 * afterwards. The fill model works at about 1440 pixels at most, so only the new area comes from it. The phone
 * enlarges that area to full size and lays the original pixels back on top, blending only the edges that meet new picture.
 */
object Outpainter {
    /** The picture upright, scaled so the whole canvas fits [Outpaint.MAX_CANVAS], and where it sits on that canvas. */
    fun load(file: File, ratio: String): Pair<Bitmap, Outpaint.Layout> {
        val (w, h, rotation) = upright(file)
        val full = Outpaint.layout(w, h, ratio)
        val f = minOf(1.0, Outpaint.MAX_CANVAS.toDouble() / maxOf(full.canvasW, full.canvasH))
        val targetW = maxOf(1, Math.round(w * f).toInt()); val targetH = maxOf(1, Math.round(h * f).toInt())
        val stored = if (rotation % 180 == 0) w to h else h to w
        var sample = 1
        while (stored.first / (sample * 2) >= (if (rotation % 180 == 0) targetW else targetH) && stored.second / (sample * 2) >= (if (rotation % 180 == 0) targetH else targetW)) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UserError("This picture could not be read.")
        val turned = if (rotation == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== decoded) decoded.recycle() }
        val exact = if (turned.width == targetW && turned.height == targetH) turned else Bitmap.createScaledBitmap(turned, targetW, targetH, true).also { if (it !== turned) turned.recycle() }
        return exact to Outpaint.layout(exact.width, exact.height, ratio)
    }

    /** Real-ESRGAN on Replicate refuses inputs above 2,096,704 pixels (its GPU limit); Upscale is offered only below this. */
    const val UPSCALE_MAX_PIXELS = 2_000_000

    /** The picture's upright size in pixels, without decoding it. */
    fun size(file: File): Pair<Int, Int> = upright(file).let { it.first to it.second }

    /** Checks the shape before anything is uploaded: reads only the picture's size and orientation. */
    fun check(file: File, ratio: String): Outpaint.Layout { val (w, h, _) = upright(file); return Outpaint.layout(w, h, ratio) }

    private fun upright(file: File): Triple<Int, Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UserError("This picture could not be read.")
        val rotation = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        } catch (_: Exception) { 0 }
        return if (rotation % 180 == 0) Triple(bounds.outWidth, bounds.outHeight, rotation) else Triple(bounds.outHeight, bounds.outWidth, rotation)
    }

    /** The reduced canvas (JPEG, picture centered on gray) and its mask (PNG: white is filled, black is kept). */
    fun prepare(file: File, ratio: String): Pair<ByteArray, ByteArray> {
        val (picture, layout) = load(file, ratio)
        val send = Outpaint.send(layout)
        val s = send.scale
        val place = Rect((layout.x * s).toInt(), (layout.y * s).toInt(), Math.round((layout.x + layout.w) * s).toInt(), Math.round((layout.y + layout.h) * s).toInt())
        val canvasBitmap = Bitmap.createBitmap(send.w, send.h, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply { drawColor(Color.rgb(128, 128, 128)); drawBitmap(picture, null, place, Paint(Paint.FILTER_BITMAP_FLAG)) }
        picture.recycle()
        // The kept area stops a little short of the edges that meet new picture, so the model blends across them.
        // Only a quarter of the blend width: inside the rest, the model keeps the original, so the two versions agree.
        val f = maxOf(4, (Outpaint.feather(layout) * s / 4).toInt())
        val keep = Rect(place.left + if (layout.x > 0) f else 0, place.top + if (layout.y > 0) f else 0,
            place.right - if (layout.x > 0) f else 0, place.bottom - if (layout.y > 0) f else 0)
        val maskBitmap = Bitmap.createBitmap(send.w, send.h, Bitmap.Config.ARGB_8888)
        Canvas(maskBitmap).apply { drawColor(Color.WHITE); drawRect(keep, Paint().apply { color = Color.BLACK }) }
        val image = ByteArrayOutputStream().also { canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val mask = ByteArrayOutputStream().also { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        canvasBitmap.recycle(); maskBitmap.recycle()
        return image to mask
    }

    /** Writes the full-size result to [out]: the model's picture enlarged, with the original laid back on top. */
    fun finish(source: File, ratio: String, generated: File, out: File) {
        val (picture, layout) = load(source, ratio)
        val send = Outpaint.send(layout)
        val made = BitmapFactory.decodeFile(generated.path) ?: throw UserError("The expanded picture could not be read.")
        // The model may round the size; the canvas part is the same share of whatever came back.
        val cropW = minOf(made.width, Math.round(made.width * send.contentW.toDouble() / send.w).toInt())
        val cropH = minOf(made.height, Math.round(made.height * send.contentH.toDouble() / send.h).toInt())
        val result = Bitmap.createBitmap(layout.canvasW, layout.canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(made, Rect(0, 0, cropW, cropH), Rect(0, 0, layout.canvasW, layout.canvasH), Paint(Paint.FILTER_BITMAP_FLAG))
        made.recycle()
        matchColors(result, picture, layout)
        canvas.drawBitmap(feathered(picture, layout), layout.x.toFloat(), layout.y.toFloat(), null)
        picture.recycle()
        out.outputStream().use { if (!result.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw UserError("The expanded picture could not be saved.") }
        result.recycle()
    }

    /**
     * The fill model returns the kept area a little lighter or darker than the original. Each color channel of [result]
     * is shifted and scaled so that, where the original sits, it has the original's average and spread.
     */
    private fun matchColors(result: Bitmap, picture: Bitmap, layout: Outpaint.Layout) {
        fun stats(b: Bitmap, left: Int, top: Int): Array<DoubleArray> {
            val sum = DoubleArray(3); val sq = DoubleArray(3); var n = 0
            val row = IntArray(layout.w)
            var y = 0
            while (y < layout.h) {
                b.getPixels(row, 0, layout.w, left, top + y, layout.w, 1)
                var x = 0
                while (x < layout.w) {
                    val c = row[x]
                    val v = intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
                    for (k in 0..2) { sum[k] += v[k]; sq[k] += v[k] * v[k].toDouble() }
                    n++; x += 3
                }
                y += 3
            }
            return Array(3) { k -> val m = sum[k] / n; doubleArrayOf(m, Math.sqrt(maxOf(1.0, sq[k] / n - m * m))) }
        }
        val want = stats(picture, 0, 0)
        val have = stats(result, layout.x, layout.y)
        val gain = DoubleArray(3) { (want[it][1] / have[it][1]).coerceIn(0.8, 1.25) }
        val shift = DoubleArray(3) { want[it][0] - have[it][0] * gain[it] }
        val table = Array(3) { k -> IntArray(256) { v -> (v * gain[k] + shift[k]).toInt().coerceIn(0, 255) } }
        val w = result.width
        val row = IntArray(w)
        for (y in 0 until result.height) {
            result.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                row[x] = (c and -0x1000000) or (table[0][(c shr 16) and 255] shl 16) or (table[1][(c shr 8) and 255] shl 8) or table[2][c and 255]
            }
            result.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** The picture with its alpha fading to zero over [Outpaint.feather] pixels along the edges that meet new picture. */
    private fun feathered(picture: Bitmap, layout: Outpaint.Layout): Bitmap {
        val p = if (picture.config == Bitmap.Config.ARGB_8888 && picture.isMutable) picture else picture.copy(Bitmap.Config.ARGB_8888, true)
        // A decoded JPEG says it has no transparency, and drawing would then ignore the fade.
        p.setHasAlpha(true)
        val f = Outpaint.feather(layout)
        val sides = layout.x > 0; val ends = layout.y > 0
        val w = p.width; val h = p.height
        val row = IntArray(w)
        for (y in 0 until h) {
            val dy = if (ends) minOf(y, h - 1 - y) else Int.MAX_VALUE
            if (dy >= f && (!sides)) continue
            p.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (x in 0 until w) {
                val dx = if (sides) minOf(x, w - 1 - x) else Int.MAX_VALUE
                val d = minOf(dx, dy)
                if (d >= f) continue
                val a = (255 * d / f).coerceIn(0, 255)
                row[x] = (row[x] and 0x00FFFFFF) or (a shl 24); changed = true
            }
            if (changed) p.setPixels(row, 0, w, 0, y, w, 1)
        }
        return p
    }
}
