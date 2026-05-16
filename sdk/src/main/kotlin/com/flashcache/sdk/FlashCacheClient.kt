package com.flashcache.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.flashcache.protocol.WireRequest
import com.flashcache.protocol.WireResponse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional

/**
 * Blocking TCP client for the FlashCache JSON line protocol. Intended for JVM services (for example Spring Boot).
 */
class FlashCacheClient(
    private val host: String,
    private val port: Int,
    readTimeout: Duration,
) : AutoCloseable {
    init {
        require(host.isNotBlank()) { "host" }
    }
    private val readTimeoutMs =
        readTimeout.toMillis().coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()

    private val ioLock = Any()
    private var socket: Socket? = null
    private var out: Writer? = null
    private var `in`: BufferedReader? = null

    @Throws(IOException::class)
    fun connect() {
        synchronized(ioLock) {
            closeQuietly()
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = readTimeoutMs
            socket = s
            out = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
            `in` = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        }
    }

    @Throws(IOException::class)
    private fun ensureConnected() {
        val s = socket
        if (s == null || s.isClosed || !s.isConnected) {
            connect()
        }
    }

    /**
     * Low-level request for router/replication; prefer typed methods when possible.
     */
    @Throws(IOException::class)
    fun execute(req: WireRequest): WireResponse = send(req)

    @Throws(IOException::class)
    private fun send(req: WireRequest): WireResponse {
        synchronized(ioLock) {
            ensureConnected()
            val w = out ?: throw IOException("not connected")
            val r = `in` ?: throw IOException("not connected")
            w.write(MAPPER.writeValueAsString(req))
            w.write("\n")
            w.flush()
            val line = r.readLine() ?: throw IOException("unexpected EOF from FlashCache")
            return MAPPER.readValue<WireResponse>(line)
        }
    }

    @Throws(IOException::class)
    fun ping() {
        val res = send(WireRequest(op = "PING"))
        if (!res.ok) {
            throw IOException("ping failed: ${res.error}")
        }
    }

    @Throws(IOException::class)
    fun get(key: String): Optional<String> {
        val res = send(WireRequest(op = "GET", key = key))
        if (!res.ok) {
            throw IOException(res.error)
        }
        return Optional.ofNullable(res.value)
    }

    @Throws(IOException::class)
    fun set(key: String, value: String, ttl: Duration?) {
        val res =
            send(
                WireRequest(
                    op = "SET",
                    key = key,
                    value = value,
                    ttlMs = ttl?.toMillis(),
                ),
            )
        if (!res.ok) {
            throw IOException(res.error)
        }
    }

    /**
     * @return true if the key was absent and the value was stored
     */
    @Throws(IOException::class)
    fun setIfAbsent(key: String, value: String, ttl: Duration?): Boolean {
        val res =
            send(
                WireRequest(
                    op = "SETNX",
                    key = key,
                    value = value,
                    ttlMs = ttl?.toMillis(),
                ),
            )
        if (!res.ok) {
            throw IOException(res.error)
        }
        return res.present == true
    }

    @Throws(IOException::class)
    fun delete(key: String) {
        val res = send(WireRequest(op = "DEL", key = key))
        if (!res.ok) {
            throw IOException(res.error)
        }
    }

    @Throws(IOException::class)
    fun exists(key: String): Boolean {
        val res = send(WireRequest(op = "EXISTS", key = key))
        if (!res.ok) {
            throw IOException(res.error)
        }
        return res.present == true
    }

    /**
     * Atomically increments a numeric string value. Missing key starts at 0 + amount.
     *
     * @return the new value after increment
     */
    @Throws(IOException::class)
    fun increment(key: String, amount: Long, ttlIfCreate: Duration?): Long {
        val res =
            send(
                WireRequest(
                    op = "INCR",
                    key = key,
                    increment = amount,
                    ttlMs = ttlIfCreate?.toMillis(),
                ),
            )
        if (!res.ok) {
            throw IOException(res.error)
        }
        val c = res.counter ?: throw IOException("missing counter in response")
        return c
    }

    override fun close() {
        synchronized(ioLock) {
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        try {
            out?.close()
        } catch (_: IOException) {
        }
        try {
            `in`?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        out = null
        `in` = null
        socket = null
    }

    companion object {
        private val MAPPER =
            jacksonObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    }
}
