package com.wasti.airpad

/** Pure bounded backoff policy so retry timing can be tested without Android services. */
internal object WifiReconnectPolicy {
    const val MAX_ATTEMPTS = 5
    const val STABLE_CONNECTION_RESET_SECONDS = 60L

    fun delaySeconds(attempt: Int): Long? {
        if (attempt !in 1..MAX_ATTEMPTS) return null
        return 1L shl (attempt - 1)
    }
}
