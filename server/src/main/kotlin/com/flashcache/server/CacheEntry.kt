package com.flashcache.server

internal class CacheEntry(
    @Volatile var value: String,
    /** Epoch millis when entry expires (0 = no expiry). */
    @Volatile var expiresAtMs: Long,
    @Volatile var lastAccessNanos: Long,
)
