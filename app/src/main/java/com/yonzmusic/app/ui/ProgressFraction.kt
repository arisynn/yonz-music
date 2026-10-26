package com.yonzmusic.app.ui

/** Unknown totals stay indeterminate. Evaluate from one immutable snapshot before drawing. */
object ProgressFraction {
    fun of(received: Long, total: Long): Float? {
        if (total <= 0) return null
        return (received.coerceIn(0, total).toDouble() / total.toDouble()).toFloat()
    }
}
