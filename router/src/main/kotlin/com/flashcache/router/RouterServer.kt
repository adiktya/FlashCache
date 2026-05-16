@file:JvmName("RouterServer")

package com.flashcache.router

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Cache router: routes the JSON wire protocol to one or more backends.
 *
 * - [RouterMode.SHARD] (default): consistent-hash each key to one peer.
 * - [RouterMode.REPLICATE]: full replica set — writes go to all peers; reads fail over across peers.
 */
fun main() {
    val port = firstNonBlank(System.getenv("FLASHCACHE_ROUTER_PORT"), "7653").toInt()
    val peersSpec = firstNonBlank(System.getenv("FLASHCACHE_PEERS"), "127.0.0.1:7654")
    val mode = parseRouterMode(System.getenv("FLASHCACHE_ROUTER_MODE"))
    val peers = parsePeers(peersSpec)
    val ring = ConsistentHashRing(peers)
    val defaultPeer = peers[0]
    val pool = BackendClientPool(Duration.ofSeconds(10))

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
                            .addLast(RouterWireHandler(mode, peers, ring, defaultPeer, pool))
                    }
                },
            )
        val bind = b.bind(port).sync()
        println("FlashCache router on $port mode=$mode -> peers $peersSpec")
        bind.channel().closeFuture().sync()
    } finally {
        boss.shutdownGracefully()
        worker.shutdownGracefully()
        pool.close()
    }
}

private fun parseRouterMode(raw: String?): RouterMode =
    when (raw?.trim()?.lowercase()) {
        "replicate", "broadcast", "full" -> RouterMode.REPLICATE
        else -> RouterMode.SHARD
    }

private fun parsePeers(spec: String): List<InetSocketAddress> {
    val out = ArrayList<InetSocketAddress>()
    for (part in spec.split(",")) {
        val p = part.trim()
        if (p.isEmpty()) {
            continue
        }
        val colon = p.lastIndexOf(':')
        if (colon <= 0 || colon == p.length - 1) {
            throw IllegalArgumentException("bad peer: $p")
        }
        val host = p.substring(0, colon).trim()
        val prt = p.substring(colon + 1).trim().toInt()
        out.add(InetSocketAddress(host, prt))
    }
    if (out.isEmpty()) {
        throw IllegalArgumentException("FLASHCACHE_PEERS empty")
    }
    return out
}

private fun firstNonBlank(a: String?, b: String): String = if (!a.isNullOrBlank()) a else b
