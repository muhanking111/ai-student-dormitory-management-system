package com.example.dormitory;

import com.example.dormitory.ai.infrastructure.fake.InMemoryVersionedObjectStorage;
import com.example.dormitory.ai.knowledge.KnowledgeFileScanner;
import com.example.dormitory.ai.knowledge.KnowledgeUploadSessionRepository;
import com.example.dormitory.ai.knowledge.UploadSessionService;
import com.example.dormitory.ai.port.ObjectStoragePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ActiveProfiles("real-it")
@SpringBootTest
@ContextConfiguration(initializers = RealInfrastructureIT.RootEnvInitializer.class)
class AiUploadRealInfrastructureIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeUploadSessionRepository uploadSessions;
    @Autowired ObjectStoragePort objectStorage;
    @Autowired KnowledgeFileScanner scanner;

    @Test
    void mysqlRestoresUploadStateAndAllowsIdenticalBodiesWithRandomQuarantineKeys() {
        long owner = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class,
                RealInfrastructureIT.RootEnvInitializer.rootEnvironment().get("BOOTSTRAP_ADMIN_USERNAME"));
        String sourcePublicId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_knowledge_source(public_id,name,source_type,owner_user_id,classification,"
                        + "permission_match_mode,object_store_code,acl_version,status,created_at,updated_at) "
                        + "VALUES(?,'上传真实库测试','UPLOAD',?,'L1','ANY','real-it',1,'ACTIVE',"
                        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                sourcePublicId, owner);
        long sourceId = jdbc.queryForObject("SELECT id FROM ai_knowledge_source WHERE public_id=?",
                Long.class, sourcePublicId);
        byte[] content = ("真实 MySQL 上传恢复 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String sha = InMemoryVersionedObjectStorage.sha256(content);
        UploadSessionService creator = service();
        UploadSessionService.UploadSession first = creator.create(sourceId, owner, sourcePublicId, sha,
                content.length, "text/plain", Instant.now().plusSeconds(300));
        UploadSessionService.UploadSession second = creator.create(sourceId, owner, sourcePublicId, sha,
                content.length, "text/plain", Instant.now().plusSeconds(300));
        try {
            assertNotEquals(first.quarantineObjectKey(), second.quarantineObjectKey());
            UploadSessionService restarted = service();
            for (UploadSessionService.UploadSession upload : java.util.List.of(first, second)) {
                restarted.upload(owner, upload.publicId(), new ByteArrayInputStream(content), content.length);
                restarted.finalizeUpload(owner, sourcePublicId, upload.publicId());
            }
            assertEquals(restarted.promoteFinalized(owner, sourcePublicId, first.publicId()).objectReference(),
                    service().promoteFinalized(owner, sourcePublicId, second.publicId()).objectReference());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT quarantine_object_key) "
                    + "FROM ai_upload_session WHERE public_id IN (?,?)", Integer.class,
                    first.publicId(), second.publicId()));
        } finally {
            jdbc.update("DELETE FROM ai_upload_session WHERE public_id IN (?,?)", first.publicId(), second.publicId());
            jdbc.update("DELETE FROM ai_knowledge_source WHERE public_id=?", sourcePublicId);
        }
    }

    private UploadSessionService service() {
        return new UploadSessionService(objectStorage, uploadSessions, scanner, Clock.systemUTC());
    }
}
