@file:JvmName("FlashCacheServer")

package com.flashcache.server

import com.flashcache.sdk.FlashCacheClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * FlashCache TCP server (JSON line protocol), optional async replication, background TTL sweep, and Prometheus
 * metrics on [FLASHCACHE_METRICS_PORT].
 */
fun main() {
    val port = firstNonBlank(System.getenv("FLASHCACHE_PORT"), "7654").toInt()
    val maxKeys = firstNonBlank(System.getenv("FLASHCACHE_MAX_KEYS"), "500000").toInt()
    val metricsPort = firstNonBlank(System.getenv("FLASHCACHE_METRICS_PORT"), "7655").toInt()
    val sweepMs = firstNonBlank(System.getenv("FLASHCACHE_TTL_SWEEP_MS"), "1000").toInt()

    val engine = CacheEngine(maxKeys)
    val replicationErrors = AtomicLong()

    var replicaClient: FlashCacheClient? = null
    var replicaService: ExecutorService? = null
    val replicaSpec = System.getenv("FLASHCACHE_REPLICA")
    if (!replicaSpec.isNullOrBlank()) {
        val hp = replicaSpec.trim().split(":")
        if (hp.size == 2) {
            val h = hp[0].trim()
            val p = hp[1].trim().toInt()
            val rc = FlashCacheClient(h, p, Duration.ofSeconds(5))
            rc.connect()
            replicaClient = rc
            replicaService = Executors.newSingleThreadExecutor { r -> Thread(r, "flashcache-replica") }
            println("Async replication enabled to $replicaSpec")
        }
    }
    val replicaFinal = replicaClient
    val replicaExecForHandler: Executor? = replicaService

    val sweeper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "flashcache-ttl-sweep") }
    if (sweepMs > 0) {
        sweeper.scheduleAtFixedRate(
            {
                try {
                    engine.sweepExpired()
                } catch (_: Exception) {
                }
            },
            sweepMs.toLong(),
            sweepMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    val metrics = startMetrics(engine, replicationErrors, metricsPort)

    val boss = NioEventLoopGroup(1)
    val worker = NioEventLoopGroup()
    try {
        val b = ServerBootstrap()
        b.group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(
                object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
                            .addLast(LineBasedFrameDecoder(1 shl 20))
                            .addLast(StringDecoder(StandardCharsets.UTF_8))
                            .addLast(
                                FlashCacheWireHandler(
                                    engine,
                                    replicaFinal,
                                    replicaExecForHandler,
                                    replicationErrors,
                                ),
                            )
                    }
                },
            )
        val bind = b.bind(port).sync()
        println("FlashCache listening on port $port")
        bind.channel().closeFuture().sync()
    } finally {
        boss.shutdownGracefully()
        worker.shutdownGracefully()
        sweeper.shutdownNow()
        replicaFinal?.close()
        replicaService?.shutdownNow()
        metrics?.stop(0)
    }
}

private fun startMetrics(engine: CacheEngine, replicationErrors: AtomicLong, port: Int): HttpServer? {
    if (port <= 0) {
        return null
    }
    return try {
        val http = HttpServer.create(InetSocketAddress(port), 0)
        http.createContext("/metrics") { ex: HttpExchange ->
            val body =
                """
                # HELP flashcache_hits_total Cache hits
                # TYPE flashcache_hits_total counter
                flashcache_hits_total ${engine.hits()}
                # HELP flashcache_misses_total Cache misses
                # TYPE flashcache_misses_total counter
                flashcache_misses_total ${engine.misses()}
                # HELP flashcache_evictions_total Evictions (expiry + LRU)
                # TYPE flashcache_evictions_total counter
                flashcache_evictions_total ${engine.evictions()}
                # HELP flashcache_incr_total INCR operations applied
                # TYPE flashcache_incr_total counter
                flashcache_incr_total ${engine.incrOps()}
                # HELP flashcache_replication_errors_total Async replication failures
                # TYPE flashcache_replication_errors_total counter
                flashcache_replication_errors_total ${replicationErrors.get()}
                """.trimIndent() + "\n"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain; version=0.0.4")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { os: OutputStream ->
                os.write(bytes)
            }
        }
        http.setExecutor(Executors.newSingleThreadExecutor { r -> Thread(r, "flashcache-metrics") })
        http.start()
        println("FlashCache metrics on port $port")
        http
    } catch (e: Exception) {
        System.err.println("Metrics server disabled: ${e.message}")
        null
    }
}

private fun firstNonBlank(a: String?, b: String): String = if (!a.isNullOrBlank()) a else b
