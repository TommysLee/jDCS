package com.ty.jdcs.test;

import com.ty.jdcs.modbus.core.ModbusByteOrder;
import com.ty.jdcs.modbus.core.ModbusDataType;
import com.ty.jdcs.modbus.core.ModbusPoint;
import com.ty.jdcs.modbus.parser.ModbusPointParser;

import java.math.BigInteger;

/**
 * ModbusPointParser 自测套件
 * 无需JUnit，main方法直接运行，失败时退出码为1，可直接挂CI
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public class TestModbusPointParser {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 基础类型解析测试
        testInt16();
        testUint16();
        testInt32();
        testUint32();
        testFloat32();
        testInt64();
        testDouble64();

        // 字节序测试
        testByteOrderABCD();
        testByteOrderCDAB();
        testByteOrderBADC();
        testByteOrderDCBA();

        // 奇数寄存器测试（7个寄存器字符串落单保留）
        testStringOddRegister();
        testStringEvenRegister();

        // 配置校验测试
        testConfigValidation();
        testByteOrderAlias();
        testDataTypeAlias();

        // 实体填充测试
        testFillMethod();
        testTryFillMethod();

        // 容错测试
        testFaultTolerance();

        // 缩放偏移测试
        testScaleOffset();

        // parseAt测试（连续寄存器逐点解析）
        testParseAt();

        // 单寄存器字节交换测试
        testSingleRegisterByteSwap();

        // 未知字节序配置期报错测试
        testUnknownByteOrderRejected();

        // 终审回归用例：fill原始值别名、失败对称性、宽容入口null安全
        testFillRawBytesNotAliased();
        testFillFailureSymmetry();
        testTryFillNullPoint();

        // 终审回归用例：别名表可达性、parseAt 偏移溢出
        testAliasTableReachable();
        testParseAtHugeOffset();

        // 上线前终审回归用例：parsedValue类型契约、INT64精度、rawBytes一致性、
        // scale非有限值拦截、resolveRegisterCount、parseAt负偏移
        testParsedValueTypeContract();
        testInt64PrecisionNoLoss();
        testRawBytesSliceConsistency();
        testNonFiniteScaleRejected();
        testResolveRegisterCount();
        testParseAtNegativeOffset();

        // 映翰通数据类型对齐（新增BIT/ULONG，修正INT/LONG映射）
        testBool();
        testUint64();
        testYinghantongTypeMapping();

        // IEC 61131-3 无符号别名族对齐（UINT/UDINT/ULINT 与有符号族位宽对称）
        testIecUnsignedAlias();

        System.out.printf("%n总计: %d, 通过: %d, 失败: %d%n", passed + failed, passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 上线前回归用例 ====================

    /**
     * parsedValue 类型契约：未配置缩放时保留数据类型对应的原始类型，
     * 不得一律转成 Double（否则消费方按契约强转会 ClassCastException）。
     */
    private static void testParsedValueTypeContract() {
        ModbusPoint p16 = createPoint(ModbusDataType.INT16, "ABCD", null);
        assertTrue("INT16 fill成功", ModbusPointParser.fill(p16, new byte[]{0x00, 0x64}));
        assertTrue("INT16 parsedValue为Short", p16.getValue() instanceof Short);
        assertEqualsShort("INT16 parsedValue值", (short) 100, p16.getValue());

        ModbusPoint p32 = createPoint(ModbusDataType.INT32, "ABCD", null);
        assertTrue("INT32 fill成功", ModbusPointParser.fill(p32, new byte[]{0x00, 0x00, 0x00, 0x64}));
        assertTrue("INT32 parsedValue为Integer", p32.getValue() instanceof Integer);

        ModbusPoint pu32 = createPoint(ModbusDataType.UINT32, "ABCD", null);
        assertTrue("UINT32 fill成功", ModbusPointParser.fill(pu32, new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}));
        assertTrue("UINT32 parsedValue为Long", pu32.getValue() instanceof Long);
        assertEqualsLong("UINT32 parsedValue值", 4294967295L, pu32.getValue());

        ModbusPoint pf = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        assertTrue("FLOAT32 fill成功", ModbusPointParser.fill(pf, new byte[]{0x41, (byte)0xCC, 0x00, 0x00}));
        assertTrue("FLOAT32 parsedValue为Float", pf.getValue() instanceof Float);

        ModbusPoint pd = createPoint(ModbusDataType.DOUBLE64, "ABCD", null);
        assertTrue("DOUBLE64 fill成功", ModbusPointParser.fill(pd, new byte[]{0x40, 0x09, 0x21, (byte)0xFB, 0x54, 0x44, 0x2D, 0x18}));
        assertTrue("DOUBLE64 parsedValue为Double", pd.getValue() instanceof Double);

        ModbusPoint ps = createStringPoint(2, "ABCD");
        assertTrue("STRING fill成功", ModbusPointParser.fill(ps, new byte[]{0x41, 0x42, 0x43, 0x44}));
        assertTrue("STRING parsedValue为String", ps.getValue() instanceof String);
        assertEqualsString("STRING parsedValue值", "ABCD", ps.getValue());

        // 配置了缩放时返回 Double
        ModbusPoint pscaled = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        pscaled.setScale(0.1);
        assertTrue("FLOAT32(scale=0.1) fill成功", ModbusPointParser.fill(pscaled, new byte[]{0x41, (byte)0xCC, 0x00, 0x00}));
        assertTrue("配置缩放后parsedValue为Double", pscaled.getValue() instanceof Double);
        assertEqualsDouble("缩放后工程值", 2.55, (Double) pscaled.getValue(), 0.001);
    }

    /**
     * INT64 默认无缩放时不得丢精度：恒等变换下若强行转 double，
     * 2^53+1 会变成 2^53，Long.MAX_VALUE 会变成近似值。
     */
    private static void testInt64PrecisionNoLoss() {
        long beyondDouble = 9007199254740993L; // 2^53 + 1，double无法精确表示
        ModbusPoint p1 = createPoint(ModbusDataType.INT64, "ABCD", null);
        byte[] b1 = new byte[]{
                (byte) (beyondDouble >>> 56), (byte) (beyondDouble >>> 48),
                (byte) (beyondDouble >>> 40), (byte) (beyondDouble >>> 32),
                (byte) (beyondDouble >>> 24), (byte) (beyondDouble >>> 16),
                (byte) (beyondDouble >>> 8), (byte) beyondDouble};
        assertTrue("INT64(2^53+1) fill成功", ModbusPointParser.fill(p1, b1));
        assertTrue("INT64(2^53+1) parsedValue为Long", p1.getValue() instanceof Long);
        assertEqualsLong("INT64(2^53+1)不丢精度", beyondDouble, p1.getValue());

        long max = Long.MAX_VALUE;
        ModbusPoint p2 = createPoint(ModbusDataType.INT64, "ABCD", null);
        byte[] b2 = new byte[]{
                (byte) (max >>> 56), (byte) (max >>> 48), (byte) (max >>> 40), (byte) (max >>> 32),
                (byte) (max >>> 24), (byte) (max >>> 16), (byte) (max >>> 8), (byte) max};
        assertTrue("INT64(Long.MAX_VALUE) fill成功", ModbusPointParser.fill(p2, b2));
        assertEqualsLong("INT64(Long.MAX_VALUE)不丢精度", max, p2.getValue());

        // parse 路径同样不丢精度
        ModbusPoint p3 = createPoint(ModbusDataType.INT64, "ABCD", null);
        assertEqualsLong("INT64 parse不丢精度", beyondDouble, ModbusPointParser.parse(p3, b1));
    }

    /**
     * fill 与 fillAt 的 rawBytes 语义必须一致：都只存本次解析所用的字节片段。
     */
    private static void testRawBytesSliceConsistency() {
        byte[] buffer = new byte[]{0x00, 0x64, 0x11, 0x22, 0x33, 0x44};

        ModbusPoint viaFill = createPoint(ModbusDataType.INT16, "ABCD", null);
        assertTrue("fill成功", ModbusPointParser.fill(viaFill, buffer));

        ModbusPoint viaFillAt = createPoint(ModbusDataType.INT16, "ABCD", null);
        assertTrue("fillAt成功", ModbusPointParser.fillAt(viaFillAt, buffer, 0));

        assertEqual("fill rawBytes长度=解析切片", 2, viaFill.getRawValue().length);
        assertEqual("fillAt rawBytes长度=解析切片", 2, viaFillAt.getRawValue().length);
        assertEqual("fill与fillAt的rawBytes长度一致",
                viaFillAt.getRawValue().length, viaFill.getRawValue().length);
    }

    /**
     * 非有限缩放系数/偏移量必须在配置期拦截，否则会静默产出 NaN 工程值。
     */
    private static void testNonFiniteScaleRejected() {
        ModbusPoint nan = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        nan.setScale(Double.NaN);
        try {
            ModbusPointParser.validate(nan);
            assertTrue("scale=NaN配置期报错", false);
        } catch (IllegalArgumentException e) {
            assertTrue("scale=NaN配置期报错", true);
        }

        ModbusPoint inf = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        inf.setScale(Double.POSITIVE_INFINITY);
        try {
            ModbusPointParser.validate(inf);
            assertTrue("scale=Infinity配置期报错", false);
        } catch (IllegalArgumentException e) {
            assertTrue("scale=Infinity配置期报错", true);
        }

        ModbusPoint nanOff = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        nanOff.setOffset(Double.NaN);
        try {
            ModbusPointParser.validate(nanOff);
            assertTrue("offset=NaN配置期报错", false);
        } catch (IllegalArgumentException e) {
            assertTrue("offset=NaN配置期报错", true);
        }

        // 正常值不受影响
        ModbusPoint ok = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        ok.setScale(0.1);
        ok.setOffset(5.0);
        ModbusPointParser.validate(ok);
        assertTrue("正常scale/offset校验通过", true);
    }

    /**
     * resolveRegisterCount 应统一给出读取数量，调用方无需自行特判 STRING。
     */
    private static void testResolveRegisterCount() {
        assertEqual("INT16 需要1个寄存器", 1,
                ModbusPointParser.resolveRegisterCount(createPoint(ModbusDataType.INT16, "ABCD", null)));
        assertEqual("FLOAT32 需要2个寄存器", 2,
                ModbusPointParser.resolveRegisterCount(createPoint(ModbusDataType.FLOAT32, "ABCD", null)));
        assertEqual("DOUBLE64 需要4个寄存器", 4,
                ModbusPointParser.resolveRegisterCount(createPoint(ModbusDataType.DOUBLE64, "ABCD", null)));
        assertEqual("STRING 取配置长度7", 7,
                ModbusPointParser.resolveRegisterCount(createStringPoint(7, "ABCD")));

        // 数值类型未配置registerCount时也应正确推导
        ModbusPoint noCount = createPoint(ModbusDataType.INT32, "ABCD", null);
        assertEqual("INT32未配置registerCount推导为2", 2, ModbusPointParser.resolveRegisterCount(noCount));
        assertEqual("validate后registerCount被补足", 2, noCount.getRegisterCount());
    }

    /**
     * parseAt 负偏移必须抛 IllegalArgumentException（严格API契约）。
     */
    private static void testParseAtNegativeOffset() {
        ModbusPoint point = createPoint(ModbusDataType.INT16, "ABCD", null);
        byte[] bytes = new byte[]{0x00, 0x64, 0x00, 0x0A};
        try {
            ModbusPointParser.parseAt(point, bytes, -1);
            assertTrue("parseAt负偏移抛IAE", false);
        } catch (IllegalArgumentException e) {
            assertTrue("parseAt负偏移抛IAE", true);
        }

        // 宽容入口不抛异常
        assertFalse("tryParse入参为null返回失败", ModbusPointParser.tryParse(point, null).isSuccess());
        assertFalse("tryFill实体为null返回false", ModbusPointParser.tryFill(null, bytes));
    }

    // ==================== 基础类型解析 ====================
    private static void testInt16() {
        ModbusPoint point = createPoint(ModbusDataType.INT16, "ABCD", null);
        byte[] bytes = new byte[]{0x00, 0x64}; // 100
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsShort("INT16解析", (short) 100, result);
    }

    private static void testUint16() {
        ModbusPoint point = createPoint(ModbusDataType.UINT16, "ABCD", null);
        byte[] bytes = new byte[]{(byte)0xFF, (byte)0xFF}; // 65535
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsInt("UINT16解析", 65535, result);
    }

    private static void testInt32() {
        ModbusPoint point = createPoint(ModbusDataType.INT32, "ABCD", null);
        byte[] bytes = new byte[]{0x00, 0x00, 0x00, 0x64}; // 100
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsInt("INT32解析", 100, result);
    }

    private static void testUint32() {
        ModbusPoint point = createPoint(ModbusDataType.UINT32, "ABCD", null);
        byte[] bytes = new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}; // 4294967295
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsLong("UINT32解析", 4294967295L, result);
    }

    private static void testFloat32() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        // 25.5f 的IEEE754大端表示: 0x41CC0000
        byte[] bytes = new byte[]{0x41, (byte)0xCC, 0x00, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("FLOAT32 ABCD解析", 25.5f, result);
    }

    private static void testInt64() {
        ModbusPoint point = createPoint(ModbusDataType.INT64, "ABCD", null);
        byte[] bytes = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100L
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsLong("INT64解析", 100L, result);
    }

    private static void testDouble64() {
        ModbusPoint point = createPoint(ModbusDataType.DOUBLE64, "ABCD", null);
        // 25.5 大端: 0x4039800000000000
        byte[] bytes = new byte[]{0x40, 0x39, (byte)0x80, 0x00, 0x00, 0x00, 0x00, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsDouble("DOUBLE64解析", 25.5, result);
    }

    // ==================== 字节序测试 ====================
    private static void testByteOrderABCD() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "ABCD", null);
        byte[] bytes = new byte[]{0x41, (byte)0xCC, 0x00, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("ABCD字节序FLOAT解析", 25.5f, result);
    }

    private static void testByteOrderCDAB() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        // CDAB模式下低字在前，高字在后：0x0000 0x41CC
        byte[] bytes = new byte[]{0x00, 0x00, 0x41, (byte)0xCC};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("CDAB字节序FLOAT解析", 25.5f, result);
    }

    private static void testByteOrderBADC() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "BADC", null);
        // BADC模式：寄存器内字节交换，寄存器顺序不变：0xCC41 0x0000
        byte[] bytes = new byte[]{(byte)0xCC, 0x41, 0x00, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("BADC字节序FLOAT解析", 25.5f, result);
    }

    private static void testByteOrderDCBA() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "DCBA", null);
        // DCBA标准小端：0x00 0x00 0xCC 0x41
        byte[] bytes = new byte[]{0x00, 0x00, (byte)0xCC, 0x41};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("DCBA字节序FLOAT解析", 25.5f, result);
    }

    // ==================== 字符串解析测试（奇数/偶数寄存器） ====================
    private static void testStringOddRegister() {
        ModbusPoint point = createStringPoint(7, "CDAB");
        // 7个寄存器：ABCDEFGHIJKLMN
        // CDAB成对交换：CD AB GH EF KL IJ MN（最后一个落单保留）
        byte[] bytes = new byte[]{
                0x41, 0x42, 0x43, 0x44, // AB CD → CD AB
                0x45, 0x46, 0x47, 0x48, // EF GH → GH EF
                0x49, 0x4A, 0x4B, 0x4C, // IJ KL → KL IJ
                0x4D, 0x4E              // MN（落单保留）
        };
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsString("7寄存器CDAB字符串解析", "CDABGHEFKLIJMN", result);
    }

    private static void testStringEvenRegister() {
        ModbusPoint point = createStringPoint(4, "CDAB");
        // 4个寄存器：ABCDEFGH → CDABGHEF
        byte[] bytes = new byte[]{
                0x41, 0x42, 0x43, 0x44,
                0x45, 0x46, 0x47, 0x48
        };
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsString("4寄存器CDAB字符串解析", "CDABGHEF", result);
    }

    // ==================== 配置校验测试 ====================
    private static void testConfigValidation() {
        ModbusPoint point = new ModbusPoint();
        point.setSlaveId(1);
        point.setFunctionCode(4);
        point.setAddress(0);
        point.setDataType(ModbusDataType.FLOAT32);
        point.setByteOrder("CDAB");
        // 数值类型不需要配置registerCount，validate自动填充
        ModbusPointParser.validate(point);
        assertEqual("FLOAT32自动填充registerCount", 2, point.getRegisterCount());

        // 字符串不配置registerCount应报错
        ModbusPoint strPoint = new ModbusPoint();
        strPoint.setSlaveId(1);
        strPoint.setFunctionCode(3);
        strPoint.setAddress(0);
        strPoint.setDataType(ModbusDataType.STRING);
        strPoint.setByteOrder("ABCD");
        boolean threw = false;
        try {
            ModbusPointParser.validate(strPoint);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("STRING必须配置registerCount", threw);
    }

    private static void testByteOrderAlias() {
        // 映翰通Swapped别名
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "Swapped", null);
        ModbusPointParser.validate(point);
        byte[] bytes = new byte[]{0x00, 0x00, 0x41, (byte)0xCC};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("Swapped别名解析CDAB", 25.5f, result);

        // 尾随空格兼容
        ModbusPoint point2 = createPoint(ModbusDataType.FLOAT32, "CDAB ", null);
        ModbusPointParser.validate(point2);
        Object result2 = ModbusPointParser.parse(point2, bytes);
        assertEqualsFloat("字节序尾随空格兼容", 25.5f, result2);
    }

    private static void testUnknownByteOrderRejected() {
        // 未知字节序严格校验报错
        ModbusPoint point3 = new ModbusPoint();
        point3.setSlaveId(1);
        point3.setFunctionCode(4);
        point3.setAddress(0);
        point3.setDataType(ModbusDataType.FLOAT32);
        point3.setByteOrder("UNKNOWN");
        boolean threw = false;
        try {
            ModbusPointParser.validate(point3);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("未知字节序配置期报错", threw);
    }

    private static void testDataTypeAlias() {
        // FLOAT别名
        ModbusPoint point = createPoint("float", "ABCD", null);
        ModbusPointParser.validate(point);
        byte[] bytes = new byte[]{0x41, (byte)0xCC, 0x00, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsFloat("float别名解析FLOAT32", 25.5f, result);

        // REAL别名
        ModbusPoint point2 = createPoint("real", "ABCD", null);
        ModbusPointParser.validate(point2);
        Object result2 = ModbusPointParser.parse(point2, bytes);
        assertEqualsFloat("real别名解析FLOAT32", 25.5f, result2);
    }

    // ==================== 实体填充测试 ====================
    private static void testFillMethod() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "CDAB", 0.1);
        byte[] bytes = new byte[]{0x00, 0x00, 0x41, (byte)0xCC};
        boolean success = ModbusPointParser.fill(point, bytes);
        assertTrue("fill方法解析成功", success);
        assertTrue("fill设置success为true", point.getSuccess());
        assertEqualsDouble("fill填充parsedValue(25.5*0.1=2.55)", 2.55, (Double) point.getValue(), 0.001);
        assertEqual("fill填充rawBytes长度", 4, point.getRawValue().length);
        assertNotNull("fill填充timestamp", point.getTimestamp());
    }

    private static void testTryFillMethod() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        byte[] invalidBytes = new byte[]{0x00}; // 长度不足
        boolean success = ModbusPointParser.tryFill(point, invalidBytes);
        assertFalse("tryFill解析失败返回false", success);
        assertFalse("tryFill设置success为false", point.getSuccess());
        assertNotNull("tryFill填充errorMsg", point.getErrorMsg());
    }

    // ==================== 容错测试 ====================
    private static void testFaultTolerance() {
        // tryParse长度不足不抛出异常
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        byte[] invalidBytes = new byte[]{0x00};
        ModbusPointParser.ParseResult result = ModbusPointParser.tryParse(point, invalidBytes);
        assertFalse("tryParse失败返回false", result.isSuccess());
        assertNotNull("tryParse失败返回errorMsg", result.getErrorMsg());

        // null字节数组宽容处理
        ModbusPointParser.ParseResult result2 = ModbusPointParser.tryParse(point, null);
        assertFalse("null字节tryParse失败", result2.isSuccess());
    }

    // ==================== 缩放偏移测试 ====================
    private static void testScaleOffset() {
        ModbusPoint point = createPoint(ModbusDataType.INT16, "ABCD", 0.1);
        point.setOffset(5.0);
        byte[] bytes = new byte[]{0x00, (byte)0x64}; // 100
        double value = ModbusPointParser.parseScaled(point, bytes);
        // 100 * 0.1 + 5 = 15.0
        assertEqualsDouble("缩放偏移计算", 15.0, value, 0.001);
    }

    // ==================== parseAt测试 ====================
    private static void testParseAt() {
        // 连续寄存器：第0个寄存器是INT16值100，第1~2个寄存器是CDAB的FLOAT25.5
        byte[] bytes = new byte[]{
                0x00, 0x64,             // Reg0: 100 (INT16)
                0x00, 0x00, 0x41, (byte)0xCC // Reg1-2: 25.5 (FLOAT32 CDAB)
        };

        ModbusPoint intPoint = createPoint(ModbusDataType.INT16, "ABCD", null);
        Object intValue = ModbusPointParser.parseAt(intPoint, bytes, 0);
        assertEqualsShort("parseAt偏移0解析INT16", (short)100, intValue);

        ModbusPoint floatPoint = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        Object floatValue = ModbusPointParser.parseAt(floatPoint, bytes, 1);
        assertEqualsFloat("parseAt偏移1解析FLOAT", 25.5f, floatValue);
    }

    // ==================== 单寄存器字节交换测试 ====================
    private static void testSingleRegisterByteSwap() {
        // BADC模式下单寄存器字节交换：0x6400 → 0x0064 = 100
        ModbusPoint point = createPoint(ModbusDataType.INT16, "BADC", null);
        byte[] bytes = new byte[]{0x64, 0x00};
        Object result = ModbusPointParser.parse(point, bytes);
        assertEqualsShort("单寄存器字节交换解析", (short)100, result);
    }

    // ==================== 终审回归用例 ====================

    /**
     * 回归：fill 存入实体的 rawBytes 必须与调用方数组解耦。
     * 修复前 fill 直接保存入参引用，调用方复用缓冲区会污染已存原始值。
     */
    private static void testFillRawBytesNotAliased() {
        ModbusPoint point = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        byte[] buf = new byte[]{0x00, 0x00, 0x41, (byte) 0xCC};
        ModbusPointParser.fill(point, buf);
        byte[] stored = point.getRawValue();
        buf[0] = (byte) 0xFF; // 调用方复用/改写缓冲区
        assertEqual("fill后rawBytes与调用方数组解耦", 0x00, stored[0] & 0xFF);
    }

    /**
     * 回归：fill 与 fillAt 在失败时的字段处理必须一致（rawBytes、parsedValue 均清空）。
     */
    private static void testFillFailureSymmetry() {
        // fill 路径：先成功后失败
        ModbusPoint a = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        ModbusPointParser.fill(a, new byte[]{0x00, 0x00, 0x41, (byte) 0xCC});
        boolean ok = ModbusPointParser.fill(a, new byte[]{0x00}); // 长度不足
        assertFalse("fill失败返回false", ok);
        assertTrue("fill失败后rawBytes清空", a.getRawValue() == null);
        assertTrue("fill失败后parsedValue清空", a.getValue() == null);
        assertNotNull("fill失败后errorMsg有值", a.getErrorMsg());

        // fillAt 路径：直接失败
        ModbusPoint b = createPoint(ModbusDataType.FLOAT32, "CDAB", null);
        boolean ok2 = ModbusPointParser.fillAt(b, new byte[]{0x00}, 0);
        assertFalse("fillAt失败返回false", ok2);
        assertTrue("fillAt失败后rawBytes清空", b.getRawValue() == null);
        assertTrue("fillAt失败后parsedValue清空", b.getValue() == null);
    }

    /**
     * 回归：宽容入口对 null 实体不抛异常（与 tryParse 语义对齐）。
     */
    private static void testTryFillNullPoint() {
        boolean threw = false;
        boolean result = false;
        try {
            result = ModbusPointParser.tryFill(null, new byte[]{0x00, 0x00, 0x41, (byte) 0xCC});
        } catch (Throwable t) {
            threw = true;
        }
        assertFalse("tryFill(null)不抛异常", threw);
        assertFalse("tryFill(null)返回false", result);
    }

    // ==================== 映翰通数据类型对齐测试 ====================

    /**
     * BOOL（映翰通 BIT）类型：占 1 个寄存器，非零即 true。
     */
    private static void testBool() {
        ModbusPoint point = createPoint(ModbusDataType.BOOL, "ABCD", null);
        assertEqual("BOOL 需要1个寄存器", 1, ModbusPointParser.resolveRegisterCount(point));

        assertEqualsBoolean("BOOL 0x0000 → false", false,
                ModbusPointParser.parse(point, new byte[]{0x00, 0x00}));
        assertEqualsBoolean("BOOL 0x0001 → true", true,
                ModbusPointParser.parse(point, new byte[]{0x00, 0x01}));
        assertEqualsBoolean("BOOL 0x0100（非零）→ true", true,
                ModbusPointParser.parse(point, new byte[]{0x01, 0x00}));
        assertEqualsBoolean("BOOL 0xFFFF → true", true,
                ModbusPointParser.parse(point, new byte[]{(byte) 0xFF, (byte) 0xFF}));

        // fill 路径：类型契约应为 Boolean
        ModbusPoint filled = createPoint(ModbusDataType.BOOL, "ABCD", null);
        assertTrue("BOOL fill成功", ModbusPointParser.fill(filled, new byte[]{0x00, 0x01}));
        assertTrue("BOOL parsedValue为Boolean", filled.getValue() instanceof Boolean);
        assertEqualsBoolean("BOOL fill后值", true, filled.getValue());
    }

    /**
     * UINT64（映翰通 ULONG）类型：占 4 个寄存器，用 BigInteger 承载 0 ~ 2^64-1。
     */
    private static void testUint64() {
        ModbusPoint point = createPoint(ModbusDataType.UINT64, "ABCD", null);
        assertEqual("UINT64 需要4个寄存器", 4, ModbusPointParser.resolveRegisterCount(point));

        assertEqualsBigInteger("UINT64 全0", BigInteger.ZERO,
                ModbusPointParser.parse(point, new byte[]{0, 0, 0, 0, 0, 0, 0, 0}));
        assertEqualsBigInteger("UINT64 =1", BigInteger.ONE,
                ModbusPointParser.parse(point, new byte[]{0, 0, 0, 0, 0, 0, 0, 1}));
        assertEqualsBigInteger("UINT64 Long.MAX_VALUE", new BigInteger("9223372036854775807"),
                ModbusPointParser.parse(point,
                        new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        // 2^63：超出 Long.MAX_VALUE，验证无符号语义正确
        assertEqualsBigInteger("UINT64 2^63", new BigInteger("9223372036854775808"),
                ModbusPointParser.parse(point,
                        new byte[]{(byte) 0x80, 0, 0, 0, 0, 0, 0, 0}));
        // 2^64-1：有符号表示为 -1，验证不出现负数
        assertEqualsBigInteger("UINT64 2^64-1", new BigInteger("18446744073709551615"),
                ModbusPointParser.parse(point,
                        new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));

        // CDAB 按「成对交换」规则处理（reg0↔reg1, reg2↔reg3），非整体倒序
        ModbusPoint cdab = createPoint(ModbusDataType.UINT64, "CDAB", null);
        assertEqualsBigInteger("UINT64 CDAB按成对交换规则", BigInteger.valueOf(65536L),
                ModbusPointParser.parse(cdab, new byte[]{0, 0, 0, 0, 0, 0, 0, 1}));

        // fill 路径：类型契约应为 BigInteger
        ModbusPoint filled = createPoint(ModbusDataType.UINT64, "ABCD", null);
        assertTrue("UINT64 fill成功", ModbusPointParser.fill(filled,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        assertTrue("UINT64 parsedValue为BigInteger", filled.getValue() instanceof BigInteger);
    }

    /**
     * 映翰通工业网关数据类型全表映射校验。
     * <p>重点回归：INT 必须映射到 16 位（不是32位）、LONG 必须映射到 64 位（不是32位），
     * 否则配置映翰通类型名会读错寄存器长度、拿到错误数据。</p>
     */
    private static void testYinghantongTypeMapping() {
        // BIT → BOOL(1)
        assertYhtType("BIT", ModbusDataType.BOOL, 1);
        // WORD → UINT16(1)
        assertYhtType("WORD", ModbusDataType.UINT16, 1);
        // INT → INT16(1)  ← 修正：原映射到 INT32
        assertYhtType("INT", ModbusDataType.INT16, 1);
        // DWORD → UINT32(2)
        assertYhtType("DWORD", ModbusDataType.UINT32, 2);
        // DINT → INT32(2)  ← 新增
        assertYhtType("DINT", ModbusDataType.INT32, 2);
        // FLOAT → FLOAT32(2)
        assertYhtType("FLOAT", ModbusDataType.FLOAT32, 2);
        // DOUBLE → DOUBLE64(4)
        assertYhtType("DOUBLE", ModbusDataType.DOUBLE64, 4);
        // ULONG → UINT64(4)  ← 新增
        assertYhtType("ULONG", ModbusDataType.UINT64, 4);
        // LONG → INT64(4)  ← 修正：原映射到 INT32
        assertYhtType("LONG", ModbusDataType.INT64, 4);
        // STRING → STRING（长度自定义）
        assertYhtType("STRING", ModbusDataType.STRING, -1);

        // 大小写与首尾空白不敏感
        assertYhtType("bit", ModbusDataType.BOOL, 1);
        assertYhtType(" ulong ", ModbusDataType.UINT64, 4);
        assertYhtType("Long", ModbusDataType.INT64, 4);

        // BOOL 的别名族
        assertEqualsString("BOOLEAN别名→BOOL", ModbusDataType.BOOL,
                ModbusDataType.normalize("BOOLEAN"));
        // UINT64 的别名族
        assertEqualsString("QWORD别名→UINT64", ModbusDataType.UINT64,
                ModbusDataType.normalize("QWORD"));
    }

    private static void assertYhtType(String alias, String expectedStandard, int expectedRegs) {
        assertEqualsString("映翰通类型[" + alias + "]→标准名", expectedStandard,
                ModbusDataType.normalize(alias));
        assertEqual("映翰通类型[" + alias + "]寄存器数", expectedRegs,
                ModbusDataType.getRequiredRegisters(alias));
    }

    /**
     * IEC 61131-3 无符号别名族：必须与有符号族位宽严格对称。
     * <p>重点回归：UINT 曾错误映射到 32 位（UINT32），而与之对称的 INT 是 16 位，
     * 造成同一命名族内自相矛盾。按 IEC 61131-3，UINT / UDINT / ULINT 应分别
     * 对齐 INT16 / INT32 / INT64。若配置写 UINT 却按 32 位读取，会静默错位读错寄存器。</p>
     */
    private static void testIecUnsignedAlias() {
        // 无符号族 ↔ 有符号族，位宽一一对称
        assertYhtType("UINT", ModbusDataType.UINT16, 1);
        assertYhtType("UDINT", ModbusDataType.UINT32, 2);
        assertYhtType("ULINT", ModbusDataType.UINT64, 4);
        assertYhtType("INT", ModbusDataType.INT16, 1);
        assertYhtType("DINT", ModbusDataType.INT32, 2);
        assertYhtType("LINT", ModbusDataType.INT64, 4);

        // UINT 实际解析：16 位无符号，0xFFFF → 65535（Integer 容器）
        ModbusPoint p16 = createPoint("UINT", "ABCD", null);
        assertEqualsInt("UINT自动推导寄存器数=1", 1, p16.getRegisterCount());
        Object v16 = ModbusPointParser.parse(p16, new byte[]{(byte) 0xFF, (byte) 0xFF});
        assertEqualsInt("UINT 0xFFFF=65535", 65535, v16);

        // UDINT 实际解析：32 位无符号，0xFFFFFFFF → 4294967295（Long 容器）
        ModbusPoint p32 = createPoint("UDINT", "ABCD", null);
        assertEqualsInt("UDINT自动推导寄存器数=2", 2, p32.getRegisterCount());
        Object v32 = ModbusPointParser.parse(p32,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEqualsLong("UDINT 0xFFFFFFFF=4294967295", 4294967295L, v32);

        // 大小写与首尾空白不敏感
        assertYhtType(" uint ", ModbusDataType.UINT16, 1);
        assertYhtType("Udint", ModbusDataType.UINT32, 2);
        assertYhtType("uLINT", ModbusDataType.UINT64, 4);
    }

    // ==================== 工具方法 ====================
    private static ModbusPoint createPoint(String dataType, String byteOrder, Double scale) {
        ModbusPoint point = new ModbusPoint();
        point.setSlaveId(1);
        point.setFunctionCode(4);
        point.setAddress(0);
        point.setDataType(dataType);
        point.setByteOrder(byteOrder);
        point.setScale(scale == null ? 1.0 : scale);
        point.setOffset(0.0);
        point.setCharset("US-ASCII");
        // 先设置registerCount为null，让validate自动推导
        point.setRegisterCount(null);
        ModbusPointParser.validate(point);
        return point;
    }

    private static ModbusPoint createStringPoint(int registerCount, String byteOrder) {
        ModbusPoint point = new ModbusPoint();
        point.setSlaveId(1);
        point.setFunctionCode(3);
        point.setAddress(0);
        point.setDataType(ModbusDataType.STRING);
        point.setRegisterCount(registerCount);
        point.setByteOrder(byteOrder);
        point.setScale(1.0);
        point.setOffset(0.0);
        point.setCharset("US-ASCII");
        ModbusPointParser.validate(point);
        return point;
    }

    // ==================== 终审回归用例 ====================

    /**
     * 别名表可达性：ALIASES 的键必须是归一化后的形式。
     * 若用 BIG_ENDIAN 这类带下划线的原始写法作键，归一化会删掉下划线，
     * 该键永远无法命中（不可达条目 / 死代码）。
     */
    @SuppressWarnings("unchecked")
    private static void testAliasTableReachable() {
        try {
            java.lang.reflect.Field f = ModbusByteOrder.class.getDeclaredField("ALIASES");
            f.setAccessible(true);
            java.util.Map<String, ModbusByteOrder> map =
                    (java.util.Map<String, ModbusByteOrder>) f.get(null);

            boolean allNormalized = true;
            for (String key : map.keySet()) {
                if (key.indexOf('_') >= 0 || key.indexOf(' ') >= 0 || key.indexOf('-') >= 0
                        || !key.equals(key.toUpperCase(java.util.Locale.ROOT))) {
                    allNormalized = false;
                    System.out.printf("  不可达别名键: [%s]%n", key);
                }
            }
            assertTrue("别名表键全部为归一化形式（无不可达条目）", allNormalized);

            java.util.EnumSet<ModbusByteOrder> present = java.util.EnumSet.noneOf(ModbusByteOrder.class);
            present.addAll(map.values());
            assertEqual("别名表覆盖全部四种字节序", 4, present.size());
        } catch (Exception e) {
            assertTrue("别名表反射检查异常: " + e, false);
        }
    }

    /**
     * parseAt 偏移溢出：registerOffset * 2 在 int 下会溢出成负数，
     * 使边界检查被绕过，arraycopy 抛 ArrayIndexOutOfBoundsException 而非 IllegalArgumentException。
     */
    private static void testParseAtHugeOffset() {
        int[] offsets = {Integer.MAX_VALUE, 1_073_741_824, 1_000_000_000};
        for (int offset : offsets) {
            ModbusPoint point = createPoint(ModbusDataType.INT16, "ABCD", null);
            String result = "no-throw";
            try {
                ModbusPointParser.parseAt(point, new byte[]{0x00, 0x01}, offset);
            } catch (IllegalArgumentException e) {
                result = "IAE";
            } catch (Throwable t) {
                result = t.getClass().getSimpleName();
            }
            assertEqualsString("parseAt 超大offset=" + offset + " 抛IAE", "IAE", result);
        }
    }

    private static void assertEqualsBoolean(String name, boolean expected, Object actual) {
        if (actual instanceof Boolean && ((Boolean) actual) == expected) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(boolean), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsBigInteger(String name, BigInteger expected, Object actual) {
        if (actual instanceof BigInteger && ((BigInteger) actual).compareTo(expected) == 0) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(BigInteger), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsShort(String name, short expected, Object actual) {
        if (actual instanceof Short && (Short) actual == expected) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(short), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsInt(String name, int expected, Object actual) {
        if (actual instanceof Integer && (Integer) actual == expected) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(int), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsLong(String name, long expected, Object actual) {
        if (actual instanceof Long && (Long) actual == expected) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(long), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsFloat(String name, float expected, Object actual) {
        if (actual instanceof Float && Math.abs((Float) actual - expected) < 0.001f) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s(float), actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsDouble(String name, double expected, double actual, double delta) {
        if (Math.abs(actual - expected) < delta) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s, actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqualsDouble(String name, double expected, Object actual) {
        if (actual instanceof Double && Math.abs((Double) actual - expected) < 0.001) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s, actual=%s(%s)%n", name, expected, actual, actual == null ? "null" : actual.getClass().getSimpleName());
            failed++;
        }
    }

    private static void assertEqualsString(String name, String expected, Object actual) {
        if (actual instanceof String && expected.equals(actual)) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s, actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertEqual(String name, int expected, int actual) {
        if (expected == actual) {
            System.out.printf("[PASS] %s: %s%n", name, actual);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=%s, actual=%s%n", name, expected, actual);
            failed++;
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.printf("[PASS] %s%n", name);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=true, actual=false%n", name);
            failed++;
        }
    }

    private static void assertFalse(String name, boolean condition) {
        if (!condition) {
            System.out.printf("[PASS] %s%n", name);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected=false, actual=true%n", name);
            failed++;
        }
    }

    private static void assertNotNull(String name, Object obj) {
        if (obj != null) {
            System.out.printf("[PASS] %s%n", name);
            passed++;
        } else {
            System.out.printf("[FAIL] %s: expected not null%n", name);
            failed++;
        }
    }
}
