package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.diag.DiagCheck;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * disk.space:logs 卷使用率;>80% warn、>90% fail。
 */
@Component
public class DiskSpaceCheck implements DiagCheck {

    private final MonitorProperties properties;

    public DiskSpaceCheck(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String key() {
        return "disk.space";
    }

    @Override
    public String name() {
        return "磁盘空间";
    }

    @Override
    public Item run() {
        try {
            FileStore store = Files.getFileStore(Path.of(properties.getLogs().getFile().getDir()));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return Item.fail(key(), name(), null, "磁盘总量读取失败", "检查挂载与权限");
            }
            double usedPercent = Math.round((total - usable) * 1000.0 / total) / 10.0;
            if (usedPercent > 90) {
                return Item.fail(key(), name(), null, "logs 卷使用率 " + usedPercent + "%(>90%)",
                        "清理日志/临时文件或扩容磁盘");
            }
            if (usedPercent > 80) {
                return Item.warn(key(), name(), null, "logs 卷使用率 " + usedPercent + "%(>80%)",
                        "关注日志增长,确认保留期与轮转生效");
            }
            return Item.pass(key(), name(), null, "logs 卷使用率 " + usedPercent + "%", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), null, "磁盘探测失败: " + e.getMessage(), "检查 logs 目录配置与权限");
        }
    }
}
