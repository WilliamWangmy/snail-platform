package com.snail.platform.function.storage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:租户存储用量出参——平台侧租户详情抽屉"存储总览"区的数据面(统计层设计 §5.5,契约冻结)。
 * 口径:limitMb 为 null=不限(前端显示"不限",使用率与剩余不渲染);
 * 剩余与使用率由前端按 limitMb/usedBytes 计算,后端不重复给;
 * usedBytes/fileCount 与配额校验链同一 WHERE 口径(deleted=0,租户条件显式),
 * 上传被拒时统计同步——同一聚合天然一致(D-05)。
 * 上游:FileQuotaService.usage;下游:平台管理端。
 */
@Schema(description = "租户存储用量")
public record StorageUsageVO(

        @Schema(description = "存储配额 MB,null 为不限;前端按 GB 展示(MB÷1024)")
        Long limitMb,

        @Schema(description = "已用字节数")
        long usedBytes,

        @Schema(description = "文件数(不含已逻辑删)")
        long fileCount) {
}
