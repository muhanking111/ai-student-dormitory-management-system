package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("repair_order")
public class RepairOrder extends AuditableEntity {
    @TableId
    private Long id;
    private String code;
    private String reporter;
    private String location;
    private String type;
    private String date;
    private String status;
    private String description;
    private Long assigneeUserId;
}
