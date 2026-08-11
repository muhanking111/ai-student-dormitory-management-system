package com.example.dormitory.ai.infrastructure.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** PD-05 确认前的建议初始阈值；均可通过 dormitory.ai.risk.thresholds 覆盖。 */
@Component
@ConfigurationProperties(prefix = "dormitory.ai.risk.thresholds")
public class OperationalRiskThresholds {

    private int repairBacklogHours = 72;
    private int repeatRepairCount = 3;
    private int repeatRepairWindowDays = 30;
    private int longPendingHours = 48;

    public OperationalRiskThresholds() { }

    public OperationalRiskThresholds(
            int repairBacklogHours,
            int repeatRepairCount,
            int repeatRepairWindowDays,
            int longPendingHours) {
        this.repairBacklogHours = repairBacklogHours;
        this.repeatRepairCount = repeatRepairCount;
        this.repeatRepairWindowDays = repeatRepairWindowDays;
        this.longPendingHours = longPendingHours;
        validate();
    }

    public int getRepairBacklogHours() { return repairBacklogHours; }
    public void setRepairBacklogHours(int value) { repairBacklogHours = positive(value, "repairBacklogHours"); }
    public int getRepeatRepairCount() { return repeatRepairCount; }
    public void setRepeatRepairCount(int value) { repeatRepairCount = positive(value, "repeatRepairCount"); }
    public int getRepeatRepairWindowDays() { return repeatRepairWindowDays; }
    public void setRepeatRepairWindowDays(int value) { repeatRepairWindowDays = positive(value, "repeatRepairWindowDays"); }
    public int getLongPendingHours() { return longPendingHours; }
    public void setLongPendingHours(int value) { longPendingHours = positive(value, "longPendingHours"); }

    public void validate() {
        positive(repairBacklogHours, "repairBacklogHours");
        positive(repeatRepairCount, "repeatRepairCount");
        positive(repeatRepairWindowDays, "repeatRepairWindowDays");
        positive(longPendingHours, "longPendingHours");
    }

    private int positive(int value, String field) {
        if (value < 1 || value > 8_760) throw new IllegalArgumentException(field + " 阈值不合法");
        return value;
    }
}
