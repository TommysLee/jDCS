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
    private int dataBits = 8;

    /** 停止位 */
    private int stopBits;

    /** I/O 执行器队列积压大小 */
    private int ioQueueSize;

    /** 测点总数 */
    private int pointCount;

    /** 最近一次采集时间戳（毫秒，0 = 尚未开始过） */
    private long lastCollectTime;

    /** 服务当前时间戳（毫秒） */
    private long serverTime;
}
