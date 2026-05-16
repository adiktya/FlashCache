package com.flashcache.router

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.flashcache.protocol.WireRequest
import com.flashcache.protocol.WireResponse
import com.flashcache.sdk.FlashCacheClient
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

internal enum class RouterMode {
    /** Each key lives on exactly one peer (consistent hashing). */
    SHARD,

    /** Every peer holds a full copy: mutations fan out to all peers; reads try peers in order until one responds. */
    REPLICATE,
}

internal class RouterWireHandler(
    private val mode: RouterMode,
    private val allPeers: List<InetSocketAddress>,
    private val ring: ConsistentHashRing,
    private val defaultPeer: InetSocketAddress,
    private val pool: BackendClientPool,
) : SimpleChannelInboundHandler<String>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
        val req =
            try {
                MAPPER.readValue<WireRequest>(msg)
            } catch (e: Exception) {
                val err = WireResponse.fail("invalid json: ${e.message}")
                ctx.writeAndFlush(Unpooled.copiedBuffer(MAPPER.writeValueAsString(err) + "\n", StandardCharsets.UTF_8))
                return
            }
        val res =
            when (mode) {
                RouterMode.SHARD -> forwardSingle(pickShardPeer(req), req)
                RouterMode.REPLICATE -> forwardReplicated(req)
            }
        ctx.writeAndFlush(Unpooled.copiedBuffer(MAPPER.writeValueAsString(res) + "\n", StandardCharsets.UTF_8))
    }

    private fun forwardReplicated(req: WireRequest): WireResponse {
        val op = req.op?.uppercase() ?: ""
        return when (op) {
            "SET", "UPDATE", "DEL", "DELETE", "INCR" -> forwardAllPeers(req)
            "SETNX" -> forwardSetNxAllPeers(req)
            "GET", "EXISTS" -> forwardReadFailover(req)
            "PING" -> forwardSingle(defaultPeer, req)
            else -> forwardSingle(pickShardPeer(req), req)
        }
    }

    private fun forwardAllPeers(req: WireRequest): WireResponse {
        var last: WireResponse? = null
        for (peer in allPeers) {
            last = forwardSingle(peer, req)
            if (!last.ok) {
                return last
            }
        }
        return last ?: WireResponse.fail("no peers")
    }

    /**
     * Applies SETNX to every peer; succeeds only if all peers agree on the same outcome (true = acquired everywhere).
     */
    private fun forwardSetNxAllPeers(req: WireRequest): WireResponse {
        var seen: Boolean? = null
        for (peer in allPeers) {
            val r = forwardSingle(peer, req)
            if (!r.ok) {
                return r
            }
            val acquired = r.present == true
            if (seen == null) {
                seen = acquired
            } else if (seen != acquired) {
                return WireResponse.fail("SETNX mismatch across peers (split brain?)")
            }
        }
        return WireResponse.okPresent(seen ?: false)
    }

    private fun forwardReadFailover(req: WireRequest): WireResponse {
        var lastErr = "all peers failed"
        for (peer in allPeers) {
            try {
                val r = forwardSingle(peer, req)
                if (r.ok) {
                    return r
                }
                lastErr = r.error ?: "error"
            } catch (e: Exception) {
                lastErr = e.message ?: "exception"
            }
        }
        return WireResponse.fail(lastErr)
    }

    private fun pickShardPeer(req: WireRequest): InetSocketAddress {
        if (req.op.equals("PING", ignoreCase = true)) {
            return defaultPeer
        }
        if (req.key.isNullOrEmpty()) {
            return defaultPeer
        }
        return ring.route(req.key!!)
    }

    private fun forwardSingle(peer: InetSocketAddress, req: WireRequest): WireResponse {
        return try {
            val client = pool.clientFor(peer)
            synchronized(client) {
                client.execute(req)
            }
        } catch (e: Exception) {
            WireResponse.fail("router forward failed: ${e.message}")
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        ctx.close()
    }

    companion object {
        private val MAPPER =
            jacksonObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}
