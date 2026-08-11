package com.example.dormitory.ai.infrastructure.persistence;

import com.example.dormitory.ai.risk.RiskScanScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 风险扫描命令与 worker 状态的 MySQL 权威仓储。 */
@Repository
public class JdbcRiskScanRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "PARTIAL", "FAILED", "NEEDS_REVIEW");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRiskScanRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Scan create(
            long actor,
            List<String> requestedRoles,
            List<String> requestedPermissions,
            String permissionDigest,
            RiskScanScope scope,
            Instant now) {
        if (actor < 1 || requestedRoles == null || requestedPermissions == null || scope == null
                || scope.effectiveSubjectUserId() != actor || permissionDigest == null
                || !permissionDigest.matches("[0-9a-f]{64}") || now == null) {
            throw new IllegalArgumentException("风险扫描创建参数不合法");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_risk_scan(public_id,requested_by_user_id,actor_kind,"
                        + "service_principal_code,initiated_by_user_id,effective_subject_user_id,"
                        + "requested_role_codes_text,requested_permission_codes_text,requested_scope_text,"
                        + "permission_digest,state,provider_versions_text,unavailable_providers_text,"
                        + "created_at,updated_at) VALUES (?,?,'SERVICE','risk-scan',?,?,?,?,?,?,"
                        + "'QUEUED','[]','[]',?,?)",
                id, actor, actor, actor, write(requestedRoles), write(requestedPermissions), write(scope),
                permissionDigest, Timestamp.from(now), Timestamp.from(now));
        return get(actor, id).orElseThrow();
    }

    /**
     * 终态是幂等 receipt；QUEUED/RUNNING 均可由当前唯一 outbox lease 重入，支持进程恢复。
     */
    @Transactional
    public Optional<ScanWork> begin(String id, Instant now) {
        ScanWork current = work(id).orElse(null);
        if (current == null || TERMINAL.contains(current.state())) return Optional.ofNullable(current);
        int changed = jdbc.update("UPDATE ai_risk_scan SET state='RUNNING',started_at=COALESCE(started_at,?),"
                        + "version=version+1,updated_at=? WHERE public_id=? AND state IN ('QUEUED','RUNNING') "
                        + "AND version=?",
                Timestamp.from(now), Timestamp.from(now), id, current.version());
        if (changed != 1) throw new IllegalStateException("风险扫描 claim CAS 冲突");
        return work(id);
    }

    public Optional<ScanWork> work(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("SELECT * FROM ai_risk_scan WHERE public_id=?", this::mapWork, id)
                .stream().findFirst();
    }

    public void complete(String id, List<String> versions, List<String> unavailable,
                         int signals, int cases, int duplicates, Instant now) {
        int changed = jdbc.update("UPDATE ai_risk_scan SET state=?,provider_versions_text=?,"
                        + "unavailable_providers_text=?,signal_count=?,case_count=?,duplicate_count=?,"
                        + "error_code=?,finished_at=?,version=version+1,updated_at=? "
                        + "WHERE public_id=? AND state='RUNNING'",
                unavailable.isEmpty() ? "SUCCEEDED" : "PARTIAL", write(versions), write(unavailable),
                signals, cases, duplicates, unavailable.isEmpty() ? null : "RISK_PROVIDER_UNAVAILABLE",
                Timestamp.from(now), Timestamp.from(now), id);
        if (changed != 1) throw new IllegalStateException("风险扫描完成 CAS 冲突");
    }

    public void fail(String id, String errorCode, boolean needsReview, Instant now) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}") || now == null) {
            throw new IllegalArgumentException("风险扫描失败状态不合法");
        }
        int changed = jdbc.update("UPDATE ai_risk_scan SET state=?,error_code=?,finished_at=?,"
                        + "version=version+1,updated_at=? WHERE public_id=? "
                        + "AND state IN ('QUEUED','RUNNING')",
                needsReview ? "NEEDS_REVIEW" : "FAILED", errorCode,
                Timestamp.from(now), Timestamp.from(now), id);
        if (changed == 0 && work(id).map(ScanWork::state).filter(TERMINAL::contains).isEmpty()) {
            throw new IllegalStateException("风险扫描失败 CAS 冲突");
        }
    }

    public Optional<Scan> get(long actor, String id) {
        return jdbc.query("SELECT * FROM ai_risk_scan WHERE public_id=? AND requested_by_user_id=?",
                (rs, row) -> mapScan(rs), id, actor).stream().findFirst();
    }

    private ScanWork mapWork(ResultSet rs, int row) throws SQLException {
        return new ScanWork(rs.getString("public_id"), rs.getLong("requested_by_user_id"),
                readStrings(rs.getString("requested_role_codes_text")),
                readStrings(rs.getString("requested_permission_codes_text")),
                rs.getString("permission_digest"), readScope(rs.getString("requested_scope_text")),
                rs.getString("state"), rs.getLong("version"));
    }

    private Scan mapScan(ResultSet rs) throws SQLException {
        return new Scan(rs.getString("public_id"), rs.getString("state"),
                readStrings(rs.getString("provider_versions_text")),
                readStrings(rs.getString("unavailable_providers_text")),
                rs.getInt("signal_count"), rs.getInt("case_count"),
                rs.getInt("duplicate_count"), rs.getString("error_code"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("风险扫描快照序列化失败", exception);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return value == null ? List.of() : List.copyOf(json.readValue(value, STRINGS));
        } catch (Exception exception) {
            throw new IllegalStateException("风险扫描授权快照反序列化失败", exception);
        }
    }

    private RiskScanScope readScope(String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalStateException("风险扫描缺少 scope 快照");
            return json.readValue(value, RiskScanScope.class);
        } catch (Exception exception) {
            throw new IllegalStateException("风险扫描 scope 快照反序列化失败", exception);
        }
    }

    public record Scan(String id, String state, List<String> providerVersions,
                       List<String> unavailableProviders, int signalCount,
                       int caseCount, int duplicateCount, String errorCode) {
        public Scan {
            providerVersions = List.copyOf(providerVersions);
            unavailableProviders = List.copyOf(unavailableProviders);
        }
    }

    public record ScanWork(
            String id,
            long initiatedByUserId,
            List<String> requestedRoleCodes,
            List<String> requestedPermissionCodes,
            String permissionDigest,
            RiskScanScope requestedScope,
            String state,
            long version) {
        public ScanWork {
            requestedRoleCodes = List.copyOf(requestedRoleCodes);
            requestedPermissionCodes = List.copyOf(requestedPermissionCodes);
        }
    }
}
