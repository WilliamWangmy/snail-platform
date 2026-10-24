package com.snail.platform.function.notify.dto;

import com.snail.platform.function.notify.entity.SysNotifyMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:平台公告出参对象——消息主体投影 + 扇出人数统计。
 * 上游:NotifyService.announcements;下游:公告管理列表。
 */
@Schema(description = "公告信息")
public record AnnouncementVO(

        @Schema(description = "消息 ID")
        Long messageId,

        @Schema(description = "目标租户 ID")
        Long tenantId,

        @Schema(description = "标题")
        String title,

        @Schema(description = "正文,纯文本")
        String content,

        @Schema(description = "发送人显示名快照")
        String senderName,

        @Schema(description = "发送时间")
        LocalDateTime sendTime,

        @Schema(description = "扇出收件人数")
        long recipientCount) {

    public static AnnouncementVO of(SysNotifyMessage message, long recipientCount) {
        return new AnnouncementVO(message.getMessageId(), message.getTenantId(), message.getTitle(),
                message.getContent(), message.getSenderName(), message.getSendTime(), recipientCount);
    }
}
