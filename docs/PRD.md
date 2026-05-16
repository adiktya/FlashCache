# FlashCache — Distributed Low-Latency Caching Platform

## Product overview

**FlashCache** is a distributed low-latency caching and coordination platform for accelerating backends: in-memory storage, **router-driven clustering** (sharded or fully replicated), **best-effort async replication** between two nodes at the server layer, locking primitives (`SETNX`), counters (`INCR`), concurrent request handling (Netty), and **Prometheus** metrics. The codebase is **Kotlin on JVM 17**.

## Problem statement

Repeated reads, session lookups, hot keys, counters, and coordination overload databases and add latency. Single-node caches hit memory and availability limits. FlashCache ships **distributed** deployments today: **router + N peers** in either **shard** (partitioned memory) or **replicate** (full copy on every peer) mode, plus optional **single-target async replication** (`FLASHCACHE_REPLICA`) and a **single-node** path for local development.

## Goals

- Ultra-low-latency access paths
- Reduce database dependency for coordination and hot reads
- Distributed coordination primitives (locks, `SETNX`)
- Horizontal scalability via **router** + multiple cache peers: **`shard`** (consistent hashing) or **`replicate`** (write fan-out + read failover)
- Fault-tolerant availability path: **async replication** to a configured replica (not strongly consistent); **replicate** router mode for N-way in-sync copies (best-effort)
- High concurrency (Netty on server and router; blocking SDK for simple integration)

## Use cases

- API response caching (`GET /user/profile` style hot keys)
- Session storage with TTL
- Distributed locking (`lock:payment_123`)
- Rate limiting (counter keys via `INCR`; enforcement logic in callers)
- Real-time counters
- Feature flags (`enable_new_ui=true`)

## Architecture

- **Clients** connect to a **cache router** (`router` module) or directly to a node.
- **`FLASHCACHE_ROUTER_MODE=shard` (default):** each key maps to **one** peer via a **consistent hash ring** (MD5-derived positions, 128 virtual nodes per peer). Total cache capacity scales with cluster size.
- **`FLASHCACHE_ROUTER_MODE=replicate`:** mutating ops run against **all** peers so every node holds a **full replica**; `GET` / `EXISTS` try peers in order. `SETNX` requires **identical** boolean outcomes on all peers (detects divergence).
- **Nodes** (`server` module) run the in-memory engine with TTL, LRU eviction, and optional **async replication** after successful mutating operations (`FLASHCACHE_REPLICA=host:port`) for a hot standby.
- **Metrics** on each node: JDK `HttpServer` `/metrics` (Prometheus text).

For a minimal demo, run **one** Netty server process only; for multi-node, run **router + N servers** (see [README](../README.md) and `docker-compose.cluster.yml`).

## Core components (reference implementation)

### Cache engine

- **Storage:** access-ordered `LinkedHashMap` protected by a read/write lock (LRU via `removeEldestEntry` when `FLASHCACHE_MAX_KEYS` exceeded).
- **TTL:** per-key absolute expiry; lazy purge on access plus **background sweep** (`FLASHCACHE_TTL_SWEEP_MS`, default 1s; set `0` to disable).
- **Ops:** `GET`, `SET`, `UPDATE` (alias), `SETNX`, `DEL`, `DELETE` (alias), `EXISTS`, `PING`, `INCR`.
- **Wire format:** UTF-8 JSON, one request object and one response object per line (`\n` delimited).

### Networking

- **Server:** Netty (`LineBasedFrameDecoder` + `StringDecoder` + handler).
- **Router:** Netty TCP; forwards full `WireRequest` JSON to one peer (`shard`) or all peers (`replicate`).
- **SDK:** blocking TCP client using `java.net.Socket` (minimal dependencies for Spring services). `execute(WireRequest)` supports replication and custom tooling. Implemented in **Kotlin**; binary-compatible with Java callers.

### Metrics

- JDK `HttpServer` exposes Prometheus exposition format on `FLASHCACHE_METRICS_PORT` (default **7655** on server; set `0` or negative to disable).
- Counters include hits, misses, evictions, `INCR` operations, and replication error count when replication is enabled.

## Non-functional requirements

- Scalability path: add peers + router in front (implemented).
- Performance: in-memory reads/writes; replication is **async** and does not block the client response path.
- Reliability: in-memory data is volatile; async replica is **eventual** and may lag or drop under failure; pair with durable stores for source of truth.
- Observability: `/metrics` as above.

## Technology stack

| Component | Technology |
| --- | --- |
| Language | Kotlin (JVM 17) |
| Networking | Netty |
| SDK | Kotlin / JVM 17 (blocking TCP) |
| Build | Gradle |
| Metrics | Prometheus text (minimal) |
| Containers | Docker |

## Failure handling

- **Process crash:** in-memory data is lost; callers must tolerate loss or pair with durable stores.
- **Memory pressure:** LRU eviction plus TTL expiry (lazy + sweep).
- **Network errors:** SDK throws `IOException`; router returns `WireResponse.fail(...)` when forwarding fails.
- **Replication:** server-side async replica failures increment `flashcache_replication_errors_total`; primary still succeeds if the write applied locally. Router **replicate** mode fails the client if any peer rejects a mutation.

## Future enhancements

- Raft / strong consistency options
- Disk snapshots / AOF-style persistence
- Redis protocol compatibility
- Multi-region replication
- Kubernetes autoscaling
- Compression
- Pub/sub

## Reference code layout

- `protocol/` — wire DTOs
- `sdk/` — `FlashCacheClient`
- `server/` — Netty server + metrics HTTP + replication + TTL sweep
- `router/` — shard + replicate routing

See the root [README](../README.md) for run instructions.
