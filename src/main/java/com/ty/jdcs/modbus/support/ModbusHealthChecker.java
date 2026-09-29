package com.ty.jdcs.modbus.support;

import com.ty.jdcs.modbus.api.ModbusRtuMaster;
import com.ty.jdcs.spring.config.properties.ModbusSerialProperties;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Modbus 编程式健康检查 API
 *
 * <p>用于检测 Modbus 通信层的运行时健康状态
 * <ul>
 *   <li>串口是否仍可用；</li>
 *   <li>队列积压大小、串口参数、测点数量等运行时状态。</li>
 * </ul>
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
public class ModbusHealthChecker {

    private final ModbusRtuMaster master;
    private final ModbusSerialProperties props;

    /** 上次采集开始时间（毫秒）。-1 表示从未完成过。 */
    private final AtomicLong lastCollectStartTime = new AtomicLong(-1L);

    public ModbusHealthChecker(ModbusRtuMaster master, ModbusSerialProperties props) {
        this.master = master;
        this.props = props;
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

    /**
     * 返回 Modbus 连接健康状态
     *
     * @return boolean
     */
    public boolean isHealthy() {
        return master.isConnected();
    }

    /**
     * 返回完整健康状态，供 /health 端点序列化使用
     *
     * @return ModbusHealthStatus
     */
    public ModbusHealthStatus getStatus() {
        long now = System.currentTimeMillis();
        long last = lastCollectStartTime.get();
        return ModbusHealthStatus.builder()
                .connected(isHealthy())
                .serialPort(props.getSerialPort())
                .baudRate(props.getBaudRate())
                .parity(props.getParity())
                .dataBits(props.getDataBits())
                .stopBits(props.getStopBits())
                .ioQueueSize(master.getIoQueueSize())
                .pointCount(props.getPoints() == null ? 0 : props.getPoints().size())
                .lastCollectAgoMs(last <= 0 ? -1 : Math.max(0L, now - last))
                .serverTime(now)
                .build();
    }
}
