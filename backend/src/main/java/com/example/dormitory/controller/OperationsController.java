package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.Payment;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.dto.HygieneCheckRequest;
import com.example.dormitory.dto.NoticeRequest;
import com.example.dormitory.dto.PaymentBillRequest;
import com.example.dormitory.dto.PaymentRecordResponse;
import com.example.dormitory.dto.PaymentRecordRequest;
import com.example.dormitory.dto.RepairAssignmentRequest;
import com.example.dormitory.dto.RepairOrderRequest;
import com.example.dormitory.dto.RepairRecordRequest;
import com.example.dormitory.dto.RepairRecordResponse;
import com.example.dormitory.service.OperationsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OperationsController {

    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/repair-orders")
    public ApiResponse<PageResponse<RepairOrder>> repairOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("repair:read");
        return ApiResponse.ok(operationsService.repairOrders(page, pageSize, keyword, type, status));
    }

    @GetMapping("/repair-records")
    public ApiResponse<PageResponse<RepairRecordResponse>> repairRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long repairOrderId) {
        StpUtil.checkPermission("repair:read");
        return ApiResponse.ok(operationsService.repairRecords(page, pageSize, keyword, repairOrderId));
    }

    @PostMapping("/repair-orders")
    public ResponseEntity<ApiResponse<RepairOrder>> createRepairOrder(@Valid @RequestBody RepairOrderRequest request) {
        StpUtil.checkPermission("repair:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.createRepairOrder(request)));
    }

    @PatchMapping("/repair-orders/{id}/assignee")
    public ApiResponse<RepairOrder> assignRepairOrder(
            @PathVariable Long id,
            @Valid @RequestBody RepairAssignmentRequest request) {
        StpUtil.checkPermission("repair:write");
        if (!StpUtil.hasRole("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "仅系统管理员可以分配维修单");
        }
        return ApiResponse.ok(operationsService.assignRepairOrder(id, request.assigneeUserId()));
    }

    @PostMapping("/repair-orders/{id}/records")
    public ResponseEntity<ApiResponse<RepairOrder>> addRepairRecord(
            @PathVariable Long id,
            @Valid @RequestBody RepairRecordRequest request) {
        StpUtil.checkPermission("repair:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.addRepairRecord(id, request)));
    }

    @GetMapping("/payment-bills")
    public ApiResponse<PageResponse<Payment>> paymentBills(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("payment:read");
        return ApiResponse.ok(operationsService.paymentBills(page, pageSize, keyword, type, status));
    }

    @GetMapping("/payment-records")
    public ApiResponse<PageResponse<PaymentRecordResponse>> paymentRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long paymentId) {
        StpUtil.checkPermission("payment:read");
        return ApiResponse.ok(operationsService.paymentRecords(page, pageSize, keyword, paymentId));
    }

    @PostMapping("/payment-bills")
    public ResponseEntity<ApiResponse<Payment>> createPaymentBill(@Valid @RequestBody PaymentBillRequest request) {
        StpUtil.checkPermission("payment:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.createPaymentBill(request)));
    }

    @PostMapping("/payment-bills/{id}/payments")
    public ResponseEntity<ApiResponse<Payment>> payBill(
            @PathVariable Long id,
            @Valid @RequestBody PaymentRecordRequest request) {
        StpUtil.checkPermission("payment:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.payBill(id, request)));
    }

    @GetMapping("/hygiene-checks")
    public ApiResponse<PageResponse<HygieneCheck>> hygieneChecks(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String result) {
        StpUtil.checkPermission("hygiene:read");
        return ApiResponse.ok(operationsService.hygieneChecks(page, pageSize, keyword, result));
    }

    @PostMapping("/hygiene-checks")
    public ResponseEntity<ApiResponse<HygieneCheck>> createHygieneCheck(@Valid @RequestBody HygieneCheckRequest request) {
        StpUtil.checkPermission("hygiene:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.createHygieneCheck(request)));
    }

    @PatchMapping("/hygiene-checks/{id}")
    public ApiResponse<HygieneCheck> updateHygieneCheck(@PathVariable Long id, @Valid @RequestBody HygieneCheckRequest request) {
        StpUtil.checkPermission("hygiene:write");
        return ApiResponse.ok(operationsService.updateHygieneCheck(id, request));
    }

    @DeleteMapping("/hygiene-checks/{id}")
    public ResponseEntity<Void> deleteHygieneCheck(@PathVariable Long id) {
        StpUtil.checkPermission("hygiene:write");
        operationsService.deleteHygieneCheck(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notices")
    public ApiResponse<PageResponse<Notice>> notices(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        StpUtil.checkPermission("notice:read");
        return ApiResponse.ok(operationsService.notices(page, pageSize, keyword, type, status));
    }

    @PostMapping("/notices")
    public ResponseEntity<ApiResponse<Notice>> createNotice(@Valid @RequestBody NoticeRequest request) {
        StpUtil.checkPermission("notice:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(operationsService.createNotice(request)));
    }

    @PatchMapping("/notices/{id}")
    public ApiResponse<Notice> updateNotice(@PathVariable Long id, @Valid @RequestBody NoticeRequest request) {
        StpUtil.checkPermission("notice:write");
        return ApiResponse.ok(operationsService.updateNotice(id, request));
    }

    @DeleteMapping("/notices/{id}")
    public ResponseEntity<Void> deleteNotice(@PathVariable Long id) {
        StpUtil.checkPermission("notice:write");
        operationsService.deleteNotice(id);
        return ResponseEntity.noContent().build();
    }
}
