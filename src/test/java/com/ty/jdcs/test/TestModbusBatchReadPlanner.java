package com.ty.jdcs.test;

import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner.GroupKey;
import com.ty.jdcs.modbus.core.ModbusBatchReadPlanner.ReadBatch;
import com.ty.jdcs.modbus.core.ModbusPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModbusBatchReadPlanner} 单元测试。
 *
 * <p>覆盖两类关注点：
 * <ul>
 *   <li><b>分组正确性</b>：不同 (slaveId, functionCode) 必须分开</li>
 *   <li><b>合并逻辑</b>：同组内相邻地址合并、间隙拆分、超长拆分、重叠处理</li>
 * </ul>
 *
 * @Author Tommy
 * @Date 2026/9/28
 */
public class TestModbusBatchReadPlanner {

    // ---------- 工具方法 ----------

    /** 构造一个已显式指定 registerCount 的测点，避免触发 parser 推导。 */
    private static ModbusPoint point(int slaveId, int functionCode, String name,
                                     int address, int count) {
        ModbusPoint p = new ModbusPoint();
        p.setSlaveId(slaveId);
        p.setFunctionCode(functionCode);
        p.setPointName(name);
        p.setAddress(address);
        p.setRegisterCount(count);
        return p;
    }

    /** 位型：线圈（FC01） */
    private static ModbusPoint coil(int slaveId, String name, int address) {
        return point(slaveId, 1, name, address, 1);
    }

    /** 位型：离散输入（FC02） */
    private static ModbusPoint discrete(int slaveId, String name, int address) {
        return point(slaveId, 2, name, address, 1);
    }

    /** 保持寄存器（FC03） **/
    private static ModbusPoint holding(int slaveId, String name, int address, int count) {
        return point(slaveId, 3, name, address, count);
    }

    /** 输入寄存器（FC04） **/
    private static ModbusPoint input(int slaveId, String name, int address, int count) {
        return point(slaveId, 4, name, address, count);
    }

    // ---------- 分组：核心用例 ----------

    @Test
    void differentFunctionCode_sameSlaveId_separateGroups() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "H0", 0, 2),
                input(1, "I0", 0, 2)
        ), 5);

        assertThat(grouped).hasSize(2);
        assertThat(grouped).containsKeys(
                new GroupKey(1, 3),
                new GroupKey(1, 4));
    }

    @Test
    void differentSlaveId_sameFunctionCode_separateGroups() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 2),
                holding(2, "B", 0, 2)
        ), 5);

        assertThat(grouped).hasSize(2);
    }

    @Test
    void mixedSlaveAndFunctionCode_groupedCorrectly() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "H-A", 0, 2),
                holding(1, "H-B", 2, 2),
                input(1, "I-A", 0, 2),
                holding(2, "H-C", 0, 2),
                input(2, "I-B", 0, 2)
        ), 5);

        assertThat(grouped).hasSize(4);

        List<ReadBatch> g13 = grouped.get(new GroupKey(1, 3));
        assertThat(g13).hasSize(1);
        assertThat(g13.get(0).totalCount()).isEqualTo(4);

        assertThat(grouped.get(new GroupKey(1, 4))).hasSize(1);
        assertThat(grouped.get(new GroupKey(2, 3))).hasSize(1);
        assertThat(grouped.get(new GroupKey(2, 4))).hasSize(1);
    }

    // ---------- 边界 ----------

    @Test
    void emptyList_returnsEmptyMap() {
        assertThat(ModbusBatchReadPlanner.planGrouped(List.of(), 5)).isEmpty();
    }

    @Test
    void singlePoint_returnsOneGroupOneBatch() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(
                List.of(holding(1, "A", 0, 2)), 5);

        assertThat(grouped).hasSize(1);
        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).startAddress()).isZero();
        assertThat(batches.get(0).totalCount()).isEqualTo(2);
    }

    // ---------- 合并：同组内 ----------

    @Test
    void adjacentPoints_sameGroup_merged() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 2),
                holding(1, "B", 2, 2),
                holding(1, "C", 4, 2)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).totalCount()).isEqualTo(6);
        assertThat(batches.get(0).slices()).hasSize(3);
        assertThat(batches.get(0).slices().get(1).offsetInBatch()).isEqualTo(2);
        assertThat(batches.get(0).slices().get(2).offsetInBatch()).isEqualTo(4);
    }

    @Test
    void gapExceedsMaxGap_split() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 2),
                holding(1, "B", 100, 2)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(2);
    }

    @Test
    void totalExceedsMaxRegistersPerRequest_split() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 50),
                holding(1, "B", 50, 50),
                holding(1, "C", 100, 50)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).totalCount()).isEqualTo(100);
        assertThat(batches.get(1).totalCount()).isEqualTo(50);
        batches.forEach(b -> assertThat(b.totalCount())
                .isLessThanOrEqualTo(ModbusBatchReadPlanner.MAX_REGISTERS_PER_REQUEST));
    }

    @Test
    void overlappingPoints_handledGracefully() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 4),
                holding(1, "B", 2, 2)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).totalCount()).isEqualTo(4);
    }

    @Test
    void maxGapZero_onlyAdjacentMerged() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "A", 0, 2),
                holding(1, "B", 2, 2),
                holding(1, "C", 5, 2)
        ), 0);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).totalCount()).isEqualTo(4);
        assertThat(batches.get(1).totalCount()).isEqualTo(2);
    }

    @Test
    void outOfOrderInput_sortedByAddress() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "C", 4, 2),
                holding(1, "A", 0, 2),
                holding(1, "B", 2, 2)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 3));
        assertThat(batches).hasSize(1);
        List<ModbusBatchReadPlanner.PointSlice> slices = batches.get(0).slices();
        assertThat(slices.get(0).point().getPointName()).isEqualTo("A");
        assertThat(slices.get(1).point().getPointName()).isEqualTo("B");
        assertThat(slices.get(2).point().getPointName()).isEqualTo("C");
    }

    // ---------- 位型功能码（FC01 线圈 / FC02 离散输入）：不参与合并 ----------

    @Test
    void coils_notMerged_eachBecomesSingleBatch() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                coil(1, "C6", 6),
                coil(1, "C7", 7)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 1));
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(b -> {
            assertThat(b.totalCount()).isEqualTo(1);
            assertThat(b.slices()).hasSize(1);
            assertThat(b.slices().get(0).offsetInBatch()).isZero();
        });
    }

    @Test
    void discreteInputs_notMerged_eachBecomesSingleBatch() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                discrete(1, "D0", 0),
                discrete(1, "D1", 1),
                discrete(1, "D2", 2)
        ), 5);

        List<ReadBatch> batches = grouped.get(new GroupKey(1, 2));
        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(b -> assertThat(b.totalCount()).isEqualTo(1));
    }

    @Test
    void holdingRegisters_stillMerged_control() {
        Map<GroupKey, List<ReadBatch>> grouped = ModbusBatchReadPlanner.planGrouped(List.of(
                holding(1, "H0", 0, 2),
                holding(1, "H1", 2, 2)
        ), 5);

        assertThat(grouped.get(new GroupKey(1, 3))).hasSize(1);
        assertThat(grouped.get(new GroupKey(1, 3)).get(0).totalCount()).isEqualTo(4);
    }
}
