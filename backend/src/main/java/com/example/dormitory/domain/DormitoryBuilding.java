package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("dormitory_building")
public class DormitoryBuilding extends AuditableEntity {
    @TableId
    private Long dormitoryId;
    private Long buildingId;
}
