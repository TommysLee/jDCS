package com.ty.jdcs.test;

import com.ty.jdcs.modbus.core.ModbusPoint;
import com.ty.jdcs.modbus.parser.ModbusPointParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * ModbusPoint 解析，简单测试
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public class TestModbusPointParserSimple {

    @Test
    void test() {
        // 二进制数值 258
        byte[] bytes = new byte[] {0x00, 0x00, 0x01, 0x02};
        System.out.println("原始值：" + ByteBuffer.wrap(bytes).getInt());

        // 构建测点信息
        ModbusPoint point = new ModbusPoint();
        point.setPointId("temperature_hall");
        point.setPointName("大厅温度");
        point.setSlaveId(1);
        point.setFunctionCode(3);
        point.setAddress(1);
        point.setDataType("UINT32");
        point.setByteOrder("ABCD");
        point.setUnit("℃");
        point.setScale(0.1);

        // 解析
        boolean flag = ModbusPointParser.tryFill(point, bytes);
        System.out.println("解析 " + (flag? "成功" : "失败"));

        // 输出结果
        System.out.println(point);
        if (point.getSuccess()) {
            System.out.print(point.getValue());
            System.out.println(point.getUnit());
        }
    }
}
