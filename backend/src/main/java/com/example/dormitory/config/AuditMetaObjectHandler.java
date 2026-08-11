package com.example.dormitory.config;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        Long operatorUserId = currentOperatorUserId();
        if (operatorUserId != null) {
            strictInsertFill(metaObject, "createdOperatorUserId", Long.class, operatorUserId);
            strictInsertFill(metaObject, "updatedOperatorUserId", Long.class, operatorUserId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        Long operatorUserId = currentOperatorUserId();
        if (operatorUserId != null) {
            setFieldValByName("updatedOperatorUserId", operatorUserId, metaObject);
        }
    }

    private Long currentOperatorUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
