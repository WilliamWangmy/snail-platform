package com.snail.platform.function.monitor.diag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:线程快照出参契约(主方案 §5.4,冻结)——线程列表 + 死锁线程 ID 列表。
 */
@Schema(description = "线程快照")
public record ThreadSnapshotVO(

        @Schema(description = "线程列表")
        List<Item> threads,

        @Schema(description = "死锁线程 ID 列表(无死锁为空)")
        List<Long> deadlocked) {

    /**
     * 线程项:name/state/daemon/stackTop(8 行)。
     */
    public record Item(
            /** 线程名 */
            String name,
            /** 状态 */
            String state,
            /** 是否守护线程 */
            boolean daemon,
            /** 栈顶 8 行 */
            List<String> stackTop) {
    }
}
