package com.ty.jdcs.modbus.service;

import com.ty.jdcs.modbus.core.ModbusPoint;

/**
 * Modbus 数据出口接口
 *
 * <p>框架保证：{@link #handle} 抛出的任何异常都会被捕获并记录，
 * <b>不会影响 Modbus 采集链路</b>。
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
public interface ModbusDataHandler {

    /**
     * 处理一次成功采集的数据
     *
     * @param point 已填充采集数据的测点
     */
    void handle(ModbusPoint point);
}
