package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.example.dormitory.domain.CheckInApplication;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.StatisticCard;
import com.example.dormitory.domain.Student;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.mapper.CheckInApplicationMapper;
import com.example.dormitory.mapper.CheckInRecordMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.HygieneCheckMapper;
import com.example.dormitory.mapper.RepairOrderMapper;
import com.example.dormitory.mapper.StudentMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;
import com.example.dormitory.domain.CheckInRecord;
import com.example.dormitory.dto.CheckInTrendPoint;

@Service
public class DormitoryQueryService {

    private final DormitoryMapper dormitoryMapper;
    private final StudentMapper studentMapper;
    private final CheckInApplicationMapper applicationMapper;
    private final CheckInRecordMapper checkInRecordMapper;
    private final RepairOrderMapper repairOrderMapper;
    private final HygieneCheckMapper hygieneCheckMapper;
    private final DashboardCacheService dashboardCacheService;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;

    public DormitoryQueryService(
            DormitoryMapper dormitoryMapper,
            StudentMapper studentMapper,
            CheckInApplicationMapper applicationMapper,
            CheckInRecordMapper checkInRecordMapper,
            RepairOrderMapper repairOrderMapper,
            HygieneCheckMapper hygieneCheckMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper,
            DashboardCacheService dashboardCacheService) {
        this.dormitoryMapper = dormitoryMapper;
        this.studentMapper = studentMapper;
        this.applicationMapper = applicationMapper;
        this.checkInRecordMapper = checkInRecordMapper;
        this.repairOrderMapper = repairOrderMapper;
        this.hygieneCheckMapper = hygieneCheckMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    public List<StatisticCard> statistics() {
        if (isRestrictedRepairer()) {
            return buildStatistics(StpUtil.getLoginIdAsLong());
        }
        Optional<List<StatisticCard>> cached = dashboardCacheService.getStatistics();
        if (cached.isPresent()) {
            return cached.get();
        }
        List<StatisticCard> statistics = buildStatistics(null);
        dashboardCacheService.putStatistics(statistics);
        return statistics;
    }

    public List<StatisticCard> statisticsForActor(RbacService.AuthorizationSnapshot actor) {
        if (actor == null || !actor.enabled() || !actor.permissionCodes().contains("dashboard:read")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权读取数据驾驶舱");
        }
        Long restrictedRepairerId = actor.roleCodes().contains("REPAIRER")
                && !actor.roleCodes().contains("ADMIN") ? actor.userId() : null;
        return buildStatistics(restrictedRepairerId);
    }

    private List<StatisticCard> buildStatistics(Long repairerUserId) {
        List<Dormitory> dormitories = dormitoryMapper.selectList(null);
        long occupiedStudents = studentMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Student>lambdaQuery()
                        .eq(Student::getCheckInStatus, "已入住"));
        int vacantBeds = dormitories.stream().mapToInt(Dormitory::getVacant).sum();
        LambdaQueryWrapper<RepairOrder> repairQuery = Wrappers.<RepairOrder>lambdaQuery()
                .ne(RepairOrder::getStatus, "已完成");
        if (repairerUserId != null) repairQuery.eq(RepairOrder::getAssigneeUserId, repairerUserId);
        long pendingRepairs = repairOrderMapper.selectCount(repairQuery);
        long todayApplications = applicationMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CheckInApplication>lambdaQuery()
                        .eq(CheckInApplication::getDate, LocalDate.now().toString()));
        long hygieneChecks = hygieneCheckMapper.selectCount(null);
        return List.of(
                new StatisticCard("宿舍总数", dormitories.size(), "间", "实时数据", "blue", "home"),
                new StatisticCard("学生入住人数", Math.toIntExact(occupiedStudents), "人", "实时数据", "green", "team"),
                new StatisticCard("空余床位", vacantBeds, "个", "实时数据", "purple", "bed"),
                new StatisticCard("待维修数量", Math.toIntExact(pendingRepairs), "条", "待处理", "orange", "tool"),
                new StatisticCard("今日申请", Math.toIntExact(todayApplications), "条", "实时数据", "cyan", "form"),
                new StatisticCard("卫生检查", Math.toIntExact(hygieneChecks), "次", "累计记录", "blue", "safety")
        );
    }

    public List<CheckInTrendPoint> checkInTrend() {
        LocalDate startDate = LocalDate.now().minusDays(29);
        List<CheckInRecord> records = checkInRecordMapper.selectList(
                Wrappers.<CheckInRecord>lambdaQuery()
                        .ge(CheckInRecord::getCheckInDate, startDate.atStartOfDay()));
        Map<LocalDate, Long> counts = records.stream()
                .filter(record -> record.getCheckInDate() != null)
                .collect(Collectors.groupingBy(
                        record -> record.getCheckInDate().toLocalDate(),
                        Collectors.counting()));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        return java.util.stream.IntStream.rangeClosed(0, 29)
                .mapToObj(offset -> startDate.plusDays(offset))
                .map(date -> new CheckInTrendPoint(date.format(formatter), counts.getOrDefault(date, 0L)))
                .toList();
    }

    public PageResponse<Dormitory> dormitories(
            long page,
            long pageSize,
            String keyword,
            Long buildingId,
            String type,
            String status) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
        LambdaQueryWrapper<Dormitory> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) query.like(Dormitory::getName, keyword.trim());
        if (buildingId != null) {
            List<Long> dormitoryIds = dormitoryBuildingMapper.selectList(
                            Wrappers.<DormitoryBuilding>lambdaQuery().eq(DormitoryBuilding::getBuildingId, buildingId))
                    .stream().map(DormitoryBuilding::getDormitoryId).toList();
            if (dormitoryIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(Dormitory::getId, dormitoryIds);
        }
        if (StringUtils.hasText(type)) query.eq(Dormitory::getType, type);
        if (StringUtils.hasText(status)) query.eq(Dormitory::getStatus, status);
        query.orderByAsc(Dormitory::getBuilding).orderByAsc(Dormitory::getName);
        IPage<Dormitory> result = dormitoryMapper.selectPage(Page.of(page, pageSize), query);
        if (!result.getRecords().isEmpty()) {
            java.util.Map<Long, Long> buildingByDormitory = dormitoryBuildingMapper.selectByIds(
                            result.getRecords().stream().map(Dormitory::getId).toList())
                    .stream().collect(java.util.stream.Collectors.toMap(
                            DormitoryBuilding::getDormitoryId, DormitoryBuilding::getBuildingId));
            result.getRecords().forEach(dormitory -> dormitory.setBuildingId(buildingByDormitory.get(dormitory.getId())));
        }
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    private boolean isRestrictedRepairer() {
        return StpUtil.hasRole("REPAIRER") && !StpUtil.hasRole("ADMIN");
    }
}
