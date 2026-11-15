package com.snail.platform.function.monitor.diag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 职责:诊断检查注册表——12 项并行执行(虚拟线程 + Future.get 单项超时,DD-23),总预算兜底。
 * 上游:DiagnosticService.run();下游:DiagCheck 实现(Spring 注入全部 @Component 检查项)。
 * 口径:每项自带超时(HTTP 2s/SQL 1s),Future.get 只是兜底;超时项 skip「执行超时」不阻塞;
 * overall = 最差项(fail > warn > pass),全 skip 时 pass;执行器 finally shutdownNow 不留线程。
 */
@Component
public class CheckRegistry {

    /** 全部检查项(Spring 按 @Component 注入,顺序即类路径序) */
    private final List<DiagCheck> checks;

    public CheckRegistry(List<DiagCheck> checks) {
        this.checks = checks;
    }

    /** 并行执行全部检查;返回 items 与 overall */
    public Result runAll(Duration itemTimeout, Duration totalTimeout) {
        long start = System.currentTimeMillis();
        record Pending(DiagCheck check, Future<DiagCheck.Item> future) {
        }
        List<Pending> pendings = new ArrayList<>();
        // 编译目标 Java 17:newVirtualThreadPerTaskExecutor 不可用,用 cached 池等价并行;
        // 运行时由 spring.threads.virtual 驱动的调度仍是虚拟线程
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            checks.forEach(check -> pendings.add(new Pending(check,
                    executor.submit(() -> safeRun(check)))));
            long deadline = start + totalTimeout.toMillis();
            List<DiagCheck.Item> items = new ArrayList<>();
            for (Pending pending : pendings) {
                long remaining = Math.max(0, deadline - System.currentTimeMillis());
                try {
                    items.add(pending.future().get(Math.min(remaining, itemTimeout.toMillis()),
                            TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    pending.future().cancel(true);
                    items.add(DiagCheck.Item.skip(pending.check().key(), pending.check().name(),
                            "执行超时", "检查项超预算被跳过,可重跑确认"));
                }
            }
            long costMs = System.currentTimeMillis() - start;
            return new Result(items, overall(items), costMs);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单项安全执行:任何异常转 fail,永不抛出 */
    private DiagCheck.Item safeRun(DiagCheck check) {
        long start = System.nanoTime();
        try {
            return check.run();
        } catch (Exception e) {
            return DiagCheck.Item.fail(check.key(), check.name(), (System.nanoTime() - start) / 1_000_000,
                    "检查执行异常: " + e.getMessage(), "查看应用日志定位异常堆栈");
        }
    }

    /** overall:最差项 fail > warn > pass;全 skip 视为 pass */
    static String overall(List<DiagCheck.Item> items) {
        boolean anyWarn = false;
        boolean anyPass = false;
        for (DiagCheck.Item item : items) {
            switch (item.status()) {
                case "fail" -> {
                    return "fail";
                }
                case "warn" -> anyWarn = true;
                case "pass" -> anyPass = true;
                default -> {
                }
            }
        }
        return anyWarn ? "warn" : anyPass ? "pass" : "pass";
    }

    /**
     * 执行结果:items + overall + 总耗时。
     */
    public record Result(
            /** 检查项列表 */
            List<DiagCheck.Item> items,
            /** 总体结论:pass/warn/fail */
            String overall,
            /** 总耗时(ms) */
            long costMs) {
    }
}
