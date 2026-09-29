package com.ty.jdcs.modbus.monitor;

import com.ty.jdcs.modbus.api.ModbusRtuMaster;
import com.ty.jdcs.modbus.support.ModbusFatalErrorHandler;
import com.ty.jdcs.spring.config.properties.ModbusSerialProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modbus 数采看门狗
 *
 * <p>覆盖 systemd 覆盖不到的两类"进程活着但服务假死"故障：
 *
 * <ul>
 *   <li><b>调度停摆</b>：{@code @Scheduled} 因线程耗尽、调度器损坏等原因不再触发采集。
 *       本看门狗通过"距上次采集开始的时间"判定。</li>
 *   <li><b>JNI 卡死</b>：{@code modbus-io} 线程消费不动请求，队列持续积压。
 *       这是比读超时更早的卡死信号——读超时要等 5 秒才判定，
 *       队列停滞在请求积压的瞬间就能观察到。</li>
 * </ul>
 *
 * <p><b>为什么阈值与采集周期解耦</b>：调度方式可能是 fixedDelay 或 cron，
 * 周期不固定。用独立配置的绝对时间阈值可以同时兼容两种方式。
 *
 * <p><b>判定逻辑</b>：
 * <ul>
 *   <li>调度活性：距上次采集开始超过 {@code watchdogIdleThresholdMs} → fatal</li>
 *   <li>队列停滞：队列有元素且连续不下降超过 {@code watchdogQueueStagnantMs} → fatal</li>
 * </ul>
 *
 * <p>任一条件触发时调用 {@code FatalErrorHandler.fatal}，由操作系统服务重启进程。
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
@Slf4j
public class ModbusWatchdog {

    private final ModbusSerialProperties props;
    private final ModbusRtuMaster master;
    private final ModbusFatalErrorHandler fatal;

    /** 上次采集开始时间。0 = 尚未开始过（启动期宽限）。 */
    private final AtomicLong lastCollectStartTime = new AtomicLong(0);

    /** 上次观测到的队列大小。用于判断队列是否在下降。 */
    private final AtomicInteger lastQueueSize = new AtomicInteger(-1);

    /** 队列停滞起始时间。0 = 队列正常（空或正在下降）。 */
    private final AtomicLong queueStagnantSince = new AtomicLong(0);

    public ModbusWatchdog(ModbusSerialProperties props, ModbusRtuMaster master, ModbusFatalErrorHandler fatal) {
        this.props = props;
        this.master = master;
        this.fatal = fatal;
    }

    /**
     * 记录本轮采集开始时间
     *
     * <p>由 {@code ModbusCollectionScheduler} 在每轮采集开始时调用。
     *
     * <p>看门狗据此感知"调度器仍在工作"。
     */
    public void markCollectStart() {
        lastCollectStartTime.set(System.currentTimeMillis());
    }

    // ============================================================
    // 周期性检查
    // ============================================================

    @Scheduled(fixedDelayString = "${modbus.watchdog-interval-ms:60000}", initialDelay = 5000)
    public void check() {
        try {
            checkScheduleAlive();
            checkQueueProgress();
        } catch (Exception e) {
            log.error("看门狗执行异常", e);
        }
    }

    /**
     * 检查调度是否停摆
     */
    private void checkScheduleAlive() {
        long threshold = props.getWatchdogIdleThresholdMs();
        if (threshold <= 0) { // 禁用
            return;
        }

        long last = lastCollectStartTime.get();
        if (last == 0) { // 启动宽限期，采集还没跑过
            return;
        }

        long idle = System.currentTimeMillis() - last;
        if (idle > threshold) {
            fatal.fatal(String.format(
                    "采集调度疑似停摆：距上次采集开始 %.1f 秒，超过阈值 %.1f 秒",
                    idle / 1000.0, threshold / 1000.0), null);
        }
    }

    /**
     * 检查 modbus-io 队列是否停滞
     *
     * <p>判定条件：队列有元素，且与上次观测相比未下降，持续超过阈值。
     * 队列清空或大小下降都视为"消费正常"，重置停滞计时。
     */
    private void checkQueueProgress() {
        long threshold = props.getWatchdogQueueStagnantMs();
        if (threshold <= 0) { // 禁用
            return;
        }

        int current = master.getIoQueueSize();
        int previous = lastQueueSize.getAndSet(current);

        // 队列空 或 相比上次下降 → 消费正常，重置
        boolean draining = (current == 0) || (previous >= 0 && current < previous);
        if (draining) {
            if (queueStagnantSince.getAndSet(0) != 0) {
                log.info("modbus-io 队列已恢复，size={}", current);
            }
            return;
        }

        // 队列有积压且未下降
        long since = queueStagnantSince.get();
        if (since == 0) {
            queueStagnantSince.set(System.currentTimeMillis());
            log.warn("modbus-io 队列开始停滞：size={}", current);
            return;
        }

        long duration = System.currentTimeMillis() - since;
        if (duration > threshold) {
            fatal.fatal(String.format(
                    "modbus-io 队列持续停滞：size=%d，已 %.1f 秒未下降，超过阈值 %.1f 秒，疑似 JNI 卡死",
                    current, duration / 1000.0, threshold / 1000.0), null);
        }
    }
}
