package com.ty.jdcs.modbus.core;

/**
 * Modbus数据类型常量类
 * <p>
 * 单一职责：统一管理所有Modbus支持的数据类型、对应寄存器占用数量、合法性校验
 * </p>
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public final class ModbusDataType {

    private ModbusDataType() {
    }

    /**
     * 布尔型（0/1）
     * <p>寄存器值非零即为 true，零即为 false。
     * 对应工业场景用于开关量、状态位、使能位等。</p>
     * 占用寄存器数量：1
     * Java类型：{@link Boolean}
     */
    public static final String BOOL = "BOOL";

    /**
     * 16位有符号整数
     * 占用寄存器数量：1
     * Java类型：{@link Short}
     */
    public static final String INT16 = "INT16";

    /**
     * 16位无符号整数
     * 占用寄存器数量：1
     * Java类型：{@link Integer}
     */
    public static final String UINT16 = "UINT16";

    /**
     * 32位有符号整数
     * 占用寄存器数量：2
     * Java类型：{@link Integer}
     */
    public static final String INT32 = "INT32";

    /**
     * 32位无符号整数
     * 占用寄存器数量：2
     * Java类型：{@link Long}
     */
    public static final String UINT32 = "UINT32";

    /**
     * 32位单精度浮点数（工业场景最常用）
     * 占用寄存器数量：2
     * Java类型：{@link Float}
     */
    public static final String FLOAT32 = "FLOAT32";

    /**
     * 64位有符号长整数
     * 占用寄存器数量：4
     * Java类型：{@link Long}
     */
    public static final String INT64 = "INT64";

    /**
     * 64位无符号整数
     * <p>Java 基本类型无法表示完整的 64 位无符号范围（0 ~ 2^64-1），
     * 因此返回 {@link java.math.BigInteger}，与 UINT16→Integer、UINT32→Long
     * 的「无符号类型拓宽到更大容器」模式保持一致。</p>
     * 占用寄存器数量：4
     * Java类型：{@link java.math.BigInteger}
     */
    public static final String UINT64 = "UINT64";

    /**
     * 64位双精度浮点数
     * 占用寄存器数量：4
     * Java类型：{@link Double}
     */
    public static final String DOUBLE64 = "DOUBLE64";

    /**
     * ASCII字符串
     * 占用寄存器数量：自定义，由registerCount指定
     * Java类型：{@link String}
     */
    public static final String STRING = "STRING";

    /**
     * 获取数据类型对应的固定寄存器占用数量
     *
     * @param dataType 数据类型字符串，自动归一化
     * @return 寄存器数量；STRING类型返回-1，需由用户手动指定registerCount
     * @throws IllegalArgumentException 数据类型不支持
     */
    public static int getRequiredRegisters(String dataType) {
        String normalized = normalize(dataType);
        return switch (normalized) {
            case BOOL, INT16, UINT16 -> 1;
            case INT32, UINT32, FLOAT32 -> 2;
            case INT64, UINT64, DOUBLE64 -> 4;
            case STRING -> -1; // 字符串长度不固定
            default -> throw new IllegalArgumentException("Unsupported data type: " + dataType);
        };
    }

    /**
     * 校验数据类型是否合法
     *
     * @param dataType 数据类型字符串
     * @return true=合法
     */
    public static boolean isValid(String dataType) {
        try {
            normalize(dataType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断是否为字符串类型
     */
    public static boolean isString(String dataType) {
        return STRING.equals(normalize(dataType));
    }

    /**
     * 数据类型归一化：转大写、去除空白字符
     */
    public static String normalize(String dataType) {
        if (dataType == null || dataType.isEmpty()) {
            throw new IllegalArgumentException("Data type must not be empty");
        }
        // 去除首尾空白字符（处理配置文件中尾随空格问题）
        String trimmed = dataType.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Data type must not be empty");
        }
        // 区域无关转大写，避免土耳其语i问题
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) (c - 32));
            } else if (c == ' ' || c == '_' || c == '-') {
                // 兼容"float 32"、"float_32"、"float-32"写法
                continue;
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString();
        return switch (result) {
            case BOOL -> BOOL;
            case INT16 -> INT16;
            case UINT16 -> UINT16;
            case INT32 -> INT32;
            case UINT32 -> UINT32;
            case FLOAT32 -> FLOAT32;
            case INT64 -> INT64;
            case UINT64 -> UINT64;
            case DOUBLE64 -> DOUBLE64;
            case STRING -> STRING;

            // ==================== 别名兼容 ====================
            // 命名对照（对齐映翰通工业网关数据类型）：
            //   BIT  → BOOL      WORD  → UINT16    INT   → INT16     BCD16 → 未实现
            //   DWORD→ UINT32    DINT  → INT32     BCD32 → 未实现
            //   FLOAT→ FLOAT32   DOUBLE→ DOUBLE64
            //   ULONG→ UINT64    LONG  → INT64     STRING→ STRING
            //
            // IEC 61131-3 无符号族（与有符号族位宽严格对称）：
            //   UINT → UINT16    UDINT → UINT32    ULINT → UINT64

            // 布尔类
            case "BIT", "BOOLEAN" -> BOOL;

            // 16位整数（IEC 61131-3：INT / UINT 位宽均为 16，符号相反）
            case "SHORT", "INT" ->     // 映翰通/IEC 61131-3 约定：INT = 16位有符号
                    INT16;
            case "USHORT", "WORD", "UINT" ->    // IEC 61131-3 约定：UINT = 16位无符号（与 INT 位宽对称）
                    UINT16;

            // 32位整数（IEC 61131-3：DINT / UDINT 位宽均为 32）
            case "DINT" ->    // 映翰通/IEC 61131-3 约定：DINT = 32位有符号
                    INT32;   // IEC 61131-3 约定：UDINT = 32位无符号（与 DINT 位宽对称）
            case "DWORD", "UDINT", "UNSIGNEDLONG" -> // 语义有歧义（C# ulong=64位 / C unsigned long 随平台），
                // 仅为向后兼容保留；新配置请使用 UINT32 / DWORD / UDINT
                    UINT32;

            // 32位浮点
            case "FLOAT", "REAL" -> FLOAT32;

            // 64位整数
            // 映翰通约定：LONG = 64位有符号（注意：不是32位）
            case "LONG", "LONG64", "LINT" -> INT64;   // 映翰通约定：ULONG = 64位无符号
            case "ULONG", "QWORD", "DWORD64", "ULINT" ->   // IEC 61131-3 约定：ULINT = 64位无符号（与 LINT 位宽对称）
                    UINT64;

            // 64位浮点
            case "DOUBLE", "LREAL" -> DOUBLE64;

            // 字符串
            case "STR", "ASCII" -> STRING;
            default -> throw new IllegalArgumentException("Unsupported data type: " + dataType);
        };
    }
}