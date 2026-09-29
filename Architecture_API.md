<h1 align="center">jDCS 架构与 API 说明</h1>

<p align="center">
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

> **本文档定位**：面向使用者的**架构说明 + API 参考**。它描述 jDCS 的整体分层、采集主链路、全部配置项（含参数间的依赖关系）、数据类型与字节序约定、解析 API、故障兜底链路与已知边界。
> 一句话概览与快速上手，请见 `README.md`。

---

## 一、项目定位

**jDCS = 面向工业现场的 Modbus RTU 数据采集基础框架。**

它不是一个"开箱即用的成品软件"，而是一个**可运行、可裁剪、可扩展的基础骨架**：

| 维度 | 说明 |
| --- | --- |
| 形态 | 标准 Maven 项目 / Spring Boot 3 应用，`mvn clean package` 后可直接运行 |
| 定位 | **基础框架**：提供从"串口连接 → 批量采集 → 解析 → 数据出口"的完整可跑通链路 |
| 技术栈 | JDK 17+ / Spring Boot 3.5 / digitalpetri `modbus-serial` 2.1.6 / jSerialComm 2.11.0 |
| 设计取向 | **内核极简且强大**——日志、落库、告警等外围能力留给使用者按实际项目改造 |

**三个刻意的"留白"（自由扩展点）**：

1. **数据出口**（`ModbusDataHandler`）：框架只提供一个"打日志"的默认实现，落库/上报/转发由使用者自行实现；
2. **测点配置来源**：默认用配置文件（见第四章），适合小型系统；大型系统可改为数据库/配置中心加载——只要最终得到一个 `List<ModbusPoint>`；
3. **日志落地策略**：默认只输出控制台，落盘与轮转交给使用者的日志规范。

> 📌 一句话：**jDCS 把"采集内核"做扎实，把"外围"留给你。**



---

## 二、总体架构

### 2.1 分层结构

```
com.ty.jdcs
├── BootApplication                     启动类（@SpringBootApplication + @EnableScheduling）
├── ServletInitializer                  外部容器部署入口（可选）
├── web/
│   └── ModbusHealthController          GET /health —— 运行状态端点（JSON）
├── spring.config/
│   ├── ModbusSerialConfig              Bean 装配（6 个）
│   └── properties/
│       └── ModbusSerialProperties      @ConfigurationProperties(prefix = "modbus")
└── modbus/
    ├── api/
    │   └── ModbusRtuMaster             主站封装：连接管理 / 自动重连 / 硬超时 / 重试 / JNI 卡死检测
    ├── core/
    │   ├── ModbusPoint                 测点实体（纯 POJO，无业务逻辑）
    │   ├── ModbusDataType              数据类型常量 + 别名归一化 + 长度映射
    │   ├── ModbusByteOrder             字节序枚举 + 重排算法
    │   └── ModbusBatchReadPlanner      批量读取规划器（分组 + 合并）
    ├── parser/
    │   └── ModbusPointParser           解析器（纯静态、无状态、线程安全）
    ├── scheduler/
    │   └── ModbusCollectionScheduler   @Scheduled 采集调度
    ├── monitor/
    │   └── ModbusWatchdog              @Scheduled 看门狗（调度停摆 + 队列停滞）
    ├── service/
    │   ├── ModbusDataHandler           数据出口接口（扩展点）
    │   └── ModbusLoggingDataHandler    默认实现：仅记录日志
    ├── support/
    │   ├── ModbusHealthChecker         健康状态聚合
    │   ├── ModbusHealthStatus          健康状态 DTO
    │   └── ModbusFatalErrorHandler     致命错误处理（Runtime.halt）
    └── exception/
        └── ModbusReadException         读取异常（含"是否可重试"语义）
```

### 2.2 模块职责

| 模块 | 职责 | 关键点 |
| --- | --- | --- |
| `core` | 数据模型与算法 | `ModbusPoint` 是**纯 POJO**，可直接作为 SQLite 实体、前端 DTO |
| `parser` | 字节 → 工程值 | **纯静态无状态**，无类型特判，全类型统一路径 |
| `api` | 串口通信 | 单串口句柄；会话 = 客户端 + 专用执行器，硬超时后整会话废弃 |
| `scheduler` | 采集编排 | 规划 → 读取 → 切片 → 解析 → 分发，逐层异常隔离 |
| `monitor` | 故障发现 | 覆盖 systemd 覆盖不到的"进程活着但服务假死" |
| `support` | 健康与兜底 | 健康端点 + 致命退出（交操作系统重启） |
| `service` | 数据出口 | 扩展点，默认只打日志 |

### 2.3 依赖方向（单向，无循环）

```
web ──► support ──► api ──► core
                      │
scheduler ────────────┼──► parser ──► core
                      │
monitor ──────────────┴──► support
service ──► core
```

`core` 处于最底层，不依赖任何其他业务模块——这是它可以被独立复用的原因。

---

## 三、采集主链路（数据流）

### 3.1 一轮采集的完整流程

```
ModbusCollectionScheduler.collectAll()          ← @Scheduled(fixedDelay = collection-interval-ms)
  │
  ├─ ① notification()                           通知看门狗与健康检查器"本轮开始"
  │
  ├─ ② ModbusBatchReadPlanner.planGrouped(points, merge-max-gap)
  │      按 (slaveId, functionCode) 分组
  │      → 同组内地址相邻的测点合并为一次请求
  │      → ⚠️ 位型功能码（01/02）不合并，每个测点独立成批
  │
  └─ ③ 逐批次执行
         ├─ master.read(slaveId, startAddress, totalCount, functionCode)
         │      └─ 重试（仅传输异常）→ 硬超时（应用层）→ 会话废弃
         │      └─ 返回前 padToEven(bytes) ← 源头补齐偶数长度
         │
         └─ 逐切片
              ├─ extractSlice(...)  按寄存器偏移切出本测点字节
              ├─ dispatch(point, bytes)
              │     ├─ tryFill(point, bytes)   解析 + 填充实体
              │     └─ dataHandler.handle(point)  数据出口回调
              └─ 单点异常就地隔离（不中断本批其余测点）
```

### 3.2 合并读取规则

- **分组键**：`(slaveId, functionCode)`——不同功能码代表不同地址空间，混读会返回错误数据；
- **合并条件**（组内同时满足）：
  1. 并入后总长度 ≤ `MAX_REGISTERS_PER_REQUEST`（协议上限 **125**）；
  2. 地址间隙 ≤ `merge-max-gap`；
- **位型功能码（01 读线圈 / 02 读离散输入）不参与合并**：

  > 位型响应按**位打包**（每点 1 bit、每字节 8 点），与"测点数 × 2 字节"不可换算。若参与合并，第二个及之后的测点会按寄存器偏移切片，**必然越界或取到错误位**。故每个位型测点独立成批。

### 3.3 异常隔离分层（三层，各司其职）

| 层级 | 保护对象 | 越界后果（若无此层） |
| --- | --- | --- |
| **批次级** `catch (Exception)` | 整个批次 | 单个批次异常会导致**整轮采集中断**，后续批次全部跳过 |
| **切片级** `try / catch (Exception)` | 本批次内其余测点 | 单点切片失败会让**同批次其余测点被一并跳过** |
| **Handler 级** `catch (Throwable)` | 采集链路本身 | 使用者的数据出口抛异常会**反噬采集线程** |

> 📌 三者是**互补**关系，不是冗余——每一层对应一类不同粒度的故障。

### 3.4 数据出口（扩展点）

```java
public interface ModbusDataHandler {
    /** 框架保证：本方法抛出的任何异常都会被捕获并记录，不会影响采集链路 */
    void handle(ModbusPoint point);
}
```

默认实现 `ModbusLoggingDataHandler` 只打日志。**接入你自己的落库/上报逻辑，只需实现该接口并注册为 Bean。**

---

## 四、配置手册

jDCS 通过 Spring Profile 把配置拆为三个文件：

| 文件 | 内容 | 是否启用 |
| --- | --- | --- |
| `application.yml` | 服务器与 Spring 基础配置 | 始终 |
| `application-modbus.yml` | 串口采集参数 | 由 `spring.profiles.include: modbus` 引入 |
| `application-modbus-points.yml` | 测点清单 | 由 `spring.profiles.include: modbus-points` 引入 |

### 4.1 application.yml

```yaml
server:
  port: 8080                # HTTP 端口（/health 与静态状态页使用）
  servlet:
    encoding:
      charset: UTF-8

spring:
  profiles:
    include: modbus, modbus-points   # 引入采集参数与测点清单
  task:
    scheduling:
      pool:
        size: 2             # 定时任务线程池大小
      thread-name-prefix: "sched-"
  thymeleaf:
    cache: false
  jackson:
    default-property-inclusion: NON_EMPTY
```

### 4.2 application-modbus.yml —— 串口采集参数

```yaml
modbus:
  # === 串口参数 ===
  serial-port: COM8                 # Windows: COM8；Linux: /dev/ttyUSB0
  baud-rate: 9600
  parity: NONE                      # NONE / EVEN / ODD / MARK / SPACE
  data-bits: 8
  stop-bits: 1

  # === 超时参数 ===
  response-timeout-ms: 2000         # 单次请求响应超时
  operation-timeout-ms: 5000        # 应用层硬超时，必须 > response-timeout-ms

  # === 读取重试（仅针对传输异常） ===
  read-retry-count: 2
  read-retry-interval-ms: 200

  # === 连接参数 ===
  reconnect-interval-ms: 10000      # 重建冷却窗口间隔
  max-reconnect-attempts: 5         # 连续重连失败 N 次后强制退出
  queue-capacity: 10                # 单线程执行器队列容量

  # === JNI 卡死检测 ===
  timeout-threshold: 3              # 累计硬超时 N 次后强制退出
  leaked-thread-threshold: 5        # modbus-io 泄漏线程 N 个后强制退出

  # === 调度参数 ===
  collection-interval-ms: 2000      # 采集轮询周期，必须 < watchdog-idle-threshold-ms
  watchdog-interval-ms: 60000       # 看门狗检查周期

  # === 看门狗 ===
  watchdog-idle-threshold-ms: 300000   # 距上次采集开始超过此值 → 判定调度停摆
  watchdog-queue-stagnant-ms: 60000    # 队列停滞超过此值 → 判定 JNI 卡死

  # === 合并读取 ===
  merge-max-gap: 5                  # 地址间隙 ≤ N 个寄存器的测点被合并读取
```

#### 参数全表

| 参数 | 默认值 | 说明 | 备注 |
| --- | :---: | --- | --- |
| `serial-port` | COM7 | 串口名 | Windows `COM8`；Linux `/dev/ttyUSB0` |
| `baud-rate` | 9600 | 波特率 | 需与设备一致 |
| `parity` | NONE | 校验位 | 不区分大小写；非法值回退 NONE |
| `data-bits` | 8 | 数据位 | — |
| `stop-bits` | 1 | 停止位 | 仅 1 或 2 |
| `response-timeout-ms` | 2000 | 单次请求响应超时 | **必须 < `operation-timeout-ms`** |
| `operation-timeout-ms` | 5000 | 应用层硬超时 | **必须 > `response-timeout-ms`** |
| `read-retry-count` | 2 | 重试次数（仅传输异常） | 总尝试次数 = 该值 + 1 |
| `read-retry-interval-ms` | 200 | 重试间隔 | — |
| `reconnect-interval-ms` | 10000 | 连接重建冷却窗口 | 防止一轮内密集重建 |
| `max-reconnect-attempts` | 5 | 连续重连失败上限 | 超限 → 致命退出 |
| `queue-capacity` | 10 | 执行器队列容量 | 满则拒绝（快速失败） |
| `timeout-threshold` | 3 | 累计硬超时上限 | 超限 → 致命退出 |
| `leaked-thread-threshold` | 5 | 泄漏线程数上限 | 超限 → 致命退出 |
| `collection-interval-ms` | 30000 | 采集周期 | **必须 < `watchdog-idle-threshold-ms`** |
| `watchdog-interval-ms` | 60000 | 看门狗周期 | — |
| `watchdog-idle-threshold-ms` | 300000 | 调度停摆阈值 | **必须 > 采集一轮最坏耗时**；`0` = 禁用 |
| `watchdog-queue-stagnant-ms` | 60000 | 队列停滞阈值 | `0` = 禁用 |
| `merge-max-gap` | 5 | 合并最大地址间隙 | 建议 5~10 |

#### ⚠️ 参数间的依赖关系（务必逐条核对）

> **重要提醒**：以下依赖关系**仅写在注释里，代码不做运行期强制校验**。设错不会在启动时报错，而是在运行期以"误判 / 频繁重启 / 采集中断"的形式暴露——**请务必手动核对**。

**① `operation-timeout-ms` > `response-timeout-ms`**（硬性）

```
        ├── response-timeout-ms（单次响应超时，2000）
   ─────┼───────────── operation-timeout-ms（应用层硬超时，5000）
        │                          │
        └─ 正常响应在这里返回        └─ 超过这里 = 判定 JNI 卡死，废弃整个会话
```

- 若 `operation-timeout-ms` **≤** `response-timeout-ms`：正常的慢响应会在应用层硬超时前还没返回，被**误判为卡死** → 会话被丢弃 + 计入硬超时 → 累计达 `timeout-threshold` 后**进程强制退出**；
- 建议关系：`operation-timeout-ms ≥ response-timeout-ms × 2`，并给重试留出余量。

**② `watchdog-idle-threshold-ms` > 一轮采集最坏耗时**（硬性）

一轮采集的**最坏耗时**（设备全部离线时）≈

```
批次数 × (read-retry-count + 1) × (response-timeout-ms + read-retry-interval-ms)
```

以默认值代入：`批次数 × 3 × (2000 + 200) ≈ 批次数 × 6.6 秒`

- **批次数**由 `(slaveId, functionCode)` 分组 + `merge-max-gap` 合并决定；**位型测点每个独占一批**；
- 若阈值小于最坏耗时，看门狗会把"慢轮次"误判为"调度停摆" → **强制退出** → 重启（设备仍离线）→ 可能形成**周期性重启循环**；
- **建议**：`watchdog-idle-threshold-ms ≥ 一轮最坏耗时 × 3`。

**③ `collection-interval-ms` < `watchdog-idle-threshold-ms`**（硬性）

调度用 `fixedDelay`（上一轮结束后再等固定间隔）。若采集周期本身已接近或超过停摆阈值，看门狗会**持续误判**。

**④ `merge-max-gap` 与 `watchdog-idle-threshold-ms` 联动**

`merge-max-gap` 越大 → 合并越积极 → 批次数越少 → 单批读取越长；反之批次数增加、一轮总耗时上升。**改动合并策略后，须重新核算 ②**。

**⑤ JNI 卡死检测三兄弟**

`timeout-threshold`（硬超时累计）、`leaked-thread-threshold`（泄漏线程数）、`queue-capacity`（队列上限）共同决定"何时判定卡死并退出"。见第十章。

**⑥ `reconnect-interval-ms`**

连接重建的冷却窗口。一轮采集内最多触发一次重建，避免把总线拖垮。

#### 常见误配速查

| 误配 | 后果 | 修正 |
| --- | --- | --- |
| `operation-timeout-ms` ≤ `response-timeout-ms` | 慢响应被误判为卡死，会话频繁废弃 | 放大硬超时 |
| `watchdog-idle-threshold-ms` 过小 | 慢轮次被误判停摆 → 周期重启 | 按 ② 重算 |
| `merge-max-gap` 调大后未重算 ② | 一轮耗时超阈值 → 误判 | 重算 ② |
| `watchdog-idle-threshold-ms: 0` | **看门狗停摆检测被关闭** | 确认是否有意为之 |
| 测点 `function-code` 与 `data-type` 不匹配 | 位型配数值类型 → 解析值与预期不符 | 线圈/离散统一用 `BOOL` |

### 4.3 application-modbus-points.yml —— 测点清单

```yaml
modbus:
  points:
    - point-id: temperature_hall     # 测点唯一 ID
      point-name: 大厅温度            # 测点名称（中文/英文均可）
      slave-id: 1                    # 从站地址 1~247
      function-code: 3               # 功能码：1/2/3/4
      address: 1                     # 起始寄存器地址
      data-type: UINT16              # 数据类型（见第五章）
      byte-order: ABCD               # 字节序：ABCD/CDAB/BADC/DCBA
      scale: 0.1                     # 缩放系数（工程值 = 原始值 × scale + offset）
      offset: 0                      # 偏移量
      unit: "℃"                      # 单位（仅用于展示）

    - point-id: sn
      point-name: 序列号
      slave-id: 1
      function-code: 3
      address: 2
      data-type: STRING
      register-count: 4              # STRING 必须指定长度；数值类型可省略
```

#### 测点字段说明

| 字段 | 必填 | 说明 |
| --- | :---: | --- |
| `point-id` | ✅ | 测点唯一标识，建议全局唯一 |
| `point-name` | ✅ | 显示名，日志中体现 |
| `slave-id` | ✅ | 从站地址，**1~247**（0 为广播、248+ 保留） |
| `function-code` | ✅ | 1=线圈（RW） / 2=离散输入（RO） / 3=保持寄存器（RW） / 4=输入寄存器（RO） |
| `address` | ✅ | 起始寄存器地址，**0~65535** |
| `data-type` | ✅ | 数据类型，见第五章；不区分大小写，支持别名 |
| `byte-order` | ❌ | 缺省 `ABCD`；映翰通网关可填 `Swapped`（等价 CDAB） |
| `scale` | ❌ | 缺省 1.0；**不允许 NaN / Infinity** |
| `offset` | ❌ | 缺省 0.0；同 scale 限制 |
| `unit` | ❌ | 仅用于展示 |
| `register-count` | 条件必填 | **仅 STRING 必填**；数值类型由类型唯一确定，配置值会被自动对齐 |
| `charset` | ❌ | 缺省 US-ASCII（仅 STRING 生效） |
| `parity` / 其他 | — | 测点级不支持，串口参数为全局配置 |

#### 💡 扩展点：从配置文件到数据库

当前采用**配置文件**组织测点，**是为简化而做的选择**——它适合：

- 小型工业采集系统（数十到数百测点）；
- 配置随代码一起版本管理、便于审阅；
- 无需引入数据库依赖即可运行。

**当规模变大（数千测点、需要在线增删改、多租户等），可自由扩展为数据库驱动**：

```
数据库 / 配置中心 / 远程接口
        │  （自行实现加载逻辑）
        ▼
   List<ModbusPoint>
        │
        ▼
ModbusSerialProperties.points   ← 唯一的对接点
```

**扩展方式**（任选其一）：

1. 保留 `ModbusSerialProperties`，用 `@PostConstruct` 从数据库加载并 `setPoints(...)`；
2. 自定义 `@ConfigurationProperties` 数据源，最终产出 `List<ModbusPoint>` 注入采集链路。

> 框架对"测点从哪来"**不做任何假设**——只要最终能得到 `List<ModbusPoint>`，采集链路原样可用。这正是"基础框架"应有的自由度。



---

## 五、数据类型对照表（对齐映翰通工业网关）

### 5.1 主对照表

| 映翰通类型 | 本库标准名 | 含义 | 寄存器数 | Java 返回类型（无缩放） |
| --- | --- | --- | :---: | --- |
| BIT | **BOOL** | 0 或 1（非零即 true） | 1 | Boolean |
| WORD | UINT16 | 16 位无符号整数 | 1 | Integer |
| INT | INT16 | 16 位有符号整数 | 1 | Short |
| DWORD | UINT32 | 32 位无符号整数 | 2 | Long |
| DINT | INT32 | 32 位有符号整数 | 2 | Integer |
| FLOAT | FLOAT32 | 32 位单精度浮点 | 2 | Float |
| DOUBLE | DOUBLE64 | 64 位双精度浮点 | 4 | Double |
| ULONG | **UINT64** | 64 位无符号整数 | 4 | BigInteger |
| LONG | INT64 | 64 位有符号整数 | 4 | Long |
| STRING | STRING | ASCII 字符串（遇 `\0` 截断） | 自定义 | String |
| BCD16 / BCD32 | — | BCD 码 | — | **暂未实现** |

### 5.2 全部别名（不区分大小写，忽略空格 / 下划线 / 连字符）

| 标准名 | 寄存器数 | 可用别名 |
| --- | :---: | --- |
| `BOOL` | 1 | `BIT`、`BOOLEAN` |
| `INT16` | 1 | `SHORT`、`INT` |
| `UINT16` | 1 | `USHORT`、`WORD`、`UINT` |
| `INT32` | 2 | `DINT` |
| `UINT32` | 2 | `DWORD`、`UDINT`、`UNSIGNEDLONG`⚠️ |
| `FLOAT32` | 2 | `FLOAT`、`REAL` |
| `INT64` | 4 | `LONG`、`LONG64`、`LINT` |
| `UINT64` | 4 | `ULONG`、`QWORD`、`DWORD64`、`ULINT` |
| `DOUBLE64` | 4 | `DOUBLE`、`LREAL` |
| `STRING` | 自定义 | `STR`、`ASCII` |

### 5.3 IEC 61131-3 无符号别名族

本库的 IEC 无符号族与有符号族**位宽严格对称**：

| IEC 别名 | 本库标准名 | 寄存器数 | 对照有符号别名 |
| --- | --- | :---: | --- |
| `UINT` | UINT16 | 1 | `INT`（16 位） |
| `UDINT` | UINT32 | 2 | `DINT`（32 位） |
| `ULINT` | UINT64 | 4 | `LINT`（64 位） |

> ⚠️ **`UNSIGNEDLONG` 语义有歧义**（C# 的 `ulong` 是 64 位，C 的 `unsigned long` 随平台 32/64 位）。本库保留其映射为 UINT32 **仅为兼容历史配置**。**新配置请勿使用**，改用 `UINT32`/`DWORD`/`UDINT`（32 位）或 `UINT64`/`ULONG`/`QWORD`/`ULINT`（64 位）。

### 5.4 关于 8 位类型（SINT / USINT / BYTE）

**这些类型会被显式拒绝**，这是刻意行为，不是遗漏：

- `SINT`（8 位有符号）、`USINT`（8 位无符号）、`BYTE`（8 位位串）；
- **Modbus 最小数据单位是 16 位寄存器**，没有 8 位类型可承载；
- **拒绝优于硬塞**——硬塞进 16 位会静默产出错误值。

### 5.5 关于 BCD16 / BCD32

BCD 不是"数值类型"，而是**编码格式**（每 4 位表示 1 位十进制数）。

```
十进制 12 → BCD 编码 0x0012 → 当 INT16 读 = 18 ❌（必须专门解码）
```

当前配置期会显式报错 `Unsupported data type: BCD16`——**不会静默解析出错值**。主要出现在智能电表、老式数显仪表、RTC 时钟芯片等场景；如你的现场确实需要，可实现专门的解码逻辑后接入。



---

## 六、字节序说明

### 6.1 四种字节序

| 字节序 | 排列顺序 | 适用场景 |
| --- | --- | --- |
| `ABCD` | A B C D | 标准大端，西门子/施耐德等欧系 PLC 默认 |
| `CDAB` | C D A B | 字交换模式，国产 PLC / 映翰通网关默认，可填 `Swapped` |
| `BADC` | B A D C | 字节交换模式，部分早期嵌入式设备 |
| `DCBA` | D C B A | 标准小端，x86 / 部分 DSP 设备 |

> **核心规则**：**先做寄存器级成对交换，再做寄存器内部字节交换；奇数个寄存器时最后一个落单保留。**
>
> 示例（7 个寄存器）：`(Reg0,Reg1)→(Reg1,Reg0)`、`(Reg2,Reg3)→(Reg3,Reg2)`、`(Reg4,Reg5)→(Reg5,Reg4)`，**Reg6 落单保留**。

### 6.2 别名（不区分大小写）

| 字节序 | 可用别名 |
| --- | --- |
| `ABCD` | `BIG_ENDIAN`、`BIGENDIAN`、`NETWORK` |
| `CDAB` | `Swapped`、`WORD_SWAP`、`WORDSWAP`、`MODBUSSWAPPED` |
| `BADC` | `BYTE_SWAP`、`BYTESWAP` |
| `DCBA` | `LITTLE_ENDIAN`、`LITTLEENDIAN`、`INTEL` |

> 映翰通网关界面中的 **`Swapped`** 可直接填入 `byte-order`。

---

## 七、API 说明

### 7.1 解析器 `ModbusPointParser`（纯静态 / 无状态 / 线程安全）

| 方法 | 说明 | 场景 |
| --- | --- | --- |
| `validate(point)` | 配置期严格校验（含就地归一化） | **程序启动时调用**，拦截无效配置 |
| `resolveRegisterCount(point)` | 计算需读取的寄存器数量 | 发起读请求前获取 `count` |
| `parse(point, bytes)` | 严格解析，失败抛异常 | 确定配置正确的场景 |
| `parseAt(point, bytes, offset)` | 从连续字节流指定偏移解析 | 一次读多寄存器、逐点解析 |
| `parseScaled(point, bytes)` | 解析并应用缩放偏移，返回 `double` | 直接获取工程值 |
| `tryParse(point, bytes)` | 宽容解析，不抛异常 | 采集循环单测点容错 |
| `tryParseScaled(point, bytes)` | 宽容解析 + 缩放 | 采集循环获取工程值 |
| `fill(point, bytes)` | 解析并填充实体全部字段 | 直接存库 / 返回前端 |
| `fillAt(point, bytes, offset)` | 指定偏移解析并填充实体 | 连续寄存器逐点填充 |
| `tryFill(point, bytes)` | `fill` 的宽容版；`point` 为 null 返回 false | 采集循环直接落库 |
| `slice(bytes, offset, count)` | 按寄存器偏移切出字节片段 | 批读后切片 |
| `padToEven(bytes)` | 奇数长度补为偶数（高位补 0） | **采集层源头调用**，见 7.4 |

### 7.2 主站 `ModbusRtuMaster`

| 方法 | 说明 |
| --- | --- |
| `readCoils(slaveId, addr, count)` | FC01 读线圈 |
| `readDiscreteInputs(slaveId, addr, count)` | FC02 读离散输入 |
| `readHoldingRegisters(slaveId, addr, count)` | FC03 读保持寄存器 |
| `readInputRegisters(slaveId, addr, count)` | FC04 读输入寄存器 |
| `read(slaveId, addr, count, functionCode)` | 通用读取入口（内部重试 + 硬超时 + 会话管理） |
| `getIoQueueSize()` | 当前执行器队列积压大小 |
| `isConnected()` | 串口是否已连接 |
| `close()` | 关闭主站与所有资源 |

**读取语义**：

```
传输异常（CRC 错误 / 响应格式错误）  → 重试（最多 read-retry-count 次）
协议异常（从站返回错误码）          → 立即抛出，不重试
应用层硬超时（operation-timeout-ms）→ 废弃会话 + 抛出，不重试
```

### 7.3 数据出口 `ModbusDataHandler`（扩展点）

| 方法 | 说明 |
| --- | --- |
| `handle(point)` | 处理一次成功采集的数据 |

框架保证：`handle` 抛出的任何异常都会被捕获并记录，**不会影响采集链路**。

### 7.4 `padToEven` —— 源头补齐（重要约定）

```java
public static byte[] padToEven(byte[] input) {
    if (null == input || (input.length & 1) == 0) return input;  // 偶数：透传原引用
    byte[] result = new byte[input.length + 1];                  // 默认全 0
    System.arraycopy(input, 0, result, 1, input.length);         // 高位补 0
    return result;
}
```

**为什么需要它**：Modbus 的硬约束是 **1 寄存器 = 2 字节**，因此解析器要求字节数组长度**必须为偶数**（否则抛 `Byte array length must be even`）。而**位型功能码（01/02）是位打包**——读 1 个线圈时设备只返回 **1 字节**，长度是奇数。

**如何处理**：在**采集层源头**调用一次——`ModbusRtuMaster.doRead()` 的返回值统一过 `padToEven`：

```
设备响应 → doRead() → padToEven() → 上层
                        ↑ 唯一调用点（源头单点）
```

| 功能码 | 是否有实际作用 |
| --- | --- |
| 03 / 04（寄存器） | **无**（长度恒为偶数，直接透传原引用，零开销） |
| 01 / 02（位） | **有**（单点读取返回 1 字节 → 补为 2 字节） |

**为什么放在源头而不是解析器内部**：

> 解析器是**契约的执行者**，应保持"入参必为偶数长度"的纯净契约；采集层是**数据的入口**，应由它负责把数据对齐到契约。
> 源头单点修复（一处）优于过程多点补丁（解析器内处处判断）——后者一旦遗漏就会产生线上风险。

**对字节序解析的影响**：**无**。理由：

- 寄存器数据长度恒为偶数 → `padToEven` 透传，字节未被改动；
- 位型数据补零后，**BOOL 的判定是"非零即真"，而字节交换不改变"是否存在非零字节"这一事实**——故与字节序无关（实测 4 种字节序 × 256 取值全部正确）；
- 补零方向为**高位前置**（`[0x01]` → `[0x00,0x01]`），等价于"这个数本就是 0x0001"，数值不变。

> ⚠️ **若不走 `padToEven`**：位型测点会因奇数长度被解析器拒绝（`Byte array length must be even`）。这正是它必须存在于采集层的原因。



---

## 八、字段有效性契约

### 8.1 `ModbusPoint` 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pointId` / `pointName` | String | 测点标识与名称 |
| `slaveId` | Integer | 从站地址 1~247 |
| `functionCode` | Integer | 功能码 1~127（常用 1/2/3/4） |
| `address` | Integer | 起始地址 0~65535 |
| `registerCount` | Integer | 寄存器数量；**数值类型由类型唯一确定，STRING 必须显式指定** |
| `dataType` | String | 数据类型（见第五章） |
| `byteOrder` | String | 字节序（见第六章） |
| `scale` / `offset` | Double | 缩放与偏移；工程值 = 原始值 × scale + offset |
| `charset` | String | 仅 STRING 生效，缺省 US-ASCII |
| `unit` | String | 单位（展示用） |
| **`rawValue`** | `byte[]` | **Modbus 原始字节片段**（本次解析所用，已克隆，与调用方缓冲区解耦） |
| **`value`** | `Object` | **解析后的工程值**（类型见 8.2） |
| `timestamp` | Long | 采集时间戳（毫秒） |
| `success` | Boolean | `true`=解析成功；`false`=通信/解析失败 |
| `errorMsg` | String | 失败原因 |

> **`registerCount` 语义**：配置值与类型固有长度不一致时，`validate()` 会**自动对齐为类型固有长度**。
> - **数值类型**（INT16/UINT32/FLOAT32/…）：长度由 `dataType` **唯一确定**（对标映翰通等主流工业网关的 1:1 定义），配置值只是它的显式化；
> - **STRING**：类型本身不含长度信息，**必须**由使用者指定。

### 8.2 `fill` / `fillAt` / `tryFill` 的字段契约（三者完全一致）

| 返回 | `success` | `rawValue` | `value` | `errorMsg` |
| --- | :---: | --- | --- | --- |
| `true` | `true` | 本次解析所用字节片段（**已克隆**） | 见下方类型契约 | `null` |
| `false` | `false` | **`null`** | **`null`** | 失败原因 |

**`value` 类型契约**：

| 情况 | 返回类型 |
| --- | --- |
| 未配置缩放 / 偏移 | 原始类型：<br />`BOOL`→Boolean <br />`INT16`→Short <br />`UINT16`→Integer <br />`INT32`→Integer <br />`UINT32`→Long <br />`FLOAT32`→Float <br />`INT64`→Long <br />`UINT64`→BigInteger <br />`DOUBLE64`→Double <br />`STRING`→String |
| 配置了缩放 / 偏移 | Double（缩放后的工程值） |

> **为什么未缩放时要保留原始类型**：让 INT64 超出 2^53 的大值**不丢精度**。例如 `9007199254740993`（2^53+1）若转 double 会变成 `9007199254740992`。这是累积电量、高精度计数器一类测点的关键。
>
> **需要统一按 double 取值时**：用 `((Number) point.getValue()).doubleValue()`，或直接调用 `parseScaled` / `tryParseScaled`。
>
> **失败时清空 `rawValue` 与 `value`**：避免消费方未校验 `success` 就把无法解析的字节流当作有效原始值持久化。



---

## 九、配置文本裁剪策略

配置文本（`dataType`、`byteOrder`）的首尾空白统一使用标准库 **`String.strip()`** 裁剪，**不使用 `String.trim()`**。两者语义不同，实测差异：

| 字符 | `trim()` | `strip()` | 说明 |
| --- | :---: | :---: | --- |
| 半角空格 / TAB / CR / LF | ✅ 裁剪 | ✅ 裁剪 | 两者一致 |
| **全角空格 U+3000** | ❌ **不裁剪** | ✅ 裁剪 | 中文环境高频踩坑 |
| EN QUAD U+2000、EM SPACE U+2003 等 | ❌ 不裁剪 | ✅ 裁剪 | Unicode 空白 |
| 行分隔符 U+2028、段分隔符 U+2029 | ❌ 不裁剪 | ✅ 裁剪 | Unicode 空白 |
| NUL U+0000、ESC U+001B | ✅ 裁剪 | ❌ 不裁剪 | `trim()` 裁剪所有 ≤ U+0020 |

> **为什么不用 `trim()`**：`trim()` 只裁剪码点 ≤ U+0020 的字符，**无法识别全角空格 U+3000**。中文输入法下配置写成 `FLOAT32　`（尾随全角空格）时，`trim()` 会原样保留，报错为 `Unsupported data type: FLOAT32　`——**错误信息里的空格不可见，现场极难排查**。`strip()` 按 `Character.isWhitespace` 裁剪，可正确处理。已用"换回 `trim()` 的变体 + 同一套探针"反证：全角空格 4 例全部失败。

> **JDK 版本要求**：`String.strip()` 自 JDK 11 引入，本项目要求 **JDK 17+**。

> **已知未覆盖字符**：不换行空格 U+00A0、数字空格 U+2007、BOM U+FEFF 三者 `strip()` 也不裁剪（`Character.isWhitespace` 对其返回 `false`）。但它们**不会被静默误解析**——配置期会直接报错，符合「配置期严」原则，仅错误信息中的字符不可见。

---

## 十、故障兜底链路（多层防御）

### 10.1 为什么需要多层

**核心事实：串口没有"断开事件"。** TCP 断开会收到 FIN/RST，串口对端消失时则**什么都没有**——在库看来一切正常，只是对端沉默。

更麻烦的是底层：jSerialComm 的读写默认**无限阻塞**，而 `Future.cancel(true)` **无法穿透 JNI 调用**。因此当驱动卡死时：

- 主线程能靠应用层硬超时返回；
- 但**JNI 线程会永久泄漏**（不响应 interrupt）。

**承认这一点，是整套设计的起点。**

### 10.2 五层防御

```
┌─────────────────────────────────────────────────────────────┐
│ L5  systemd / Docker restart policy / K8s liveness          │  进程级重启
├─────────────────────────────────────────────────────────────┤
│ L4  ModbusFatalErrorHandler → Runtime.halt(-1)              │  主动退出，交 OS 重启
│       · 累计硬超时 ≥ timeout-threshold                       │
│       · 泄漏线程 ≥ leaked-thread-threshold                   │
│       · 连续重连失败 ≥ max-reconnect-attempts                │
│       · 看门狗判定调度停摆 / 队列停滞                         │
├─────────────────────────────────────────────────────────────┤
│ L3  ModbusWatchdog（独立 @Scheduled）                        │  旁路监控
│       · 调度活性：距上次采集开始 > watchdog-idle-threshold    │
│       · 队列进度：积压持续不降 > watchdog-queue-stagnant      │
├─────────────────────────────────────────────────────────────┤
│ L2  会话废弃 + 重建（ModbusRtuMaster）                       │  连接级自愈
│       · 硬超时 → discardSession()，不复用半死会话            │
│       · 冷却窗口 reconnect-interval-ms，一轮最多重建一次     │
├─────────────────────────────────────────────────────────────┤
│ L1  单次读取：重试 + 应用层硬超时                            │  请求级
│       · 传输异常重试 read-retry-count 次                     │
│       · operation-timeout-ms 到点即返回，绝不无限等待        │
└─────────────────────────────────────────────────────────────┘
```

| 层 | 机制 | 应对的故障 |
| :---: | --- | --- |
| L1 | 重试 + 硬超时 | 瞬时干扰、单次超时 |
| L2 | 会话废弃 + 冷却重建 | 链路半死、串口句柄异常 |
| L3 | 看门狗（旁路） | 进程活着但服务假死 |
| L4 | 致命退出 | 无法自愈的 JNI 卡死 |
| L5 | 操作系统重启 | 进程级故障 |

### 10.3 ⚠️ 关于队列停滞检测的实际效力（重要说明）

`watchdog-queue-stagnant-ms` 的判定逻辑是"**执行器队列持续有元素且长时间不下降**"。但在当前架构下，它的**实际效力有限**：

> **采集是"同步单任务提交"**——调度线程每次提交一个任务后，最多等待 `operation-timeout-ms` 就会放弃并更换会话。因此队列**很难在 60 秒阈值内持续非空**，该检查通常不会触发。

**真正兜住 JNI 卡死的是另外两个信号**：

| 信号 | 判定 | 触发后果 |
| --- | --- | --- |
| **`leaked-thread-threshold`**（泄漏线程数） | `modbus-io-*` 前缀线程数 ≥ 阈值 | 致命退出 |
| **`timeout-threshold`**（累计硬超时） | 硬超时累计次数 ≥ 阈值 | 致命退出 |

**实践建议**：

- **不要因为"有队列停滞检测"就低估对 `leaked-thread-threshold` 的依赖**——后者才是 JNI 卡死的主要发现手段；
- 调参数时优先确保**泄漏线程检测**能正常工作（阈值不宜过大）；
- 队列停滞检测作为**补充信号**保留即可，无需为其调参而纠结。

> 这三者（队列停滞 / 泄漏线程 / 硬超时累计）是**互补**关系：前者是"更早的信号"，后两者是"更可靠的信号"。多层防御本就如此——不追求单一机制的全面覆盖。



---

## 十一、生产环境使用纪律

1. **配置加载时必调 `validate()`**：数据类型/字节序拼写错误、非有限缩放值在**启动期**暴露。（框架已在 `ModbusRtuMaster.init()` 中对全部测点自动执行，手工加载配置时也请自行调用）
2. **采集循环用 `tryFill()` / `tryParse()`**：单测点异常降级为失败结果，不中断整轮轮询。
3. **数值类型无需手动配置 `registerCount`**：自动根据 `dataType` 推导；仅 STRING 需要指定长度。
4. **同一 `ModbusPoint` 实例不要跨线程并发写**：`validate` / `parse` 会**就地归一化**配置（`dataType`、`byteOrder`、`registerCount`、`charset`、`scale`、`offset` 会被写回实体）。
5. **读 `rawValue` / `value` 前先判 `success`**：失败时二者为 `null`。
6. **取数值统一用 `((Number) v).doubleValue()`**：未缩放时 `value` 是原始类型，直接强转 `Double` 会抛 `ClassCastException`。
7. **核对第四章参数依赖**：`operation-timeout-ms > response-timeout-ms`、`watchdog-idle-threshold-ms > 一轮最坏耗时`——**代码不强制校验，设错在运行期才暴露**。
8. **线圈 / 离散测点统一配 `BOOL`**：位型数据若配成 `UINT16` 等数值类型，在部分字节序下会得到非预期值（详见 12.3）。
9. **工程需引入 Lombok**；JDK 17+。

---

## 十二、已知边界与设计取舍

### 12.1 配置校验范围

`validate()` 校验：`slaveId`(1~247)、`functionCode`(1~127)、`address`(0~65535)、数据类型合法性、字节序合法性、charset 合法性、缩放非有限值、`registerCount` 与类型长度对齐。

**不校验**：功能码与数据类型的搭配是否合理（如 FC01 配 `FLOAT32`）。原因：`ModbusPoint` 同时用于读/写测点，写测点无需数据类型，强制耦合会误拒合法写配置。

### 12.2 STRING 解析会裁剪首尾空白

```
原始字节 [20 41 42 20 00 00]（" AB " + NUL 填充） → 解析结果 "AB"
```

先按首个 `\0` 截断，再裁剪首尾空白。这符合工业网关惯例（去除设备填充），但**若设备字符串的前导空格具有业务语义，会静默丢失**。如需保留前导空白，可将裁剪改为仅尾随。

### 12.3 位型测点必须配 `BOOL`

位型功能码（01/02）的响应是**位打包**格式。若把单线圈响应当作数值类型解析：

- `UINT16` 在 `BADC` / `DCBA` 下会做寄存器内字节交换，得到 256 而非 1；
- `BOOL` 则"非零即真"，**与字节序无关**（实测 4 字节序 × 256 取值全部正确）。

**结论**：线圈 / 离散输入测点统一配 `data-type: BOOL`（映翰通 `BIT`）。

### 12.4 位型测点不参与合并

位型测点每个独占一个批次，**请求数随线圈测点数线性增长**。线圈点通常较少，影响可忽略；若现场有数百个线圈点，请评估一轮请求数是否超出采集周期预算。

### 12.5 `watchdog-queue-stagnant-ms` 效力有限

见 10.3。真正的 JNI 卡死兜底是 `leaked-thread-threshold` 与 `timeout-threshold`。

### 12.6 日志与落盘

默认只输出**控制台**（`logback-spring.xml` 仅 CONSOLE appender），且 `ModbusRtuMaster.read()` 与 `ModbusLoggingDataHandler` 均为 **INFO 级**。

> 这是刻意取舍：**内核极简**，落盘与轮转交给使用者的日志规范。生产环境建议：调整日志级别（`read()` 降 DEBUG 或按批汇总），并自行添加带 RollingPolicy 的文件 appender。

**日志落盘的两条路，二选一，切勿同时启用**（同时启用会把同一份日志写两遍）：

| 方案                                | 做法                                                         | 轮转由谁负责                                                 |
| ----------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **A. logback 自理（推荐用于生产）** | 在 `logback-spring.xml` 中增加 `RollingFileAppender`         | **logback 自身**——原生支持按时间/体积滚动、保留份数与压缩，**无需任何外部工具**。此时应把 `jdcs.service` 的 `StandardOutput`/`StandardError` 改为 `journal`（或删除该两行走默认），避免双写 |
| **B. 部署包默认（最简）**           | `jdcs.service` 用 `StandardOutput=append:` 把控制台输出重定向到 `/var/log/jdcs/*.log`，**不改代码、不改日志配置** | **使用者自行决定**——该文件**不会自动滚动**。需要轮转时建议改用方案 A；若坚持此方式，则自行接入 logrotate 等外部工具（注意 systemd 长期持有文件描述符，**必须用** `copytruncate`）。详见 15.2 节 |

> 📌 部署包**不预置**任何轮转配置：日志是使用者按自身项目规范自由发挥的领域，框架不预设方案。方案 B 的定位只是「开箱即用、能看到日志文件」的**最简默认**，而非生产唯一解。

### 12.7 批次读取失败时的测点状态

批次读取失败时，框架**不更新**该批次内测点的实体字段（`success` / `value` 保持上一轮的值），仅计入失败数并记录日志。

> 这也是刻意留白：**"失败要不要落库、要不要置失败态"属于业务契约**，不同场景处理方式不同。框架不替使用者规定，避免锁死扩展空间。使用时请依据 `success` 字段自行判断。

### 12.8 时区与序列化

`spring.jackson.time-zone: GMT+8`、`date-format: yyyy-MM-dd HH:mm:ss`、`default-property-inclusion: NON_EMPTY`（空值字段不序列化，`/health` 等接口返回更简洁）。



---

## 十三、健康检查与状态页

### 13.1 `GET /health`

返回当前运行状态（`ModbusHealthStatus`）：

```json
{
  "connected": true,
  "serialPort": "COM8",
  "baudRate": 9600,
  "parity": "NONE",
  "dataBits": 8,
  "stopBits": 1,
  "ioQueueSize": 0,
  "pointCount": 7,
  "lastCollectAgoMs": 215,
  "serverTime": 1759023456789
}
```

| 字段 | 说明 |
| --- | --- |
| `connected` | 串口是否已连接 |
| `ioQueueSize` | 执行器队列积压大小（积压持续增长 = 异常信号） |
| `pointCount` | 测点数量 |
| `lastCollectAgoMs` | 距上次采集开始的毫秒数（`-1` = 从未采集） |
| `serverTime` | 服务端当前时间 |

### 13.2 静态状态页

`src/main/resources/static/index.html` 是一个**纯静态、零依赖**的单文件状态页，浏览器访问 `http://<host>:<port>/` 即可看到实时状态（每 3 秒轮询 `/health`）。

- 无需任何前端构建工具、不引用任何 CDN；
- 未连接 / 采集停滞 / 接口异常均有醒目的视觉提示；
- 可自由替换为你自己的前端方案。

---

## 十四、编译与测试

### 14.1 构建

```bash
mvn clean package
java -jar target/jdcs-1.0.0.jar
```

### 14.2 测试套件

| 测试类 | 形态 | 内容 | 是否需要环境 |
| --- | --- | --- | :---: |
| `TestModbusPointParser` | `main()` 自测 | **159 项断言**：类型映射 / 字节序 / 奇数落单 / 缩放 / 宽容 API / 实体填充 / 异常容错 | 否 |
| `TestModbusBatchReadPlanner` | JUnit 5 | **14 项**：分组、合并、位型不合并、协议上限、地址间隙 | 否 |
| `TestModbusPointParserSimple` | JUnit 5 | 1 项：端到端填充示例 | 否 |
| `TestModbusRead` | `@SpringBootTest` | 真实串口读取（集成测试） | **是**（需 Spring 上下文 + 串口设备） |

离线环境可直接运行解析器自测套件：

```bash
# 编译主代码
javac -encoding UTF-8 -Xlint:all -cp <deps> -d out $(find src/main/java -name '*.java')

# 运行解析器自测（159 项断言，失败退出码 1，可直接挂 CI）
java -Dfile.encoding=UTF-8 -cp "out:<deps>" com.ty.jdcs.test.TestModbusPointParser
```

### 14.3 依赖

父工程：`spring-boot-starter-parent` **3.5.16**；编译目标 Java **17**。

| 依赖 | 作用域 | 用途 |
| --- | :---: | --- |
| `com.digitalpetri.modbus:modbus-serial`（2.1.6） | compile | Modbus RTU 协议栈（传递引入 `modbus`、`jSerialComm` 2.11.0、`slf4j-api`） |
| `spring-boot-starter-web` | compile | Web 端点（`/health`）与静态资源（状态页）；并传递引入 Jackson |
| `spring-boot-configuration-processor` | provided / optional | 生成配置元数据，供 IDE 提示 `@ConfigurationProperties` |
| `org.projectlombok:lombok` | provided | 简化样板代码（`@Data` / `@Slf4j` / `@Builder`） |
| `spring-boot-starter-test` | **test** | 仅用于 `TestModbusRead`（`@SpringBootTest`） |

> **jSerialComm 2.11.0** 与 **jackson-annotations** 由上述依赖传递引入，无需显式声明。
> 底层串口 I/O 使用 jSerialComm；注意其**读写默认无限阻塞**、且**写超时仅在 Windows 生效**（见第十章的设计前提）。



---

## 附录 A：一分钟上手路线

1. 改 `application-modbus.yml`：填你的 `serial-port` / `baud-rate` / `parity`（**务必核对第四章参数依赖**）；
2. 改 `application-modbus-points.yml`：填你的测点（`slave-id`、`function-code`、`address`、`data-type`、`byte-order`）；
3. `mvn clean package`；
4. 开发调试：`java -jar target/jdcs-1.0.0.jar`，浏览器打开 `http://localhost:8080/` 看采集状态；
5. 生产部署：见第十五章；
6. 接入你的落库逻辑：实现 `ModbusDataHandler`，注册为 Bean。

## 第十五章 生产部署（systemd 服务）

工业现场的最终兜底不在 JVM 里，而在**进程管理器**里。即使所有线程都被 JNI 卡死、看门狗线程也无法再调度，`Runtime.halt(-1)` 把进程强退后，仍需系统级守护把它拉起来。这一层由 **systemd** 完成。

### 15.1 文件一览

| 文件 | 作用 |
| :--- | :--- |
| `install.sh` | 一键部署脚本：创建用户、授权串口、复制 jar、注册服务、自启动 |
| `jdcs.service` | systemd 服务单元：进程身份、JVM 参数、日志重定向、自动重启策略 |

### 15.2 `jdcs.service` 关键设计

```ini
[Unit]
Description=JDCS - Java Data Collector Service
After=network.target
StartLimitIntervalSec=300      ; 5 分钟内
StartLimitBurst=5             ; 连续 5 次启动失败 → systemd 标记为 failed，停止无限重启

[Service]
Type=simple
User=jdcs                     ; 独立系统用户
Group=dialout                 ; 串口访问所需组（Linux 默认）
WorkingDirectory=/opt/jdcs

ExecStart=/usr/bin/java \
  -Xms256m -Xmx512m \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/jdcs/dumps \
  -XX:+ExitOnOutOfMemoryError \
  -Duser.timezone=Asia/Shanghai \
  -jar /opt/jdcs/jdcs.jar

Restart=always                ; 任何原因退出都重启
RestartSec=10                 ; 重启前等 10 秒，避免紧循环

StandardOutput=append:/var/log/jdcs/stdout.log
StandardError=append:/var/log/jdcs/stderr.log

[Install]
WantedBy=multi-user.target
```

**关键设计说明**：

| 项 | 设计意图 |
| :--- | :--- |
| **独立用户 `jdcs`** | 最小权限：不 root、不与其它服务共享身份；串口访问通过加入 `dialout` 组获得 |
| **`-Xmx512m`** | 采集框架内存占用极低（实测 <100MB），512MB 足够；OOM 时 `-XX:+ExitOnOutOfMemoryError` 让 JVM 退出交给 systemd 重启 |
| **`HeapDumpOnOutOfMemoryError`** | 堆转储保留在 `/opt/jdcs/dumps`，事后可分析 |
| **`StartLimitBurst=5 / StartLimitIntervalSec=300`** | **防重启风暴**：配置错误导致的反复崩溃（如串口不存在）不会无限打日志；5 分钟内连续 5 次失败 → 服务标记 failed，运维介入 |
| **`Restart=always`** | 无论正常退出、异常退出还是被信号杀掉，10 秒后拉起——这是 `halt(-1)` 的唯一外部响应者 |
| **日志 append 到文件** | 日志不丢失、不经过 syslog 转发，`/var/log/jdcs/` 直接可查；后续接 logrotate 即可滚动 |

### 15.3 一键部署流程

```bash
# 1. 构建
mvn clean package -DskipTests

# 2. 以 root 执行部署脚本
sudo bash deploy/install.sh

# 3. 验证
sudo systemctl status jdcs
sudo journalctl -u jdcs -f           # 实时看日志
curl http://localhost:8080/health    # 健康接口
```

脚本执行内容：创建专用用户并加入 `dialout` 组 → 创建目录并授权 → 复制 jar → 注册服务 → `daemon-reload` → `enable --now`。

### 15.4 运维常用命令

```bash
sudo systemctl start/stop/restart jdcs     # 启停
sudo systemctl status jdcs                 # 运行状态
sudo journalctl -u jdcs -n 200 -f          # 实时日志
sudo journalctl -u jdcs --since "1 hour ago"
tail -f /var/log/jdcs/stdout.log           # 业务日志（与 journalctl 同一来源）
ls /opt/jdcs/dumps/                        # OOM 堆转储目录
```

### 15.5 为什么需要 L6（操作系统级）兜底

把第十章的五层防御再延伸一层：

```
L1 请求级  →  read-retry-count（应用层重试）
L2 会话级  →  operation-timeout-ms 硬超时 + discardSession
L3 线程级  →  dedicated singleThreadExecutor 隔离
L4 监控级  →  ModbusWatchdog（调度停摆 + 队列停滞）
L5 进程级  →  ModbusFatalErrorHandler → Runtime.halt(-1)
L6 系统级  →  systemd Restart=always（本层）
```

前五层都在 JVM 内部。**若 L5 自身无法执行**（例如 OOM 前的最后阶段、JNI 把 JVM 整体挂死），`Restart=always` 就是唯一的恢复机制。这是工业网关的标准实践：**承认进程可能崩溃，把恢复交给系统。**

> ⚠️ **关于"队列停滞"检测的效力**：由于采集是"同步单任务提交"，调度线程提交后最多等 `operation-timeout-ms` 就放弃并更换会话，因此执行器队列**很难在 60 秒阈值内持续非空**，该检查通常不会触发。**真正兜住 JNI 卡死的是 `leaked-thread-threshold`（泄漏线程数）与 `timeout-threshold`（累计硬超时）**——实践上优先确保泄漏线程检测正常工作，队列停滞检测作为补充信号保留即可，不要因为"有队列停滞检测"而低估对泄漏线程计数的依赖。

## 附录 B：关键约定速查

| 事项 | 约定 |
| --- | --- |
| 寄存器单位 | **1 寄存器 = 2 字节**（位型除外，位型为位打包） |
| 字节数组长度 | 必须为偶数；采集层源头用 `padToEven` 保证 |
| 字节序默认 | `ABCD`；映翰通填 `Swapped`（= CDAB） |
| 数值类型 `registerCount` | 由 `dataType` 唯一确定，无需手配 |
| STRING `registerCount` | **必须**显式指定 |
| 未缩放 `value` 类型 | 保留原始类型（不丢精度） |
| 缩放后 `value` 类型 | Double |
| 失败时 | `success=false`，`rawValue` / `value` 均为 null |
| 线圈 / 离散 | 统一配 `BOOL` |
| 位型功能码 | 不参与合并读取 |
