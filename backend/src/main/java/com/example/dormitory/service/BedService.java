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
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.Student;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.dto.BedResponse;
import com.example.dormitory.dto.BedStatusRequest;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.BuildingMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.StudentMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BedService {

    private final BedMapper bedMapper;
    private final DormitoryMapper dormitoryMapper;
    private final BuildingMapper buildingMapper;
    private final StudentMapper studentMapper;
    private final DashboardCacheService dashboardCacheService;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;

    public BedService(
            BedMapper bedMapper,
            DormitoryMapper dormitoryMapper,
            BuildingMapper buildingMapper,
            StudentMapper studentMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper,
            DashboardCacheService dashboardCacheService) {
        this.bedMapper = bedMapper;
        this.dormitoryMapper = dormitoryMapper;
        this.buildingMapper = buildingMapper;
        this.studentMapper = studentMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    public PageResponse<BedResponse> list(
            long page,
            long pageSize,
            String keyword,
            Long buildingId,
            Long dormitoryId,
            String status) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Bed> query = Wrappers.lambdaQuery();
        if (dormitoryId != null) query.eq(Bed::getDormitoryId, dormitoryId);
        if (buildingId != null && dormitoryId == null) {
            List<Long> dormitoryIds = dormitoryBuildingMapper.selectList(
                            Wrappers.<DormitoryBuilding>lambdaQuery().eq(DormitoryBuilding::getBuildingId, buildingId))
                    .stream().map(DormitoryBuilding::getDormitoryId).toList();
            if (dormitoryIds.isEmpty()) return new PageResponse<>(List.of(), 0, page, pageSize);
            query.in(Bed::getDormitoryId, dormitoryIds);
        }
        if (StringUtils.hasText(keyword)) query.like(Bed::getBedNo, keyword.trim());
        if (StringUtils.hasText(status)) query.eq(Bed::getStatus, status);
        query.orderByAsc(Bed::getDormitoryId).orderByAsc(Bed::getBedNo);
        IPage<Bed> result = bedMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(enrich(result.getRecords()), result.getTotal(), page, pageSize);
    }

    @Transactional
    public BedResponse updateStatus(Long id, BedStatusRequest request) {
        StpUtil.checkPermission("dormitory:write");
        Bed bed = bedMapper.selectById(id);
        if (bed == null) throw new BusinessException(HttpStatus.NOT_FOUND, "床位不存在");
        if ("已占用".equals(bed.getStatus()) || bed.getStudentId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "已占用床位不能手动变更状态");
        }
        bed.setStatus(request.status());
        bedMapper.updateById(bed);
        refreshDormitoryCounters(bed.getDormitoryId());
        dashboardCacheService.evictStatistics();
        return enrich(List.of(bed)).getFirst();
    }

    private List<BedResponse> enrich(List<Bed> beds) {
        if (beds.isEmpty()) return List.of();
        List<Long> dormitoryIds = beds.stream().map(Bed::getDormitoryId).distinct().toList();
        Map<Long, Dormitory> dormitories = dormitoryMapper.selectByIds(dormitoryIds).stream()
                .collect(Collectors.toMap(Dormitory::getId, Function.identity()));
        Map<Long, Long> buildingByDormitory = dormitoryBuildingMapper.selectByIds(dormitoryIds).stream()
                .collect(Collectors.toMap(DormitoryBuilding::getDormitoryId, DormitoryBuilding::getBuildingId));
        List<Long> buildingIds = buildingByDormitory.values().stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Building> buildings = buildingIds.isEmpty() ? Map.of() : buildingMapper.selectByIds(buildingIds)
                .stream().collect(Collectors.toMap(Building::getId, Function.identity()));
        List<Long> studentIds = beds.stream().map(Bed::getStudentId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Student> students = studentIds.isEmpty() ? Map.of() : studentMapper.selectByIds(studentIds)
                .stream().collect(Collectors.toMap(Student::getId, Function.identity()));
        return beds.stream().map(bed -> {
            Dormitory dormitory = dormitories.get(bed.getDormitoryId());
            Long buildingId = buildingByDormitory.get(bed.getDormitoryId());
            Building building = buildingId == null ? null : buildings.get(buildingId);
            Student student = bed.getStudentId() == null ? null : students.get(bed.getStudentId());
            return new BedResponse(bed.getId(), bed.getBedNo(), bed.getStatus(), bed.getStudentId(),
                    student == null ? null : student.getName(), bed.getDormitoryId(),
                    dormitory == null ? null : dormitory.getName(),
                    buildingId,
                    building == null ? dormitory == null ? null : dormitory.getBuilding() : building.getName());
        }).toList();
    }

    private void refreshDormitoryCounters(Long dormitoryId) {
        Dormitory dormitory = dormitoryMapper.selectById(dormitoryId);
        if (dormitory == null) return;
        List<Bed> beds = bedMapper.selectList(Wrappers.<Bed>lambdaQuery().eq(Bed::getDormitoryId, dormitoryId));
        int occupied = (int) beds.stream().filter(bed -> "已占用".equals(bed.getStatus())).count();
        int vacant = (int) beds.stream().filter(bed -> "空闲".equals(bed.getStatus())).count();
        dormitory.setBeds(beds.size());
        dormitory.setOccupied(occupied);
        dormitory.setVacant(vacant);
        dormitory.setStatus(vacant == 0 ? "已满" : "入住中");
        dormitoryMapper.updateById(dormitory);
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }
}
