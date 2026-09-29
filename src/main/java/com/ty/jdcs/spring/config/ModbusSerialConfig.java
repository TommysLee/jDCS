package com.ty.jdcs.spring.config;

import com.ty.jdcs.modbus.api.ModbusRtuMaster;
import com.ty.jdcs.modbus.monitor.ModbusWatchdog;
import com.ty.jdcs.modbus.scheduler.ModbusCollectionScheduler;
import com.ty.jdcs.modbus.service.ModbusDataHandler;
import com.ty.jdcs.modbus.service.ModbusLoggingDataHandler;
import com.ty.jdcs.modbus.support.ModbusFatalErrorHandler;
import com.ty.jdcs.modbus.support.ModbusHealthChecker;
import com.ty.jdcs.spring.config.properties.ModbusSerialProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Modubs RTU 串口采集配置类
 *
 * @Author Tommy
 * @Date 2026/9/25
 */
@Configuration
@EnableConfigurationProperties(ModbusSerialProperties.class)
@Slf4j
public class ModbusSerialConfig {

    /**
     * Modbus RTU主站
     */
    @Bean(destroyMethod = "close")
    public ModbusRtuMaster modbusRtuMaster(ModbusSerialProperties props, ModbusFatalErrorHandler fatal) {
        return new ModbusRtuMaster(props, fatal);
    }

    /**
     * Modbus 看门狗
     */
    @Bean
    public ModbusWatchdog modbusWatchdog(ModbusSerialProperties props, ModbusRtuMaster master, ModbusFatalErrorHandler fatal) {
        return new ModbusWatchdog(props, master, fatal);
    }

    /**
     * Modbus 采集调度器
     */
    @Bean
    public ModbusCollectionScheduler modbusCollectionScheduler(ModbusSerialProperties props, ModbusRtuMaster master, ModbusDataHandler dataHandler, ModbusWatchdog watchdog, ModbusHealthChecker healthChecker) {
        return new ModbusCollectionScheduler(props, master, dataHandler, watchdog, healthChecker);
    }

    /**
     * Modbus 数据出口Handler
     */
    @Bean
    public ModbusDataHandler modbusDataHandler() {
        return new ModbusLoggingDataHandler();
    }

    /**
     * Modbus 致命错误处理器
     */
    @Bean
    public ModbusFatalErrorHandler modbusFatalErrorHandler() {
        return new ModbusFatalErrorHandler();
    }

    /**
     * Modbus 健康检查器
     */
    @Bean
    public ModbusHealthChecker modbusHealthChecker(ModbusRtuMaster master, ModbusSerialProperties props) {
        return new ModbusHealthChecker(master, props);
    }
}
