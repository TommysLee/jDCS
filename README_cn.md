<h1 align="center">jDCS</h1>

<p align="center">
  <strong> Java Data Collector Service</strong><br />
  <strong>面向工业现场的 Modbus RTU 数据采集基础框架</strong><br />
  <em>把"稳定采集"做扎实，把"外围"留给你。</em>
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

## 为什么会有这个项目

工业现场的 Modbus 数采，看似简单，实则暗坑密布：

- **串口没有"断开事件"**。TCP 对端消失会收到 FIN/RST，串口对端沉默时什么都没有——在库看来一切正常，只是对端不再回应。
- **JNI 会卡死**。`Future.cancel(true)` 只能设置中断标志，无法穿透 JNI 调用。底层驱动一旦卡住，主线程能靠硬超时返回，但 JNI 线程会永久泄漏。
- **进程假死 systemd 检测不到**。JVM 还活着，但采集已经停了十分钟——`Restart=always` 不会救你，因为进程还在。

这些不是理论问题，而是每个跑过**长期无人值守** Modbus 采集的人都踩过的坑。

jDCS 把这些坑的解法**固化成一套基础框架**：

- **采集内核做扎实**：自动重连、硬超时、重试、JNI 卡死检测、看门狗、systemd 兜底。
- **外围留给你**：数据出口、测点来源、日志策略——只提供接口与默认实现，不做任何强假设。

它不是一个"开箱即用的成品软件"，而是一个**可运行、可裁剪、可扩展的骨架**。

---

## 项目截图

### 系统状态页

![系统状态页](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/status-page.jpg)

访问 `http://localhost:8080/` 即可看到实时运行状态——串口连接、测点数量、I/O 队列积压、最近采集时刻，每 3 秒自动刷新。单文件静态页，零依赖、零构建。

### 健康接口

![健康接口](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/health-api.jpg)

`GET /health` 返回 JSON 格式的运行状态，可直接对接 Prometheus、K8s liveness probe 或自研管理台。

### 采集日志

![采集日志](https://raw.githubusercontent.com/TommysLee/images-bed/refs/heads/main/jDCS/collection-log.jpg)

默认数据出口逐点记录工程值。可通过实现 `ModbusDataHandler` 一行替换为你自己的落库/上报逻辑。

---

## 核心特性

| 特性 | 说明 |
| --- | --- |
| **批量合并读取** | 按 `(slaveId, functionCode)` 分组，同组内地址相邻的测点合并为一次请求，减少总线往返 |
| **位型功能码安全隔离** | FC01/FC02 位打包响应**不参与合并**，避免切片错位导致的静默错误 |
| **分层异常隔离** | 批次 / 切片 / Handler 三层隔离，单点故障不影响整轮采集 |
| **硬超时 + 会话废弃** | 超时后丢弃整个会话（客户端 + 执行器），不复用半死连接 |
| **JNI 卡死检测** | 直接观察 `modbus-io-*` 线程数，超阈值触发进程主动退出 |
| **独立看门狗** | 旁路 `@Scheduled` 监控调度停摆与队列停滞 |
| **工业数据模型** | 数据类型对齐映翰通网关，覆盖 BOOL / INT16 / UINT16 / INT32 / UINT32 / FLOAT32 / INT64 / UINT64 / DOUBLE64 / STRING |
| **字节序全覆盖** | ABCD / CDAB / BADC / DCBA 四种，含 `Swapped` / `Big_Endian` 等工业别名 |
| **极简依赖** | 仅 `modbus-serial` + `spring-boot-starter-web` + Lombok |
| **零侵入扩展** | 数据出口、测点来源、日志策略均可自由替换 |

> 完整特性列表与配置手册，详见 **[Architecture_API.md 第四章 · 配置手册](Architecture_API.md#四配置手册)**。

---

## 架构设计：鲁棒性从何而来

**这是理解 jDCS 最重要的一章。**

jDCS 的鲁棒性不是靠"堆保险"，而是靠**多层防御，各司其职**——每一层应对**不同种类**的故障，缺一不可。

```
┌─────────────────────────────────────────────────────────────┐
│ L5  systemd / Docker restart policy / K8s liveness          │  进程级重启
├─────────────────────────────────────────────────────────────┤
│ L4  ModbusFatalErrorHandler → Runtime.halt(-1)              │  主动退出，交 OS 重启
├─────────────────────────────────────────────────────────────┤
│ L3  ModbusWatchdog（独立 @Scheduled）                        │  旁路监控
├─────────────────────────────────────────────────────────────┤
│ L2  会话废弃 + 冷却重建                                      │  连接级自愈
├─────────────────────────────────────────────────────────────┤
│ L1  单次读取：重试 + 应用层硬超时                            │  请求级
└─────────────────────────────────────────────────────────────┘
```

### L1 · 请求级：重试 + 硬超时

每次 Modbus 请求都有两道保护：

- **传输异常自动重试**（CRC 错误、响应格式错误）——最多 `read-retry-count` 次
- **应用层硬超时**——`operation-timeout-ms` 到点即返回，绝不无限等待

关键区分：**协议异常**（从站返回错误码）**不重试**——它反映从站本身的问题，重试只会浪费总线。

### L2 · 连接级：会话废弃 + 冷却重建

这是 jDCS 与其他 Modbus 封装最重要的差异点。

**Modbus RTU 是串行协议**。一旦某次请求超时，会话可能已经处于半死状态——继续用它只会污染后续请求。jDCS 的处理是**丢弃整个会话**（客户端 + 专用执行器），下一次读取时重建。

**重建有冷却窗口**。若一轮采集中有 N 个测点都在等待重建，没有冷却窗口就会在一轮内烧掉全部重连机会。冷却窗口把重试频率约束到 `reconnect-interval-ms`，让调度周期成为真正的重试驱动者。

### L3 · 监控级：看门狗（旁路）

**这是 L1 / L2 覆盖不到的一层。**

即使采集链路本身"看起来正常"，仍可能出现两种情况：

1. **调度停摆**：`@Scheduled` 因线程耗尽或调度器损坏而不再触发；
2. **队列停滞**：`modbus-io` 线程消费不动请求，队列持续积压。

看门狗是**独立线程**上的周期性检查——即使采集线程卡死，它也能照常运行并发现问题。

### L4 · 进程级：致命退出

当错误无法在 JVM 内恢复（JNI 层卡死、连续重连失败、线程泄漏超阈值），jDCS 调用 `Runtime.halt(-1)` 主动退出。

**为什么不用 `System.exit()`？** 因为 `System.exit()` 会触发 shutdown hook → `@PreDestroy` → `client.disconnect()`。而在致命错误场景下，`disconnect()` 很可能**正是卡死的源头**——调用它会让 JVM 永远无法退出。

`Runtime.halt()` 直接终止 JVM，行为等价于 `kill -9`。**由操作系统回收全部资源，比任何"优雅关闭"都可靠。**

### L5 · 系统级：systemd

前四层都在 JVM 内部。如果 L4 本身也无法执行（例如 OOM 前最后阶段、JNI 把 JVM 整体挂死），唯一的恢复机制是**进程管理器**。

jDCS 提供的 `jdcs.service` 用 `Restart=always` + `StartLimitBurst=5` 实现：

- 任何原因退出都在 10 秒后拉起；
- 5 分钟内连续 5 次失败则标记为 `failed`，**防止配置错误导致重启风暴**。

**承认进程可能崩溃，把恢复交给系统**——这是工业网关的标准实践。

---

> 每一层的实现细节、设计取舍、以及"为什么这样分层"，详见 **[Architecture_API.md 第十章 · 故障兜底链路](Architecture_API.md#十故障兜底链路多层防御)**。

---

## 快速开始

### 环境要求

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| **JDK** | **17 或更高** | **必须** |
| Maven | 3.8+ | 构建 |
| 串口 | 物理设备或模拟器 | 开发调试可用 [ModbusPal](https://sourceforge.net/projects/modbuspal/) 等工具模拟 |

> ⚠️ **关于 JDK 版本**
>
> 本项目基于 [`digitalpetri/modbus-serial`](https://github.com/digitalpetri/modbus) 构建，该库自 v2.x 起**要求 JDK 17+**。
> 项目代码本身也使用了 JDK 17 的语言特性（`String.strip()`、`Record`、`Switch` 表达式等）。
> **JDK 8 / 11 无法编译运行**——这不是"建议"，是硬性前提。

### 三步启动

**第一步：配置串口**

编辑 `src/main/resources/application-modbus.yml`：

```yaml
modbus:
  serial-port: /dev/ttyUSB0      # Windows: COM8；Linux: /dev/ttyUSB0
  baud-rate: 9600
  parity: NONE
  data-bits: 8
  stop-bits: 1

  # 关键：operation-timeout-ms 必须 > response-timeout-ms
  response-timeout-ms: 2000
  operation-timeout-ms: 5000

  # 其余参数保持默认即可
  collection-interval-ms: 30000
  # ...
```

**第二步：配置测点**

编辑 `src/main/resources/application-modbus-points.yml`：

```yaml
modbus:
  points:
    - point-id: temperature_hall
      point-name: 大厅温度
      slave-id: 1
      function-code: 3
      address: 1
      data-type: UINT16
      byte-order: ABCD
      scale: 0.1
      unit: "℃"
```

**第三步：打包运行**

```bash
mvn clean package
java -jar target/jdcs-1.0.0.jar
```

启动后打开 `http://localhost:8080/` 即可看到实时状态。

> 📖 **配置项的完整说明、参数间依赖关系、以及常见误配速查，见 [Architecture_API.md 第四章 · 配置手册](Architecture_API.md#四配置手册)。**
>
> 尤其是**参数依赖**——`operation-timeout-ms > response-timeout-ms` 和 `watchdog-idle-threshold-ms > 一轮最坏耗时` 这两条，**代码不做运行期强制校验**，设错会在运行期以"误判 / 频繁重启"的形式暴露。

---

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | 应用框架、调度、配置绑定 |
| **digitalpetri/modbus-serial** | **2.1.6** | **Modbus RTU 协议栈（核心）** |
| jSerialComm | 2.11.0 | 底层串口 I/O（传递依赖） |
| Lombok | 最新 | 样板代码简化 |

### 关于底层协议栈

本项目**基于 `digitalpetri/modbus-serial` 作为底层协议栈进行开发**——所有 Modbus 报文的编解码、CRC 校验、串口收发都由它完成，jDCS 专注于其上的**连接生命周期管理与采集编排**。

Java 生态里的 Modbus 库屈指可数。选择 `digitalpetri/modbus-serial` 的原因有三：

1. **模块化**：`modbus-serial` 只依赖 jSerialComm，**不传递引入 Netty 这样的重型依赖**（`modbus-tcp` 才依赖 Netty）。对一个工业边缘设备场景，这一点很重要。
2. **维护活跃**：版本迭代频繁，Issue 响应迅速，社区持续关注。
3. **关键缺陷已修复**：历史上曾出现的超时内存泄漏（Issue #124）已在 v2.1.2 修复，当前 v2.1.6 稳定可用。

> 关于底层协议栈的能力边界与设计前提（如 **jSerialComm 读写默认无限阻塞**、**写超时仅在 Windows 生效**），详见 [Architecture_API.md 第十四章 · 编译与测试](Architecture_API.md#十四编译与测试)。

---

## 扩展：接入你的数据出口

jDCS 只提供一个"打日志"的默认实现。接入你自己的落库 / 上报 / 转发逻辑，只需实现一个接口：

```java
public interface ModbusDataHandler {
    /**
     * 框架保证：本方法抛出的任何异常都会被捕获并记录，
     * 不会影响采集链路。
     */
    void handle(ModbusPoint point);
}
```

**示例：接 MQTT**

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
            return;    // 失败测点不发布
        }
        String topic = "factory/" + point.getPointId();
        String payload = String.format("{\"value\":%s,\"unit\":\"%s\",\"ts\":%d}",
                point.getValue(), point.getUnit(), point.getTimestamp());
        mqttClient.publish(topic, payload.getBytes());
    }
}
```

**框架保证**：`handle` 抛出的任何异常都会被捕获并记录，**不会影响 Modbus 采集链路**。你的 MQTT 抖动、数据库超时、HTTP 502——都不会让 Modbus 设备被误判为故障。

> jDCS 提供三个扩展点：**数据出口**（本章）、**测点来源**（配置 → 数据库）、**日志策略**（控制台 → 落盘轮转）。完整说明见 [Architecture_API.md 第七章 · API 说明](Architecture_API.md#七api-说明)。

---

## 深入了解

`README.md` 是引子。**完整的架构说明与 API 参考**见：

### 📖 [Architecture_API.md](Architecture_API.md)

其中包括：

| 章节 | 内容 |
| --- | --- |
| **第二章** | 总体架构与分层 |
| **第三章** | 采集主链路与异常隔离分层 |
| **第四章** | 配置手册（含参数依赖 + 常见误配速查）|
| **第五章** | 数据类型对照表（含映翰通工业网关映射）|
| **第六章** | 字节序说明 |
| **第七章** | API 说明 |
| **第八章** | 字段有效性契约 |
| **第十章** | **故障兜底链路（五层防御的完整设计）** |
| **第十一章** | 生产环境使用纪律 |
| **第十二章** | 已知边界与设计取舍 |
| **第十三章** | 健康检查与状态页 |
| **第十五章** | 生产部署（systemd 服务）|

**尤其推荐两个章节**：

- **[第十章 · 故障兜底链路](Architecture_API.md#十故障兜底链路多层防御)**：理解 jDCS 鲁棒性设计的关键。
- **[第十一章 · 生产环境使用纪律](Architecture_API.md#十一生产环境使用纪律)**：上线前的必读清单。

---

## 部署到生产

### systemd（推荐）

项目提供一键部署脚本与 systemd 服务单元：

```bash
# 构建
mvn clean package

# 部署（需要 root）
sudo bash install.sh

# 验证
systemctl status jdcs
journalctl -u jdcs -f
curl http://localhost:8080/health
```

`install.sh` 会自动完成：创建专用用户 `jdcs` 并加入 `dialout` 组 → 创建目录并授权 → 复制 jar → 注册服务 → 启用自启动。

### 关键 JVM 参数

`jdcs.service` 已内置：

```bash
-Xms256m -Xmx512m                            # 采集框架内存占用极低，512MB 足够
-XX:+HeapDumpOnOutOfMemoryError              # OOM 时保留现场
-XX:HeapDumpPath=/opt/jdcs/dumps
-XX:+ExitOnOutOfMemoryError                  # OOM 时主动退出，交 systemd 重启
```

> 完整的部署说明、日志管理、运维常用命令，见 [Architecture_API.md 第十五章 · 生产部署](Architecture_API.md#第十五章-生产部署systemd-服务)。

---

## 测试

jDCS 的测试套件覆盖三类场景：

| 测试类 | 形态 | 内容 | 是否需要硬件 |
| --- | --- | --- | :---: |
| `TestModbusPointParser` | `main()` 自测 | 159 项断言：类型映射 / 字节序 / 奇数落单 / 缩放 / 宽容 API / 实体填充 / 异常容错 | 否 |
| `TestModbusBatchReadPlanner` | JUnit 5 | 14 项：分组、合并、位型不合并、协议上限、地址间隙 | 否 |
| `TestModbusRead` | `@SpringBootTest` | 真实串口读取（集成测试）| **是** |

**离线环境可直接运行**：

```bash
java -cp "out:$(deps)" com.ty.jdcs.test.TestModbusPointParser
# 输出示例：
# [PASS] INT16解析: 100
# [PASS] FLOAT32 ABCD解析: 25.5
# ...
# 总计: 159, 通过: 159, 失败: 0
```

失败时退出码为 1，可直接挂 CI。

---

## 许可证

[Apache License 2.0](LICENSE)

> 可自由使用、修改、分发——无论商业用途还是个人项目。唯一的要求是保留版权声明与许可证文件。

---

**jDCS — Java Data Collector Service**
*夯实采集内核，把外围留给开发者。*