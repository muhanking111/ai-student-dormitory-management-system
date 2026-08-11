package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class UserAccount extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String roleCode;
    private Boolean enabled;
}
