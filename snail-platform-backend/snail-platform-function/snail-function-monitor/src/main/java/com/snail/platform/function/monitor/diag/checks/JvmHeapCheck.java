package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import org.springframework.stereotype.Component;

/**
 * jvm.heap:堆使用率 used/max;>80% warn、>90% fail。
 */
@Component
public class JvmHeapCheck implements DiagCheck {

    @Override
    public String key() {
        return "jvm.heap";
    }

    @Override
    public String name() {
        return "JVM 堆";
    }

    @Override
    public Item run() {
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            long used = memory.getHeapMemoryUsage().getUsed();
            long max = memory.getHeapMemoryUsage().getMax();
            if (max <= 0) {
                return Item.fail(key(), name(), null, "堆上限读取失败(-Xmx 未定义?)", "检查 JVM 启动参数");
            }
            double percent = Math.round(used * 1000.0 / max) / 10.0;
            if (percent > 90) {
                return Item.fail(key(), name(), null, "堆使用率 " + percent + "%(>90%)",
                        "排查大对象/缓存无界增长,必要时调堆或加压测");
            }
            if (percent > 80) {
                return Item.warn(key(), name(), null, "堆使用率 " + percent + "%(>80%)",
                        "观察 GC 频率与堆趋势,排查泄漏");
            }
            return Item.pass(key(), name(), null, "堆使用率 " + percent + "%(" + used / 1048576 + "/" + max / 1048576 + "MB)", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), null, "堆探测失败: " + e.getMessage(), null);
        }
    }
}
