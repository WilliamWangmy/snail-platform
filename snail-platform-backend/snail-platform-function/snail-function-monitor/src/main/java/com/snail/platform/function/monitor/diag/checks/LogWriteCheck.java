package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.diag.DiagCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * log.write:logs 目录可写探测(写临时文件再删);失败 fail。无 warn 档。
 */
@Component
public class LogWriteCheck implements DiagCheck {

    private final MonitorProperties properties;

    public LogWriteCheck(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String key() {
        return "log.write";
    }

    @Override
    public String name() {
        return "日志落盘";
    }

    @Override
    public Item run() {
        Path dir = Path.of(properties.getLogs().getFile().getDir());
        Path probe = dir.resolve("monitor-diag-probe-" + UUID.randomUUID() + ".tmp");
        long start = System.nanoTime();
        try {
            Files.createDirectories(dir);
            Files.writeString(probe, "probe");
            Files.deleteIfExists(probe);
            return Item.pass(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "logs 目录可写", null);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(probe);
            } catch (Exception ignored) {
                // 清理失败忽略
            }
            return Item.fail(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "logs 目录不可写: " + e.getMessage(), "检查容器卷挂载与目录权限(否则重启丢日志)");
        }
    }
}
