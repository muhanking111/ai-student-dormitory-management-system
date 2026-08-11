package com.example.dormitory.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("dormitory")
public class Dormitory extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String name;
    private String type;
    @TableField(exist = false)
    private Long buildingId;
    private String building;
    private Integer beds;
    private Integer occupied;
    private Integer vacant;
    private String status;

    public Dormitory(Long id, String name, String type, String building, Integer beds,
                     Integer occupied, Integer vacant, String status) {
        this(id, name, type, null, building, beds, occupied, vacant, status);
    }

    public Dormitory(Long id, String name, String type, Long buildingId, String building, Integer beds,
                     Integer occupied, Integer vacant, String status) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.buildingId = buildingId;
        this.building = building;
        this.beds = beds;
        this.occupied = occupied;
        this.vacant = vacant;
        this.status = status;
    }
}
