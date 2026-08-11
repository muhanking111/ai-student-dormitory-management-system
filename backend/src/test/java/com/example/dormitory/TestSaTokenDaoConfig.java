package com.example.dormitory;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class TestSaTokenDaoConfig {

    @Bean
    @Primary
    public SaTokenDao inMemoryTestSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }
}
