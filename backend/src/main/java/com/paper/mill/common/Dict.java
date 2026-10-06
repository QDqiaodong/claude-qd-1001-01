package com.paper.mill.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 造纸厂词典与业务规则参数表：浆种与浆池状态、纸机状态、纸卷等级与状态流转、
 * 切纸订单状态流转、成品件状态，以及定量偏差 / 切裁余量等各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 浆池 ----------------
    public static final List<String> PULP_TYPES = List.of("针叶浆", "阔叶浆", "废纸浆", "竹浆");

    /** 在用 → 检修 → 在用；停用是终态之外的人工封存，可再回到检修 */
    private static final Map<String, List<String>> CHEST_NEXT = Map.of(
            "在用", List.of("检修", "停用"),
            "检修", List.of("在用", "停用"),
            "停用", List.of("检修"));

    public static final List<String> CHEST_STATUSES = List.of("在用", "检修", "停用");

    // ---------------- 纸机 ----------------
    public static final List<String> MACHINE_STATUSES = List.of("运行", "停机", "检修");

    private static final Map<String, List<String>> MACHINE_NEXT = Map.of(
            "运行", List.of("停机", "检修"),
            "停机", List.of("运行", "检修"),
            "检修", List.of("停机"));

    // ---------------- 纸卷 ----------------
    public static final List<String> ROLL_GRADES = List.of("一等", "二等", "等外");

    /** 待检 → 可用 / 报废；可用 → 已切完 / 报废。只有「可用」的纸卷能上切纸机 */
    private static final Map<String, List<String>> ROLL_NEXT = Map.of(
            "待检", List.of("可用", "报废"),
            "可用", List.of("已切完", "报废"),
            "已切完", List.of(),
            "报废", List.of());

    public static final List<String> ROLL_STATUSES = List.of("待检", "可用", "已切完", "报废");

    // ---------------- 切纸订单 ----------------
    /** 待排产 → 切裁中 → 已完成，逐级推进，不可跳级；已取消为旁路 */
    public static final List<String> ORDER_FLOW = List.of("待排产", "切裁中", "已完成");

    public static final List<String> ORDER_STATUSES = List.of("待排产", "切裁中", "已完成", "已取消");

    // ---------------- 成品件 ----------------
    public static final List<String> BUNDLE_STATUSES = List.of("已入库", "已出库");

    public static final List<String> WAREHOUSE_ZONES = List.of("A区", "B区", "C区");

    // ---------------- 业务阈值 ----------------
    /** 定量偏差绝对值 <= 本值判一等（g/m2） */
    public static final int GRADE_TOP_DEVIATION = 2;

    /** 定量偏差绝对值 <= 本值判二等，超过判等外（g/m2） */
    public static final int GRADE_SECOND_DEVIATION = 5;

    /** 切纸订单目标定量与母卷定量允许的偏差上限（g/m2） */
    public static final int GRAMMAGE_TOLERANCE = 3;

    /** 单次补浆量上限（立方米），防止误操作一次灌满 */
    public static final BigDecimal MAX_FILL_M3 = BigDecimal.valueOf(50);

    /** 纸卷切裁后至少保留的余长（米），不足则整卷判为已切完 */
    public static final int MIN_REMAIN_LENGTH_M = 200;

    // ---------------- 取值方法 ----------------

    public static boolean isValidPulpType(String type) {
        return type != null && PULP_TYPES.contains(type);
    }

    public static boolean isValidChestStatus(String status) {
        return status != null && CHEST_STATUSES.contains(status);
    }

    /** 浆池状态流转：停用池不允许直接补浆，但可以先转检修 */
    public static boolean canTransferChest(String from, String to) {
        return CHEST_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidMachineStatus(String status) {
        return status != null && MACHINE_STATUSES.contains(status);
    }

    public static boolean canTransferMachine(String from, String to) {
        return MACHINE_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidRollGrade(String grade) {
        return grade != null && ROLL_GRADES.contains(grade);
    }

    public static boolean isValidRollStatus(String status) {
        return status != null && ROLL_STATUSES.contains(status);
    }

    public static boolean canTransferRoll(String from, String to) {
        return ROLL_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    /** 只有可用状态的纸卷才能上切纸机 */
    public static boolean isRollUsable(String status) {
        return "可用".equals(status);
    }

    public static boolean isValidOrderStatus(String status) {
        return status != null && ORDER_STATUSES.contains(status);
    }

    /** 订单状态只能逐级推进：待排产 → 切裁中 → 已完成 */
    public static boolean canTransferOrder(String from, String to) {
        int i = ORDER_FLOW.indexOf(from);
        return i >= 0 && i + 1 < ORDER_FLOW.size() && ORDER_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidBundleStatus(String status) {
        return status != null && BUNDLE_STATUSES.contains(status);
    }

    public static boolean isValidWarehouseZone(String zone) {
        return zone != null && WAREHOUSE_ZONES.contains(zone);
    }

    /**
     * 按定量偏差判等级：偏差 <= 2 一等，<= 5 二等，超过等外。
     *
     * @param grammage    实测定量
     * @param targetGsm   目标定量
     * @return 一等 / 二等 / 等外
     */
    public static String decideGrade(int grammage, int targetGsm) {
        int deviation = Math.abs(grammage - targetGsm);
        if (deviation <= GRADE_TOP_DEVIATION) {
            return "一等";
        }
        if (deviation <= GRADE_SECOND_DEVIATION) {
            return "二等";
        }
        return "等外";
    }
}
