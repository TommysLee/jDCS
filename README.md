<h1 align="center">jDCS</h1>

<p align="center">
  <strong> Java Data Collector Service</strong><br />
  <strong> A Modbus RTU data collection framework for industrial sites.</strong><br />
  <em>Get the collection core right. Leave the rest to you.</em>
</p>

<p align="center">
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/JDK-17%2B-orange.svg" alt="JDK17" /></a>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg" alt="Spring Boot" />
  <a href="https://github.com/digitalpetri/modbus"><img src="https://img.shields.io/badge/digitalpetri%2Fmodbus--serial-2.1.6-blue.svg" alt="modbus-serial" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-green.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/build-passing-brightgreen" alt="PRs Welcome" />
  <a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen" alt="PRs Welcome" /></a>
</p>

---

## Why this project exists

Modbus data collection looks simple. In practice, it's full of traps:

- **Serial ports don't have "disconnect" events.** When a TCP peer disappears, you get a FIN/RST. When a serial peer goes silent, you get nothing. The library thinks everything is fine — the other side just stops answering.
- **JNI calls can hang forever.** `Future.cancel(true)` only sets an interrupt flag — it can't break through a JNI call. When the driver underneath gets stuck, the main thread can bail out via a hard timeout, but the JNI thread leaks permanently.
- **A frozen process looks alive to systemd.** The JVM is still running, but collection stopped ten minutes ago. `Restart=always` won't help — the process never died.

These aren't theoretical. Anyone who has run Modbus collection unattended for months has hit them.

jDCS takes the solutions to these problems and turns them into a base framework:

- **The collection core is solid** — automatic reconnect, hard timeouts, retries, JNI hang detection, watchdog, systemd fallback.
- **The periphery is yours** — data sinks, point sources, log policies. We give you interfaces and default implementations, and assume nothing else.

It's not turnkey. It's a runnable, trimmable, extensible skeleton.

---

## Screenshots

### Status page

![Status page](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/status-page.jpg)

Open `http://localhost:8080/` and you'll see live status — serial connection, point count, I/O queue backlog, time since last collection. Refreshes every 3 seconds. Single static HTML file. No build step, no CDN.

### Health endpoint

![Health endpoint](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/health-api.jpg)

`GET /health` returns JSON. Wire it into Prometheus, a K8s liveness probe, or your own admin UI.

### Collection log

![Collection log](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/collection-log.jpg)

The default data sink logs each point's engineering value. Swap in your own storage or reporting by implementing `ModbusDataHandler` — it's one interface.

---

## Features

| Feature | Notes |
| --- | --- |
| **Batch read merging** | Groups points by `(slaveId, functionCode)` and merges adjacent addresses into one request. Fewer round trips on the bus. |
| **Bit-type function codes are isolated** | FC01/FC02 responses are bit-packed. They never get merged — merging them would silently slice the wrong bits. |
| **Layered exception isolation** | Batch / slice / handler — three layers. One point failing doesn't take down the whole cycle. |
| **Hard timeout + session discard** | On timeout, throw away the whole session (client + executor). Never reuse a half-dead connection. |
| **JNI hang detection** | Counts `modbus-io-*` threads directly. Exceeds threshold → process exits on purpose. |
| **Independent watchdog** | A separate `@Scheduled` thread monitors for stalled scheduling and queue backups. |
| **Industrial data model** | Types match InHand gateways: BOOL / INT16 / UINT16 / INT32 / UINT32 / FLOAT32 / INT64 / UINT64 / DOUBLE64 / STRING. |
| **Full byte order coverage** | ABCD / CDAB / BADC / DCBA, plus aliases like `Swapped` and `Big_Endian`. |
| **Minimal dependencies** | `modbus-serial` + `spring-boot-starter-web` + Lombok. That's it. |
| **Zero-friction extension** | Swap the data sink, point source, or log policy without touching the core. |

> Full feature list and configuration reference: **[Architecture_API.md](Architecture_API.md)**.

---

## Architecture: where the robustness comes from

**If you read one section, read this one.**

The robustness here isn't from stacking safety nets. It comes from layered defense — each layer handles a **different kind** of failure. Skip any layer and something will slip through.

```
┌─────────────────────────────────────────────────────────────┐
│ L5  systemd / Docker restart policy / K8s liveness          │  Process-level restart
├─────────────────────────────────────────────────────────────┤
│ L4  ModbusFatalErrorHandler → Runtime.halt(-1)              │  Intentional exit, OS restarts
├─────────────────────────────────────────────────────────────┤
│ L3  ModbusWatchdog (its own @Scheduled thread)              │  Side-channel monitoring
├─────────────────────────────────────────────────────────────┤
│ L2  Session discard + cooled rebuild                        │  Connection-level self-healing
├─────────────────────────────────────────────────────────────┤
│ L1  Per-read: retries + application-level hard timeout      │  Request-level
└─────────────────────────────────────────────────────────────┘
```

### L1 · Request level: retries + hard timeout

Every Modbus request has two protections:

- **Transport errors retry automatically** (CRC errors, malformed responses) — up to `read-retry-count` times.
- **Application-level hard timeout** — `operation-timeout-ms`. Returns on the dot. No infinite waits.

The key distinction: **protocol errors do not retry.** A slave returning an exception code is telling you about its own problem. Retrying just wastes bus time.

### L2 · Connection level: session discard + cooled rebuild

This is where jDCS differs most from typical Modbus wrappers.

**Modbus RTU is serial by nature.** Once a request times out, the session might be half-dead. Continuing to use it just poisons the next request. jDCS's answer: **throw away the whole session** (client + dedicated executor). The next read rebuilds it.

**Rebuilds are rate-limited.** If N points are all waiting on a rebuild in one cycle, with no cooling window you'd burn through every reconnect attempt in a single pass. The cooling window throttles retries to `reconnect-interval-ms`, letting the schedule drive reconnection instead.

### L3 · Monitor level: watchdog (side channel)

**This covers what L1 and L2 can't see.**

Even when the collection path looks fine, two things can still happen:

1. **The scheduler stalls.** `@Scheduled` stops firing — thread exhaustion, scheduler corruption.
2. **The queue backs up.** The `modbus-io` thread can't keep up; the queue keeps growing.

The watchdog runs on its own thread. Even if the collection thread is stuck, it keeps checking and spots the problem.

### L4 · Process level: intentional exit

When an error can't be recovered inside the JVM (JNI hang, too many reconnect failures, thread leak past threshold), jDCS calls `Runtime.halt(-1)`.

**Why not `System.exit()`?** Because `System.exit()` fires shutdown hooks → `@PreDestroy` → `client.disconnect()`. In a fatal-error scenario, `disconnect()` is very likely the thing that hung. Calling it keeps the JVM alive forever.

`Runtime.halt()` kills the JVM outright. Same effect as `kill -9`. **The OS reclaims everything — which is more reliable than any "graceful shutdown."**

### L5 · System level: systemd

The first four layers live inside the JVM. If L4 itself can't run (say, the JVM is wedged just before OOM), the only thing left is the process manager.

jDCS ships a `jdcs.service` with `Restart=always` + `StartLimitBurst=5`:

- Any exit → restart after 10 seconds.
- 5 failures within 5 minutes → mark as `failed`. **Stops config errors from causing a restart storm.**

**Admit the process might crash. Hand recovery to the OS.** That's how industrial gateways usually do it.

> Layer-by-layer implementation details, design trade-offs, and "why this layering" — see **[Architecture_API.md, Chapter 10](Architecture_API.md#十故障兜底链路多层防御)**.

---

## Quick start

### Requirements

| Item | Requirement | Notes |
| --- | --- | --- |
| **JDK** | **17 or higher** | **Mandatory** |
| Maven | 3.8+ | Build |
| Serial port | Physical device or simulator | For development, use something like [ModbusPal](https://sourceforge.net/projects/modbuspal/) |

> ⚠️ **About the JDK version**
>
> jDCS is built on [`digitalpetri/modbus-serial`](https://github.com/digitalpetri/modbus), which requires JDK 17+ as of v2.x.
> The project code itself also uses JDK 17 features (`String.strip()`, records, switch expressions).
> **JDK 8 / 11 won't compile or run.** This is a hard requirement, not a suggestion.

### Three steps to running

**Step 1 — configure the serial port**

Edit `src/main/resources/application-modbus.yml`:

```yaml
modbus:
  serial-port: /dev/ttyUSB0      # Windows: COM8; Linux: /dev/ttyUSB0
  baud-rate: 9600
  parity: NONE
  data-bits: 8
  stop-bits: 1

  # Important: operation-timeout-ms must be > response-timeout-ms
  response-timeout-ms: 2000
  operation-timeout-ms: 5000

  # Leave the rest at defaults
  collection-interval-ms: 30000
  # ...
```

**Step 2 — configure your points**

Edit `src/main/resources/application-modbus-points.yml`:

```yaml
modbus:
  points:
    - point-id: temperature_hall
      point-name: Hall Temperature
      slave-id: 1
      function-code: 3
      address: 1
      data-type: UINT16
      byte-order: ABCD
      scale: 0.1
      unit: "degC"
```

**Step 3 — build and run**

```bash
mvn clean package
java -jar target/jdcs-1.0.0.jar
```

Open `http://localhost:8080/` and you'll see live status.

> 📖 **For the full configuration reference, parameter dependencies, and a cheat sheet of common misconfigurations, see [Architecture_API.md, Chapter 4](Architecture_API.md#四配置手册).**
>
> Pay special attention to the **parameter dependencies**: `operation-timeout-ms > response-timeout-ms` and `watchdog-idle-threshold-ms > worst-case cycle time`. **The code doesn't enforce these at startup.** Get them wrong and you'll see it at runtime — misjudged hangs, restart loops, or interrupted collection.

---

## Stack

| Component | Version | Purpose |
| --- | --- | --- |
| Spring Boot | 3.5.16 | Framework, scheduling, config binding |
| **digitalpetri/modbus-serial** | **2.1.6** | **The Modbus RTU stack (core)** |
| jSerialComm | 2.11.0 | Low-level serial I/O (transitive) |
| Lombok | latest | Boilerplate reduction |

### On the underlying stack

jDCS **builds on `digitalpetri/modbus-serial`.** All Modbus framing, CRC checks, and serial I/O go through it. jDCS handles the connection lifecycle and collection orchestration on top.

There aren't many Modbus libraries in the Java ecosystem. Three reasons we picked `digitalpetri/modbus-serial`:

1. **Modular.** The `modbus-serial` artifact depends only on jSerialComm. It **doesn't drag in Netty** (that's only for `modbus-tcp`). For an edge device, that matters.
2. **Actively maintained.** Frequent releases, quick issue responses.
3. **The known bug is fixed.** The timeout memory leak (Issue #124) was fixed in v2.1.2. v2.1.6 is stable.

> For the stack's limits and design assumptions (like **jSerialComm's default infinite blocking reads/writes** and **write timeouts only working on Windows**), see [Architecture_API.md, Chapter 14](Architecture_API.md#十四编译与测试).

---

## Extending: plugging in your data sink

jDCS ships with one data sink that just logs. To plug in your own storage, reporting, or forwarding, implement one interface:

```java
public interface ModbusDataHandler {
    /**
     * Framework guarantee: any exception thrown here is caught and logged.
     * The collection pipeline is unaffected.
     */
    void handle(ModbusPoint point);
}
```

**Example: MQTT sink**

```java
@Component
public class MqttDataHandler implements ModbusDataHandler {

    private final MqttClient mqttClient;

    public MqttDataHandler(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    @Override
    public void handle(ModbusPoint point) {
        if (!Boolean.TRUE.equals(point.getSuccess())) {
            return;    // Skip failed reads
        }
        String topic = "factory/" + point.getPointId();
        String payload = String.format("{\"value\":%s,\"unit\":\"%s\",\"ts\":%d}",
                point.getValue(), point.getUnit(), point.getTimestamp());
        mqttClient.publish(topic, payload.getBytes());
    }
}
```

**Framework guarantee:** any exception thrown from `handle` is caught and logged. **The Modbus collection pipeline is unaffected.** Your MQTT broker hiccups, your database times out, your HTTP call returns 502 — none of it makes the Modbus device look broken.

> jDCS has three extension points: the **data sink** (this chapter), the **point source** (config → database), and the **log policy** (console → rolling files). Full details in [Architecture_API.md, Chapter 7](Architecture_API.md#七api-说明).

---

## Going deeper

This README is the front door. **The full architecture and API reference** lives in:

### 📖 [Architecture_API.md](Architecture_API.md)

Contents:

| Chapter | Topic |
| --- | --- |
| 2 | Overall architecture and layering |
| 3 | Collection pipeline and exception isolation |
| 4 | Configuration reference (parameter dependencies + common misconfigurations) |
| 5 | Data type mapping (including InHand gateway compatibility) |
| 6 | Byte order reference |
| 7 | API reference |
| 8 | Field validity contracts |
| **10** | **The five-layer fault fallback (the full robustness design)** |
| 11 | Production usage discipline |
| 12 | Known limits and trade-offs |
| 13 | Health check and status page |
| 15 | Production deployment (systemd) |

**Two chapters are especially worth reading:**

- **[Chapter 10 · Fault fallback chain](Architecture_API.md#十故障兜底链路多层防御)** — the key to understanding jDCS's robustness design.
- **[Chapter 11 · Production usage discipline](Architecture_API.md#十一生产环境使用纪律)** — the pre-launch checklist.

---

## Deploying to production

### systemd (recommended)

The repo ships a one-shot deploy script and a systemd unit:

```bash
# Build
mvn clean package

# Deploy (needs root)
sudo bash install.sh

# Verify
systemctl status jdcs
journalctl -u jdcs -f
curl http://localhost:8080/health
```

`install.sh` handles: creating the `jdcs` user and adding it to `dialout`, setting up directories, copying the jar, registering the service, enabling autostart.

### Key JVM flags

`jdcs.service` already includes:

```bash
-Xms256m -Xmx512m                            # Collection framework uses very little memory; 512MB is plenty
-XX:+HeapDumpOnOutOfMemoryError              # Preserve the scene on OOM
-XX:HeapDumpPath=/opt/jdcs/dumps
-XX:+ExitOnOutOfMemoryError                  # Exit on OOM, let systemd restart
```

> Full deployment guide, log management, and common ops commands: [Architecture_API.md, Chapter 15](Architecture_API.md#第十五章-生产部署systemd-服务).

---

## Tests

Three kinds of tests:

| Test class | Form | What it covers | Needs hardware? |
| --- | --- | --- | :---: |
| `TestModbusPointParser` | `main()` self-test | 159 assertions: type mapping / byte order / odd register handling / scaling / lenient API / entity filling / exception tolerance | No |
| `TestModbusBatchReadPlanner` | JUnit 5 | 14 cases: grouping, merging, bit-type isolation, protocol limit, address gaps | No |
| `TestModbusRead` | `@SpringBootTest` | Real serial read (integration test) | **Yes** |

**Offline run**:

```bash
java -cp "out:$(deps)" com.ty.jdcs.test.TestModbusPointParser
# Output:
# [PASS] INT16 parse: 100
# [PASS] FLOAT32 ABCD parse: 25.5
# ...
# Total: 159, passed: 159, failed: 0
```

Exit code is 1 on failure — CI-friendly.

---

## License

[Apache License 2.0](LICENSE)

> Use it, modify it, redistribute it — commercial or personal, doesn't matter. The only requirement is keeping the copyright notice and license file.

---

**jDCS — Java Data Collector Service**
*Get the collection core right. Leave the rest to you.*