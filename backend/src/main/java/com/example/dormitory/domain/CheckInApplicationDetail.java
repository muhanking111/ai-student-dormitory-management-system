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
@TableName("check_in_application_detail")
public class CheckInApplicationDetail extends AuditableEntity {
    @TableId
    private Long applicationId;
    private Long studentId;
    private Long dormitoryId;
    private Long bedId;
    private String applyRemark;
    private String reviewRemark;
    private Long reviewerUserId;
    private LocalDateTime reviewedAt;
}
