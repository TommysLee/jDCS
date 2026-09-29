package com.ty.jdcs.modbus.exception;

import java.io.Serial;

/**
 * Modbus 读取异常类
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public class ModbusReadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -9086455942310125623L;

    private final boolean retryable;

    public ModbusReadException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * 是否值得重试
     * <ul>
     *     <li>{@code true}：传输异常（CRC 错误、响应格式错误等），由内部自动重试</li>
     *     <li>{@code false}：协议异常（从站返回错误码）、硬超时、中断，不重试</li>
     * </ul>
     *
     * @return true-传输异常需重试，false-协议异常无需重试
     */
    public boolean isRetryable() {
        return retryable;
    }
}
