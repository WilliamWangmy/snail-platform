package com.snail.platform.function.notify.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:平台公告发送契约。目标租户的用户扇出与上限保护在服务端,契约只收三要素。
 * 上游:公告管理页面;下游:NotifyService.announce。
 */
@Schema(description = "发送公告请求")
public record AnnouncementSendRequest(

        @Schema(description = "目标租户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择目标租户")
        Long tenantId,

        @Schema(description = "公告标题", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入公告标题")
        @Size(max = 100, message = "标题过长,至多 100 字")
        String title,

        @Schema(description = "公告正文,纯文本", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入公告正文")
        @Size(max = 5000, message = "正文过长,至多 5000 字")
        String content) {
}
