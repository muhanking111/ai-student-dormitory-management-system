package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Building;
import com.example.dormitory.dto.BedResponse;
import com.example.dormitory.dto.BedStatusRequest;
import com.example.dormitory.dto.BuildingRequest;
import com.example.dormitory.service.BedService;
import com.example.dormitory.service.BuildingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ResourceController {

    private final BuildingService buildingService;
    private final BedService bedService;

    public ResourceController(BuildingService buildingService, BedService bedService) {
        this.buildingService = buildingService;
        this.bedService = bedService;
    }

    @GetMapping("/buildings")
    public ApiResponse<PageResponse<Building>> buildings(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("dormitory:read");
        return ApiResponse.ok(buildingService.list(page, pageSize, keyword, status));
    }

    @GetMapping("/buildings/options")
    public ApiResponse<List<Building>> buildingOptions() {
        StpUtil.checkPermission("dormitory:read");
        return ApiResponse.ok(buildingService.options());
    }

    @PostMapping("/buildings")
    public ResponseEntity<ApiResponse<Building>> createBuilding(@Valid @RequestBody BuildingRequest request) {
        StpUtil.checkPermission("dormitory:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(buildingService.create(request)));
    }

    @PutMapping("/buildings/{id}")
    public ApiResponse<Building> updateBuilding(
            @PathVariable Long id,
            @Valid @RequestBody BuildingRequest request) {
        StpUtil.checkPermission("dormitory:write");
        return ApiResponse.ok(buildingService.update(id, request));
    }

    @DeleteMapping("/buildings/{id}")
    public ResponseEntity<Void> deleteBuilding(@PathVariable Long id) {
        StpUtil.checkPermission("dormitory:write");
        buildingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/beds")
    public ApiResponse<PageResponse<BedResponse>> beds(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long dormitoryId,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("dormitory:read");
        return ApiResponse.ok(bedService.list(page, pageSize, keyword, buildingId, dormitoryId, status));
    }

    @PatchMapping("/beds/{id}/status")
    public ApiResponse<BedResponse> updateBedStatus(
            @PathVariable Long id,
            @Valid @RequestBody BedStatusRequest request) {
        StpUtil.checkPermission("dormitory:write");
        return ApiResponse.ok(bedService.updateStatus(id, request));
    }
}
