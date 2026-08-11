package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("repair_record")
public class RepairRecord extends AuditableEntity {
    @TableId
    private Long id;
    private Long repairOrderId;
    private String handler;
    private String content;
    private BigDecimal cost;
    private String status;
    private LocalDateTime handledAt;
    private Long operatorUserId;
}
