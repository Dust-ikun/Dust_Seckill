package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.core.DiagnosisStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ai_diagnosis_task} 的一行（SPEC 第 14.1 节）。
 *
 * <p>它是一个纯数据载体，不含任何判定逻辑 —— 所有关于「这条告警该不该并进这个任务」
 * 的推理都在 {@code IncidentAggregator} 里，因为那部分必须能<b>脱离数据库</b>被单测。
 * 把判定逻辑写进 row 对象是这类代码最常见的一步走偏：它会让「窗口边界」
 * 这类问题只能在起了 MySQL 之后才测得出来。
 *
 * <p>字段与建表语句逐列对应，含两处补强（{@code alertId} / {@code alertCount}，
 * 见 {@code schema-ai-monitor.sql} 的 ★ 说明）。
 *
 * @param id          自增主键（{@code ai_tool_execution.task_id} 外键指向它）
 * @param incidentId  事故编号（唯一键，SPEC 第 14.1 节）
 * @param serviceName 服务名
 * @param alertType   异常类型
 * @param severity    严重级别
 * @param status      状态机（SPEC 第 13 节）
 * @param startTime   异常开始时间（来自 Alertmanager 的 startsAt）
 * @param endTime     异常结束时间（来自 resolved 通知）；仍活跃时为 {@code null}
 * @param alertId     首条触发本任务的告警 id
 * @param alertCount  被聚合进本任务的告警条数
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 */
public record DiagnosisTaskRow(
        long id,
        String incidentId,
        String serviceName,
        String alertType,
        String severity,
        DiagnosisStatus status,
        Instant startTime,
        Instant endTime,
        String alertId,
        int alertCount,
        Instant createdAt,
        Instant updatedAt
) {

    /** 是否已结束（见 {@link DiagnosisStatus#terminal()} 的注释：它决定能不能再并告警进来） */
    public boolean terminal() {
        return status != null && status.terminal();
    }

    /** 给查询 API 用的形态（字段名与 SPEC 第 14.1 节一致，便于前端与 Grafana 直接读） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(16);
        map.put("id", id);
        map.put("incidentId", incidentId);
        map.put("serviceName", serviceName);
        map.put("alertType", alertType);
        map.put("severity", severity);
        map.put("status", status == null ? null : status.name());
        map.put("startTime", startTime == null ? null : startTime.toString());
        map.put("endTime", endTime == null ? null : endTime.toString());
        map.put("alertId", alertId);
        map.put("alertCount", alertCount);
        map.put("active", endTime == null);
        map.put("createdAt", createdAt == null ? null : createdAt.toString());
        map.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        return map;
    }
}
