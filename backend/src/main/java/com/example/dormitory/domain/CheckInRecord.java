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
@TableName("check_in_record")
public class CheckInRecord extends AuditableEntity {
    @TableId
    private Long id;
    private Long studentId;
    private Long bedId;
    private Long applicationId;
    private Long activeStudentId;
    private Long activeBedId;
    private LocalDateTime checkInDate;
    private LocalDateTime checkOutDate;
    private String status;
    private String remark;
    private Long checkInOperatorUserId;
    private Long checkOutOperatorUserId;
}
