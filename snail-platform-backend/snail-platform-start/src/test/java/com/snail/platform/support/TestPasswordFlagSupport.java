package com.snail.platform.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 职责:测试专用密码标记自愈——V7 之后新建账号与被重置账号生而待改密(兜底闸只放改密/登出/me),
 * 与密码需求无关的既有用例只要拿到能调业务接口的干净令牌即可,不逐个接线强制改密流程。
 * 启动期先把种子 admin 垫成已改密(保住 admin123 口令的既有断言口径);各用例在"建号/重置之后、
 * 首次登录之前"调用 clearPendingFlags 清掉新账号的标记。
 * 上游:各 @SpringBootTest 用例类;下游:sys_user 单列 UPDATE,幂等可重复执行。
 */
@Component
public class TestPasswordFlagSupport implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public TestPasswordFlagSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        clearPendingFlags(jdbcTemplate);
    }

    /** 把全部待改密标记垫成当前时间;只在测试进程里调用,生产路径的标记语义不受影响 */
    public static void clearPendingFlags(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("UPDATE sys_user SET password_modify_time = NOW() WHERE password_modify_time IS NULL");
    }
}
