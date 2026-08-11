package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Bed;
import com.example.dormitory.domain.Building;
import com.example.dormitory.domain.CheckInApplication;
import com.example.dormitory.domain.CheckInApplicationDetail;
import com.example.dormitory.domain.CheckInRecord;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.domain.Student;
import com.example.dormitory.dto.CheckInApplicationRequest;
import com.example.dormitory.dto.CheckInApplicationResponse;
import com.example.dormitory.dto.CheckInRecordRequest;
import com.example.dormitory.dto.CheckInRecordResponse;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.BuildingMapper;
import com.example.dormitory.mapper.CheckInApplicationDetailMapper;
import com.example.dormitory.mapper.CheckInApplicationMapper;
import com.example.dormitory.mapper.CheckInRecordMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.StudentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CheckInLifecycleService {

    private final StudentMapper studentMapper;
    private final DormitoryMapper dormitoryMapper;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;
    private final BuildingMapper buildingMapper;
    private final BedMapper bedMapper;
    private final CheckInApplicationMapper applicationMapper;
    private final CheckInApplicationDetailMapper applicationDetailMapper;
    private final CheckInRecordMapper checkInRecordMapper;
    private final DashboardCacheService dashboardCacheService;

    public CheckInLifecycleService(
            StudentMapper studentMapper,
            DormitoryMapper dormitoryMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper,
            BuildingMapper buildingMapper,
            BedMapper bedMapper,
            CheckInApplicationMapper applicationMapper,
            CheckInApplicationDetailMapper applicationDetailMapper,
            CheckInRecordMapper checkInRecordMapper,
            DashboardCacheService dashboardCacheService) {
        this.studentMapper = studentMapper;
        this.dormitoryMapper = dormitoryMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
        this.buildingMapper = buildingMapper;
        this.bedMapper = bedMapper;
        this.applicationMapper = applicationMapper;
        this.applicationDetailMapper = applicationDetailMapper;
        this.checkInRecordMapper = checkInRecordMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    public PageResponse<CheckInApplicationResponse> listApplications(
            long page,
            long pageSize,
            String keyword,
            String status,
            Long studentId,
            Long dormitoryId) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<CheckInApplication> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(wrapper -> wrapper.like(CheckInApplication::getStudentNo, value)
                    .or().like(CheckInApplication::getName, value)
                    .or().like(CheckInApplication::getDormitory, value));
        }
        if (StringUtils.hasText(status)) query.eq(CheckInApplication::getStatus, status.trim());
        if (studentId != null || dormitoryId != null) {
            var detailQuery = Wrappers.<CheckInApplicationDetail>lambdaQuery();
            if (studentId != null) detailQuery.eq(CheckInApplicationDetail::getStudentId, studentId);
            if (dormitoryId != null) detailQuery.eq(CheckInApplicationDetail::getDormitoryId, dormitoryId);
            List<Long> applicationIds = applicationDetailMapper.selectList(detailQuery).stream()
                    .map(CheckInApplicationDetail::getApplicationId).toList();
            if (applicationIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(CheckInApplication::getId, applicationIds);
        }
        query.orderByDesc(CheckInApplication::getId);
        IPage<CheckInApplication> result = applicationMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(enrichApplications(result.getRecords()), result.getTotal(), page, pageSize);
    }

    public PageResponse<CheckInRecordResponse> listRecords(
            long page,
            long pageSize,
            String keyword,
            String status,
            Long dormitoryId) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<CheckInRecord> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(status)) query.eq(CheckInRecord::getStatus, status.trim());
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            List<Long> studentIds = studentMapper.selectList(Wrappers.<Student>lambdaQuery()
                            .like(Student::getStudentNo, value).or().like(Student::getName, value))
                    .stream().map(Student::getId).toList();
            if (studentIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(CheckInRecord::getStudentId, studentIds);
        }
        if (dormitoryId != null) {
            List<Long> bedIds = bedMapper.selectList(
                            Wrappers.<Bed>lambdaQuery().eq(Bed::getDormitoryId, dormitoryId))
                    .stream().map(Bed::getId).toList();
            if (bedIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(CheckInRecord::getBedId, bedIds);
        }
        query.orderByDesc(CheckInRecord::getCheckInDate).orderByDesc(CheckInRecord::getId);
        IPage<CheckInRecord> result = checkInRecordMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(enrichRecords(result.getRecords()), result.getTotal(), page, pageSize);
    }

    @Transactional
    public CheckInApplicationResponse createApplication(CheckInApplicationRequest request) {
        StpUtil.checkPermission("checkin:review");
        Student student = requireStudent(request.studentId(), false);
        Dormitory dormitory = requireDormitory(request.dormitoryId());
        ensureStudentCanCheckIn(student);
        ensureNoPendingApplication(student.getId());

        long operatorUserId = StpUtil.getLoginIdAsLong();
        LocalDateTime appliedAt = LocalDateTime.now();
        String buildingName = buildingName(dormitory.getId(), dormitory.getBuilding());
        CheckInApplication application = new CheckInApplication(null, student.getStudentNo(), student.getName(),
                buildingName + "-" + dormitory.getName(), appliedAt.toLocalDate().toString(), "待审核",
                operatorUserId, appliedAt);
        applicationMapper.insert(application);
        applicationDetailMapper.insert(new CheckInApplicationDetail(application.getId(), student.getId(),
                dormitory.getId(), null, normalizeRemark(request.remark()), null, null, null));
        dashboardCacheService.evictStatistics();
        return enrichApplications(List.of(application)).getFirst();
    }

    @Transactional
    public CheckInApplicationResponse approveApplication(Long applicationId, Long bedId, String remark) {
        StpUtil.checkPermission("checkin:review");
        CheckInApplication application = requireApplication(applicationId, true);
        ensurePending(application);
        CheckInApplicationDetail detail = requireApplicationDetail(applicationId);
        long operatorUserId = StpUtil.getLoginIdAsLong();

        assign(detail.getStudentId(), bedId, applicationId, detail.getDormitoryId(), remark, operatorUserId);
        application.setStatus("已通过");
        applicationMapper.updateById(application);
        detail.setBedId(bedId);
        detail.setReviewRemark(normalizeRemark(remark));
        detail.setReviewerUserId(operatorUserId);
        detail.setReviewedAt(LocalDateTime.now());
        applicationDetailMapper.updateById(detail);
        dashboardCacheService.evictStatistics();
        return enrichApplications(List.of(application)).getFirst();
    }

    @Transactional
    public CheckInApplicationResponse rejectApplication(Long applicationId, String remark) {
        StpUtil.checkPermission("checkin:review");
        CheckInApplication application = requireApplication(applicationId, true);
        ensurePending(application);
        CheckInApplicationDetail detail = requireApplicationDetail(applicationId);
        long operatorUserId = StpUtil.getLoginIdAsLong();
        application.setStatus("已拒绝");
        applicationMapper.updateById(application);
        detail.setReviewRemark(normalizeRemark(remark));
        detail.setReviewerUserId(operatorUserId);
        detail.setReviewedAt(LocalDateTime.now());
        applicationDetailMapper.updateById(detail);
        dashboardCacheService.evictStatistics();
        return enrichApplications(List.of(application)).getFirst();
    }

    @Transactional
    public CheckInRecordResponse createRecord(CheckInRecordRequest request) {
        StpUtil.checkPermission("checkin:review");
        CheckInRecord record = assign(request.studentId(), request.bedId(), null, null, request.remark(),
                StpUtil.getLoginIdAsLong());
        return enrichRecords(List.of(record)).getFirst();
    }

    @Transactional
    public CheckInRecordResponse checkout(Long recordId, String remark) {
        StpUtil.checkPermission("checkin:review");
        CheckInRecord record = checkInRecordMapper.selectOne(Wrappers.<CheckInRecord>lambdaQuery()
                .eq(CheckInRecord::getId, recordId).last("FOR UPDATE"));
        if (record == null) throw new BusinessException(HttpStatus.NOT_FOUND, "入住记录不存在");
        if (!"在住".equals(record.getStatus()) || record.getActiveStudentId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "入住记录已办理退宿");
        }

        Bed bed = requireBed(record.getBedId(), true);
        Student student = requireStudent(record.getStudentId(), true);
        if (!"已占用".equals(bed.getStatus()) || !Objects.equals(bed.getStudentId(), student.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "床位入住数据不一致，不能办理退宿");
        }

        record.setStatus("已退宿");
        record.setCheckOutDate(LocalDateTime.now());
        record.setCheckOutOperatorUserId(StpUtil.getLoginIdAsLong());
        record.setActiveStudentId(null);
        record.setActiveBedId(null);
        if (StringUtils.hasText(remark)) record.setRemark(remark.trim());
        var recordUpdate = Wrappers.<CheckInRecord>lambdaUpdate()
                .eq(CheckInRecord::getId, record.getId())
                .set(CheckInRecord::getStatus, record.getStatus())
                .set(CheckInRecord::getCheckOutDate, record.getCheckOutDate())
                .set(CheckInRecord::getCheckOutOperatorUserId, record.getCheckOutOperatorUserId())
                .set(CheckInRecord::getActiveStudentId, null)
                .set(CheckInRecord::getActiveBedId, null);
        if (StringUtils.hasText(remark)) recordUpdate.set(CheckInRecord::getRemark, record.getRemark());
        checkInRecordMapper.update(null, recordUpdate);

        bed.setStatus("空闲");
        bed.setStudentId(null);
        bedMapper.update(null, Wrappers.<Bed>lambdaUpdate()
                .eq(Bed::getId, bed.getId())
                .set(Bed::getStatus, "空闲")
                .set(Bed::getStudentId, null));
        student.setCheckInStatus("未入住");
        studentMapper.updateById(student);
        refreshDormitoryCounters(bed.getDormitoryId());
        dashboardCacheService.evictStatistics();
        return enrichRecords(List.of(record)).getFirst();
    }

    private CheckInRecord assign(
            Long studentId,
            Long bedId,
            Long applicationId,
            Long expectedDormitoryId,
            String remark,
            Long operatorUserId) {
        Student student = requireStudent(studentId, true);
        Bed bed = requireBed(bedId, true);
        ensureStudentCanCheckIn(student);
        if (!"空闲".equals(bed.getStatus()) || bed.getStudentId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "床位当前不可用");
        }
        if (expectedDormitoryId != null && !expectedDormitoryId.equals(bed.getDormitoryId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "所选床位不属于申请宿舍");
        }
        Dormitory dormitory = requireDormitory(bed.getDormitoryId());
        ensureGenderMatches(student, dormitory);

        LocalDateTime now = LocalDateTime.now();
        CheckInRecord record = new CheckInRecord(null, studentId, bedId, applicationId, studentId, bedId,
                now, null, "在住", normalizeRemark(remark), operatorUserId, null);
        try {
            checkInRecordMapper.insert(record);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生或床位已存在有效入住记录");
        }

        bed.setStatus("已占用");
        bed.setStudentId(studentId);
        bedMapper.updateById(bed);
        student.setCheckInStatus("已入住");
        studentMapper.updateById(student);
        refreshDormitoryCounters(bed.getDormitoryId());
        dashboardCacheService.evictStatistics();
        return record;
    }

    private void ensureStudentCanCheckIn(Student student) {
        long activeRecords = checkInRecordMapper.selectCount(Wrappers.<CheckInRecord>lambdaQuery()
                .eq(CheckInRecord::getActiveStudentId, student.getId()));
        if (activeRecords > 0 || "已入住".equals(student.getCheckInStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生已存在有效入住记录");
        }
    }

    private void ensureNoPendingApplication(Long studentId) {
        List<Long> applicationIds = applicationDetailMapper.selectList(
                        Wrappers.<CheckInApplicationDetail>lambdaQuery()
                                .eq(CheckInApplicationDetail::getStudentId, studentId))
                .stream().map(CheckInApplicationDetail::getApplicationId).toList();
        if (!applicationIds.isEmpty() && applicationMapper.selectCount(Wrappers.<CheckInApplication>lambdaQuery()
                .in(CheckInApplication::getId, applicationIds).eq(CheckInApplication::getStatus, "待审核")) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生已有待审核入住申请");
        }
    }

    private void ensureGenderMatches(Student student, Dormitory dormitory) {
        if ("男生宿舍".equals(dormitory.getType()) && !"男".equals(student.getGender())) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生性别与宿舍类型不匹配");
        }
        if ("女生宿舍".equals(dormitory.getType()) && !"女".equals(student.getGender())) {
            throw new BusinessException(HttpStatus.CONFLICT, "学生性别与宿舍类型不匹配");
        }
    }

    private void ensurePending(CheckInApplication application) {
        if (!"待审核".equals(application.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "入住申请已处理");
        }
    }

    private Student requireStudent(Long id, boolean lock) {
        Student student = lock
                ? studentMapper.selectOne(Wrappers.<Student>lambdaQuery().eq(Student::getId, id).last("FOR UPDATE"))
                : studentMapper.selectById(id);
        if (student == null) throw new BusinessException(HttpStatus.NOT_FOUND, "学生不存在");
        return student;
    }

    private Dormitory requireDormitory(Long id) {
        Dormitory dormitory = dormitoryMapper.selectById(id);
        if (dormitory == null) throw new BusinessException(HttpStatus.NOT_FOUND, "宿舍不存在");
        return dormitory;
    }

    private Bed requireBed(Long id, boolean lock) {
        Bed bed = lock
                ? bedMapper.selectOne(Wrappers.<Bed>lambdaQuery().eq(Bed::getId, id).last("FOR UPDATE"))
                : bedMapper.selectById(id);
        if (bed == null) throw new BusinessException(HttpStatus.NOT_FOUND, "床位不存在");
        return bed;
    }

    private CheckInApplication requireApplication(Long id, boolean lock) {
        CheckInApplication application = lock
                ? applicationMapper.selectOne(Wrappers.<CheckInApplication>lambdaQuery()
                        .eq(CheckInApplication::getId, id).last("FOR UPDATE"))
                : applicationMapper.selectById(id);
        if (application == null) throw new BusinessException(HttpStatus.NOT_FOUND, "入住申请不存在");
        return application;
    }

    private CheckInApplicationDetail requireApplicationDetail(Long applicationId) {
        CheckInApplicationDetail detail = applicationDetailMapper.selectById(applicationId);
        if (detail == null) throw new BusinessException(HttpStatus.CONFLICT, "入住申请明细不存在");
        return detail;
    }

    private void refreshDormitoryCounters(Long dormitoryId) {
        Dormitory dormitory = requireDormitory(dormitoryId);
        List<Bed> beds = bedMapper.selectList(
                Wrappers.<Bed>lambdaQuery().eq(Bed::getDormitoryId, dormitoryId));
        int occupied = (int) beds.stream().filter(bed -> "已占用".equals(bed.getStatus())).count();
        int vacant = (int) beds.stream().filter(bed -> "空闲".equals(bed.getStatus())).count();
        dormitory.setBeds(beds.size());
        dormitory.setOccupied(occupied);
        dormitory.setVacant(vacant);
        dormitory.setStatus(vacant == 0 ? "已满" : "入住中");
        dormitoryMapper.updateById(dormitory);
    }

    private List<CheckInApplicationResponse> enrichApplications(List<CheckInApplication> applications) {
        if (applications.isEmpty()) return List.of();
        List<Long> applicationIds = applications.stream().map(CheckInApplication::getId).toList();
        Map<Long, CheckInApplicationDetail> details = applicationDetailMapper.selectByIds(applicationIds).stream()
                .collect(Collectors.toMap(CheckInApplicationDetail::getApplicationId, Function.identity()));
        Map<Long, Student> students = loadStudents(details.values().stream()
                .map(CheckInApplicationDetail::getStudentId).toList());
        Map<Long, Dormitory> dormitories = loadDormitories(details.values().stream()
                .map(CheckInApplicationDetail::getDormitoryId).toList());
        Map<Long, Bed> beds = loadBeds(details.values().stream()
                .map(CheckInApplicationDetail::getBedId).filter(Objects::nonNull).toList());
        Map<Long, String> buildingNames = loadBuildingNames(dormitories.keySet().stream().toList(), dormitories);

        return applications.stream().map(application -> {
            CheckInApplicationDetail detail = details.get(application.getId());
            Student student = detail == null ? null : students.get(detail.getStudentId());
            Dormitory dormitory = detail == null ? null : dormitories.get(detail.getDormitoryId());
            Bed bed = detail == null || detail.getBedId() == null ? null : beds.get(detail.getBedId());
            return new CheckInApplicationResponse(application.getId(), detail == null ? null : detail.getStudentId(),
                    student == null ? application.getStudentNo() : student.getStudentNo(),
                    student == null ? application.getName() : student.getName(),
                    detail == null ? null : detail.getDormitoryId(),
                    dormitory == null ? application.getDormitory() : dormitory.getName(),
                    dormitory == null ? null : buildingNames.get(dormitory.getId()),
                    application.getDate(), application.getStatus(), detail == null ? null : detail.getBedId(),
                    bed == null ? null : bed.getBedNo(), detail == null ? null : detail.getApplyRemark(),
                    detail == null ? null : detail.getReviewRemark(), application.getCreatedByUserId(),
                    application.getAppliedAt(), detail == null ? null : detail.getReviewerUserId(),
                    detail == null ? null : detail.getReviewedAt());
        }).toList();
    }

    private List<CheckInRecordResponse> enrichRecords(List<CheckInRecord> records) {
        if (records.isEmpty()) return List.of();
        Map<Long, Student> students = loadStudents(records.stream().map(CheckInRecord::getStudentId).toList());
        Map<Long, Bed> beds = loadBeds(records.stream().map(CheckInRecord::getBedId).toList());
        Map<Long, Dormitory> dormitories = loadDormitories(beds.values().stream().map(Bed::getDormitoryId).toList());
        Map<Long, String> buildingNames = loadBuildingNames(dormitories.keySet().stream().toList(), dormitories);
        return records.stream().map(record -> {
            Student student = students.get(record.getStudentId());
            Bed bed = beds.get(record.getBedId());
            Dormitory dormitory = bed == null ? null : dormitories.get(bed.getDormitoryId());
            return new CheckInRecordResponse(record.getId(), record.getStudentId(),
                    student == null ? null : student.getStudentNo(), student == null ? null : student.getName(),
                    record.getBedId(), bed == null ? null : bed.getBedNo(),
                    dormitory == null ? null : dormitory.getId(), dormitory == null ? null : dormitory.getName(),
                    dormitory == null ? null : buildingNames.get(dormitory.getId()), record.getApplicationId(),
                    record.getCheckInDate(), record.getCheckOutDate(), record.getStatus(), record.getRemark(),
                    record.getCheckInOperatorUserId(), record.getCheckOutOperatorUserId());
        }).toList();
    }

    private Map<Long, Student> loadStudents(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return studentMapper.selectByIds(distinctIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
    }

    private Map<Long, Dormitory> loadDormitories(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return dormitoryMapper.selectByIds(distinctIds).stream()
                .collect(Collectors.toMap(Dormitory::getId, Function.identity()));
    }

    private Map<Long, Bed> loadBeds(List<Long> ids) {
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Map.of();
        return bedMapper.selectByIds(distinctIds).stream()
                .collect(Collectors.toMap(Bed::getId, Function.identity()));
    }

    private Map<Long, String> loadBuildingNames(List<Long> dormitoryIds, Map<Long, Dormitory> dormitories) {
        if (dormitoryIds.isEmpty()) return Map.of();
        Map<Long, Long> buildingIds = dormitoryBuildingMapper.selectByIds(dormitoryIds).stream()
                .collect(Collectors.toMap(DormitoryBuilding::getDormitoryId, DormitoryBuilding::getBuildingId));
        List<Long> ids = buildingIds.values().stream().distinct().toList();
        Map<Long, Building> buildings = ids.isEmpty() ? Map.of() : buildingMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Building::getId, Function.identity()));
        return dormitoryIds.stream().collect(Collectors.toMap(Function.identity(), dormitoryId -> {
            Building building = buildings.get(buildingIds.get(dormitoryId));
            Dormitory dormitory = dormitories.get(dormitoryId);
            return building == null ? dormitory.getBuilding() : building.getName();
        }));
    }

    private String buildingName(Long dormitoryId, String fallback) {
        DormitoryBuilding relation = dormitoryBuildingMapper.selectById(dormitoryId);
        if (relation == null) return fallback;
        Building building = buildingMapper.selectById(relation.getBuildingId());
        return building == null ? fallback : building.getName();
    }

    private String normalizeRemark(String remark) {
        return StringUtils.hasText(remark) ? remark.trim() : null;
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }
}
