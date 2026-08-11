package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("student")
public class Student extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String studentNo;
    private String name;
    private String gender;
    private String college;
    private String grade;
    private String phone;
    private String checkInStatus;
}
