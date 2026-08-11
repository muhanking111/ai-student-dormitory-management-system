package com.example.dormitory.ai.port;

import com.example.dormitory.ai.risk.RiskScanScope;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

/** 固定、actor-scoped 的运营风险事实读取端口；实现不得先读取范围外业务行再过滤。 */
public interface OperationalRiskReadPort {

    List<RepairBacklogFact> incompleteRepairs(RiskScanScope scope);

    List<RepeatRepairFact> repairsCreatedBetween(RiskScanScope scope, Instant from, Instant to);

    List<DormitoryConsistencyFact> dormitoryConsistency(RiskScanScope scope);

    List<FailedHygieneFact> failedHygieneChecks(RiskScanScope scope);

    List<PendingCheckInFact> pendingCheckInApplications(RiskScanScope scope);

    List<OverduePaymentFact> overduePayments(RiskScanScope scope);

    record RepairBacklogFact(long id, String status, Instant createdAt) { }

    record RepeatRepairFact(long id, String location, String type, Instant createdAt) { }

    record DormitoryConsistencyFact(
            long id,
            int beds,
            int occupied,
            int vacant,
            int actualBeds,
            int occupiedBeds,
            int activeCheckIns) { }

    record FailedHygieneFact(long dormitoryId, String result, int score, String inspectedOn) { }

    record PendingCheckInFact(long id, String status, Instant appliedAt) { }

    record OverduePaymentFact(long id, String status, String deadline, BigDecimal amountDue, BigDecimal amountPaid) { }
}
