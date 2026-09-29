package com.ty.jdcs.modbus.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modbus 寄存器批量读取规划器。
 *
 * <p>负责两个职责：
 * <ol>
 *   <li><b>分组</b>：按 {@code (slaveId, functionCode)} 分组。
 *       不同功能码代表独立的地址空间，混读会返回错误数据。</li>
 *   <li><b>合并</b>：同组内地址相邻的测点合并为一次 Modbus 请求，
 *       读回后按各自偏移切片分发。</li>
 * </ol>
 *
 * <p><b>合并规则</b>（仅在同组内执行）：
 * <ul>
 *   <li>按起始地址升序排列所有测点</li>
 *   <li>依次尝试将后续测点并入当前批次</li>
 *   <li>遇到以下任一情况开启新批次：
 *     <ul>
 *       <li>并入后总长度超过 {@link #MAX_REGISTERS_PER_REQUEST}（125）</li>
 *       <li>地址间隙超过配置的 {@code mergeMaxGap}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>位型功能码（01 读线圈 / 02 读离散输入）不参与合并</b>：
 * <p>其响应按位打包（每点 1 bit、每字节 8 点），与"测点数 × 2 字节"不可换算。
 * 若参与合并，第二个及之后的测点会按寄存器偏移切片，必然越界（抛{@code IllegalArgumentException}）或取到错误位（静默出错值）。
 * 故每个位型测点独立成批。
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
public class ModbusBatchReadPlanner {

    /** Modbus 单次请求最大寄存器数（协议规定） */
    public static final int MAX_REGISTERS_PER_REQUEST = 125;

    /**
     * 按 {@code (slaveId, functionCode)} 分组，并在每组内规划合并批次。
     *
     * @param points 所有测点配置
     * @param maxGap 同一组内允许合并的最大地址间隙（寄存器数）
     * @return 按分组键组织的批次映射，遍历顺序与配置顺序一致
     */
    public static Map<GroupKey, List<ReadBatch>> planGrouped(List<ModbusPoint> points, int maxGap) {
        Map<GroupKey, List<ModbusPoint>> grouped = groupBySlaveAndFunction(points);
        Map<GroupKey, List<ReadBatch>> result = new LinkedHashMap<>();
        for (Map.Entry<GroupKey, List<ModbusPoint>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), planSameGroup(entry.getValue(), maxGap, entry.getKey().functionCode));
        }
        return result;
    }

    private static Map<GroupKey, List<ModbusPoint>> groupBySlaveAndFunction(List<ModbusPoint> points) {
        Map<GroupKey, List<ModbusPoint>> grouped = new LinkedHashMap<>();
        for (ModbusPoint p : points) {
            GroupKey key = new GroupKey(p.getSlaveId(), p.getFunctionCode());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        return grouped;
    }

    /**
     * 同一分组内的批次规划
     *
     * @param functionCode 功能码；为 1 或 2（位型）时不参与合并，每个测点独立成批
     */
    private static List<ReadBatch> planSameGroup(List<ModbusPoint> points, int maxGap, int functionCode) {
        List<ModbusPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingInt(ModbusPoint::getAddress));
        boolean isBitFunctionCode = functionCode == 1 || functionCode == 2;

        List<ReadBatch> batches = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            ModbusPoint first = sorted.get(i);
            final int startAddr = first.getAddress();
            int endAddr = startAddr + first.registerCount();

            List<PointSlice> slices = new ArrayList<>();
            slices.add(new PointSlice(first, 0));
            i++;

            while (i < sorted.size()) {
                if (isBitFunctionCode) break;

                ModbusPoint next = sorted.get(i);
                int nextStart = next.getAddress();
                int nextEnd = nextStart + next.registerCount();
                int newEnd = Math.max(endAddr, nextEnd);

                // 约束1：单次请求总长度不超过协议上限
                if (newEnd - startAddr > MAX_REGISTERS_PER_REQUEST) {
                    break;
                }

                // 约束2：地址间隙不超过配置上限
                if (nextStart - endAddr > maxGap) {
                    break;
                }

                slices.add(new PointSlice(next, nextStart - startAddr));
                endAddr = newEnd;
                i++;
            }

            batches.add(new ReadBatch(startAddr, endAddr - startAddr, slices));
        }
        return batches;
    }

    /**
     * 分组键：从站地址 + 功能码。
     */
    public record GroupKey(int slaveId, int functionCode) {
    }

    /**
     * 一次 Modbus 请求的读取范围及范围内的设备切片
     *
     * @param startAddress 起始寄存器地址
     * @param totalCount   总读取寄存器数
     * @param slices       本批次包含的设备切片
     */
    public record ReadBatch(int startAddress, int totalCount, List<PointSlice> slices) {
    }

    /**
     * 设备在批次中的切片位置
     *
     * @param point         测点配置
     * @param offsetInBatch 相对批次起始地址的寄存器偏移（0 基）
     */
    public record PointSlice(ModbusPoint point, int offsetInBatch) {
    }
}
