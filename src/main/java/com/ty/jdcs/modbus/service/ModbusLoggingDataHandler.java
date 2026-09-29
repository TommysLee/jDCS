package com.ty.jdcs.modbus.service;

import com.ty.jdcs.modbus.core.ModbusPoint;
import lombok.extern.slf4j.Slf4j;

/**
 * Modbus 数据出口默认实现：仅记录日志
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
@Slf4j
public class ModbusLoggingDataHandler implements ModbusDataHandler {

    @Override
    public void handle(ModbusPoint point) {
        log.info("{} 采集状态：{} \t数据：{}{} \t详情：{}",
                point.getPointName(),
                point.getSuccess()? "成功" : "失败",
                point.getValue(),
                (null != point.getUnit()? point.getUnit() : ""),
                point);
    }
}
