package com.azadlink.vpn.core
import kotlin.math.min
import kotlin.math.pow
object ReconnectBackoff {
    fun delayMillis(failedAttempt: Int, initialMillis: Long = 1_000L, maximumMillis: Long = 60_000L): Long {
        require(failedAttempt >= 0) { "failedAttempt must be >= 0" }
        require(initialMillis > 0) { "initialMillis must be > 0" }
        require(maximumMillis >= initialMillis) { "maximumMillis must be >= initialMillis" }
        val scaled = initialMillis.toDouble() * 2.0.pow(min(failedAttempt, 30))
        return min(scaled, maximumMillis.toDouble()).toLong()
    }
}
