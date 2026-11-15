package com.snail.platform.function.monitor.datasource;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.log.LogFileRegistry;
import com.snail.platform.function.monitor.log.LogLineParser;
import com.snail.platform.function.monitor.log.LogLineParser.LogLine;
import com.snail.platform.function.monitor.log.dto.AppLogDetailQuery;
import com.snail.platform.function.monitor.log.dto.AppLogDetailVO;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.LogLineVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * 职责:本地文件日志源——候选文件粗筛时间窗后流式逐行解析过滤,内存 deque 只留 limit 条;
 * tail 末尾定长回读(2MB,seek 到行首,DD-26);detail 按 fileId 重扫定位行号取上下文。
 * 上游:AppLogService(按 logs.source 取用);下游:LogFileRegistry(白名单)、LogLineParser(解析)。
 * 口径:候选按文件名日期或 mtime 与 [begin-1d, end+1d] 有交集,旧到新;扫描行数 > scanMaxLines 或
 * 耗时 > scanTimeoutMs 即停 truncated=true;gz 用 GZIPInputStream 流式;IO 异常 → 400 不 500;
 * 永不静默回退别的来源。
 */
@Component
public class FileLogSource implements LogSource {

    private final MonitorProperties properties;
    private final LogFileRegistry registry;

    public FileLogSource(MonitorProperties properties, LogFileRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public LogFilesVO files() {
        return new LogFilesVO("file", registry.files().stream()
                .map(f -> new LogFilesVO.Item(f.fileId(), f.name(), f.sizeBytes(),
                        f.mtime().toString()))
                .toList());
    }

    @Override
    public AppLogPageVO page(AppLogPageQuery query) {
        long start = System.currentTimeMillis();
        ArrayDeque<LogLine> tail = new ArrayDeque<>(query.limit());
        long[] scanned = {0};
        boolean[] truncated = {false};
        for (Path candidate : candidates(query.beginTime().toLocalDate(), query.endTime().toLocalDate())) {
            ScanState state = new ScanState(query);
            readLines(candidate, (lineNo, raw) -> {
                state.lineNo = lineNo;
                state.scanned++;
                if (state.scanned > properties.getLogs().getScanMaxLines()
                        || System.currentTimeMillis() - start > properties.getLogs().getScanTimeoutMs()) {
                    state.truncated = true;
                    return false;
                }
                LogLine parsed = LogLineParser.parse(raw);
                if (parsed == null) {
                    // 堆栈行:追加到上一条命中的消息
                    LogLine previous = tail.peekLast();
                    if (previous != null && raw.getBytes(StandardCharsets.UTF_8).length > 0) {
                        tail.pollLast();
                        tail.addLast(new LogLine(previous.fileId(), previous.lineNo(), previous.time(),
                                previous.level(), previous.traceId(), previous.thread(), previous.logger(),
                                LogLineParser.appendStack(previous.message(), raw), previous.raw() + "\n" + raw));
                    }
                    return true;
                }
                LogLine line = new LogLine(candidate.getFileName().toString(), lineNo, parsed.time(),
                        parsed.level(), parsed.traceId(), parsed.thread(), parsed.logger(), parsed.message(),
                        parsed.raw());
                if (line.time().isBefore(query.beginTime())) {
                    return true;
                }
                if (line.time().isAfter(query.endTime())) {
                    return false;
                }
                if (matches(line, query.level(), query.traceId(), query.keyword())) {
                    tail.addLast(line);
                    while (tail.size() > query.limit()) {
                        tail.pollFirst();
                    }
                }
                return true;
            });
            scanned[0] += state.scanned;
            if (state.truncated) {
                truncated[0] = true;
                break;
            }
        }
        List<LogLine> rows = new ArrayList<>(tail);
        rows.sort(Comparator.comparing(LogLine::time).reversed().thenComparing(LogLine::lineNo, Comparator.reverseOrder()));
        return new AppLogPageVO("file", rows.stream().map(LogLineVO_FROM).toList(), scanned[0], truncated[0]);
    }

    /** 行号重建:堆栈合并使原行号漂移,以解析命中顺序编号即可 */
    private static final java.util.function.Function<LogLine, com.snail.platform.function.monitor.log.dto.LogLineVO>
            LogLineVO_FROM = com.snail.platform.function.monitor.log.dto.LogLineVO::from;

    /** 扫描状态载体(计数与截停) */
    private static final class ScanState {
        long scanned;
        long lineNo;
        boolean truncated;

        ScanState(AppLogPageQuery query) {
        }
    }

    @Override
    public List<LogLine> tail(TailQuery query) {
        Path active = registry.resolve(registry.activeName());
        long fileSize;
        try {
            fileSize = Files.size(active);
        } catch (IOException e) {
            throw BizException.badRequest("日志文件读取失败: " + e.getMessage());
        }
        long readBytes = Math.min(fileSize, properties.getLogs().getTailMaxBytes());
        long start = Math.max(0, fileSize - readBytes);
        byte[] buffer;
        try (var channel = Files.newByteChannel(active)) {
            buffer = new byte[(int) (fileSize - start)];
            channel.position(start);
            int offset = 0;
            var readBuffer = java.nio.ByteBuffer.allocate(buffer.length);
            while (readBuffer.hasRemaining() && channel.position() < fileSize) {
                int n = channel.read(readBuffer);
                if (n < 0) {
                    break;
                }
            }
            readBuffer.flip();
            readBuffer.get(buffer, 0, Math.min(buffer.length, readBuffer.remaining()));
        } catch (IOException e) {
            throw BizException.badRequest("日志文件读取失败: " + e.getMessage());
        }
        java.time.LocalDateTime sinceTime =
                java.time.Instant.ofEpochMilli(query.since()).atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime();
        // 逐行解码:跳过首残行(start>0 时),UTF-8 解码保持中文可读
        List<LogLine> result = new ArrayList<>();
        int index = start > 0 ? indexOfLineStart(buffer) : 0;
        while (index < buffer.length && result.size() < query.limit()) {
            int end = index;
            while (end < buffer.length && buffer[end] != '\n') {
                end++;
            }
            String raw = new String(buffer, index, end - index, StandardCharsets.UTF_8);
            index = end + 1;
            LogLine parsed = LogLineParser.parse(raw);
            if (parsed != null && parsed.time().isAfter(sinceTime)) {
                result.add(parsed);
            }
        }
        return result;
    }

    /** 从字节缓冲中找到第一个行首(跳过残行) */
    private int indexOfLineStart(byte[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] == '\n') {
                return i + 1;
            }
        }
        return buffer.length;
    }

    @Override
    public AppLogDetailVO detail(AppLogDetailQuery query) {
        Path target = registry.resolve(query.fileId());
        List<LogLine> window = new ArrayList<>();
        long before = query.lineNo() - query.context();
        long after = query.lineNo() + query.context();
        AtomicLongRef lineNoRef = new AtomicLongRef();
        boolean[] found = {false};
        try {
            readLines(target, (lineNo, raw) -> {
                lineNoRef.value = lineNo;
                LogLine parsed = LogLineParser.parse(raw);
                if (parsed == null) {
                    // 堆栈行并入上一条
                    if (!window.isEmpty()) {
                        LogLine previous = window.get(window.size() - 1);
                        window.set(window.size() - 1, new LogLine(previous.fileId(), previous.lineNo(),
                                previous.time(), previous.level(), previous.traceId(), previous.thread(),
                                previous.logger(), LogLineParser.appendStack(previous.message(), raw),
                                previous.raw() + "\n" + raw));
                    }
                    return true;
                }
                LogLine line = new LogLine(target.getFileName().toString(), lineNo, parsed.time(), parsed.level(),
                        parsed.traceId(), parsed.thread(), parsed.logger(), parsed.message(), parsed.raw());
                if (lineNo >= before && lineNo <= after) {
                    window.add(line);
                    if (lineNo == query.lineNo()) {
                        found[0] = true;
                    }
                }
                if (lineNo > after) {
                    return false;
                }
                return true;
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("日志文件读取失败: " + e.getMessage());
        }
        if (!found[0]) {
            throw BizException.badRequest("日志文件不存在或行号超出范围");
        }
        LogLine targetLine = window.stream().filter(l -> l.lineNo() == query.lineNo()).findFirst().orElse(null);
        if (targetLine == null) {
            throw BizException.badRequest("日志文件不存在或行号超出范围");
        }
        List<LogLineVO> beforeList = new ArrayList<>();
        List<LogLineVO> afterList = new ArrayList<>();
        window.forEach(line -> {
            if (line.lineNo() < query.lineNo()) {
                beforeList.add(com.snail.platform.function.monitor.log.dto.LogLineVO.from(line));
            } else if (line.lineNo() > query.lineNo()) {
                afterList.add(com.snail.platform.function.monitor.log.dto.LogLineVO.from(line));
            }
        });
        return new AppLogDetailVO(com.snail.platform.function.monitor.log.dto.LogLineVO.from(targetLine),
                beforeList, afterList);
    }

    // ---------- 内部 ----------

    /** 简单可变 long 载体 */
    private static final class AtomicLongRef {
        long value;
    }

    /** 候选文件:文件名日期或 mtime 与 [begin-1d, end+1d] 有交集,旧到新 */
    private List<Path> candidates(LocalDate begin, LocalDate end) {
        LocalDate low = begin.minusDays(1);
        LocalDate high = end.plusDays(1);
        return registry.files().stream()
                .filter(f -> {
                    var date = LogFileRegistry.dateOf(f.fileId());
                    return date.isEmpty() // 活跃文件恒入候选
                            || (!date.get().isBefore(low) && !date.get().isAfter(high));
                })
                .sorted(Comparator.comparing(LogFileRegistry.LogFile::mtime))
                .map(f -> registry.dir().resolve(f.fileId()))
                .toList();
    }

    /** level 精确 / traceId 精确 / keyword 忽略大小写子串 */
    private boolean matches(LogLine line, String level, String traceId, String keyword) {
        if (level != null && !level.isBlank() && !level.equals(line.level())) {
            return false;
        }
        if (traceId != null && !traceId.isBlank()
                && (line.traceId() == null || !line.traceId().equals(traceId))) {
            return false;
        }
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.toLowerCase();
            String haystack = (line.message() + " " + line.logger() + " " + line.thread()).toLowerCase();
            if (!haystack.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    /** 流式读文件(gz 自动解压),回调返回 false 即停;IO 异常转 400 */
    private void readLines(Path path, LineConsumer consumer) {
        try (BufferedReader reader = path.toString().endsWith(".gz")
                ? new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))
                : Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            long lineNo = 0;
            while ((raw = reader.readLine()) != null) {
                lineNo++;
                if (!consumer.accept(lineNo, raw)) {
                    return;
                }
            }
        } catch (IOException e) {
            throw BizException.badRequest("日志文件读取失败: " + e.getMessage());
        }
    }

    /** 行回调:返回 false 停止读取 */
    @FunctionalInterface
    private interface LineConsumer {
        boolean accept(long lineNo, String raw);
    }
}
