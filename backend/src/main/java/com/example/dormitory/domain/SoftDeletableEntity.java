package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class SoftDeletableEntity extends AuditableEntity {
    @JsonIgnore
    @TableLogic
    private Boolean deleted;
}
