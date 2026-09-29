package com.ty.jdcs.modbus.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ty.jdcs.modbus.parser.ModbusPointParser;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Modbus测点实体类
 *
 * @Author Tommy
 * @Date 2026/9/26
 */
@Data
public class ModbusPoint implements Serializable {

    @Serial
    private static final long serialVersionUID = -2361481489015753197L;

    // ==================== 基础标识字段 ====================

    /** 测点唯一ID **/
    private String pointId;

    /** 测点名称 **/
    private String pointName;

    // ==================== Modbus通信参数 ====================

    /**
     * Modbus从站地址
     * 取值范围：1~247
     */
    private Integer slaveId;

    /**
     * Modbus功能码
     * <p>常用值：
     * <ul>
     *     <li>1 (0x01)：读线圈</li>
     *     <li>2 (0x02)：读离散输入</li>
     *     <li>3 (0x03)：读保持寄存器</li>
     *     <li>4 (0x04)：读输入寄存器</li>
     *     <li>5 (0x05)：写单线圈</li>
     *     <li>6 (0x06)：写单保持寄存器</li>
     *     <li>15 (0x0F)：写多线圈</li>
     *     <li>16 (0x10)：写多保持寄存器</li>
     * </ul>
     * 用int类型方便yml配置直接写数字，无需转byte
     */
    private Integer functionCode;

    /**
     * 寄存器起始地址
     * 取值范围：0~65535（0基址偏移，对应40001地址偏移0）
     */
    private Integer address;

    /**
     * 读取/写入寄存器数量
     * <p>数值类型（INT16/FLOAT32等）长度由dataType决定，无需配置：配置为空或小于类型长度时，
     * {@code validate()} 会自动补足为类型固有长度；仅STRING类型必须手动指定寄存器个数
     * （每个寄存器2字节）。</p>
     * <p>解析时实际使用的长度以 {@code ModbusPointParser.resolveRegisterCount(point)} 为准
     * （数值类型取类型固有长度，STRING取本字段值）。</p>
     */
    private Integer registerCount;

    // ==================== 数据解析配置 ====================

    /**
     * 数据类型
     * 取值参见{@link ModbusDataType}常量定义，支持别名：float/real/word/dword等
     */
    private String dataType;

    /**
     * 字节序配置
     * 取值：ABCD/CDAB/BADC/DCBA，也支持别名：swapped/big_endian/little_endian等
     * 映翰通网关默认配置Swapped对应CDAB模式
     */
    private String byteOrder;

    /**
     * 缩放系数
     * 解析后的数值 = 原始解析值 * scale + offset
     * 例如：温度原始值为255，scale=0.1，offset=0 → 实际值25.5℃
     * 默认值1.0，不缩放
     */
    private Double scale = 1.0;

    /**
     * 偏移量
     * 解析后的数值 = 原始解析值 * scale + offset
     * 默认值0.0
     */
    private Double offset = 0.0;

    /**
     * 字符串编码
     * 仅STRING类型生效，默认US-ASCII，可配置为UTF-8/GBK等
     */
    private String charset = StandardCharsets.US_ASCII.name();

    /** 单位 **/
    private String unit;

    // ==================== 实时数据字段（采集后填充） ====================

    /**
     * Modbus原始返回字节数组
     * <p>从digitalpetri/modbus-serial的Response.getRegisters()直接获取，用于存库、溯源、异常排查。</p>
     * <p><b>有效性契约</b>：仅当 {@code success=true} 时本字段有效；内容为<b>本次解析所用的字节片段</b>
     * （长度 = 寄存器数量 × 2），是入参数组的克隆副本，与调用方接收缓冲区完全解耦，
     * 因此缓冲区被下一轮采集复用时不会污染本字段。{@code success=false} 时本字段为 null。</p>
     */
    @JsonIgnore
    private byte[] rawValue;

    /**
     * 解析完成后的值
     * <p><b>未配置缩放时（scale=1.0 且 offset=0.0，默认）</b>：保留数据类型对应的原始类型，不丢精度。
     * 类型对应关系：INT16→Short, UINT16→Integer, INT32→Integer, UINT32→Long,
     * FLOAT32→Float, INT64→Long, DOUBLE64→Double, STRING→String。</p>
     * <p><b>配置了缩放/偏移时</b>：返回缩放后的 Double 值。</p>
     * <p><b>有效性契约</b>：仅当 {@code success=true} 时本字段有效，否则为 null。</p>
     * <p>需要统一按 double 取值时，可用 {@code ((Number) parsedValue).doubleValue()}，
     * 或直接调用 {@code ModbusPointParser.parseScaled}。</p>
     */
    private Object value;

    /**
     * 采集时间戳（毫秒级）
     * 数据采集时间，用于时序存储
     */
    private Long timestamp;

    /**
     * 采集是否成功标记
     * true=解析成功，false=通信失败/解析失败
     */
    private Boolean success = false;

    /**
     * 错误信息
     * 采集/解析失败时的错误描述，用于日志排查
     */
    private String errorMsg;

    /**
     * 根据数据类型推导的寄存器数量
     *
     * @return Integer
     */
    public Integer registerCount() {
        return Objects.nonNull(this.registerCount)? this.registerCount : ModbusPointParser.resolveRegisterCount(this);
    }
}