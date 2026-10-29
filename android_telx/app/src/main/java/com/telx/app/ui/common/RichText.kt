package com.telx.app.ui.common

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView

/**
 * Telegram-style rich text renderer for TelX v6.
 *
 * Supported markup (typed directly or inserted via the format toolbar):
 *   **bold**   __italic__   ~~strike~~   `mono`   ||spoiler||
 *   #hashtag   @username   (tap to search / open profile)
 *
 * Spoilers start hidden and reveal on tap. Web links keep their native
 * autoLink behaviour because we only add spans, never remove them.
 */
object RichText {

    /** Hidden text; draws as an invisible block until tapped. */
    class SpoilerSpan : android.text.style.CharacterStyle(), android.text.style.UpdateAppearance {
        @Volatile var revealed = false
        override fun updateDrawState(tp: TextPaint) {
            if (!revealed) {
                tp.bgColor = 0xFF262B33.toInt()
                tp.color = 0xFF262B33.toInt()
            }
        }
    }

    private class RevealSpoiler(val span: SpoilerSpan) : ClickableSpan() {
        override fun onClick(widget: View) { span.revealed = true; widget.invalidate() }
        override fun updateDrawState(tp: TextPaint) {}
    }

    private class TagSpan(val kind: String, val value: String,
                          val onTag: ((String, String) -> Unit)?) : ClickableSpan() {
        override fun onClick(widget: View) { onTag?.invoke(kind, value) }
        override fun updateDrawState(tp: TextPaint) { tp.isUnderlineText = false; tp.color = 0xFF3390EC.toInt() }
    }

    private fun strip(sb: SpannableStringBuilder, rx: Regex, from: Int, spanFor: () -> Any): Int {
        val m = rx.find(sb, from) ?: return -1
        val inner = m.groupValues[1]
        val start = m.range.first
        sb.replace(start, m.range.last + 1, inner)
        sb.setSpan(spanFor(), start, start + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return start + inner.length.coerceAtLeast(1)
    }

    /**
     * Render `source` into `textView` with formatting, spoilers and clickable
     * #tags / @mentions. `onTag(kind, value)` — kind: "tag" | "mention".
     */
    fun set(textView: TextView, source: String, onTag: ((String, String) -> Unit)? = null) {
        val sb = SpannableStringBuilder(source)

        // inline styles (markers removed, spans applied)
        while (true) { val n = strip(sb, Regex("\\*\\*(.+?)\\*\\*"), 0) { StyleSpan(android.graphics.Typeface.BOLD) }; if (n < 0) break }
        while (true) { val n = strip(sb, Regex("__(.+?)__"), 0) { StyleSpan(android.graphics.Typeface.ITALIC) }; if (n < 0) break }
        while (true) { val n = strip(sb, Regex("~~(.+?)~~"), 0) { StrikethroughSpan() }; if (n < 0) break }
        while (true) { val n = strip(sb, Regex("`([^`]+)`"), 0) { TypefaceSpan("monospace") }; if (n < 0) break }

        // spoilers — keep markers' inner text hidden until tapped
        var pos = 0
        while (true) {
            val m = Regex("\\|\\|(.+?)\\|\\|").find(sb, pos) ?: break
            val inner = m.groupValues[1]
            val start = m.range.first
            sb.replace(start, m.range.last + 1, inner)
            val sp = SpoilerSpan()
            sb.setSpan(sp, start, start + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RevealSpoiler(sp), start, start + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = start + inner.length
        }

        // clickable hashtags
        var m = Regex("(?<![\\w/])#([A-Za-z0-9_\\u0600-\\u06FF]{2,64})").find(sb)
        while (m != null) {
            val s = m.range.first; val e = m.range.last + 1
            sb.setSpan(TagSpan("tag", m.groupValues[1], onTag), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            m = m.next()
        }
        // clickable mentions
        m = Regex("(?<![\\w/@])@([A-Za-z0-9_]{5,32})").find(sb)
        while (m != null) {
            val s = m.range.first; val e = m.range.last + 1
            sb.setSpan(TagSpan("mention", m.groupValues[1], onTag), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            m = m.next()
        }

        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.isLongClickable = true
        textView.text = sb
    }

    /** Wrap the current selection of `input` with a formatting marker. */
    fun wrap(input: android.widget.EditText, marker: String) {
        val s = input.selectionStart.coerceAtLeast(0)
        val e = input.selectionEnd.coerceAtLeast(0)
        val min = minOf(s, e); val max = maxOf(s, e)
        if (min == max) {                       // nothing selected → insert empty pair
            input.text.insert(min, "$marker$marker")
            input.setSelection(min + marker.length)
        } else {
            input.text.insert(max, marker)
            input.text.insert(min, marker)
            input.setSelection(min + marker.length, max + marker.length)
        }
        input.requestFocus()
    }
}
