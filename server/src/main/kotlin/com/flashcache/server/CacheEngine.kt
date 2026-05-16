package com.flashcache.server

import com.flashcache.protocol.WireRequest
import com.flashcache.protocol.WireResponse
import java.time.Clock
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Thread-safe key-value store with TTL, approximate LRU eviction (access-ordered [LinkedHashMap]), and INCR.
 */
class CacheEngine(private val maxEntries: Int) {
    private val lock = ReentrantReadWriteLock()
    private val map =
        object : LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
                size > maxEntries
        }
    private val clock: Clock = Clock.systemUTC()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val evictions = AtomicLong()
    private val incrOps = AtomicLong()

    fun hits(): Long = hits.get()

    fun misses(): Long = misses.get()

    fun evictions(): Long = evictions.get()

    fun incrOps(): Long = incrOps.get()

    /** Background TTL sweep (also invoked on read/write paths). */
    fun sweepExpired() {
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun handle(req: WireRequest?): WireResponse {
        val op = req?.op ?: return WireResponse.fail("invalid request")
        return when (op.uppercase()) {
            "PING" -> WireResponse.ok()
            "GET" -> get(req.key)
            "SET", "UPDATE" -> set(req.key, req.value, req.ttlMs)
            "SETNX" -> setnx(req.key, req.value, req.ttlMs)
            "DEL", "DELETE" -> del(req.key)
            "EXISTS" -> exists(req.key)
            "INCR" -> incr(req.key, req.increment, req.ttlMs)
            else -> WireResponse.fail("unknown op: $op")
        }
    }

    private fun purgeExpiredLocked() {
        val now = clock.millis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isExpired(e.value, now)) {
                it.remove()
                evictions.incrementAndGet()
            }
        }
    }

    private fun isExpired(e: CacheEntry, nowMs: Long): Boolean = e.expiresAtMs > 0 && nowMs >= e.expiresAtMs

    private fun get(key: String?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
            val nowMs = clock.millis()
            val e = map[key]
            if (e == null || isExpired(e, nowMs)) {
                if (e != null) {
                    map.remove(key)
                    evictions.incrementAndGet()
                }
                misses.incrementAndGet()
                return WireResponse.okValue(null)
            }
            e.lastAccessNanos = System.nanoTime()
            hits.incrementAndGet()
            return WireResponse.okValue(e.value)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun set(key: String?, value: String?, ttlMs: Long?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
            val nowMs = clock.millis()
            val exp = if (ttlMs == null || ttlMs <= 0) 0L else nowMs + ttlMs
            map[key] = CacheEntry(value ?: "", exp, System.nanoTime())
            return WireResponse.ok()
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun setnx(key: String?, value: String?, ttlMs: Long?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
            val nowMs = clock.millis()
            var cur = map[key]
            if (cur != null && !isExpired(cur, nowMs)) {
                return WireResponse.okPresent(false)
            }
            if (cur != null) {
                map.remove(key)
            }
            val exp = if (ttlMs == null || ttlMs <= 0) 0L else nowMs + ttlMs
            map[key] = CacheEntry(value ?: "", exp, System.nanoTime())
            return WireResponse.okPresent(true)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun del(key: String?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        lock.writeLock().lock()
        try {
            map.remove(key)
            return WireResponse.ok()
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun exists(key: String?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
            val nowMs = clock.millis()
            val e = map[key]
            if (e == null || isExpired(e, nowMs)) {
                if (e != null) {
                    map.remove(key)
                    evictions.incrementAndGet()
                }
                return WireResponse.okPresent(false)
            }
            return WireResponse.okPresent(true)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun incr(key: String?, increment: Long?, ttlMs: Long?): WireResponse {
        if (key.isNullOrEmpty()) {
            return WireResponse.fail("key required")
        }
        val delta = increment ?: 1L
        lock.writeLock().lock()
        try {
            purgeExpiredLocked()
            val nowMs = clock.millis()
            var cur = map[key]
            if (cur != null && isExpired(cur, nowMs)) {
                map.remove(key)
                cur = null
            }
            val next: Long
            val exp: Long
            if (cur == null) {
                next = delta
                exp = if (ttlMs == null || ttlMs <= 0) 0L else nowMs + ttlMs
            } else {
                next =
                    try {
                        cur.value.toLong() + delta
                    } catch (_: NumberFormatException) {
                        return WireResponse.fail("value is not an integer")
                    }
                exp = cur.expiresAtMs
            }
            map[key] = CacheEntry(next.toString(), exp, System.nanoTime())
            incrOps.incrementAndGet()
            return WireResponse.okCounter(next)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun sizeForTests(): Int {
        lock.readLock().lock()
        try {
            return map.size
        } finally {
            lock.readLock().unlock()
        }
    }
}
