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
@TableName("payment_record")
public class PaymentRecord extends AuditableEntity {
    @TableId
    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private String method;
    private LocalDateTime paidAt;
    private Long operatorUserId;
}
