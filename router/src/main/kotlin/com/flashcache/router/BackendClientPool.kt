package com.flashcache.router

import com.flashcache.sdk.FlashCacheClient
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One blocking [FlashCacheClient] per backend endpoint (reused across requests). */
class BackendClientPool(private val readTimeout: Duration) : AutoCloseable {
    private val pool = ConcurrentHashMap<String, FlashCacheClient>()

    @Throws(IOException::class)
    fun clientFor(addr: InetSocketAddress): FlashCacheClient {
        val k = "${addr.hostString}:${addr.port}"
        pool[k]?.let { return it }
        synchronized(this) {
            pool[k]?.let { return it }
            val c = FlashCacheClient(addr.hostString, addr.port, readTimeout)
            c.connect()
            pool[k] = c
            return c
        }
    }

    override fun close() {
        pool.values.forEach { it.close() }
        pool.clear()
    }
}
