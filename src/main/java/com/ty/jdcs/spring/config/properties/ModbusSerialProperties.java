package com.ty.jdcs.spring.config.properties;

import com.fazecast.jSerialComm.SerialPort;
import com.ty.jdcs.modbus.core.ModbusPoint;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Modubs RTU 串口采集属性配置类
 *
 * @Author Tommy
 * @Date 2026/9/25
 */
@ConfigurationProperties(prefix = "modbus")
@Data
public class ModbusSerialProperties {

    /** 串口名称，如 COM7 或 /dev/ttyUSB0 */
    private String serialPort = "COM7";

    /** 波特率，默认 9600 */
    private int baudRate = 9600;

    /** 校验位，可选 NONE / EVEN / ODD / MARK / SPACE，默认 NONE */
    private String parity = "NONE";

    /** 数据位，默认 8 */
    private int dataBits = 8;

    /** 停止位，默认 1 */
    private int stopBits = 1;

    /** 单次请求响应超时（毫秒） */
    private int responseTimeoutMs = 2000;

    /** 应用层硬超时（毫秒），必须大于 responseTimeoutMs */
    private int operationTimeoutMs = 5000;

    /** 读取异常重试次数 */
    private int readRetryCount = 2;

    /** 读取重试间隔（毫秒） */
    private long readRetryIntervalMs = 200;

    /** 连接重建冷却窗口（毫秒） */
    private long reconnectIntervalMs = 10000;

    /** 连续重连失败 N 次后强制退出 */
    private int maxReconnectAttempts = 5;

    /** 单线程执行器队列容量 */
    private int queueCapacity = 10;

    /** JNI卡死检测：累计硬超时 N 次后强制退出 */
    private int timeoutThreshold = 3;

    /** JNI卡死检测：modbus-io 泄漏线程 N 个后强制退出 */
    private int leakedThreadThreshold = 5;

    /** 采集周期（毫秒）。仅当使用 fixedDelay 调度时生效 */
    private int collectionIntervalMs = 30000;

    /** 看门狗检查周期（毫秒） */
    private int watchdogIntervalMs = 60000;

    /**
     * 看门狗：调度停摆判定阈值（毫秒）。
     *
     * <p>距上次采集开始超过此阈值仍未启动新一轮采集，判定为调度停摆。
     * 与采集周期解耦，可兼容 fixedDelay 和 cron 两种调度方式。
     *
     * <p>建议值：采集周期 × 3 ~ 5。cron 场景下取相邻两次触发间隔 × 1.5。
     * <p>0 = 禁用此检查。
     */
    private int watchdogIdleThresholdMs = 300000;

    /**
     * 看门狗：modbus-io 队列停滞判定阈值（毫秒）。
     *
     * <p>队列持续有元素且长时间不下降超过此阈值，判定为 JNI 卡死。
     * 这是比读超时更早的卡死信号。
     *
     * <p>建议值：10000 ~ 60000。过小会误判正常的瞬时积压。
     * <p>0 = 禁用此检查。
     */
    private int watchdogQueueStagnantMs = 60000;

    /**
     * 合并读取时允许的最大地址间隙（寄存器数）。
     * <p>0 = 仅合并地址紧邻的设备；建议 5~10，兼顾吞吐与无效读取。
     */
    private int mergeMaxGap = 5;

    /** 测点配置列表 */
    private List<ModbusPoint> points = new ArrayList<>();

    /**
     * 将校验位字符串转换为 jSerialComm 常量
     */
    public int parityCode() {
        return switch (parity.toUpperCase()) {
            case "EVEN" -> SerialPort.EVEN_PARITY;
            case "ODD" -> SerialPort.ODD_PARITY;
            case "MARK" -> SerialPort.MARK_PARITY;
            case "SPACE" -> SerialPort.SPACE_PARITY;
            default -> SerialPort.NO_PARITY;
        };
    }

    /**
     * 将停止位转换为 jSerialComm 常量
     */
    public int stopBitsCode() {
        return stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
    }
}
