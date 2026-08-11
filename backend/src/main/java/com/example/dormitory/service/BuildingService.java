package com.example.dormitory.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dormitory.common.BusinessException;
import com.example.dormitory.common.PageResponse;
import com.example.dormitory.domain.Building;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.dto.BuildingRequest;
import com.example.dormitory.mapper.BuildingMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BuildingService {

    private final BuildingMapper buildingMapper;
    private final DormitoryMapper dormitoryMapper;
    private final DashboardCacheService dashboardCacheService;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;

    public BuildingService(
            BuildingMapper buildingMapper,
            DormitoryMapper dormitoryMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper,
            DashboardCacheService dashboardCacheService) {
        this.buildingMapper = buildingMapper;
        this.dormitoryMapper = dormitoryMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
        this.dashboardCacheService = dashboardCacheService;
    }

    public PageResponse<Building> list(long page, long pageSize, String keyword, String status) {
        validatePage(page, pageSize);
        LambdaQueryWrapper<Building> query = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            query.and(wrapper -> wrapper.like(Building::getCode, keyword.trim())
                    .or().like(Building::getName, keyword.trim())
                    .or().like(Building::getManager, keyword.trim()));
        }
        if (StringUtils.hasText(status)) query.eq(Building::getStatus, status);
        query.orderByAsc(Building::getId);
        IPage<Building> result = buildingMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, pageSize);
    }

    public java.util.List<Building> options() {
        return buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getStatus, "启用").orderByAsc(Building::getName));
    }

    @Transactional
    public Building create(BuildingRequest request) {
        StpUtil.checkPermission("dormitory:write");
        ensureUnique(request.code(), request.name(), null);
        Building building = new Building(null, request.code(), request.name(), request.genderType(), request.floors(),
                request.manager(), request.status());
        buildingMapper.insert(building);
        dashboardCacheService.evictStatistics();
        return building;
    }

    @Transactional
    public Building update(Long id, BuildingRequest request) {
        StpUtil.checkPermission("dormitory:write");
        Building building = requireBuilding(id);
        ensureUnique(request.code(), request.name(), id);
        String previousName = building.getName();
        building.setCode(request.code());
        building.setName(request.name());
        building.setGenderType(request.genderType());
        building.setFloors(request.floors());
        building.setManager(request.manager());
        building.setStatus(request.status());
        buildingMapper.updateById(building);
        if (!previousName.equals(request.name())) {
            java.util.List<Long> dormitoryIds = dormitoryBuildingMapper.selectList(
                            Wrappers.<DormitoryBuilding>lambdaQuery().eq(DormitoryBuilding::getBuildingId, id))
                    .stream().map(DormitoryBuilding::getDormitoryId).toList();
            for (Dormitory dormitory : dormitoryIds.isEmpty() ? java.util.List.<Dormitory>of()
                    : dormitoryMapper.selectByIds(dormitoryIds)) {
                dormitory.setBuilding(request.name());
                dormitoryMapper.updateById(dormitory);
            }
        }
        dashboardCacheService.evictStatistics();
        return building;
    }

    @Transactional
    public void delete(Long id) {
        StpUtil.checkPermission("dormitory:write");
        requireBuilding(id);
        Long dormitoryCount = dormitoryBuildingMapper.selectCount(Wrappers.<DormitoryBuilding>lambdaQuery()
                .eq(DormitoryBuilding::getBuildingId, id));
        if (dormitoryCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "楼栋下仍有宿舍，不能删除");
        }
        buildingMapper.deleteById(id);
        dashboardCacheService.evictStatistics();
    }

    private Building requireBuilding(Long id) {
        Building building = buildingMapper.selectById(id);
        if (building == null) throw new BusinessException(HttpStatus.NOT_FOUND, "楼栋不存在");
        return building;
    }

    private void ensureUnique(String code, String name, Long excludedId) {
        LambdaQueryWrapper<Building> query = Wrappers.<Building>lambdaQuery()
                .and(wrapper -> wrapper.eq(Building::getCode, code).or().eq(Building::getName, name));
        if (excludedId != null) query.ne(Building::getId, excludedId);
        if (buildingMapper.selectCount(query) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "楼栋编码或名称已存在");
        }
    }

    private void validatePage(long page, long pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
    }
}
