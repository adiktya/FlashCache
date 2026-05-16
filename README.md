# FlashCache

Distributed low-latency **in-memory** cache implemented in **Kotlin** (JVM 17): **Netty** TCP server, **JSON line protocol**, optional **Prometheus** metrics, and a small **SDK** for clients (used by [PulseFlow](../pulseflow/README.md) for coordination instead of Redis).

## Distributed caching (implemented)

FlashCache supports **two complementary topologies**:

1. **Sharded cluster (`FLASHCACHE_ROUTER_MODE=shard`, default)**  
   The **router** maps each key to exactly one peer with a **consistent hash ring** (Ketama-style virtual nodes). Memory is split across nodes; clients should talk to the router (or hash the same way themselves).

2. **Replicated cluster (`FLASHCACHE_ROUTER_MODE=replicate`)**  
   Mutations (`SET`, `UPDATE`, `DEL`, `DELETE`, `INCR`) are applied to **every** peer; `GET` / `EXISTS` try peers in order until one responds. All nodes hold a **full copy** of the dataset (best-effort; partial failures return errors).

Additionally, a single **cache node** can **async-replicate** mutating ops to one standby via `FLASHCACHE_REPLICA=host:port` (eventual, not quorum-safe).

This is **not** Redis Cluster: there is no automatic quorum failover, slot migration, or cross-shard multi-key transactions.

## Modules

| Module | Role |
| --- | --- |
| `protocol` | Shared wire DTOs (Jackson + Kotlin) |
| `sdk` | Blocking `FlashCacheClient` (TCP); `execute(WireRequest)` for advanced use |
| `server` | Netty server + LRU/TTL `CacheEngine`, optional async replication, TTL background sweep, JDK `HttpServer` for `/metrics` |
| `router` | Netty front door: `shard` or `replicate` routing across `FLASHCACHE_PEERS` |

## Protocol (one JSON object per line)

Operations: `PING`, `GET`, `SET`, `UPDATE`, `SETNX`, `DEL`, `DELETE`, `EXISTS`, `INCR`.

- `UPDATE` is an alias for `SET` (overwrite). `DELETE` is an alias for `DEL`.
- `INCR`: increments a numeric string value; optional `increment` (default 1). On first create, `ttlMs` applies. Response may include `counter` on success.

Example `SET` then `GET`:

```json
{"op":"SET","key":"user:1","value":"hello","ttlMs":60000}
```

Example `INCR`:

```json
{"op":"INCR","key":"c:api","increment":1,"ttlMs":60000}
```

## Run locally (Gradle)

**Single node:**

```bash
cd flashcache
./gradlew :server:run
```

Defaults: TCP **7654**, metrics **7655** (`FLASHCACHE_METRICS_PORT=-1` to disable).

**Router + two peers** (start two terminals with different `FLASHCACHE_PORT`, then):

```bash
# Sharded (each key on one node)
FLASHCACHE_PEERS=127.0.0.1:7654,127.0.0.1:7655 ./gradlew :router:run

# Full replica set (writes to all nodes)
FLASHCACHE_ROUTER_MODE=replicate FLASHCACHE_PEERS=127.0.0.1:7654,127.0.0.1:7655 ./gradlew :router:run
```

Router listens on **7653** by default (`FLASHCACHE_ROUTER_PORT`).

## Environment (server)

| Variable | Default | Description |
| --- | --- | --- |
| `FLASHCACHE_PORT` | `7654` | TCP wire port |
| `FLASHCACHE_MAX_KEYS` | `500000` | LRU cap |
| `FLASHCACHE_METRICS_PORT` | `7655` | Prometheus text; `0` or negative disables |
| `FLASHCACHE_TTL_SWEEP_MS` | `1000` | Background expiry sweep interval; `0` disables |
| `FLASHCACHE_REPLICA` | (unset) | `host:port` of a second node; successful mutating ops are duplicated asynchronously |

## Environment (router)

| Variable | Default | Description |
| --- | --- | --- |
| `FLASHCACHE_ROUTER_PORT` | `7653` | TCP listen port |
| `FLASHCACHE_PEERS` | `127.0.0.1:7654` | Comma-separated `host:port` list of cache backends |
| `FLASHCACHE_ROUTER_MODE` | `shard` | `shard` = consistent hash per key; `replicate` (aliases `broadcast`, `full`) = fan-out writes, read failover |

## Run with Docker

**Single node** (from this directory):

```bash
docker compose up -d --build
```

**Cluster demo** (two nodes + router):

```bash
docker compose -f docker-compose.cluster.yml up -d --build
```

Clients connect to **localhost:7653** (router). The sample compose uses **replicate** mode so both nodes stay in sync without per-node `FLASHCACHE_REPLICA`.

## Build JVM SDK

```bash
./gradlew :sdk:jar
```

Coordinates: `com.flashcache:sdk:0.1.0` (use Gradle `includeBuild` as PulseFlow does, or publish to your repository).

