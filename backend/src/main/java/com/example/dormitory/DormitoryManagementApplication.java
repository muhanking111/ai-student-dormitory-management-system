package com.example.dormitory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.example.dormitory.mapper")
@SpringBootApplication
public class DormitoryManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(DormitoryManagementApplication.class, args);
    }
}
