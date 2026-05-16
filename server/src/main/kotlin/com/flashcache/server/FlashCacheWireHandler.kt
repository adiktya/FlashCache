package com.flashcache.server

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.flashcache.protocol.WireRequest
import com.flashcache.protocol.WireResponse
import com.flashcache.sdk.FlashCacheClient
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal class FlashCacheWireHandler(
    private val engine: CacheEngine,
    private val replica: FlashCacheClient?,
    private val replicaExecutor: Executor?,
    private val replicationErrors: AtomicLong,
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
        val res = engine.handle(req)
        maybeReplicateAsync(req, res)
        ctx.writeAndFlush(Unpooled.copiedBuffer(MAPPER.writeValueAsString(res) + "\n", StandardCharsets.UTF_8))
    }

    private fun maybeReplicateAsync(req: WireRequest, res: WireResponse) {
        val r = replica ?: return
        val ex = replicaExecutor ?: return
        if (!shouldReplicate(req, res)) {
            return
        }
        val copy =
            try {
                MAPPER.readValue<WireRequest>(MAPPER.writeValueAsBytes(req))
            } catch (_: Exception) {
                replicationErrors.incrementAndGet()
                return
            }
        ex.execute {
            try {
                r.execute(copy)
            } catch (_: Exception) {
                replicationErrors.incrementAndGet()
            }
        }
    }

    private fun shouldReplicate(req: WireRequest, res: WireResponse): Boolean {
        if (!res.ok) {
            return false
        }
        val op = req.op?.uppercase() ?: ""
        return when (op) {
            "SET", "UPDATE", "DEL", "DELETE", "INCR" -> true
            "SETNX" -> res.present == true
            else -> false
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
