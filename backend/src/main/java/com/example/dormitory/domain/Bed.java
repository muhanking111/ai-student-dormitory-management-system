package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("bed")
public class Bed extends AuditableEntity {
    @TableId
    private Long id;
    private Long dormitoryId;
    private String bedNo;
    private String status;
    private Long studentId;
}
