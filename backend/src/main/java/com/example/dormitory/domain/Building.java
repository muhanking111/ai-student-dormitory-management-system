package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("building")
public class Building extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String code;
    private String name;
    private String genderType;
    private Integer floors;
    private String manager;
    private String status;
}
