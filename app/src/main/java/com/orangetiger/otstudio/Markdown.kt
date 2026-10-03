package com.orangetiger.otstudio

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Shows the small part of Markdown that chat models use: headings, bold, italics, inline code, and bullets.
 * Everything else stays as plain text. The reply is untrusted, so nothing here follows links or loads anything.
 */
object Markdown {
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|`([^`]+)`""")

    fun styled(text: String): CharSequence {
        val out = SpannableStringBuilder()
        text.split('\n').forEachIndexed { i, raw ->
            if (i > 0) out.append('\n')
            var line = raw
            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) line = line.substring(heading.value.length)
            Regex("^(\\s*)[*-]\\s+").find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.value.length) }
            val start = out.length
            var last = 0
            for (m in inline.findAll(line)) {
                out.append(line, last, m.range.first)
                val s = out.length
                val (body, span) = when {
                    m.groups[1] != null -> m.groupValues[1] to StyleSpan(Typeface.BOLD)
                    m.groups[2] != null -> m.groupValues[2] to StyleSpan(Typeface.BOLD)
                    m.groups[3] != null -> m.groupValues[3] to StyleSpan(Typeface.ITALIC)
                    else -> m.groupValues[4] to TypefaceSpan("monospace")
                }
                out.append(body)
                out.setSpan(span, s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                last = m.range.last + 1
            }
            out.append(line, last, line.length)
            if (heading != null) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
