package com.ty.jdcs.modbus.parser;

import com.ty.jdcs.modbus.core.ModbusByteOrder;
import com.ty.jdcs.modbus.core.ModbusDataType;
import com.ty.jdcs.modbus.core.ModbusPoint;
import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Modbus测点解析器
 * <p>
 * 单一职责：所有解析、校验、字节重排、类型转换、缩放偏移、实体填充逻辑全部内聚于此。
 * 纯静态工具类，无状态、线程安全，仅接受 {@code byte[]} 入参，
 * 可直接对接 digitalpetri/modbus-serial 的
 * {@code ReadHoldingRegistersResponse.getRegisters()} / {@code ReadInputRegistersResponse.getRegisters()} 返回值。
 * </p>
 *
 * <p>解析流程（对所有数据类型统一）：切片 → 按字节序重排 → 大端展开 → 按类型解析。</p>
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public final class ModbusPointParser {

    private ModbusPointParser() {
        throw new AssertionError("No instances allowed");
    }

    // ==================== 配置校验方法 ====================

    /**
     * 配置期校验：启动时调用，校验测点配置是否合法，不合法直接抛出异常阻止启动。
     * <p>校验通过后会就地归一化配置（数据类型转规范名、字节序转枚举名、补默认值、推导寄存器数量），
     * 因此同一个 {@link ModbusPoint} 实例不应跨线程并发写入。</p>
     *
     * @param point 测点配置
     * @throws IllegalArgumentException 配置不合法
     */
    public static void validate(ModbusPoint point) {
        Objects.requireNonNull(point, "ModbusPoint must not be null");

        // 基础通信参数校验
        if (point.getSlaveId() == null || point.getSlaveId() < 1 || point.getSlaveId() > 247) {
            throw new IllegalArgumentException("SlaveId must be between 1 and 247: " + point.getSlaveId());
        }
        if (point.getFunctionCode() == null) {
            throw new IllegalArgumentException("FunctionCode must not be null");
        }
        int functionCode = point.getFunctionCode();
        if (functionCode < 1 || functionCode > 127) {
            throw new IllegalArgumentException("FunctionCode must be between 1 and 127: " + functionCode);
        }
        if (point.getAddress() == null || point.getAddress() < 0 || point.getAddress() > 65535) {
            throw new IllegalArgumentException("Address must be between 0 and 65535: " + point.getAddress());
        }

        // 数据类型校验（归一化后回写，避免大小写/别名导致后续判断不一致）
        String dataType = ModbusDataType.normalize(point.getDataType());
        point.setDataType(dataType);
        int requiredRegs = ModbusDataType.getRequiredRegisters(dataType);

        // 寄存器数量校验：数值类型由数据类型决定长度，仅 STRING 必须由用户指定
        Integer registerCount = point.getRegisterCount();
        if (requiredRegs == -1) {
            if (registerCount == null || registerCount < 1) {
                throw new IllegalArgumentException("STRING type must specify registerCount >= 1");
            }
        } else if (registerCount == null || registerCount != requiredRegs) {
            // 数值类型长度由 dataType 唯一确定（对标映翰通等主流工业网关的数据类型定义），
            // 配置值与类型固有长度不一致时一律对齐回类型长度：
            // 偏小会被下游判为长度不足，偏大会让批读多读寄存器（浪费总线，甚至越界）。
            point.setRegisterCount(requiredRegs);
        }

        // 字节序校验（严格：拼写错误在启动期暴露，而非运行期静默回退）
        ModbusByteOrder order = ModbusByteOrder.fromStringStrict(point.getByteOrder());
        point.setByteOrder(order.name());

        // 编码校验
        if (point.getCharset() == null || point.getCharset().isEmpty()) {
            point.setCharset(StandardCharsets.US_ASCII.name());
        } else {
            try {
                Charset.forName(point.getCharset());
            } catch (Exception e) {
                throw new IllegalArgumentException("Unsupported charset: " + point.getCharset(), e);
            }
        }

        // 缩放偏移：补默认值，并拒绝 NaN/Infinity（否则会静默产出 NaN 工程值，且难以排查）
        if (point.getScale() == null) {
            point.setScale(1.0);
        } else if (!Double.isFinite(point.getScale())) {
            throw new IllegalArgumentException("Scale must be a finite number: " + point.getScale());
        }
        if (point.getOffset() == null) {
            point.setOffset(0.0);
        } else if (!Double.isFinite(point.getOffset())) {
            throw new IllegalArgumentException("Offset must be a finite number: " + point.getOffset());
        }
    }

    /**
     * 计算测点本次读取/解析实际需要的寄存器数量。
     * <p>数值类型由 {@code dataType} 推导（BOOL/INT16/UINT16=1、INT32/UINT32/FLOAT32=2、INT64/UINT64/DOUBLE64=4），
     * STRING 取配置的 {@code registerCount}。调用方可用它决定向设备请求多少个寄存器：</p>
     * <pre>{@code
     * int count = ModbusPointParser.resolveRegisterCount(point);
     * byte[] raw = client.readHoldingRegisters(point.getSlaveId(), point.getAddress(), count).get().getRegisters();
     * }</pre>
     *
     * @param point 测点配置
     * @return 需要的寄存器数量
     * @throws IllegalArgumentException 配置不合法
     */
    public static int resolveRegisterCount(ModbusPoint point) {
        validate(point);
        return requiredRegisters(point);
    }

    // ==================== 严格解析API：配置错误/解析失败抛出异常 ====================

    /**
     * 解析原始字节数组为对应类型的值（未缩放）。
     * <p>只取前 {@code resolveRegisterCount(point)} 个寄存器对应的字节，多余部分忽略。</p>
     *
     * @param point 测点配置
     * @param bytes Modbus原始字节数组
     * @return 解析后的值，类型见 {@link ModbusPoint#getValue()}
     * @throws IllegalArgumentException 配置不合法或字节长度不足
     */
    public static Object parse(ModbusPoint point, byte[] bytes) {
        return parseAt(point, bytes, 0);
    }

    /**
     * 解析连续字节数组中指定寄存器偏移位置的值（一次读取多寄存器后逐点解析）。
     *
     * @param point          测点配置
     * @param bytes          Modbus原始字节数组
     * @param registerOffset 起始寄存器偏移量（0基）
     * @return 解析后的值
     * @throws IllegalArgumentException 配置不合法、偏移为负或字节长度不足
     */
    public static Object parseAt(ModbusPoint point, byte[] bytes, int registerOffset) {
        validate(point);
        Objects.requireNonNull(bytes, "Bytes must not be null");
        if (registerOffset < 0) {
            throw new IllegalArgumentException("Register offset must be >= 0: " + registerOffset);
        }
        return decodeValue(point, slice(bytes, registerOffset, requiredRegisters(point)));
    }

    /**
     * 解析并应用缩放偏移，返回工程值（恒为 double）。
     * <p>注意：返回值类型固定为 double，对 INT64 超出 2^53 的大值会有精度损失，
     * 如需精确值请改用 {@link #parse} 自行处理缩放。</p>
     *
     * @throws IllegalArgumentException 配置不合法、字节不足或数据类型非数值
     */
    public static double parseScaled(ModbusPoint point, byte[] bytes) {
        Object value = parse(point, bytes);
        return applyScaleAndOffset(value, point.getScale(), point.getOffset());
    }

    // ==================== 宽容解析API：解析失败返回失败结果，不抛出异常，不中断采集 ====================

    /**
     * 宽容解析结果
     */
    @Getter
    public static class ParseResult implements Serializable {
        @Serial
        private static final long serialVersionUID = 275087558697522979L;

        private final boolean success;
        private final Object value;
        private final String errorMsg;

        private ParseResult(boolean success, Object value, String errorMsg) {
            this.success = success;
            this.value = value;
            this.errorMsg = errorMsg;
        }

        public static ParseResult success(Object value) {
            return new ParseResult(true, value, null);
        }

        public static ParseResult fail(String errorMsg) {
            return new ParseResult(false, null, errorMsg);
        }
    }

    /**
     * {@link #parse} 的宽容版本：任何异常（含配置错误、字节不足、入参为 null）均转为失败结果返回。
     */
    public static ParseResult tryParse(ModbusPoint point, byte[] bytes) {
        try {
            return ParseResult.success(parse(point, bytes));
        } catch (Exception e) {
            return ParseResult.fail(e.getMessage());
        }
    }

    /**
     * {@link #parseScaled} 的宽容版本：任何异常均转为失败结果返回。
     */
    public static ParseResult tryParseScaled(ModbusPoint point, byte[] bytes) {
        try {
            return ParseResult.success(parseScaled(point, bytes));
        } catch (Exception e) {
            return ParseResult.fail(e.getMessage());
        }
    }

    // ==================== 实体填充API：直接将原始值和解析值填充到 ModbusPoint 实体中 ====================

    /**
     * 解析原始字节数组并填充到ModbusPoint实体中，自动填充
     * rawValue、value、success、errorMsg、timestamp 字段。
     *
     * <p><b>字段有效性契约（与 {@link #fillAt} 完全一致）</b>：</p>
     * <ul>
     *     <li>返回 true：success=true，rawValue 为本次解析所用的字节片段（克隆副本，与调用方缓冲区解耦），value 为有效值</li>
     *     <li>返回 false：success=false，rawValue 与 value 均被置为 null，原因见 errorMsg</li>
     * </ul>
     * <p>失败时清空 rawValue 而非保留，是为了避免消费方在未校验 success 的情况下，把无法解析的字节流当作有效原始值持久化。</p>
     *
     * @param point 测点实体
     * @param bytes Modbus原始字节数组
     * @return true=解析成功
     */
    public static boolean fill(ModbusPoint point, byte[] bytes) {
        return fillAt(point, bytes, 0);
    }

    /**
     * 从连续字节数组的指定寄存器偏移位置解析并填充实体。
     * <p>字段有效性契约与 {@link #fill} 一致。</p>
     *
     * @param point          测点实体
     * @param bytes          Modbus原始字节数组
     * @param registerOffset 起始寄存器偏移量（0基）
     * @return true=解析成功
     */
    public static boolean fillAt(ModbusPoint point, byte[] bytes, int registerOffset) {
        Objects.requireNonNull(point, "ModbusPoint must not be null");
        return fillInternal(point, bytes, registerOffset);
    }

    /**
     * {@link #fill} 的宽容版本：实体为 null 时返回 false 而非抛异常，保证采集循环不中断。
     * <p>与 {@link #tryParse} 的容错语义对齐。</p>
     *
     * @return true=解析成功；实体为 null 或解析失败均返回 false
     */
    public static boolean tryFill(ModbusPoint point, byte[] bytes) {
        if (point == null) {
            return false;
        }
        return fill(point, bytes);
    }

    /**
     * fill / fillAt 的公共实现：仅在偏移处理上不同，字段契约完全一致。
     */
    private static boolean fillInternal(ModbusPoint point, byte[] bytes, int registerOffset) {
        point.setTimestamp(System.currentTimeMillis());
        try {
            validate(point);
            Objects.requireNonNull(bytes, "Bytes must not be null");
            if (registerOffset < 0) {
                throw new IllegalArgumentException("Register offset must be >= 0: " + registerOffset);
            }

            byte[] slice = slice(bytes, registerOffset, requiredRegisters(point));
            Object value = decodeValue(point, slice);

            point.setValue(applyScaleIfNeeded(value, point.getScale(), point.getOffset()));
            // 存本次解析所用的切片：既是新数组（与调用方缓冲区解耦），
            // 又与 fillAt 语义一致（都只存该测点自身的字节）
            point.setRawValue(slice);
            point.setSuccess(true);
            point.setErrorMsg(null);
            return true;
        } catch (Exception e) {
            point.setSuccess(false);
            point.setValue(null);
            point.setRawValue(null);
            point.setErrorMsg(e.getMessage());
            return false;
        }
    }

    /**
     * 按寄存器偏移切出本次解析所需的字节片段。
     * <p>边界检查用 long 运算：{@code registerOffset * 2} 在 int 下会溢出成负数，
     * 溢出后检查会被绕过，arraycopy 将抛 ArrayIndexOutOfBoundsException 而非 IllegalArgumentException。</p>
     */
    public static byte[] slice(byte[] bytes, int registerOffset, int registerCount) {
        long start = (long) registerOffset * 2;
        long need = (long) registerCount * 2;
        if (start + need > bytes.length) {
            throw new IllegalArgumentException(String.format(
                    "Byte array not enough at register offset %d: need %d bytes, actual %d",
                    registerOffset, need, Math.max(0L, bytes.length - start)));
        }
        byte[] slice = new byte[(int) need];
        System.arraycopy(bytes, (int) start, slice, 0, slice.length);
        return slice;
    }

    /**
     * 将奇数长度的 byte 数组补齐为偶数长度（高位补 0）。
     * <p>
     * 适用场景：hex 解码要求字节数为偶数（每 2 个 hex 字符对应 1 个字节），
     * 当源数据长度为奇数时，在高位补一个 0x00 字节使其对齐。
     * </p>
     *
     * @param input 原始 byte 数组
     * @return 偶数长度的 byte 数组。若输入为 null 或已是偶数长度，直接返回原引用（不拷贝）
     */
    public static byte[] padToEven(byte[] input) {
        // null 或已是偶数长度，直接返回原引用，避免不必要的内存分配
        if (null == input || (input.length & 1) == 0) return input;

        // 新建数组（默认全 0），将原数据复制到 [1..] 位置，实现高位补 0
        byte[] result = new byte[input.length + 1];
        System.arraycopy(input, 0, result, 1, input.length);
        return result;
    }

    // ==================== 内部解析方法 ====================

    /**
     * 计算测点实际需要的寄存器数量：数值类型取类型固有长度，STRING 取配置的 registerCount。
     * <p>调用前必须已完成 validate（保证 dataType 合法、STRING 的 registerCount 已指定）。</p>
     */
    private static int requiredRegisters(ModbusPoint point) {
        String dataType = ModbusDataType.normalize(point.getDataType());
        return ModbusDataType.isString(dataType)
                ? point.getRegisterCount()
                : ModbusDataType.getRequiredRegisters(dataType);
    }

    /**
     * 将单个测点的字节片段解码为对应类型的值：按字节序重排 → 大端展开 → 按类型解析。
     * <p>调用前必须已完成 validate。</p>
     */
    private static Object decodeValue(ModbusPoint point, byte[] slice) {
        String dataType = ModbusDataType.normalize(point.getDataType());
        byte[] reordered = ModbusByteOrder.fromString(point.getByteOrder()).reorder(slice);
        ByteBuffer buffer = ByteBuffer.wrap(reordered).order(ByteOrder.BIG_ENDIAN);
        return parseValue(buffer, dataType, point.getCharset(), slice.length);
    }

    private static Object parseValue(ByteBuffer buffer, String dataType, String charset, int length) {
        switch (dataType) {
            case ModbusDataType.BOOL:
                // 布尔：寄存器值非零即 true（映翰通 BIT 类型）
                return buffer.getShort() != 0;
            case ModbusDataType.INT16:
                return buffer.getShort();
            case ModbusDataType.UINT16:
                return Short.toUnsignedInt(buffer.getShort());
            case ModbusDataType.INT32:
                return buffer.getInt();
            case ModbusDataType.UINT32:
                return Integer.toUnsignedLong(buffer.getInt());
            case ModbusDataType.FLOAT32:
                return buffer.getFloat();
            case ModbusDataType.INT64:
                return buffer.getLong();
            case ModbusDataType.UINT64:
                // 64位无符号：Java 无对应基本类型，用 BigInteger 承载 0 ~ 2^64-1
                return toUnsignedBigInteger(buffer.getLong());
            case ModbusDataType.DOUBLE64:
                return buffer.getDouble();
            case ModbusDataType.STRING:
                return parseString(buffer, Charset.forName(charset), length);
            default:
                throw new IllegalArgumentException("Unsupported data type: " + dataType);
        }
    }

    private static String parseString(ByteBuffer buffer, Charset charset, int byteLength) {
        byte[] strBytes = new byte[byteLength];
        buffer.get(strBytes);

        // 找到第一个\0的位置截断（设备常用\0填充剩余寄存器）
        int end = 0;
        while (end < strBytes.length && strBytes[end] != 0) {
            end++;
        }
        // 与配置文本裁剪策略保持一致，统一使用 strip()（需 JDK 11+）
        return new String(strBytes, 0, end, charset).strip();
    }

    /**
     * 将 64 位原始位型按「无符号」语义转换为 BigInteger。
     * <p>返回值 >= 0 时直接使用；为负说明落在无符号的高半区 [2^63, 2^64)，
     * 实际值 = 有符号值 + 2^64。例如 0xFFFFFFFFFFFFFFFF → -1 → 18446744073709551615。</p>
     */
    private static BigInteger toUnsignedBigInteger(long value) {
        if (value >= 0) {
            return BigInteger.valueOf(value);
        }
        return BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /**
     * 填充实体时应用缩放偏移。
     * <p>恒等变换（scale=1.0 且 offset=0.0）时直接返回原值，保留原始类型与精度——
     * 否则 INT64 超出 2^53 的大值会被转成 double 而丢精度（如 2^53+1 变成 2^53）。
     * 非数值类型（STRING）原样返回。</p>
     */
    private static Object applyScaleIfNeeded(Object value, Double scale, Double offset) {
        if (!(value instanceof Number)) {
            return value;
        }
        double s = scale == null ? 1.0 : scale;
        double o = offset == null ? 0.0 : offset;
        if (s == 1.0 && o == 0.0) {
            return value;
        }
        return applyScaleAndOffset(value, scale, offset);
    }

    /**
     * 计算缩放后的 double 值
     */
    private static double applyScaleAndOffset(Object value, Double scale, Double offset) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Cannot apply scale to non-numeric value");
        }
        double s = scale == null ? 1.0 : scale;
        double o = offset == null ? 0.0 : offset;
        double soValue = ((Number) value).doubleValue() * s + o;
        return Math.round(soValue * 100) / 100d;
    }
}
