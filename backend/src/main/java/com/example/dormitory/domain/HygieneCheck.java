package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("hygiene_check")
public class HygieneCheck extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String dormitory;
    private String building;
    private String date;
    private String inspector;
    private Integer score;
    private String result;
    private String remark;
}
