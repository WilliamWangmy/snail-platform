package com.snail.platform.function.monitor.log;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.config.MonitorProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 职责:应用日志文件注册表——枚举活跃文件与 gz 滚动文件,fileId=文件名的白名单唯一入口。
 * 上游:FileLogSource(检索前先过注册表);下游:AppLogService(files 端点)。
 * 口径:只接受本注册表枚举出的 fileId;任何含 / \ .. 的入参直接 400「日志文件不存在」;
 * 目录不存在/不可读返回空列表不报错(dev 首启可能还没有日志);按 mtime 降序最多 200 条。
 */
@Component
public class LogFileRegistry {

    /** 滚动文件名:snail-platform.yyyy-MM-dd.i.log.gz */
    private static final Pattern ROLLED = Pattern.compile("^(.+)\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log\\.gz$");

    /** 枚举上限 */
    private static final int MAX_FILES = 200;

    private final MonitorProperties properties;

    public LogFileRegistry(MonitorProperties properties) {
        this.properties = properties;
    }

    /**
     * 文件项:fileId(=文件名)/name/sizeBytes/mtime。
     */
    public record LogFile(
            /** 文件 ID(=文件名,白名单键) */
            String fileId,
            /** 文件名展示 */
            String name,
            /** 大小(字节) */
            long sizeBytes,
            /** 修改时间 */
            LocalDateTime mtime) {
    }

    /** 日志目录 */
    public Path dir() {
        return Path.of(properties.getLogs().getFile().getDir());
    }

    /** 活跃文件名 */
    public String activeName() {
        return properties.getLogs().getFile().getActiveName();
    }

    /** 枚举文件(活跃 + gz 滚动),mtime 降序;目录缺失返回空 */
    public List<LogFile> files() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<LogFile> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isAcceptedName)
                    .forEach(path -> {
                        try {
                            result.add(new LogFile(path.getFileName().toString(), path.getFileName().toString(),
                                    Files.size(path), LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault())));
                        } catch (IOException ignored) {
                            // 单文件元数据读取失败跳过
                        }
                    });
        } catch (IOException ignored) {
            // 目录不可读返回空列表不报错
        }
        result.sort(Comparator.comparing(LogFile::mtime).reversed());
        return result.size() > MAX_FILES ? result.subList(0, MAX_FILES) : result;
    }

    /** fileId 白名单校验并解析为路径;不接受路径参数,注入面在此收口 */
    public Path resolve(String fileId) {
        if (fileId == null || fileId.isBlank() || fileId.contains("/") || fileId.contains("\\")
                || fileId.contains("..")) {
            throw BizException.badRequest("日志文件不存在");
        }
        if (files().stream().noneMatch(f -> f.fileId().equals(fileId))) {
            throw BizException.badRequest("日志文件不存在");
        }
        return dir().resolve(fileId);
    }

    /** 是否活跃文件或滚动 gz 文件 */
    public boolean isAcceptedName(Path path) {
        String name = path.getFileName().toString();
        return name.equals(activeName()) || ROLLED.matcher(name).matches();
    }

    /** 从滚动文件名提取日期;无日期(活跃文件)返回 empty */
    public static java.util.Optional<java.time.LocalDate> dateOf(String name) {
        var matcher = ROLLED.matcher(name);
        if (matcher.matches()) {
            try {
                return java.util.Optional.of(java.time.LocalDate.parse(matcher.group(2)));
            } catch (Exception ignored) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }
}
