package com.ty.jdcs.web;

import com.ty.jdcs.modbus.support.ModbusHealthChecker;
import com.ty.jdcs.modbus.support.ModbusHealthStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Modbus 健康检查端点
 *
 * @Author Tommy
 * @Date 2026/9/29
 */
@RestController
public class ModbusHealthController {

    @Autowired
    private ModbusHealthChecker healthChecker;

    @GetMapping("/health")
    public ModbusHealthStatus health() {
        return healthChecker.getStatus();
    }
}
