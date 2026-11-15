package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:日志文件枚举出参契约(主方案 §5.3,冻结)——source 回显实际模式;file 模式为服务端枚举 ID,
 * loki 模式固定单条 selector。
 */
@Schema(description = "日志文件列表")
public record LogFilesVO(

        @Schema(description = "实际模式:file / loki")
        String source,

        @Schema(description = "文件项(file 模式)/单条 selector(loki 模式)")
        List<Item> files) {

    /**
     * 文件项:fileId 白名单键。
     */
    public record Item(
            /** 文件 ID(=文件名) */
            String fileId,
            /** 名称展示 */
            String name,
            /** 大小(字节) */
            long sizeBytes,
            /** 修改时间 ISO */
            String mtime) {
    }
}
