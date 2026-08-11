package com.example.dormitory.config;

import com.example.dormitory.domain.CheckInApplication;
import com.example.dormitory.domain.Dormitory;
import com.example.dormitory.domain.HygieneCheck;
import com.example.dormitory.domain.Notice;
import com.example.dormitory.domain.Payment;
import com.example.dormitory.domain.RepairOrder;
import com.example.dormitory.domain.Student;
import com.example.dormitory.mapper.CheckInApplicationMapper;
import com.example.dormitory.mapper.DormitoryMapper;
import com.example.dormitory.mapper.HygieneCheckMapper;
import com.example.dormitory.mapper.NoticeMapper;
import com.example.dormitory.mapper.PaymentMapper;
import com.example.dormitory.mapper.RepairOrderMapper;
import com.example.dormitory.mapper.StudentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.util.List;

@Component
@Order(30)
public class DemoDataInitializer implements ApplicationRunner {

    private final boolean enabled;
    private final DormitoryMapper dormitoryMapper;
    private final StudentMapper studentMapper;
    private final CheckInApplicationMapper applicationMapper;
    private final RepairOrderMapper repairOrderMapper;
    private final PaymentMapper paymentMapper;
    private final HygieneCheckMapper hygieneCheckMapper;
    private final NoticeMapper noticeMapper;

    public DemoDataInitializer(
            @Value("${dormitory.demo-data-enabled:false}") boolean enabled,
            DormitoryMapper dormitoryMapper,
            StudentMapper studentMapper,
            CheckInApplicationMapper applicationMapper,
            RepairOrderMapper repairOrderMapper,
            PaymentMapper paymentMapper,
            HygieneCheckMapper hygieneCheckMapper,
            NoticeMapper noticeMapper) {
        this.enabled = enabled;
        this.dormitoryMapper = dormitoryMapper;
        this.studentMapper = studentMapper;
        this.applicationMapper = applicationMapper;
        this.repairOrderMapper = repairOrderMapper;
        this.paymentMapper = paymentMapper;
        this.hygieneCheckMapper = hygieneCheckMapper;
        this.noticeMapper = noticeMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        seedDormitories();
        seedStudents();
        seedApplications();
        seedRepairs();
        seedPayments();
        seedHygieneChecks();
        seedNotices();
    }

    private void seedDormitories() {
        if (dormitoryMapper.selectCount(null) > 0) return;
        List.of(
                new Dormitory(null, "101宿舍", "男生宿舍", "1号楼", 6, 6, 0, "已满"),
                new Dormitory(null, "102宿舍", "男生宿舍", "1号楼", 6, 4, 2, "入住中"),
                new Dormitory(null, "201宿舍", "女生宿舍", "2号楼", 6, 5, 1, "入住中"),
                new Dormitory(null, "202宿舍", "女生宿舍", "2号楼", 6, 3, 3, "入住中")
        ).forEach(dormitoryMapper::insert);
    }

    private void seedStudents() {
        if (studentMapper.selectCount(null) > 0) return;
        List.of(
                new Student(null, "2026001", "张同学", "男", "计算机学院", "2026", "13800000001", "已入住"),
                new Student(null, "2026002", "李同学", "女", "外国语学院", "2026", "13800000002", "已入住"),
                new Student(null, "2026003", "王同学", "男", "机械学院", "2026", "13800000003", "未入住")
        ).forEach(studentMapper::insert);
    }

    private void seedApplications() {
        if (applicationMapper.selectCount(null) > 0) return;
        List.of(
                new CheckInApplication(null, "2026003", "王同学", "1号楼-102宿舍", "2026-07-10", "待审核", null, null),
                new CheckInApplication(null, "2026004", "赵同学", "2号楼-202宿舍", "2026-07-09", "已通过", null, null)
        ).forEach(applicationMapper::insert);
    }

    private void seedRepairs() {
        if (repairOrderMapper.selectCount(null) > 0) return;
        List.of(
                new RepairOrder(null, "WX20260710001", "张同学", "1号楼-101宿舍", "水电维修", "2026-07-10", "待处理", null, null),
                new RepairOrder(null, "WX20260709001", "李同学", "2号楼-201宿舍", "家具维修", "2026-07-09", "处理中", null, null)
        ).forEach(repairOrderMapper::insert);
    }

    private void seedPayments() {
        if (paymentMapper.selectCount(null) > 0) return;
        List.of(
                new Payment(null, "2026001", "张同学", "住宿费", new BigDecimal("800.00"), new BigDecimal("800.00"), "已缴", "2026-08-31"),
                new Payment(null, "2026002", "李同学", "水电费", new BigDecimal("35.00"), BigDecimal.ZERO, "未缴", "2026-07-31")
        ).forEach(paymentMapper::insert);
    }

    private void seedHygieneChecks() {
        if (hygieneCheckMapper.selectCount(null) > 0) return;
        List.of(
                new HygieneCheck(null, "101宿舍", "1号楼", "2026-07-10", "宿舍管理员", 90, "优秀", "保持安全整洁"),
                new HygieneCheck(null, "201宿舍", "2号楼", "2026-07-10", "宿舍管理员", 78, "良好", null)
        ).forEach(hygieneCheckMapper::insert);
    }

    private void seedNotices() {
        if (noticeMapper.selectCount(null) > 0) return;
        List.of(
                new Notice(null, "关于做好宿舍安全用电的通知", "安全卫生", "2026-07-10", "系统管理员", "已发布",
                        "请遵守宿舍安全用电规定，离开宿舍前关闭非必要电源。",
                        java.time.LocalDateTime.parse("2026-07-10T09:00:00")),
                new Notice(null, "暑期宿舍卫生检查安排", "宿舍通知", "2026-07-09", "宿舍管理员", "已发布",
                        "暑期将开展宿舍卫生检查，请保持公共区域整洁。",
                        java.time.LocalDateTime.parse("2026-07-09T09:00:00"))
        ).forEach(noticeMapper::insert);
    }
}
