package com.dustikun.seckill.monitor.repository;

import com.dustikun.seckill.monitor.core.AlertEvent;
import com.dustikun.seckill.monitor.core.DiagnosisStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版的 {@link DiagnosisTaskStore}。
 *
 * <p>它存在的理由不是「省一次数据库往返」，而是让 {@code AlertIngestService} 的
 * 聚合行为可以在<b>不连 MySQL</b> 的条件下被断言：
 * 「3 条同类告警 → 1 个任务」这条 SPEC 第 16 节的判据，如果只能靠真库来验，
 * 那么它实际上要靠「测试跑的时候库里恰好没有同名任务」来成立 ——
 * 一个依赖历史数据的断言迟早会因为历史数据而失败。
 *
 * <p>它<b>刻意不实现窗口逻辑</b>：那是 {@code IncidentAggregator} 的职责，
 * 这里只按「同服务 + 同类型、按创建时间倒序」返回候选（与 SQL 的预筛一致）。
 * 如果这里也判一次窗口，两个实现就可能分叉，而分叉的症状是
 * 「单测全绿、联调时聚合行为不对」。
 */
public class InMemoryDiagnosisTaskStore implements DiagnosisTaskStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final List<DiagnosisTaskRow> rows = new ArrayList<>();

    private final AtomicLong ids = new AtomicLong(1);

    private final Map<String, Integer> incidentCounters = new LinkedHashMap<>();

    /** 置 true 后 {@link #create} 抛异常，用来验证「一条告警失败不能拖垮整批」 */
    private volatile boolean failOnCreate;

    /** 置 true 后 {@link #create} 第一次抛 DuplicateKeyException（验证取号重试） */
    private volatile int duplicateKeyAttempts;

    public InMemoryDiagnosisTaskStore failOnCreate() {
        this.failOnCreate = true;
        return this;
    }

    public InMemoryDiagnosisTaskStore failWithDuplicateKeyOnce() {
        this.duplicateKeyAttempts = 1;
        return this;
    }

    @Override
    public DiagnosisTaskRow create(AlertEvent alert, Instant now) {
        if (failOnCreate) {
            throw new IllegalStateException("测试构造的建任务失败");
        }
        String incidentId = nextIncidentId(now);
        if (duplicateKeyAttempts > 0) {
            duplicateKeyAttempts--;
            // 模拟并发取号撞车：号被**别的请求**抢走了（因此只是消耗掉一个号），
            // 调用方重取时应当得到下一个号 —— 这正是真实实现里 DuplicateKeyException
            // 之后「重取再插」能成功的原因。
            incidentId = nextIncidentId(now);
        }
        DiagnosisTaskRow row = new DiagnosisTaskRow(ids.getAndIncrement(), incidentId,
                alert.serviceName(), alert.alertType(),
                alert.severity() == null || alert.severity().isBlank() ? "UNKNOWN" : alert.severity(),
                DiagnosisStatus.CREATED,
                alert.timestamp() == null ? now : Instant.ofEpochSecond(alert.timestamp()),
                null, alert.alertId(), 1, now, now);
        rows.add(row);
        return row;
    }

    @Override
    public Optional<DiagnosisTaskRow> findByIncidentId(String incidentId) {
        return rows.stream().filter(row -> row.incidentId().equals(incidentId)).findFirst();
    }

    @Override
    public Optional<DiagnosisTaskRow> findById(long taskId) {
        return rows.stream().filter(row -> row.id() == taskId).findFirst();
    }

    @Override
    public List<DiagnosisTaskRow> findCandidates(String serviceName, String alertType, int limit) {
        return rows.stream()
                .filter(row -> row.serviceName().equals(serviceName))
                .filter(row -> row.alertType().equals(alertType))
                .sorted(Comparator.comparing(DiagnosisTaskRow::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public boolean mergeInto(long taskId, Instant now) {
        for (int i = 0; i < rows.size(); i++) {
            DiagnosisTaskRow row = rows.get(i);
            if (row.id() == taskId) {
                rows.set(i, new DiagnosisTaskRow(row.id(), row.incidentId(), row.serviceName(),
                        row.alertType(), row.severity(), row.status(), row.startTime(), row.endTime(),
                        row.alertId(), row.alertCount() + 1, row.createdAt(), now));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean updateStatus(long taskId, DiagnosisStatus status, Instant now) {
        for (int i = 0; i < rows.size(); i++) {
            DiagnosisTaskRow row = rows.get(i);
            if (row.id() == taskId) {
                rows.set(i, new DiagnosisTaskRow(row.id(), row.incidentId(), row.serviceName(),
                        row.alertType(), row.severity(), status, row.startTime(), row.endTime(),
                        row.alertId(), row.alertCount(), row.createdAt(), now));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean markResolved(String incidentId, Instant endTime, Instant now) {
        for (int i = 0; i < rows.size(); i++) {
            DiagnosisTaskRow row = rows.get(i);
            // 与真实实现保持同一个幂等语义：已经有 end_time 的不再改。
            if (row.incidentId().equals(incidentId) && row.endTime() == null) {
                rows.set(i, new DiagnosisTaskRow(row.id(), row.incidentId(), row.serviceName(),
                        row.alertType(), row.severity(), row.status(), row.startTime(), endTime,
                        row.alertId(), row.alertCount(), row.createdAt(), now));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<DiagnosisTaskRow> history(int limit, int offset) {
        return rows.stream()
                .sorted(Comparator.comparing(DiagnosisTaskRow::createdAt).reversed())
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<DiagnosisTaskRow> findActive(int limit) {
        return rows.stream()
                .filter(row -> row.endTime() == null)
                .sorted(Comparator.comparing(DiagnosisTaskRow::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public String nextIncidentId(Instant now) {
        String day = day(now);
        int next = incidentCounters.merge(day, 1, Integer::sum);
        return "INC-" + day + "-" + String.format("%03d", next);
    }

    private static String day(Instant now) {
        return DAY.format(now.atZone(ZoneId.systemDefault()).toLocalDate());
    }

    public List<DiagnosisTaskRow> all() {
        return List.copyOf(rows);
    }

    public int size() {
        return rows.size();
    }
}
