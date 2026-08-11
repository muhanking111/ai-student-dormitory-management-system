package com.example.dormitory.ai.audit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 将每日审计链头压缩为确定性 Merkle root，并通过受控 Port 写入只追加介质。 */
public final class JdbcAiAuditAnchorService {

    static final String INTEGRITY_ALGORITHM = "SHA-256-MERKLE";
    static final String CANONICALIZATION_VERSION = "v1";

    private final JdbcTemplate jdbcTemplate;
    private final AuditAnchorSink sink;
    private final ZoneId anchorZone;
    private final ChainIntegrityVerifier chainIntegrityVerifier;
    private final TransactionTemplate preparationTransaction;

    public JdbcAiAuditAnchorService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AuditAnchorSink sink,
            ChainIntegrityVerifier chainIntegrityVerifier) {
        this(jdbcTemplate, transactionManager, sink, ZoneId.of("UTC"), chainIntegrityVerifier);
    }

    public JdbcAiAuditAnchorService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            AuditAnchorSink sink,
            ZoneId anchorZone,
            ChainIntegrityVerifier chainIntegrityVerifier) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.sink = Objects.requireNonNull(sink);
        this.anchorZone = Objects.requireNonNull(anchorZone);
        this.chainIntegrityVerifier = Objects.requireNonNull(chainIntegrityVerifier);
        this.preparationTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.preparationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public AnchorRecord anchor(LocalDate anchorDate, String chainScope) {
        validate(anchorDate, chainScope);
        AnchorPreparation preparation = Objects.requireNonNull(preparationTransaction.execute(
                status -> prepare(anchorDate, chainScope)));
        if (preparation.completed() != null) return preparation.completed();

        String rootHash = preparation.rootHash();
        long eventCount = preparation.eventCount();
        int keyVersion = preparation.integrityKeyVersion();
        try {
            AuditAnchorSink.Receipt receipt = sink.append(new AuditAnchorSink.AnchorRequest(
                    anchorDate, chainScope, rootHash, eventCount, INTEGRITY_ALGORITHM,
                    keyVersion, CANONICALIZATION_VERSION));
            validateReceipt(receipt);
            int updated = jdbcTemplate.update(
                    "UPDATE ai_audit_anchor SET external_sink_code = ?, external_receipt_hash = ?, "
                            + "state = 'ANCHORED', anchored_at = CURRENT_TIMESTAMP "
                            + "WHERE anchor_date = ? AND chain_scope = ? AND root_hash = ? "
                            + "AND state IN ('PENDING', 'FAILED')",
                    receipt.sinkCode(), receipt.receiptHash().toLowerCase(Locale.ROOT),
                    anchorDate, chainScope, rootHash);
            if (updated != 1) throw new AuditAnchorIntegrityException("audit anchor 状态竞争失败");
            return Objects.requireNonNull(find(anchorDate, chainScope));
        } catch (AuditAnchorIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    "UPDATE ai_audit_anchor SET state = 'FAILED', anchored_at = NULL, "
                            + "external_receipt_hash = NULL WHERE anchor_date = ? AND chain_scope = ? "
                            + "AND state <> 'ANCHORED'",
                    anchorDate, chainScope);
            throw new AuditAnchorUnavailableException(exception);
        }
    }

    private AnchorPreparation prepare(LocalDate anchorDate, String chainScope) {
        requireValidChains(chainScope, loadPersistedChains(chainScope));
        List<Head> heads = loadHeads(chainScope, anchorDate);
        if (heads.isEmpty()) throw new IllegalStateException("没有可外锚的审计事件");
        String rootHash = merkleRoot(heads.stream().map(this::leafHash).toList());
        long eventCount = heads.stream().mapToLong(Head::lastSequence).sum();
        int keyVersion = heads.stream().mapToInt(Head::integrityKeyVersion).max().orElseThrow();

        AnchorRecord existing = find(anchorDate, chainScope);
        if (existing != null) {
            if (!existing.rootHash().equals(rootHash) || existing.eventCount() != eventCount) {
                throw new AuditAnchorIntegrityException("同一日期和 scope 的 audit root 已发生变化");
            }
            if ("ANCHORED".equals(existing.state())) {
                return new AnchorPreparation(rootHash, eventCount, keyVersion, existing);
            }
        } else {
            insertPending(anchorDate, chainScope, rootHash, eventCount, keyVersion);
        }
        return new AnchorPreparation(rootHash, eventCount, keyVersion, null);
    }

    public AnchorRecord find(LocalDate anchorDate, String chainScope) {
        List<AnchorRecord> rows = jdbcTemplate.query(
                "SELECT anchor_date, chain_scope, root_hash, event_count, integrity_alg, "
                        + "integrity_key_version, canonicalization_version, external_sink_code, "
                        + "external_receipt_hash, state FROM ai_audit_anchor "
                        + "WHERE anchor_date = ? AND chain_scope = ?",
                (resultSet, rowNum) -> new AnchorRecord(
                        resultSet.getObject("anchor_date", LocalDate.class),
                        resultSet.getString("chain_scope"),
                        resultSet.getString("root_hash"),
                        resultSet.getLong("event_count"),
                        resultSet.getString("integrity_alg"),
                        resultSet.getInt("integrity_key_version"),
                        resultSet.getString("canonicalization_version"),
                        resultSet.getString("external_sink_code"),
                        resultSet.getString("external_receipt_hash"),
                        resultSet.getString("state")),
                anchorDate, chainScope);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Head> loadHeads(String chainScope, LocalDate anchorDate) {
        Timestamp cutoff = Timestamp.from(anchorDate.plusDays(1).atStartOfDay(anchorZone).toInstant());
        return jdbcTemplate.query(
                "SELECT e.aggregate_type,e.aggregate_public_id,e.sequence_no AS last_sequence_no,"
                        + "e.event_hash AS last_event_hash,e.integrity_key_version "
                        + "FROM ai_audit_event e JOIN (SELECT aggregate_type,aggregate_public_id,"
                        + "MAX(sequence_no) AS max_sequence FROM ai_audit_event "
                        + "WHERE chain_scope=? AND occurred_at<? GROUP BY aggregate_type,aggregate_public_id) tail "
                        + "ON tail.aggregate_type=e.aggregate_type AND tail.aggregate_public_id=e.aggregate_public_id "
                        + "AND tail.max_sequence=e.sequence_no WHERE e.chain_scope=? "
                        + "ORDER BY e.aggregate_type,e.aggregate_public_id",
                (resultSet, rowNum) -> new Head(
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_public_id"),
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getString("last_event_hash"),
                        resultSet.getInt("integrity_key_version")),
                chainScope, cutoff, chainScope);
    }

    private List<ChainIdentity> loadPersistedChains(String chainScope) {
        return jdbcTemplate.query(
                "SELECT aggregate_type,aggregate_public_id FROM ("
                        + "SELECT aggregate_type,aggregate_public_id FROM ai_audit_chain_head "
                        + "WHERE chain_scope=? UNION SELECT aggregate_type,aggregate_public_id "
                        + "FROM ai_audit_event WHERE chain_scope=?"
                        + ") scoped_chains ORDER BY aggregate_type,aggregate_public_id",
                (resultSet, rowNum) -> new ChainIdentity(
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_public_id")),
                chainScope,
                chainScope);
    }

    private void insertPending(
            LocalDate date,
            String scope,
            String rootHash,
            long eventCount,
            int keyVersion) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO ai_audit_anchor "
                            + "(anchor_date, chain_scope, root_hash, event_count, integrity_alg, "
                            + "integrity_key_version, canonicalization_version, external_sink_code, state, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', 'PENDING', CURRENT_TIMESTAMP)",
                    date, scope, rootHash, eventCount, INTEGRITY_ALGORITHM,
                    keyVersion, CANONICALIZATION_VERSION);
        } catch (DuplicateKeyException duplicate) {
            AnchorRecord concurrent = find(date, scope);
            if (concurrent == null || !concurrent.rootHash().equals(rootHash)) {
                throw new AuditAnchorIntegrityException("并发 audit root 不一致");
            }
        }
    }

    private void requireValidChains(String chainScope, List<ChainIdentity> chains) {
        try {
            for (ChainIdentity chain : chains) {
                chainIntegrityVerifier.verify(
                        chainScope,
                        chain.aggregateType(),
                        chain.aggregatePublicId());
            }
        } catch (RuntimeException exception) {
            throw new AuditAnchorIntegrityException("被锚审计链完整性校验失败");
        }
    }

    private String leafHash(Head head) {
        ByteBuffer sequence = ByteBuffer.allocate(Long.BYTES).putLong(head.lastSequence());
        ByteBuffer keyVersion = ByteBuffer.allocate(Integer.BYTES).putInt(head.integrityKeyVersion());
        return sha256(concat(
                lengthPrefixed(head.aggregateType()),
                lengthPrefixed(head.aggregatePublicId()),
                sequence.array(),
                HexFormat.of().parseHex(head.lastEventHash()),
                keyVersion.array()));
    }

    static String merkleRoot(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("Merkle leaf 不能为空");
        }
        List<byte[]> level = leafHashes.stream()
                .map(value -> HexFormat.of().parseHex(value))
                .map(byte[]::clone)
                .toList();
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int index = 0; index < level.size(); index += 2) {
                byte[] left = level.get(index);
                byte[] right = index + 1 < level.size() ? level.get(index + 1) : left;
                next.add(HexFormat.of().parseHex(sha256(concat(left, right))));
            }
            level = List.copyOf(next);
        }
        return HexFormat.of().formatHex(level.getFirst());
    }

    private static byte[] lengthPrefixed(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return concat(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array(), bytes);
    }

    private static byte[] concat(byte[]... values) {
        int length = java.util.Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] value : values) buffer.put(value);
        return buffer.array();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void validate(LocalDate date, String scope) {
        if (date == null || scope == null || !scope.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("审计外锚参数不合法");
        }
    }

    private void validateReceipt(AuditAnchorSink.Receipt receipt) {
        if (receipt == null || receipt.sinkCode() == null
                || !receipt.sinkCode().matches("[a-z0-9._-]{1,64}")
                || receipt.receiptHash() == null
                || !receipt.receiptHash().matches("[0-9a-fA-F]{64}")) {
            throw new AuditAnchorIntegrityException("外锚 receipt 合同不合法");
        }
    }

    private record Head(
            String aggregateType,
            String aggregatePublicId,
            long lastSequence,
            String lastEventHash,
            int integrityKeyVersion) {
    }

    private record ChainIdentity(String aggregateType, String aggregatePublicId) {
    }

    private record AnchorPreparation(
            String rootHash,
            long eventCount,
            int integrityKeyVersion,
            AnchorRecord completed) {
    }

    @FunctionalInterface
    public interface ChainIntegrityVerifier {
        void verify(String chainScope, String aggregateType, String aggregatePublicId);
    }

    public record AnchorRecord(
            LocalDate anchorDate,
            String chainScope,
            String rootHash,
            long eventCount,
            String integrityAlgorithm,
            int integrityKeyVersion,
            String canonicalizationVersion,
            String externalSinkCode,
            String externalReceiptHash,
            String state) {
    }
}
