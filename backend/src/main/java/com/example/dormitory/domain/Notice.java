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
@TableName("notice")
public class Notice extends SoftDeletableEntity {
    @TableId
    private Long id;
    private String title;
    private String type;
    private String date;
    private String publisher;
    private String status;
    private String content;
    private LocalDateTime publishedAt;
}
