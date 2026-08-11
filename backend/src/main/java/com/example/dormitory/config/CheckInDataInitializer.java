package com.example.dormitory.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dormitory.domain.CheckInApplication;
import com.example.dormitory.domain.CheckInApplicationDetail;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.Student;
import com.example.dormitory.mapper.CheckInApplicationDetailMapper;
import com.example.dormitory.mapper.CheckInApplicationMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.StudentMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(50)
public class CheckInDataInitializer implements ApplicationRunner {

    private final CheckInApplicationMapper applicationMapper;
    private final CheckInApplicationDetailMapper applicationDetailMapper;
    private final StudentMapper studentMapper;
    private final DormitoryMapper dormitoryMapper;

    public CheckInDataInitializer(
            CheckInApplicationMapper applicationMapper,
            CheckInApplicationDetailMapper applicationDetailMapper,
            StudentMapper studentMapper,
            DormitoryMapper dormitoryMapper) {
        this.applicationMapper = applicationMapper;
        this.applicationDetailMapper = applicationDetailMapper;
        this.studentMapper = studentMapper;
        this.dormitoryMapper = dormitoryMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Dormitory> dormitories = dormitoryMapper.selectList(null);
        for (CheckInApplication application : applicationMapper.selectList(null)) {
            if (applicationDetailMapper.selectById(application.getId()) != null) continue;
            Student student = studentMapper.selectOne(Wrappers.<Student>lambdaQuery()
                    .eq(Student::getStudentNo, application.getStudentNo()));
            Dormitory dormitory = dormitories.stream()
                    .filter(item -> application.getDormitory().equals(item.getBuilding() + "-" + item.getName()))
                    .findFirst()
                    .orElse(null);
            if (student == null || dormitory == null) continue;
            applicationDetailMapper.insert(new CheckInApplicationDetail(
                    application.getId(), student.getId(), dormitory.getId(), null, null, null, null, null));
        }
    }
}
