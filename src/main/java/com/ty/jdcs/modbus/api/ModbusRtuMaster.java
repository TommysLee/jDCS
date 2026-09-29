package com.ty.jdcs.modbus.api;

import com.digitalpetri.modbus.FunctionCode;
import com.digitalpetri.modbus.client.ModbusRtuClient;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.pdu.ReadCoilsRequest;
import com.digitalpetri.modbus.pdu.ReadCoilsResponse;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsRequest;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsResponse;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
import com.digitalpetri.modbus.serial.client.SerialPortClientTransport;
import com.ty.jdcs.modbus.core.ModbusPoint;
import com.ty.jdcs.modbus.exception.ModbusReadException;
import com.ty.jdcs.modbus.parser.ModbusPointParser;
import com.ty.jdcs.modbus.support.ModbusFatalErrorHandler;
import com.ty.jdcs.spring.config.properties.ModbusSerialProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modbus RTU 主站封装
 * <p>提供连接管理、自动重连、超时、读取重试、异常分类、JNI 卡死检测等能力
 *
 * <p><b>鲁棒性设计</b>：
 * <ul>
 *   <li>单线程执行器 + 有界队列，拒绝堆积</li>
 *   <li>无锁状态管理（AtomicReference + AtomicBoolean CAS）</li>
 *   <li>硬超时后丢弃整个会话（客户端 + 执行器），不复用</li>
 *   <li>检测 modbus-io 线程泄漏数，超阈值触发致命退出</li>
 *   <li>累计硬超时数超阈值触发致命退出</li>
 *   <li>连接重试带冷却窗口，一轮采集最多触发一次重建</li>
 * </ul>
 *
 * <p><b>关于 JNI 卡死</b>：{@code Future.cancel(true)} 只能设置中断标志，
 * 无法穿透 JNI 调用。当底层驱动卡死时，主线程会超时返回，但 JNI 线程会永久泄漏。
 * 本类通过 {@link #countModbusThreads()} 直接观察 JVM 层线程数，作为最可靠的
 * 卡死检测手段。
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
@Slf4j
public class ModbusRtuMaster implements AutoCloseable {

    private final ModbusSerialProperties props;
    private final ModbusFatalErrorHandler fatal;

    private final String modbusThreadNamePrefix = "modbus-io-";

    /** 当前会话（客户端 + 执行器）。原子引用，无锁替换 */
    private final AtomicReference<Session> session = new AtomicReference<>();

    /** 重建互斥。CAS 保证同时只有一个线程重建 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** 连续连接失败计数。连接成功后清零 */
    private final AtomicInteger connectFailures = new AtomicInteger(0);

    /** 累计硬超时计数。成功读取不清零——因为每次超时都可能已泄漏 JNI 线程 */
    private final AtomicInteger totalHardTimeouts = new AtomicInteger(0);

    /** 会话代次，用于线程命名，便于泄漏排查 */
    private final AtomicInteger generation = new AtomicInteger(0);

    /** 上次重建时间（毫秒）。用于冷却窗口，防止一轮采集内密集重试 */
    private final AtomicLong lastRebuildTime = new AtomicLong(0);

    public ModbusRtuMaster(ModbusSerialProperties props, ModbusFatalErrorHandler fatal) {
        this.props = props;
        this.fatal = fatal;
    }

    @PostConstruct
    public void init() {
        // 启动期校验全部测点配置：拼写/取值错误在启动阶段暴露，而非运行期才出错。
        // 校验会就地归一化配置（数据类型规范名、字节序枚举名、寄存器数量对齐类型长度），
        // 因此必须在建立连接、开始采集之前完成。
        for (ModbusPoint point : props.getPoints()) {
            ModbusPointParser.validate(point);
        }

        tryRebuild("应用启动");
        if (!isConnected()) {
            log.warn("Modbus RTU 启动时未连接，将由调度周期重试");
        }
    }

    /**
     * 读取线圈状态（FC01）。
     *
     * @param slaveId 从站地址 1-247
     * @param address 起始寄存器地址
     * @param count   寄存器数量 1-125
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败
     */
    public byte[] readCoils(int slaveId, int address, int count) throws ModbusReadException {
        return read(slaveId, address, count, FunctionCode.READ_COILS.getCode());
    }

    /**
     * 读取离散输入（FC02）。
     *
     * @param slaveId 从站地址 1-247
     * @param address 起始寄存器地址
     * @param count   寄存器数量 1-125
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败
     */
    public byte[] readDiscreteInputs(int slaveId, int address, int count) throws ModbusReadException {
        return read(slaveId, address, count, FunctionCode.READ_DISCRETE_INPUTS.getCode());
    }

    /**
     * 读取保持寄存器（FC03）。
     *
     * @param slaveId 从站地址 1-247
     * @param address 起始寄存器地址
     * @param count   寄存器数量 1-125
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败
     */
    public byte[] readHoldingRegisters(int slaveId, int address, int count) throws ModbusReadException {
        return read(slaveId, address, count, FunctionCode.READ_HOLDING_REGISTERS.getCode());
    }

    /**
     * 读取输入寄存器（FC04）。
     *
     * @param slaveId 从站地址 1-247
     * @param address 起始寄存器地址
     * @param count   寄存器数量 1-125
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败
     */
    public byte[] readInputRegisters(int slaveId, int address, int count) throws ModbusReadException {
        return read(slaveId, address, count, FunctionCode.READ_INPUT_REGISTERS.getCode());
    }

    /**
     * 读取寄存器数据
     *
     * @param point 测点
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败时抛出
     */
    public byte[] read(ModbusPoint point) throws ModbusReadException {
        ModbusPointParser.validate(point);
        return read(point.getSlaveId(), point.getAddress(), point.getRegisterCount(), point.getFunctionCode());
    }

    /**
     * 读取寄存器数据
     *
     * <p>内部自动处理：
     * <ul>
     *   <li>未连接时触发一次重建</li>
     *   <li>传输异常自动重试（最多 {@code read-retry-count} 次）</li>
     *   <li>协议异常立即抛出（不重试）</li>
     *   <li>硬超时丢弃会话并抛出（不重试）</li>
     * </ul>
     *
     * @param slaveId       从站地址 1-247
     * @param address       起始寄存器地址
     * @param count         寄存器数量 1-125
     * @param functionCode  功能码
     * @return 原始字节数组（长度 = count × 2）
     * @throws ModbusReadException 读取失败时抛出
     */
    public byte[] read(int slaveId, int address, int count, int functionCode) throws ModbusReadException {
        log.info("slaveId={} address={} count={} functionCode={}", slaveId, address, count, functionCode);

        ModbusReadException lastError = null;
        int maxAttempts = props.getReadRetryCount() + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return executeRead(slaveId, address, count, functionCode);
            } catch (ModbusReadException e) {
                lastError = e;
                if (!e.isRetryable() || attempt >= maxAttempts - 1) {
                    throw e;
                }
                log.warn("[slaveId={}] 传输异常（{}/{} 次尝试），{}ms 后重试: {}",
                        slaveId, attempt + 1, maxAttempts,
                        props.getReadRetryIntervalMs(), e.toString());
                sleepQuietly(props.getReadRetryIntervalMs());
            }
        }
        throw lastError;
    }

    // ============================================================
    // 读取执行与异常处理
    // ============================================================

    private byte[] executeRead(int slaveId, int address, int count, int functionCode) throws ModbusReadException {
        Session s = ensureSession();
        Future<byte[]> future = submitRead(s, slaveId, address, count, functionCode);

        try {
            return future.get(props.getOperationTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            discardSession(s);
            recordHardTimeout(slaveId);
            throw new ModbusReadException("Modbus 读取超时", e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ModbusReadException("Modbus 读取被中断", e, false);
        } catch (ExecutionException e) {
            throw handleExecutionException(e, slaveId);
        }
    }

    private Future<byte[]> submitRead(Session s, int slaveId, int address, int count, int functionCode) throws ModbusReadException {
        try {
            return s.executor().submit(() -> doRead(s.client(), slaveId, address, count, functionCode));
        } catch (RejectedExecutionException e) {
            throw new ModbusReadException("Modbus 系统繁忙，读取请求被拒绝", e, true);
        }
    }

    private byte[] doRead(ModbusRtuClient client, int slaveId, int address, int count, int functionCode) throws Exception {
        byte[] bytes = switch (functionCode) {
            case 1 -> { // 线圈状态（RW）
                ReadCoilsRequest req = new ReadCoilsRequest(address, count);
                ReadCoilsResponse resp = client
                        .readCoilsAsync(slaveId, req)
                        .toCompletableFuture()
                        .get(props.getResponseTimeoutMs(), TimeUnit.MILLISECONDS);
                yield resp.coils();
            }
            case 2 -> { // 离散输入（RO）
                ReadDiscreteInputsRequest req = new ReadDiscreteInputsRequest(address, count);
                ReadDiscreteInputsResponse resp = client
                        .readDiscreteInputsAsync(slaveId, req)
                        .toCompletableFuture()
                        .get(props.getResponseTimeoutMs(), TimeUnit.MILLISECONDS);
                yield resp.inputs();
            }
            case 3 -> { // 保持寄存器（RW）
                ReadHoldingRegistersRequest req = new ReadHoldingRegistersRequest(address, count);
                ReadHoldingRegistersResponse resp = client
                        .readHoldingRegistersAsync(slaveId, req)
                        .toCompletableFuture()
                        .get(props.getResponseTimeoutMs(), TimeUnit.MILLISECONDS);
                yield resp.registers();
            }
            case 4 -> { // 输入寄存器（RO）
                ReadInputRegistersRequest req = new ReadInputRegistersRequest(address, count);
                ReadInputRegistersResponse resp = client
                        .readInputRegistersAsync(slaveId, req)
                        .toCompletableFuture()
                        .get(props.getResponseTimeoutMs(), TimeUnit.MILLISECONDS);
                yield resp.registers();
            }
            default -> throw new ModbusReadException(
                    "不支持的功能码: " + functionCode, null, false);
        };
        return ModbusPointParser.padToEven(bytes);
    }

    private ModbusReadException handleExecutionException(ExecutionException e, int slaveId) {
        Throwable cause = e.getCause();

        // 协议异常：设备级问题，不重试，不影响连接
        if (cause instanceof ModbusResponseException respEx) {
            int code = respEx.getExceptionCode();
            log.warn("Modbus 协议异常 [slaveId={}] 异常码: 0x{}", slaveId, Integer.toHexString(code));
            return new ModbusReadException(
                    "协议异常: 0x" + Integer.toHexString(code), respEx, false);
        }

        // 传输异常：可重试
        log.warn("Modbus 传输异常 [slaveId={}]: {}", slaveId, cause.getMessage());
        return new ModbusReadException("传输异常: " + cause.getMessage(), cause, true);
    }

    // ============================================================
    // 会话管理
    // ============================================================

    private Session ensureSession() throws ModbusReadException {
        Session s = session.get();
        if (isAvailable(s)) {
            return s;
        }

        tryRebuild("Modbus RTU 检测到未连接");
        s = session.get();
        if (!isAvailable(s)) {
            throw new ModbusReadException("Modbus 未连接", null, false);
        }
        return s;
    }

    private boolean isAvailable(Session s) {
        return s != null && s.client().isConnected() && !s.executor().isShutdown();
    }

    /**
     * 带重试的连接。CAS 互斥 + 冷却窗口，保证一轮采集内最多触发一次（启动时调用 / 断线后调用）
     *
     * <p><b>为什么需要冷却窗口</b>：未连接状态下，一轮采集会调用本方法 N 次
     * （每台设备各一次）。若无冷却，一轮就会烧掉全部重连机会并触发致命退出。
     * 冷却窗口把重试频率约束到 {@code reconnectIntervalMs}，让调度周期
     * 成为真正的重试驱动者。
     */
    private void tryRebuild(String reason) {
        // 已有可用则直接返回，不消耗重建权
        if (isAvailable(session.get())) {
            return;
        }

        // CAS 抢占重建权
        if (!rebuilding.compareAndSet(false, true)) {
            log.info("Modbus 已有线程在重建中，跳过");
            return;
        }
        try {
            // 双重检查：其他线程可能刚刚重建成功
            if (isAvailable(session.get())) {
                return;
            }

            // 冷却窗口：距上次重建未超过 reconnectIntervalMs 时跳过
            long now = System.currentTimeMillis();
            long last = lastRebuildTime.get();
            if (last != 0 && now - last < props.getReconnectIntervalMs()) {
                log.debug("重建冷却中（距上次 {}ms，未达到 {}ms），跳过", now - last, props.getReconnectIntervalMs());
                return;
            }
            lastRebuildTime.set(now);

            int attempt = connectFailures.get() + 1;
            int maxAttempts = props.getMaxReconnectAttempts();
            log.warn("Modbus 连接重建（第 {}/{} 次），原因: {}", attempt, maxAttempts, reason);

            ExecutorService newExec = createExecutor();
            try {
                ModbusRtuClient newClient = openClient();
                Session newSession = new Session(newClient, newExec);
                Session old = session.getAndSet(newSession);
                closeQuietly(old);
                connectFailures.set(0);
            } catch (Exception e) {
                newExec.shutdownNow();
                recordConnectFailure(e);
            }
        } finally {
            rebuilding.set(false);
        }
    }

    private void recordConnectFailure(Exception e) {
        int failures = connectFailures.incrementAndGet();
        int max = props.getMaxReconnectAttempts();
        log.error("Modbus 连接失败（{}/{}）: {}", failures, max, e.toString());
        if (failures >= max) {
            fatal.fatal("Modbus 连续连接失败达到阈值 " + max + "，串口可能已不可恢复", e);
        }
    }

    // ============================================================
    // 硬超时与 JNI 泄漏检测
    // ============================================================

    /**
     * 记录一次硬超时，并检测两个阈值：
     * <ul>
     *   <li>累计硬超时数 ≥ timeoutThreshold → fatal</li>
     *   <li>modbus-io 泄漏线程数 ≥ leakedThreadThreshold → fatal</li>
     * </ul>
     */
    private void recordHardTimeout(int slaveId) {
        int total = totalHardTimeouts.incrementAndGet();
        int leaked = countModbusThreads();
        int timeoutThreshold = props.getTimeoutThreshold();
        int leakedThreshold = props.getLeakedThreadThreshold();

        log.error("Modbus 读取硬超时 [slaveId={}]，累计={}/{}，泄漏线程={}/{}",
                slaveId, total, timeoutThreshold, leaked, leakedThreshold);

        if (total >= timeoutThreshold) {
            fatal.fatal("Modbus 累计硬超时达到阈值 " + timeoutThreshold
                    + "，JNI 层可能已不可恢复", null);
        }
        if (leaked >= leakedThreshold) {
            fatal.fatal(modbusThreadNamePrefix + " 泄漏线程数达到阈值 " + leakedThreshold
                    + "，JNI 层可能已不可恢复", null);
        }
    }

    /**
     * 统计当前所有 modbus-io 前缀的存活线程数。
     *
     * <p>这是检测 JNI 线程泄漏最可靠的手段——不依赖任何库的内部行为，
     * 直接观察 JVM 层的客观事实。
     */
    private int countModbusThreads() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.getAllThreadIds();
        ThreadInfo[] infos = mx.getThreadInfo(ids);
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadName().startsWith(modbusThreadNamePrefix)) {
                count++;
            }
        }
        log.info("{} 前缀的存活线程数：{}", modbusThreadNamePrefix, count);
        return count;
    }

    // ============================================================
    // 会话废弃与资源释放
    // ============================================================

    private void discardSession(Session expected) {
        if (session.compareAndSet(expected, null)) {
            closeQuietly(expected);
        }
    }

    private void closeQuietly(Session s) {
        if (s == null) {
            return;
        }

        try {
            if (s.client().isConnected()) {
                s.client().disconnect();
            }
        } catch (Exception e) {
            log.warn("Modbus 关闭连接异常: {}", e.getMessage());
        }
        try {
            s.executor().shutdownNow();
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // 底层创建
    // ============================================================

    private ExecutorService createExecutor() {
        int gen = generation.incrementAndGet();
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(props.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, modbusThreadNamePrefix + gen);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private ModbusRtuClient openClient() throws Exception {
        SerialPortClientTransport transport = SerialPortClientTransport.create(cfg -> {
            cfg.setSerialPort(props.getSerialPort())
                    .setBaudRate(props.getBaudRate())
                    .setDataBits(props.getDataBits())
                    .setStopBits(props.getStopBits())
                    .setParity(props.parityCode());
        });
        ModbusRtuClient client = ModbusRtuClient.create(transport, cfg -> {
            cfg.setRequestTimeout(Duration.ofMillis(props.getResponseTimeoutMs()));
        });
        client.connect();
        log.info("Modbus RTU 已连接: {}@{} {} {} {}", props.getSerialPort(), props.getBaudRate(), props.getParity(), props.getDataBits(), props.getStopBits());
        return client;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // 状态查询与生命周期
    // ============================================================

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return isAvailable(session.get());
    }

    /**
     * 获取 modbus-io 执行器队列的当前积压大小
     *
     * <p>供看门狗判断 JNI 层是否卡死使用。返回 0 表示无积压或会话不可用。
     */
    public int getIoQueueSize() {
        Session s = session.get();
        if (s == null) {
            return 0;
        }
        ExecutorService exec = s.executor();
        if (exec instanceof ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return 0;
    }

    @Override
    public void close() {
        Session s = session.getAndSet(null);
        if (null != s) {
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                Future<?> f = closer.submit(() -> closeQuietly(s));
                f.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Modbus 关闭会话超时或异常，放弃等待: {}", e.toString());
            } finally {
                closer.shutdown();
            }
        }
        log.info("ModbusRtuMaster 已关闭");
    }

    /** 会话 = 客户端 + 专用执行器。不可变 record。 */
    private record Session(ModbusRtuClient client, ExecutorService executor) {
    }
}
