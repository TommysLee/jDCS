package com.ty.jdcs.modbus.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Modbus字节序枚举
 * <p>
 * 用「字交换（wordSwap） + 字节交换（byteSwap）」两维统一描述四种工业常用字节序，
 * 核心重排逻辑先处理寄存器级别的成对交换（奇数个寄存器最后一个落单保留），再处理寄存器内部的字节交换。
 * </p>
 *
 * <p>四种字节序对应关系（以32位值0x11223344为例，每个寄存器2字节）：</p>
 * <ul>
 *     <li>ABCD  → [0x1122, 0x3344] 标准大端，西门子/施耐德等欧系PLC默认</li>
 *     <li>CDAB  → [0x3344, 0x1122] 小端字交换，国产PLC/映翰通网关默认</li>
 *     <li>BADC  → [0x2211, 0x4433] 大端字节交换，部分早期嵌入式设备使用</li>
 *     <li>DCBA  → [0x4433, 0x2211] 标准小端，x86/部分DSP设备使用</li>
 * </ul>
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
public enum ModbusByteOrder {

    /**
     * 标准大端序（Big-Endian）
     * 字节流顺序：A B C D，无交换，符合网络字节序
     */
    ABCD(false, false),

    /**
     * 字交换模式（Word-Swapped）
     * 字节流顺序：C D A B，每两个寄存器成对交换，奇数个寄存器最后一个落单保留
     * 国产PLC、映翰通/物通博联等工业网关默认模式
     */
    CDAB(true, false),

    /**
     * 字节交换模式（Byte-Swapped）
     * 字节流顺序：B A D C，每个寄存器内部高低字节互换，寄存器顺序不变
     */
    BADC(false, true),

    /**
     * 标准小端序（Little-Endian）
     * 字节流顺序：D C B A，字交换+字节交换，x86架构默认
     */
    DCBA(true, true);

    /**
     * 是否需要字交换（寄存器之间交换）
     */
    private final boolean wordSwap;

    /**
     * 是否需要字节交换（单个寄存器内部高低字节互换）
     */
    private final boolean byteSwap;

    /**
     * 字节序别名映射集合
     */
    private static final Map<String, ModbusByteOrder> ALIASES;

    static {
        Map<String, ModbusByteOrder> map = new HashMap<>();
        addAliases(map, ABCD, "ABCD", "BIGENDIAN", "BIG_ENDIAN", "NETWORK");
        addAliases(map, CDAB, "CDAB", "SWAPPED", "WORDSWAP", "WORD_SWAP", "MODBUSSWAPPED");
        addAliases(map, BADC, "BADC", "BYTESWAP", "BYTE_SWAP");
        addAliases(map, DCBA, "DCBA", "LITTLEENDIAN", "LITTLE_ENDIAN", "INTEL");
        ALIASES = Collections.unmodifiableMap(map);
    }

    /**
     * 注册别名。别名统一经 {@link #normalize} 处理后作为键，
     * 保证「配置文本归一化后一定能命中表中的键」——若直接用原始写法（如 BIG_ENDIAN）作键，
     * 归一化会删掉下划线，该键将永远无法命中（不可达条目）。
     */
    private static void addAliases(Map<String, ModbusByteOrder> map, ModbusByteOrder order, String... aliases) {
        for (String alias : aliases) {
            map.put(normalize(alias), order);
        }
    }

    ModbusByteOrder(boolean wordSwap, boolean byteSwap) {
        this.wordSwap = wordSwap;
        this.byteSwap = byteSwap;
    }

    public boolean isWordSwap() {
        return wordSwap;
    }

    public boolean isByteSwap() {
        return byteSwap;
    }

    /**
     * 是否为恒等变换（ABCD不需要重排）
     */
    public boolean isIdentity() {
        return !wordSwap && !byteSwap;
    }

    /**
     * 按当前字节序重排原始字节数组
     * <p>
     * 核心算法：先对寄存器成对交换位置（奇数个寄存器最后一个落单保留），再处理每个寄存器内部的字节交换
     * </p>
     *
     * @param bytes 原始Modbus返回字节数组（大端序，每个寄存器2字节）
     * @return 重排后的字节数组，不修改入参
     * @throws IllegalArgumentException 字节数组长度为奇数或null
     */
    public byte[] reorder(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Bytes must not be null");
        }
        if ((bytes.length & 1) != 0) {
            throw new IllegalArgumentException("Byte array length must be even, actual: " + bytes.length);
        }
        if (isIdentity() || bytes.length == 0) {
            return bytes.clone();
        }

        int regCount = bytes.length / 2;
        // 第一步：寄存器级重排（成对交换+落单保留）
        short[] registers = new short[regCount];
        for (int i = 0; i < regCount; i++) {
            int sourceRegIndex;
            if (wordSwap) {
                sourceRegIndex = i ^ 1; // 成对寄存器索引交换：0↔1, 2↔3...
                if (sourceRegIndex >= regCount) {
                    sourceRegIndex = i; // 落单寄存器保留原位置
                }
            } else {
                sourceRegIndex = i;
            }
            // 读取原始寄存器值（大端）
            int high = bytes[sourceRegIndex * 2] & 0xFF;
            int low = bytes[sourceRegIndex * 2 + 1] & 0xFF;
            registers[i] = (short) ((high << 8) | low);
        }

        // 第二步：转回字节数组，处理字节交换
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < regCount; i++) {
            short reg = registers[i];
            int high = (reg >> 8) & 0xFF;
            int low = reg & 0xFF;
            if (byteSwap) {
                result[i * 2] = (byte) low;
                result[i * 2 + 1] = (byte) high;
            } else {
                result[i * 2] = (byte) high;
                result[i * 2 + 1] = (byte) low;
            }
        }

        return result;
    }

    /**
     * 严格模式：通过字节序文本获取ModbusByteOrder对象。未知配置，则抛出异常
     *
     * @param orderText 字节序文本，自动处理首尾空白、大小写、下划线等
     * @return 对应的ModbusByteOrder
     * @throws IllegalArgumentException 未知字节序
     */
    public static ModbusByteOrder fromStringStrict(String orderText) {
        if (orderText == null || orderText.isEmpty()) {
            return ABCD; // 未配置默认大端
        }
        // strip() 与 String.trim() 语义不同：strip() 按 Character.isWhitespace 裁剪，
        // 能正确处理全角空格 U+3000、U+2000~U+200A 等 Unicode 空白（中文配置易误输入），
        // 而 trim() 只裁剪 <= U+0020 的字符，无法识别全角空格。需 JDK 11+。
        String trimmed = orderText.strip();
        if (trimmed.isEmpty()) {
            return ABCD;
        }
        String normalized = normalize(trimmed);
        ModbusByteOrder order = ALIASES.get(normalized);
        if (order == null) {
            throw new IllegalArgumentException("Unknown Modbus byte order: " + orderText);
        }
        return order;
    }

    /**
     * 宽容模式：通过字节序文本获取ModbusByteOrder对象。未知配置回退为ABCD，用于运行期解析不中断采集
     *
     * @param orderText 字节序文本，自动处理首尾空白、大小写、下划线等
     * @return 对应的ModbusByteOrder
     */
    public static ModbusByteOrder fromString(String orderText) {
        try {
            return fromStringStrict(orderText);
        } catch (IllegalArgumentException e) {
            return ABCD;
        }
    }

    /**
     * 配置文本归一化，区域无关
     */
    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) (c - 32));
            } else if (c == '_' || c == ' ' || c == '-') {
                continue; // 忽略分隔符
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
