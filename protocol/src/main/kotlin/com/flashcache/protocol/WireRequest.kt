package com.flashcache.protocol

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WireRequest(
    var op: String? = null,
    var key: String? = null,
    var value: String? = null,
    /** Time-to-live in milliseconds from now. */
    var ttlMs: Long? = null,
    /** For INCR: amount to add (default 1). */
    var increment: Long? = null,
)
