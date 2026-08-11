package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Bed;
import com.example.dormitory.domain.CheckInApplicationDetail;
import com.example.dormitory.domain.CheckInRecord;
import com.example.dormitory.domain.Student;
import com.example.dormitory.dto.StudentImportRequest;
import com.example.dormitory.dto.StudentImportResponse;
import com.example.dormitory.dto.StudentRequest;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.CheckInApplicationDetailMapper;
import com.example.dormitory.mapper.CheckInRecordMapper;
import com.example.dormitory.mapper.StudentMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class StudentService {

    private final StudentMapper studentMapper;
    private final BedMapper bedMapper;
    private final CheckInApplicationDetailMapper applicationDetailMapper;
    private final CheckInRecordMapper checkInRecordMapper;
    private final DashboardCacheService dashboardCacheService;

    public StudentService(
            StudentMapper studentMapper,
            BedMapper bedMapper,
            CheckInApplicationDetailMapper applicationDetailMapper,
            CheckInRecordMapper checkInRecordMapper,
            DashboardCacheService dashboardCacheService) {
        this.studentMapper = studentMapper;
        this.bedMapper = bedMapper;
        this.applicationDetailMapper = applicationDetailMapper;
        this.checkInRecordMapper = checkInRecordMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    public PageResponse<Student> list(
            long page,
            long pageSize,
            String keyword,
            String college,
            String grade,
            String checkInStatus) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Student> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(Student::getStudentNo, value)
                    .or().like(Student::getName, value));
        }
        if (StringUtils.hasText(college)) query.eq(Student::getCollege, college.trim());
        if (StringUtils.hasText(grade)) query.eq(Student::getGrade, grade.trim());
        if (StringUtils.hasText(checkInStatus)) query.eq(Student::getCheckInStatus, checkInStatus.trim());
        query.orderByAsc(Student::getStudentNo);
        IPage<Student> result = studentMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    @Transactional
    public Student create(StudentRequest request) {
        StpUtil.checkPermission("student:write");
        ensureStudentNoUnique(request.studentNo(), null);
        Student student = toEntity(null, request, "未入住");
        studentMapper.insert(student);
        dashboardCacheService.evictStatistics();
        return student;
    }

    @Transactional
    public StudentImportResponse importStudents(StudentImportRequest request) {
        StpUtil.checkPermission("student:write");
        Set<String> studentNos = collectUniqueStudentNos(request.students());
        List<Student> existing = studentMapper.selectList(
                Wrappers.<Student>lambdaQuery().in(Student::getStudentNo, studentNos));
        if (!existing.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "学号已存在: " + existing.getFirst().getStudentNo());
        }

        List<Student> created = new ArrayList<>(request.students().size());
        try {
            for (StudentRequest studentRequest : request.students()) {
                Student student = toEntity(null, studentRequest, "未入住");
                studentMapper.insert(student);
                created.add(student);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "导入学生数据冲突");
        }
        dashboardCacheService.evictStatistics();
        return new StudentImportResponse(created.size(),
                created.stream().map(Student::getStudentNo).toList());
    }

    @Transactional
    public Student update(Long id, StudentRequest request) {
        StpUtil.checkPermission("student:write");
        Student current = requireStudent(id);
        ensureStudentNoUnique(request.studentNo(), id);
        Student student = toEntity(id, request, current.getCheckInStatus());
        studentMapper.updateById(student);
        return student;
    }

    @Transactional
    public void delete(Long id) {
        StpUtil.checkPermission("student:write");
        requireStudent(id);
        boolean hasBed = bedMapper.selectCount(Wrappers.<Bed>lambdaQuery().eq(Bed::getStudentId, id)) > 0;
        boolean hasApplication = applicationDetailMapper.selectCount(
                Wrappers.<CheckInApplicationDetail>lambdaQuery().eq(CheckInApplicationDetail::getStudentId, id)) > 0;
        boolean hasRecord = checkInRecordMapper.selectCount(
                Wrappers.<CheckInRecord>lambdaQuery().eq(CheckInRecord::getStudentId, id)) > 0;
        if (hasBed || hasApplication || hasRecord) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生存在入住业务记录，不能删除");
        }
        studentMapper.deleteById(id);
        dashboardCacheService.evictStatistics();
    }

    private Student requireStudent(Long id) {
        Student student = studentMapper.selectById(id);
        if (student == null) throw new BusinessException(HttpStatus.NOT_FOUND, "学生不存在");
        return student;
    }

    private void ensureStudentNoUnique(String studentNo, Long excludedId) {
        var query = Wrappers.<Student>lambdaQuery().eq(Student::getStudentNo, studentNo.trim());
        if (excludedId != null) query.ne(Student::getId, excludedId);
        if (studentMapper.selectCount(query) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "学号已存在");
        }
    }

    private Set<String> collectUniqueStudentNos(List<StudentRequest> students) {
        Set<String> studentNos = new LinkedHashSet<>();
        for (StudentRequest student : students) {
            String studentNo = student.studentNo().trim();
            if (!studentNos.add(studentNo)) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "导入批次内学号重复: " + studentNo);
            }
        }
        return studentNos;
    }

    private Student toEntity(Long id, StudentRequest request, String checkInStatus) {
        return new Student(id, request.studentNo().trim(), request.name().trim(), request.gender(),
                request.college().trim(), request.grade().trim(), request.phone().trim(), checkInStatus);
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }
}
