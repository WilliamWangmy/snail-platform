package com.snail.platform.function.notify.dto;

import com.snail.platform.function.notify.entity.SysNotifyMessage;
import com.snail.platform.function.notify.entity.SysNotifyRecipient;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:收件箱出参对象——消息主体与本人收件行的投影视图。
 * 上游:NotifyService.inbox;下游:收件箱列表与详情抽屉。
 */
@Schema(description = "收件箱条目")
public record NotifyInboxItemVO(

        @Schema(description = "收件行 ID")
        Long recipientId,

        @Schema(description = "消息 ID")
        Long messageId,

        @Schema(description = "标题")
        String title,

        @Schema(description = "正文,纯文本")
        String content,

        @Schema(description = "业务域标签")
        String bizType,

        @Schema(description = "关联业务 ID")
        Long bizId,

        @Schema(description = "发送人显示名快照")
        String senderName,

        @Schema(description = "发送时间")
        LocalDateTime sendTime,

        @Schema(description = "已读状态:0 未读 1 已读")
        Integer readStatus,

        @Schema(description = "已读时间")
        LocalDateTime readTime) {

    public static NotifyInboxItemVO of(SysNotifyRecipient recipient, SysNotifyMessage message) {
        return new NotifyInboxItemVO(recipient.getRecipientId(), recipient.getMessageId(),
                message.getTitle(), message.getContent(), message.getBizType(), message.getBizId(),
                message.getSenderName(), message.getSendTime(),
                recipient.getReadStatus(), recipient.getReadTime());
    }
}
