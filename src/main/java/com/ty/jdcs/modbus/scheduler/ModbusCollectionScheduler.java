package com.ty.jdcs.modbus.scheduler;

import com.ty.jdcs.modbus.api.ModbusRtuMaster;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner.GroupKey;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner.PointSlice;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner.ReadBatch;
import com.ty.jdcs.modbus.core.ModbusPoint;
import com.ty.jdcs.modbus.monitor.ModbusWatchdog;
import com.ty.jdcs.modbus.parser.ModbusPointParser;
import com.ty.jdcs.modbus.service.ModbusDataHandler;
import com.ty.jdcs.modbus.support.ModbusHealthChecker;
import com.ty.jdcs.spring.config.properties.ModbusSerialProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;

/**
 * Modbus 采集调度器
 *
 * <p><b>合并读取</b>：按{@code (slaveId, functionCode)} 分组，同组内地址相邻的测点合并为一次
 * Modbus 请求，读回后按各自偏移切片分发。
 * 位型功能码（01 线圈 / 02 离散输入）除外</b>——其响应是位打包格式，与"寄存器 × 2 字节"
 * 不可换算，故每个测点独立成批。详见 {@link ModbusBatchReadPlanner}。
 *
 * <p><b>异常隔离</b>：批次读取失败、单点切片/解析失败、DataHandler 处理失败均就地隔离，
 * 既不中断整轮采集，也不牵连其余批次与测点。
 *
 * <p><b>采集周期</b>：调度用 {@code fixedDelay}（上一轮结束后再等待固定间隔）。
 * 设备离线时，一轮总耗时 ≈ 批次数 × 重试次数 × (响应超时 + 重试间隔)，可能远超
 * {@code collection-interval-ms}。这是刻意的设计取舍：宁可慢，也不让请求堆积。
 * 现场设备多且离线频繁时，请按实际批次数估算最坏周期，并相应放宽
 * {@code watchdog-idle-threshold-ms}（建议 ≥ 最坏周期 × 3）。
 *
 * <p>所有鲁棒性逻辑（重连、重试、超时、异常分类、JNI 泄漏检测）均封装在
 * {@link ModbusRtuMaster} 内部。本类负责调度与分发，并在每轮开始时通知
 * {@link ModbusWatchdog}、{@link ModbusHealthChecker}。
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
@Slf4j
public class ModbusCollectionScheduler {

    private final ModbusSerialProperties props;
    private final ModbusRtuMaster master;
    private final ModbusDataHandler dataHandler;
    private final ModbusWatchdog watchdog;
    private final ModbusHealthChecker healthChecker;

    private volatile String lastSummary = "";

    public ModbusCollectionScheduler(ModbusSerialProperties props, ModbusRtuMaster master, ModbusDataHandler dataHandler, ModbusWatchdog watchdog, ModbusHealthChecker healthChecker) {
        this.props = props;
        this.master = master;
        this.dataHandler = dataHandler;
        this.watchdog = watchdog;
        this.healthChecker = healthChecker;
    }

    @Scheduled(fixedDelayString = "${modbus.collection-interval-ms:30000}", initialDelay = 1000)
    public void collectAll() {
        notification();

        Map<GroupKey, List<ReadBatch>> grouped =
                ModbusBatchReadPlanner.planGrouped(props.getPoints(), props.getMergeMaxGap());

        int success = 0;
        int fail = 0;

        for (Map.Entry<GroupKey, List<ReadBatch>> entry : grouped.entrySet()) {
            GroupKey key = entry.getKey();

            for (ReadBatch batch : entry.getValue()) {
                try {
                    byte[] batchBytes = master.read(
                            key.slaveId(), batch.startAddress(),
                            batch.totalCount(), key.functionCode());

                    for (PointSlice slice : batch.slices()) {
                        try {
                            byte[] bytes = extractSlice(batchBytes, slice);
                            dispatch(slice.point(), bytes);
                            success++;
                        } catch (Exception sliceEx) {
                            // 单点切片/解析失败：就地隔离，不中断本批次其余测点
                            fail++;
                            log.warn("[{}] 切片解析失败 [slaveId={} fc={}] addr={} offset={}: {}",
                                    slice.point().getPointName(), key.slaveId(), key.functionCode(),
                                    slice.point().getAddress(), slice.offsetInBatch(), sliceEx.getMessage());
                        }
                    }
                } catch (Exception e) {
                    fail += batch.slices().size();
                    log.warn("批次读取失败 [slaveId={} fc={}] addr={} count={} points={}: {}",
                            key.slaveId(), key.functionCode(),
                            batch.startAddress(), batch.totalCount(),
                            batch.slices().size(), e.getMessage());
                }
            }
        }

        String summary = String.format("成功=%d, 失败=%d", success, fail);
        if (!summary.equals(lastSummary)) {
            log.info("采集状态变化: {}", summary);
            lastSummary = summary;
        } else {
            log.debug("采集完成: {}", summary);
        }
    }

    /**
     * 从批次字节中切出属于该设备的那一段。
     */
    private byte[] extractSlice(byte[] batchBytes, PointSlice slice) {
        return ModbusPointParser.slice(batchBytes, slice.offsetInBatch(), slice.point().registerCount());
    }

    /**
     * 通知协同组件
     */
    private void notification() {
        if (null != watchdog) {
            watchdog.markCollectStart();
        }
        if (null != healthChecker) {
            healthChecker.markCollectStart();
        }
    }

    /**
     * 分发给 DataHandler。异常被隔离，不影响采集链路。
     */
    private void dispatch(ModbusPoint p, byte[] bytes) {
        try {
            boolean flag = ModbusPointParser.tryFill(p, bytes);
            if (!flag) {
                log.warn("try fill error: slaveId={} pointId={} pointName={} reason: {}",
                        p.getSlaveId(), p.getPointId(), p.getPointName(), p.getErrorMsg());
            }
            dataHandler.handle(p);
        } catch (Throwable handlerEx) {
            log.error("[{}] ModbusDataHandler 处理异常（不影响采集链路）: {}",
                    p.getPointName(), handlerEx.getMessage(), handlerEx);
        }
    }
}
