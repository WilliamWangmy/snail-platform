package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.springframework.stereotype.Component;

/**
 * jvm.threads:存活线程数 + 死锁检测;>500 warn、死锁或 >1000 fail。
 */
@Component
public class JvmThreadsCheck implements DiagCheck {

    @Override
    public String key() {
        return "jvm.threads";
    }

    @Override
    public String name() {
        return "JVM 线程";
    }

    @Override
    public Item run() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            int live = threads.getThreadCount();
            long[] deadlocked = threads.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                return Item.fail(key(), name(), null, "检测到 " + deadlocked.length + " 个死锁线程",
                        "下载线程快照分析锁竞争,检查锁顺序与超时");
            }
            if (live > 1000) {
                return Item.fail(key(), name(), null, "存活线程 " + live + "(>1000)",
                        "排查线程池无界创建,下载线程快照定位来源");
            }
            if (live > 500) {
                return Item.warn(key(), name(), null, "存活线程 " + live + "(>500)",
                        "关注线程数趋势与线程池配置");
            }
            return Item.pass(key(), name(), null, "存活线程 " + live, null);
        } catch (Exception e) {
            return Item.fail(key(), name(), null, "线程探测失败: " + e.getMessage(), null);
        }
    }
}
