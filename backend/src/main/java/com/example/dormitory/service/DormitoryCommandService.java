package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.domain.Bed;
import com.example.dormitory.domain.Building;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.dto.DormitoryRequest;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.BuildingMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class DormitoryCommandService {

    private final DormitoryMapper dormitoryMapper;
    private final BuildingMapper buildingMapper;
    private final BedMapper bedMapper;
    private final DashboardCacheService dashboardCacheService;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;

    public DormitoryCommandService(
            DormitoryMapper dormitoryMapper,
            BuildingMapper buildingMapper,
            BedMapper bedMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper,
            DashboardCacheService dashboardCacheService) {
        this.dormitoryMapper = dormitoryMapper;
        this.buildingMapper = buildingMapper;
        this.bedMapper = bedMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    @Transactional
    public Dormitory create(DormitoryRequest request) {
        StpUtil.checkPermission("dormitory:write");
        validateCapacity(request);
        rejectDirectOccupancyChange(request.occupied(), 0);
        Building building = resolveBuilding(request);
        ensureUnique(building.getId(), request.name(), null);
        Dormitory dormitory = toEntity(null, request, building, request.beds() - request.occupied());
        dormitoryMapper.insert(dormitory);
        dormitoryBuildingMapper.insert(new DormitoryBuilding(dormitory.getId(), building.getId()));
        generateBeds(dormitory.getId(), 1, request.beds(), request.occupied());
        dashboardCacheService.evictStatistics();
        return dormitory;
    }

    @Transactional
    public Dormitory update(Long id, DormitoryRequest request) {
        StpUtil.checkPermission("dormitory:write");
        validateCapacity(request);
        Dormitory current = requireDormitory(id);
        rejectDirectOccupancyChange(request.occupied(), current.getOccupied());
        Building building = resolveBuilding(request);
        ensureUnique(building.getId(), request.name(), id);
        ensureLegacyBeds(current);

        int currentCount = Math.toIntExact(bedMapper.selectCount(
                Wrappers.<Bed>lambdaQuery().eq(Bed::getDormitoryId, id)));
        if (request.beds() > currentCount) {
            generateBeds(id, currentCount + 1, request.beds(), 0);
        }
        if (request.beds() < currentCount) {
            removeFreeBeds(id, currentCount - request.beds());
        }

        int vacant = Math.toIntExact(bedMapper.selectCount(Wrappers.<Bed>lambdaQuery()
                .eq(Bed::getDormitoryId, id).eq(Bed::getStatus, "空闲")));
        Dormitory dormitory = toEntity(id, request, building, vacant);
        dormitoryMapper.updateById(dormitory);
        DormitoryBuilding relation = dormitoryBuildingMapper.selectById(id);
        if (relation == null) dormitoryBuildingMapper.insert(new DormitoryBuilding(id, building.getId()));
        else if (!relation.getBuildingId().equals(building.getId())) {
            relation.setBuildingId(building.getId());
            dormitoryBuildingMapper.updateById(relation);
        }
        dashboardCacheService.evictStatistics();
        return dormitory;
    }

    @Transactional
    public void delete(Long id) {
        StpUtil.checkPermission("dormitory:write");
        Dormitory dormitory = requireDormitory(id);
        Long occupiedBeds = bedMapper.selectCount(Wrappers.<Bed>lambdaQuery()
                .eq(Bed::getDormitoryId, id).eq(Bed::getStatus, "已占用"));
        if (dormitory.getOccupied() > 0 || occupiedBeds > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "存在已入住床位，不能删除宿舍");
        }
        bedMapper.delete(Wrappers.<Bed>lambdaQuery().eq(Bed::getDormitoryId, id));
        dormitoryBuildingMapper.deleteById(id);
        dormitoryMapper.deleteById(id);
        dashboardCacheService.evictStatistics();
    }

    private Building resolveBuilding(DormitoryRequest request) {
        Building building = null;
        if (request.buildingId() != null) building = buildingMapper.selectById(request.buildingId());
        if (building == null && StringUtils.hasText(request.building())) {
            building = buildingMapper.selectOne(Wrappers.<Building>lambdaQuery()
                    .eq(Building::getName, request.building().trim()));
        }
        if (building == null && StringUtils.hasText(request.building())) {
            building = createLegacyBuilding(request.building().trim(), request.type());
        }
        if (building == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "所属楼栋不能为空");
        if (!"启用".equals(building.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已停用楼栋不能新增或迁入宿舍");
        }
        return building;
    }

    private Building createLegacyBuilding(String name, String type) {
        String baseCode = "AUTO-" + Integer.toUnsignedString(name.hashCode(), 36).toUpperCase();
        String code = baseCode;
        int suffix = 1;
        while (buildingMapper.selectCount(Wrappers.<Building>lambdaQuery().eq(Building::getCode, code)) > 0) {
            code = baseCode + "-" + suffix++;
        }
        Building building = new Building(null, code, name, type, 6, "未设置", "启用");
        buildingMapper.insert(building);
        return building;
    }

    private Dormitory requireDormitory(Long id) {
        Dormitory dormitory = dormitoryMapper.selectById(id);
        if (dormitory == null) throw new BusinessException(HttpStatus.NOT_FOUND, "宿舍不存在");
        return dormitory;
    }

    private void validateCapacity(DormitoryRequest request) {
        if (request.occupied() > request.beds()) {
            throw new BusinessException(HttpStatus.CONFLICT, "已入住人数不能超过床位数");
        }
    }

    private void rejectDirectOccupancyChange(Integer requestedOccupied, int currentOccupied) {
        if (requestedOccupied == null || requestedOccupied != currentOccupied) {
            throw new BusinessException(HttpStatus.CONFLICT, "入住人数只能通过入住流程变更");
        }
    }

    private void ensureUnique(Long buildingId, String name, Long excludedId) {
        List<Long> dormitoryIds = dormitoryBuildingMapper.selectList(
                        Wrappers.<DormitoryBuilding>lambdaQuery().eq(DormitoryBuilding::getBuildingId, buildingId))
                .stream().map(DormitoryBuilding::getDormitoryId).toList();
        if (dormitoryIds.isEmpty()) return;
        var query = Wrappers.<Dormitory>lambdaQuery().in(Dormitory::getId, dormitoryIds)
                .eq(Dormitory::getName, name);
        if (excludedId != null) query.ne(Dormitory::getId, excludedId);
        if (dormitoryMapper.selectCount(query) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该楼栋已存在同名宿舍");
        }
    }

    private void ensureLegacyBeds(Dormitory dormitory) {
        Long count = bedMapper.selectCount(Wrappers.<Bed>lambdaQuery()
                .eq(Bed::getDormitoryId, dormitory.getId()));
        if (count == 0) generateBeds(dormitory.getId(), 1, dormitory.getBeds(), dormitory.getOccupied());
    }

    private void generateBeds(Long dormitoryId, int start, int end, int occupiedCount) {
        for (int index = start; index <= end; index++) {
            String status = index - start < occupiedCount ? "已占用" : "空闲";
            bedMapper.insert(new Bed(null, dormitoryId, String.format("%02d", index), status, null));
        }
    }

    private void removeFreeBeds(Long dormitoryId, int count) {
        List<Bed> freeBeds = bedMapper.selectList(Wrappers.<Bed>lambdaQuery()
                .eq(Bed::getDormitoryId, dormitoryId).eq(Bed::getStatus, "空闲")
                .orderByDesc(Bed::getBedNo).last("LIMIT " + count));
        if (freeBeds.size() < count) {
            throw new BusinessException(HttpStatus.CONFLICT, "仅可删除空闲床位，当前可缩减床位不足");
        }
        bedMapper.deleteByIds(freeBeds.stream().map(Bed::getId).toList());
    }

    private Dormitory toEntity(Long id, DormitoryRequest request, Building building, int vacant) {
        String status = vacant == 0 ? "已满" : "入住中";
        return new Dormitory(id, request.name(), request.type(), building.getId(), building.getName(),
                request.beds(), request.occupied(), vacant, status);
    }
}
