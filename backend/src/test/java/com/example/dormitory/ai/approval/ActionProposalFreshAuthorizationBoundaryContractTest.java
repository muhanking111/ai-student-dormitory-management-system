package com.example.dormitory.ai.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionProposalFreshAuthorizationBoundaryContractTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "example", "dormitory", "ai", "approval",
            "ActionProposalService.java");

    @ParameterizedTest(name = "{0} uses the commit-then-hide fresh authorization boundary")
    @MethodSource("freshMutationPaths")
    void everyFreshAuthorizedMutationUsesTheUnifiedTransactionBoundary(
            String operation,
            String sectionStart,
            String sectionEnd) throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf(sectionStart);
        int end = source.indexOf(sectionEnd, start + sectionStart.length());

        assertTrue(start >= 0 && end > start, operation + " 源码边界不存在");
        assertTrue(source.substring(start, end).contains("freshAuthorizationTransaction(() ->"),
                operation + " 必须使用统一 fresh-RBAC 事务边界");
    }

    @Test
    void unifiedBoundaryCatchesTheDenialMarkerInsideRequiredAndRejectsOuterTransactions() throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("private <T> T freshAuthorizationTransaction");
        int end = source.indexOf("private Set<String> currentPermissions", start);
        String helper = source.substring(start, end);

        assertTrue(helper.contains("TransactionSynchronizationManager.isActualTransactionActive()"));
        assertTrue(helper.contains("transactions.required(() ->"));
        assertTrue(helper.contains("catch (FreshAuthorizationDeniedException denial)"));
        assertTrue(helper.indexOf("outcome.denial().hiddenFailure()")
                > helper.indexOf("transactions.required(() ->"));
    }

    private static Stream<Arguments> freshMutationPaths() {
        return Stream.of(
                Arguments.of("approve", "public ProposalView approve(", "public ProposalView reject("),
                Arguments.of("reject", "public ProposalView reject(", "public ProposalView reconfirm("),
                Arguments.of("reconfirm", "public ProposalView reconfirm(", "public ProposalView get("),
                Arguments.of("executeLease", "private void executeLease(", "private void persistExecutionFailure("));
    }
}
