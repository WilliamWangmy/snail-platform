package com.snail.platform.function.monitor.diag.service;

import com.snail.platform.function.monitor.diag.dto.ThreadSnapshotVO;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 职责:线程快照服务——列表(栈顶 8 行 + 死锁 ID)与全量 dump 文本下载。
 * 上游:DiagController;下游:ThreadMXBean。
 * 口径:list 用 getAllThreadIds + getThreadInfo(id, 8);download 用 dumpAllThreads(true, true)
 * 逐条 ThreadInfo.toString() 拼分隔线,UTF-8 bytes,文件头含时间与 JVM 摘要。
 */
@Service
public class ThreadSnapshotService {

    private static final int STACK_TOP_LINES = 8;

    /** 线程列表:栈顶 8 行 + 死锁线程 ID */
    public ThreadSnapshotVO list() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();
        List<Long> deadlockedIds = deadlocked == null ? List.of()
                : Arrays.stream(deadlocked).boxed().toList();
        ThreadInfo[] infos = threads.getThreadInfo(threads.getAllThreadIds(), STACK_TOP_LINES);
        List<ThreadSnapshotVO.Item> items = new ArrayList<>();
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            items.add(new ThreadSnapshotVO.Item(info.getThreadName(), info.getThreadState().name(),
                    info.isDaemon(), stackTop(info)));
        }
        return new ThreadSnapshotVO(items, deadlockedIds);
    }

    /** 全量 dump 文本(UTF-8) */
    public byte[] download() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("线程快照\n");
        sb.append("时间: ").append(java.time.LocalDateTime.now().withNano(0)).append("\n");
        sb.append("JVM: ").append(System.getProperty("java.vm.name")).append(" ")
                .append(System.getProperty("java.version")).append("\n");
        sb.append("存活线程: ").append(threads.getThreadCount()).append("\n");
        sb.append("=====================================\n\n");
        ThreadInfo[] infos = threads.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            sb.append(info.toString()).append("\n");
            sb.append("-------------------------------------\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<String> stackTop(ThreadInfo info) {
        List<String> lines = new ArrayList<>();
        for (StackTraceElement element : info.getStackTrace()) {
            lines.add(element.toString());
            if (lines.size() >= STACK_TOP_LINES) {
                break;
            }
        }
        return lines;
    }
}
