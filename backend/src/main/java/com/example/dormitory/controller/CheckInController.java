package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.dto.CheckInApplicationRequest;
import com.example.dormitory.dto.CheckInApplicationResponse;
import com.example.dormitory.dto.CheckInApprovalRequest;
import com.example.dormitory.dto.CheckInRecordRequest;
import com.example.dormitory.dto.CheckInRecordResponse;
import com.example.dormitory.dto.RemarkRequest;
import com.example.dormitory.service.CheckInLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CheckInController {

    private final CheckInLifecycleService checkInLifecycleService;

    public CheckInController(CheckInLifecycleService checkInLifecycleService) {
        this.checkInLifecycleService = checkInLifecycleService;
    }

    @GetMapping("/check-in-applications")
    public ApiResponse<PageResponse<CheckInApplicationResponse>> applications(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long dormitoryId) {
        StpUtil.checkPermission("checkin:read");
        return ApiResponse.ok(checkInLifecycleService.listApplications(
                page, pageSize, keyword, status, studentId, dormitoryId));
    }

    @PostMapping("/check-in-applications")
    public ResponseEntity<ApiResponse<CheckInApplicationResponse>> createApplication(
            @Valid @RequestBody CheckInApplicationRequest request) {
        StpUtil.checkPermission("checkin:review");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(checkInLifecycleService.createApplication(request)));
    }

    @PostMapping("/check-in-applications/{id}/approve")
    public ApiResponse<CheckInApplicationResponse> approve(
            @PathVariable Long id,
            @Valid @RequestBody CheckInApprovalRequest request) {
        StpUtil.checkPermission("checkin:review");
        return ApiResponse.ok(checkInLifecycleService.approveApplication(id, request.bedId(), request.remark()));
    }

    @PostMapping("/check-in-applications/{id}/reject")
    public ApiResponse<CheckInApplicationResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RemarkRequest request) {
        StpUtil.checkPermission("checkin:review");
        return ApiResponse.ok(checkInLifecycleService.rejectApplication(id, request == null ? null : request.remark()));
    }

    @GetMapping("/check-in-records")
    public ApiResponse<PageResponse<CheckInRecordResponse>> records(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dormitoryId) {
        StpUtil.checkPermission("checkin:read");
        return ApiResponse.ok(checkInLifecycleService.listRecords(page, pageSize, keyword, status, dormitoryId));
    }

    @PostMapping("/check-in-records")
    public ResponseEntity<ApiResponse<CheckInRecordResponse>> createRecord(
            @Valid @RequestBody CheckInRecordRequest request) {
        StpUtil.checkPermission("checkin:review");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(checkInLifecycleService.createRecord(request)));
    }

    @PostMapping("/check-in-records/{id}/checkout")
    public ApiResponse<CheckInRecordResponse> checkout(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RemarkRequest request) {
        StpUtil.checkPermission("checkin:review");
        return ApiResponse.ok(checkInLifecycleService.checkout(id, request == null ? null : request.remark()));
    }
}
