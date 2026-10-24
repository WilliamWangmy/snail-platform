package com.snail.platform.function.notify.api;

import java.util.List;

/**
 * 职责:业务模块发通知的唯一入口——住 notify 的 api 包,跨模块只准 import 本包(ArchUnit 机械执法)。
 * 发送即落库:消息主体 + 收件行(分批批量),出站通道(邮件等)经投递器在事务提交后异步跟进。
 * 上游:各业务模块与 NotifyPlatformController;下游:NotifyService。
 * 口径:command.tenantId 是收件人归属租户,发送方上下文不一致时实现侧自行切换;部分收件人已失效则静默缺席。
 */
public interface NotifyApi {

    /** 定向发送;title/content 空白或收件人为空抛业务拒绝,单次收件人上限 5000 */
    void send(NotifySendCommand command);

    /**
     * 发送命令:recipients 为收件人用户 ID 集;sender 快照由调用方从登录人上下文带入,
     * 系统代发(senderId 为 null)时 senderName 写"系统通知"。
     */
    record NotifySendCommand(
            /** 收件人归属租户 */
            Long tenantId,
            /** 标题 */
            String title,
            /** 正文,纯文本 */
            String content,
            /** 业务域标签:announcement/account/… */
            String bizType,
            /** 关联业务 ID */
            Long bizId,
            /** 发送人用户 ID,系统代发为 null */
            Long senderId,
            /** 发送人显示名快照 */
            String senderName,
            /** 收件人用户 ID 集 */
            List<Long> recipients) {
    }
}
