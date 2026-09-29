package com.ty.jdcs.modbus.support;

import lombok.Builder;
import lombok.Data;

/**
 * Modbus 运行健康状态 DTO
 */
@Data
@Builder
public class ModbusHealthStatus {

    /** 串口是否已连接 */
    private boolean connected;

    /** 串口名称 */
    private String serialPort;

    /** 波特率 */
    private int baudRate;

    /** 校验位 */
    private String parity;

    /** 数据位 */
    private int dataBits;

    /** 停止位 */
    private int stopBits;

    /** I/O 执行器队列积压大小 */
    private int ioQueueSize;

    /** 测点总数 */
    private int pointCount;

    /** 距上次采集的毫秒数（从未成功则为 -1） */
    private long lastCollectAgoMs;

    /** 服务当前时间戳（毫秒） */
    private long serverTime;
}
