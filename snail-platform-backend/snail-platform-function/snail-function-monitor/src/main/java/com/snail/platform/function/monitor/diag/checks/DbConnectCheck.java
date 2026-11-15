package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * db.connect:SELECT 1 计时;>200ms warn、异常或 >1000ms fail(主方案 §5.4)。
 */
@Component
public class DbConnectCheck implements DiagCheck {

    private final ObjectProvider<DataSource> dataSource;

    public DbConnectCheck(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String key() {
        return "db.connect";
    }

    @Override
    public String name() {
        return "数据库连接";
    }

    @Override
    public Item run() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return Item.fail(key(), name(), null, "数据源不可用", "检查数据库配置与网络连通性");
        }
        long start = System.nanoTime();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(1);
            try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 1000) {
                return Item.fail(key(), name(), ms, "SELECT 1 耗时 " + ms + "ms(>1000ms)", "检查数据库负载与网络抖动");
            }
            if (ms > 200) {
                return Item.warn(key(), name(), ms, "SELECT 1 耗时 " + ms + "ms(>200ms)", "观察数据库连接与慢查询情况");
            }
            return Item.pass(key(), name(), ms, "SELECT 1 耗时 " + ms + "ms", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "连接失败: " + e.getMessage(), "检查数据库地址/账号/网络与最大连接数");
        }
    }
}
