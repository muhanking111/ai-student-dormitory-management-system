package com.example.dormitory.ai.infrastructure.business;

import java.util.List;

public interface RepairCandidateProvider {
    List<Candidate> enabledRepairers();

    record Candidate(long userId) {
        public Candidate {
            if (userId < 1) throw new IllegalArgumentException("维修候选人 ID 不合法");
        }
    }
}
