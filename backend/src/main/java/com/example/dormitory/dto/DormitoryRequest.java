package com.example.dormitory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DormitoryRequest(
        @NotBlank(message = "宿舍名称不能为空")
        @Size(max = 64, message = "宿舍名称不能超过64个字符")
        String name,
        @NotBlank(message = "宿舍类型不能为空")
        @Pattern(regexp = "男生宿舍|女生宿舍|混合宿舍", message = "宿舍类型不合法")
        String type,
        @Size(max = 32, message = "楼栋名称不能超过32个字符")
        String building,
        @Positive(message = "楼栋 ID 不合法")
        Long buildingId,
        @NotNull(message = "床位数不能为空")
        @Min(value = 1, message = "床位数必须大于0")
        Integer beds,
        @NotNull(message = "已入住人数不能为空")
        @Min(value = 0, message = "已入住人数不能小于0")
        Integer occupied) {
}
