package com.example.dormitory;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.dto.StudentRequest;
import com.example.dormitory.mapper.StudentMapper;
import com.example.dormitory.mapper.UserAccountMapper;
import com.example.dormitory.service.StudentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@SpringBootTest
class ServicePermissionBoundaryTest {

    @Autowired
    private StudentService studentService;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long viewerId;

    @BeforeEach
    void setUp() {
        UserAccount viewer = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsername, "service-viewer"));
        if (viewer == null) {
            viewer = new UserAccount(null, "service-viewer", passwordEncoder.encode("viewer-password-123"),
                    "服务层只读用户", "VIEWER", true);
            userAccountMapper.insert(viewer);
        }
        viewerId = viewer.getId();
        studentMapper.delete(Wrappers.<com.example.dormitory.domain.Student>lambdaQuery()
                .eq(com.example.dormitory.domain.Student::getStudentNo, "SVC-PERM-001"));
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
        studentMapper.delete(Wrappers.<com.example.dormitory.domain.Student>lambdaQuery()
                .eq(com.example.dormitory.domain.Student::getStudentNo, "SVC-PERM-001"));
    }

    @Test
    void readOnlyUserCannotBypassControllerAndCallWriteService() {
        StpUtil.login(viewerId);

        assertThrows(NotPermissionException.class, () -> studentService.create(new StudentRequest(
                "SVC-PERM-001", "越权学生", "男", "计算机学院", "2026", "13800009999")));
    }
}
