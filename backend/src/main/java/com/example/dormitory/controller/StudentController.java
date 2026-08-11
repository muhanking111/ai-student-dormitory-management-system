package com.example.dormitory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.common.ApiResponse;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Student;
import com.example.dormitory.dto.StudentImportRequest;
import com.example.dormitory.dto.StudentImportResponse;
import com.example.dormitory.dto.StudentRequest;
import com.example.dormitory.service.StudentService;
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
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping
    public ApiResponse<PageResponse<Student>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String checkInStatus) {
        StpUtil.checkPermission("student:read");
        return ApiResponse.ok(studentService.list(page, pageSize, keyword, college, grade, checkInStatus));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Student>> create(@Valid @RequestBody StudentRequest request) {
        StpUtil.checkPermission("student:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(studentService.create(request)));
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<StudentImportResponse>> importStudents(
            @Valid @RequestBody StudentImportRequest request) {
        StpUtil.checkPermission("student:write");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(studentService.importStudents(request)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Student> update(@PathVariable Long id, @Valid @RequestBody StudentRequest request) {
        StpUtil.checkPermission("student:write");
        return ApiResponse.ok(studentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        StpUtil.checkPermission("student:write");
        studentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
