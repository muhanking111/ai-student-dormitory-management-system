package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.dto.DormitoryRequest;
import com.example.dormitory.dto.CheckInTrendPoint;
import com.example.dormitory.service.DormitoryCommandService;
import com.example.dormitory.service.DormitoryQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DormitoryController {

    private final DormitoryQueryService queryService;
    private final DormitoryCommandService commandService;

    public DormitoryController(DormitoryQueryService queryService, DormitoryCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }

    @GetMapping("/dashboard/statistics")
    public ApiResponse<List<StatisticCard>> statistics() {
        StpUtil.checkPermission("dashboard:read");
        return ApiResponse.ok(queryService.statistics());
    }

    @GetMapping("/dashboard/check-in-trend")
    public ApiResponse<List<CheckInTrendPoint>> checkInTrend() {
        StpUtil.checkPermission("dashboard:read");
        return ApiResponse.ok(queryService.checkInTrend());
    }

    @GetMapping("/dormitories")
    public ApiResponse<PageResponse<Dormitory>> dormitories(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("dormitory:read");
        return ApiResponse.ok(queryService.dormitories(page, pageSize, keyword, buildingId, type, status));
    }

    @PostMapping("/dormitories")
    public ResponseEntity<ApiResponse<Dormitory>> createDormitory(@Valid @RequestBody DormitoryRequest request) {
        StpUtil.checkPermission("dormitory:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(commandService.create(request)));
    }

    @PutMapping("/dormitories/{id}")
    public ApiResponse<Dormitory> updateDormitory(
            @PathVariable Long id,
            @Valid @RequestBody DormitoryRequest request) {
        StpUtil.checkPermission("dormitory:write");
        return ApiResponse.ok(commandService.update(id, request));
    }

    @DeleteMapping("/dormitories/{id}")
    public ResponseEntity<Void> deleteDormitory(@PathVariable Long id) {
        StpUtil.checkPermission("dormitory:write");
        commandService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
