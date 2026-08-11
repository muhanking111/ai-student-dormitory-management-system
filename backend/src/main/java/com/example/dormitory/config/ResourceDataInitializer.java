package com.example.dormitory.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.domain.Bed;
import com.example.dormitory.domain.Building;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.DormitoryBuilding;
import com.example.dormitory.mapper.BedMapper;
import com.example.dormitory.mapper.BuildingMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.DormitoryBuildingMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(40)
public class ResourceDataInitializer implements ApplicationRunner {

    private final BuildingMapper buildingMapper;
    private final DormitoryMapper dormitoryMapper;
    private final BedMapper bedMapper;
    private final DormitoryBuildingMapper dormitoryBuildingMapper;

    public ResourceDataInitializer(
            BuildingMapper buildingMapper,
            DormitoryMapper dormitoryMapper,
            BedMapper bedMapper,
            DormitoryBuildingMapper dormitoryBuildingMapper) {
        this.buildingMapper = buildingMapper;
        this.dormitoryMapper = dormitoryMapper;
        this.bedMapper = bedMapper;
        this.dormitoryBuildingMapper = dormitoryBuildingMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Dormitory dormitory : dormitoryMapper.selectList(null)) {
            DormitoryBuilding relation = dormitoryBuildingMapper.selectById(dormitory.getId());
            Building building = relation == null ? null : buildingMapper.selectById(relation.getBuildingId());
            if (building == null) {
                building = ensureBuilding(dormitory);
                dormitoryBuildingMapper.deleteById(dormitory.getId());
                dormitoryBuildingMapper.insert(new DormitoryBuilding(dormitory.getId(), building.getId()));
            }
            normalizeLegacyCode(building);
            dormitory.setBuildingId(building.getId());
            ensureBeds(dormitory);
        }
    }

    private Building ensureBuilding(Dormitory dormitory) {
        Building byName = buildingMapper.selectOne(Wrappers.<Building>lambdaQuery()
                .eq(Building::getName, dormitory.getBuilding()));
        if (byName != null) return byName;
        String code = "AUTO-" + Integer.toUnsignedString(dormitory.getBuilding().hashCode(), 36).toUpperCase();
        Building building = new Building(null, code, dormitory.getBuilding(), dormitory.getType(),
                6, "未设置", "启用");
        buildingMapper.insert(building);
        return building;
    }

    private void ensureBeds(Dormitory dormitory) {
        Long count = bedMapper.selectCount(Wrappers.<Bed>lambdaQuery()
                .eq(Bed::getDormitoryId, dormitory.getId()));
        if (count > 0) return;
        List<Bed> beds = java.util.stream.IntStream.rangeClosed(1, dormitory.getBeds())
                .mapToObj(index -> new Bed(null, dormitory.getId(), String.format("%02d", index),
                        index <= dormitory.getOccupied() ? "已占用" : "空闲", null))
                .toList();
        beds.forEach(bedMapper::insert);
    }

    private void normalizeLegacyCode(Building building) {
        if (building.getCode() == null || !building.getCode().startsWith("AUTO-")) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(building.getName());
        if (!matcher.find()) return;
        String suggested = String.format("B%02d", Integer.parseInt(matcher.group(1)));
        Long duplicate = buildingMapper.selectCount(Wrappers.<Building>lambdaQuery()
                .eq(Building::getCode, suggested).ne(Building::getId, building.getId()));
        if (duplicate > 0) return;
        building.setCode(suggested);
        buildingMapper.updateById(building);
    }
}
