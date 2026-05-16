package com.flashcache.router

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Ketama-style consistent hash ring: virtual nodes per physical peer to minimise reshuffling when peers change.
 */
class ConsistentHashRing(peers: List<InetSocketAddress>) {
    private val ring = TreeMap<Long, InetSocketAddress>()

    init {
        require(peers.isNotEmpty()) { "at least one peer required" }
        for (peer in peers) {
            val base = "${peer.hostString}:${peer.port}"
            repeat(VIRTUAL_NODES) { i ->
                val h = hash128ToLong("$base#$i")
                ring[h] = peer
            }
        }
    }

    fun route(key: String): InetSocketAddress {
        require(key.isNotEmpty()) { "key required" }
        val h = hash128ToLong(key)
        val e = ring.ceilingEntry(h) ?: ring.firstEntry()
        return e.value
    }

    private fun hash128ToLong(s: String): Long {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(s.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(d, 0, 8).long
    }

    companion object {
        private const val VIRTUAL_NODES = 128
    }
}
