package com.example.dormitory.ai.infrastructure.business;

import com.example.dormitory.service.RbacService;

import java.util.List;

public final class RbacRepairCandidateProvider implements RepairCandidateProvider {
    private final RbacService rbacService;

    public RbacRepairCandidateProvider(RbacService rbacService) {
        this.rbacService = java.util.Objects.requireNonNull(rbacService);
    }

    @Override
    public List<Candidate> enabledRepairers() {
        return rbacService.users(1, 100, null, true).records().stream()
                .filter(user -> user.roles().stream().anyMatch(role -> "REPAIRER".equals(role.code())))
                .map(user -> new Candidate(user.id()))
                .toList();
    }
}
