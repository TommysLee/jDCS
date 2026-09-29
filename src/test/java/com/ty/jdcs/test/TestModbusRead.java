package com.ty.jdcs.test;

import com.digitalpetri.modbus.FunctionCode;
import com.ty.jdcs.modbus.api.ModbusRtuMaster;
import com.ty.jdcs.modbus.core.ModbusDataType;
import com.ty.jdcs.modbus.core.ModbusPoint;
import com.ty.jdcs.modbus.parser.ModbusPointParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Modbus 读线圈、离散输入测试
 *
 * @Author Tommy
 * @Date 2026/9/28
 */
@SpringBootTest
public class TestModbusRead {

    @Autowired
    private ModbusRtuMaster master;

    // ---------- 工具方法 ----------

    ModbusPoint point(int slaveId, int address, int fc, String dataType, String name) {
        ModbusPoint p = new ModbusPoint();
        p.setSlaveId(slaveId);
        p.setAddress(address);
        p.setFunctionCode(fc);
        p.setDataType(dataType);
        p.setPointName(name);
        return p;
    }

    void exec(ModbusPoint point) {
        byte[] bytes = master.read(point);
        ModbusPointParser.tryFill(point, bytes);

        System.out.println();
        System.out.println(point);
        System.out.println();
    }

    // ---------- 测试用例 ----------

    @Test
    public void testCoils() {
        int fc = FunctionCode.READ_COILS.getCode();
        String dataType = ModbusDataType.BOOL;
        ModbusPoint p = point(1, 6, fc, dataType, "线圈值");
        exec(p);
    }

    @Test
    public void testDiscreteInputs() {
        int fc = FunctionCode.READ_DISCRETE_INPUTS.getCode();
        String dataType = ModbusDataType.UINT16; // 仅测试：线圈正常应配 BOOL，此处 UINT16 在 BADC/DCBA 下会得到 256
        ModbusPoint p = point(1, 7, fc, dataType, "离散输入");
        exec(p);
    }
}
