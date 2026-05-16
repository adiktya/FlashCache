package com.flashcache.protocol

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WireResponse(
    var ok: Boolean = false,
    var value: String? = null,
    var present: Boolean? = null,
    var error: String? = null,
    /** Numeric result for INCR. */
    var counter: Long? = null,
) {
    companion object {
        @JvmStatic
        fun ok(): WireResponse = WireResponse(ok = true)

        @JvmStatic
        fun okValue(value: String?): WireResponse = WireResponse(ok = true, value = value)

        @JvmStatic
        fun okPresent(present: Boolean): WireResponse = WireResponse(ok = true, present = present)

        @JvmStatic
        fun okCounter(value: Long): WireResponse = WireResponse(ok = true, counter = value)

        @JvmStatic
        fun fail(message: String): WireResponse = WireResponse(ok = false, error = message)
    }
}
