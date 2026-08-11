package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("check_in_application")
public class CheckInApplication extends AuditableEntity {
    @TableId
    private Long id;
    private String studentNo;
    private String name;
    private String dormitory;
    private String date;
    private String status;
    private Long createdByUserId;
    private LocalDateTime appliedAt;
}
