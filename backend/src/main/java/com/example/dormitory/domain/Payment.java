package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("payment")
public class Payment extends AuditableEntity {
    @TableId
    private Long id;
    private String studentNo;
    private String name;
    private String type;
    private BigDecimal amountDue;
    private BigDecimal amountPaid;
    private String status;
    private String deadline;
}
