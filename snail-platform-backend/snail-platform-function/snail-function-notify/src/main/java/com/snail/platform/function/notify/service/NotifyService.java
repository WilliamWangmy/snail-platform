package com.snail.platform.function.notify.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.function.notify.api.NotifyApi;
import com.snail.platform.function.notify.api.NotifyApi.NotifySendCommand;
import com.snail.platform.function.notify.dto.AnnouncementQuery;
import com.snail.platform.function.notify.dto.AnnouncementSendRequest;
import com.snail.platform.function.notify.dto.AnnouncementVO;
import com.snail.platform.function.notify.dto.NotifyInboxItemVO;
import com.snail.platform.function.notify.dto.NotifyInboxQuery;
import com.snail.platform.function.notify.entity.SysNotifyMessage;
import com.snail.platform.function.notify.entity.SysNotifyRecipient;
import com.snail.platform.function.notify.mapper.SysNotifyMessageMapper;
import com.snail.platform.function.notify.mapper.SysNotifyRecipientMapper;
import com.snail.platform.function.system.api.UserBriefPort;
import com.snail.platform.function.system.api.UserBriefPort.UserBrief;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:站内通知全链——发送(消息主体 + 收件行分批批量落库)、收件箱分页、未读数、已读/全部已读、
 * 收件箱移除、平台公告扇出。出站通道(邮件等)经 OutboundDispatcher 在事务提交后异步跟进。
 * 口径:收件人是私人数据,查询谓词恒带本人 user_id,不走数据权限开窗;
 * 收件人显示名发送时快照,此后改名不回溯;对 system 的依赖只经 UserBriefPort(api 包)。
 * 上游:NotifyController/NotifyPlatformController 与各业务模块的 NotifyApi 调用;
 * 下游:sys_notify_message/sys_notify_recipient 写读、OutboundDispatcher、UserBriefPort。
 */
@Service
public class NotifyService implements NotifyApi {

    /** 公告业务域标签:平台面列表按它圈定 */
    public static final String BIZ_ANNOUNCEMENT = "announcement";

    /** 单次收件人上限,公告扇出同此闸,防扇出行数失控 */
    private static final int MAX_RECIPIENTS = 5000;

    /** 已读:0 未读 1 已读 */
    private static final int UNREAD = 0;
    private static final int READ = 1;

    /** 系统代发时的发送人署名 */
    private static final String SYSTEM_SENDER = "系统通知";

    /** 消息主体读写 */
    private final SysNotifyMessageMapper messageMapper;

    /** 收件行读写 */
    private final SysNotifyRecipientMapper recipientMapper;

    /** 出站投递器 */
    private final OutboundDispatcher dispatcher;

    /** 收件人简要信息,经 system 的 api 包端口 */
    private final UserBriefPort userBriefPort;

    public NotifyService(SysNotifyMessageMapper messageMapper, SysNotifyRecipientMapper recipientMapper,
                         OutboundDispatcher dispatcher, UserBriefPort userBriefPort) {
        this.messageMapper = messageMapper;
        this.recipientMapper = recipientMapper;
        this.dispatcher = dispatcher;
        this.userBriefPort = userBriefPort;
    }

    @Override
    @Transactional
    public void send(NotifySendCommand command) {
        String title = StrUtil.trimToNull(command.title());
        String content = StrUtil.trimToNull(command.content());
        if (title == null || content == null) {
            throw BizException.badRequest("标题与正文不能为空");
        }
        if (command.tenantId() == null) {
            throw BizException.badRequest("收件租户不能为空");
        }
        List<Long> recipients = command.recipients() == null ? List.of()
                : command.recipients().stream().filter(Objects::nonNull).distinct().toList();
        if (recipients.isEmpty()) {
            throw BizException.badRequest("收件人不能为空");
        }
        if (recipients.size() > MAX_RECIPIENTS) {
            throw BizException.badRequest("单次收件人超过上限 " + MAX_RECIPIENTS + ",请分批发送");
        }
        // 消息与收件行落收件人租户;调用方上下文不一致(平台代发)时在此切换,同 provision 的"替别人写数据"口径
        TenantContextHolder.asTenant(command.tenantId(), () -> {
            doSend(command, title, content, recipients);
            return null;
        });
    }

    /** 平台公告:目标租户全员扇出,上限超限拒绝;发送人取当前平台管理员快照 */
    @Transactional
    public void announce(AnnouncementSendRequest request) {
        LoginUser operator = LoginUserContextHolder.get();
        List<UserBrief> targets = userBriefPort.enabledUsersOfTenant(request.tenantId());
        if (targets.size() > MAX_RECIPIENTS) {
            throw BizException.badRequest("目标租户启用用户数超过 " + MAX_RECIPIENTS + ",请改用业务接口分批发送");
        }
        send(new NotifySendCommand(request.tenantId(), request.title(), request.content(), BIZ_ANNOUNCEMENT,
                null, operator == null ? null : operator.userId(),
                operator == null ? SYSTEM_SENDER : operator.nickname(),
                targets.stream().map(UserBrief::userId).toList()));
    }

    /** 收件箱分页:私人数据,谓词恒带本人;bizType 在消息主体上,先解析消息 ID 再过滤收件行 */
    public PageResult<NotifyInboxItemVO> inbox(NotifyInboxQuery query) {
        Long userId = requireLoginUser();
        Page<SysNotifyRecipient> page = PageAssembler.from(query);
        List<Long> bizMessageIds = bizMessageIdsOf(query.bizType());
        if (bizMessageIds != null && bizMessageIds.isEmpty()) {
            // 该业务面下一条消息都没有:空集合拼进 IN 是 SQL 语法错误,而不是"查无结果"
            return PageResult.of(List.of(), 0);
        }
        IPage<SysNotifyRecipient> result = recipientMapper.selectPage(page, Wrappers.<SysNotifyRecipient>lambdaQuery()
                .eq(SysNotifyRecipient::getUserId, userId)
                .eq(query.readStatus() != null, SysNotifyRecipient::getReadStatus, query.readStatus())
                .in(bizMessageIds != null, SysNotifyRecipient::getMessageId, bizMessageIds)
                .orderByDesc(SysNotifyRecipient::getRecipientId));
        Map<Long, SysNotifyMessage> messages = messagesOf(result.getRecords());
        return PageResult.of(result.getRecords().stream()
                .map(row -> NotifyInboxItemVO.of(row, messages.get(row.getMessageId()))).toList(),
                result.getTotal());
    }

    /** 本人未读数,索引直达,不加缓存(读频但基数小,失效面不值得) */
    public long unreadCount() {
        Long userId = requireLoginUser();
        Long count = recipientMapper.selectCount(Wrappers.<SysNotifyRecipient>lambdaQuery()
                .eq(SysNotifyRecipient::getUserId, userId)
                .eq(SysNotifyRecipient::getReadStatus, UNREAD));
        return count == null ? 0 : count;
    }

    /** 单条详情,须为本人收件行;他人行按"不存在"处理,存在性即隐私 */
    public NotifyInboxItemVO detail(Long recipientId) {
        SysNotifyRecipient recipient = requireOwnRecipient(recipientId);
        SysNotifyMessage message = messageMapper.selectById(recipient.getMessageId());
        if (message == null) {
            throw BizException.badRequest("通知不存在");
        }
        return NotifyInboxItemVO.of(recipient, message);
    }

    /** 标记已读;已读行幂等返回 */
    public void markRead(Long recipientId) {
        SysNotifyRecipient recipient = requireOwnRecipient(recipientId);
        if (!Integer.valueOf(UNREAD).equals(recipient.getReadStatus())) {
            return;
        }
        recipientMapper.update(new SysNotifyRecipient(), Wrappers.<SysNotifyRecipient>lambdaUpdate()
                .set(SysNotifyRecipient::getReadStatus, READ)
                .set(SysNotifyRecipient::getReadTime, LocalDateTime.now())
                .eq(SysNotifyRecipient::getRecipientId, recipientId));
    }

    /** 全部已读:只动本人未读行 */
    public void markAllRead() {
        Long userId = requireLoginUser();
        recipientMapper.update(new SysNotifyRecipient(), Wrappers.<SysNotifyRecipient>lambdaUpdate()
                .set(SysNotifyRecipient::getReadStatus, READ)
                .set(SysNotifyRecipient::getReadTime, LocalDateTime.now())
                .eq(SysNotifyRecipient::getUserId, userId)
                .eq(SysNotifyRecipient::getReadStatus, UNREAD));
    }

    /** 收件箱移除:逻辑删本人收件行,消息主体保留 */
    public void remove(Long recipientId) {
        SysNotifyRecipient recipient = requireOwnRecipient(recipientId);
        recipientMapper.deleteById(recipient.getRecipientId());
    }

    /** 平台公告分页:跨租户检索,走登记的 bypass 窄窗口(平台面管理动作) */
    public PageResult<AnnouncementVO> announcements(AnnouncementQuery query) {
        Page<SysNotifyMessage> page = PageAssembler.from(query);
        IPage<SysNotifyMessage> result = TenantContextHolder.withoutTenant(() ->
                messageMapper.selectPage(page, Wrappers.<SysNotifyMessage>lambdaQuery()
                        .eq(SysNotifyMessage::getBizType, BIZ_ANNOUNCEMENT)
                        .like(StrUtil.isNotBlank(query.title()), SysNotifyMessage::getTitle, query.title())
                        .orderByDesc(SysNotifyMessage::getMessageId)));
        if (result.getRecords().isEmpty()) {
            return PageResult.of(List.of(), result.getTotal());
        }
        List<Long> messageIds = result.getRecords().stream().map(SysNotifyMessage::getMessageId).toList();
        // 扇出人数按 message_id 分组一次查出,避免逐条 count 造成 N+1
        Map<Long, Long> counts = TenantContextHolder.withoutTenant(() ->
                        recipientMapper.selectMaps(new QueryWrapper<SysNotifyRecipient>()
                                .select("message_id", "count(*) AS cnt")
                                .in("message_id", messageIds)
                                .groupBy("message_id")))
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("message_id")).longValue(),
                        row -> ((Number) row.get("cnt")).longValue()));
        return PageResult.of(result.getRecords().stream()
                .map(message -> AnnouncementVO.of(message, counts.getOrDefault(message.getMessageId(), 0L)))
                .toList(), result.getTotal());
    }

    /** 发送主体:插消息、按快照批量插收件行、事务提交后交投递器 */
    private void doSend(NotifySendCommand command, String title, String content, List<Long> recipients) {
        SysNotifyMessage message = new SysNotifyMessage();
        message.setTitle(title);
        message.setContent(content);
        message.setBizType(command.bizType());
        message.setBizId(command.bizId());
        message.setSenderId(command.senderId());
        message.setSenderName(StrUtil.isBlank(command.senderName()) ? SYSTEM_SENDER : command.senderName());
        message.setSendTime(LocalDateTime.now());
        messageMapper.insert(message);

        List<UserBrief> briefs = userBriefPort.briefs(recipients);
        if (briefs.isEmpty()) {
            // 收件人全部无效时抛错,事务把刚插入的消息主体一起回滚,不留没有收件人的空壳消息
            throw BizException.badRequest("收件人均不存在或已跨租户");
        }
        List<OutboundChannel.OutboundTarget> targets = briefs.stream()
                .map(brief -> new OutboundChannel.OutboundTarget(brief.userId(), brief.email())).toList();
        List<SysNotifyRecipient> rows = briefs.stream().map(brief -> {
            SysNotifyRecipient row = new SysNotifyRecipient();
            row.setMessageId(message.getMessageId());
            row.setUserId(brief.userId());
            row.setNickname(brief.nickname());
            row.setReadStatus(UNREAD);
            return row;
        }).toList();
        // 批量插入收件行;Db 复用当前事务的 SqlSession,租户注入与审计填充同样生效
        Db.saveBatch(rows);

        dispatcher.dispatchAfterCommit(new OutboundDispatcher.DispatchJob(message.getMessageId(),
                command.tenantId(), title, content, targets));
    }

    /** bizType 过滤的前置消息 ID 集;未传返回 null 表示不参与过滤 */
    private List<Long> bizMessageIdsOf(String bizType) {
        if (StrUtil.isBlank(bizType)) {
            return null;
        }
        return messageMapper.selectList(Wrappers.<SysNotifyMessage>lambdaQuery()
                        .select(SysNotifyMessage::getMessageId)
                        .eq(SysNotifyMessage::getBizType, bizType))
                .stream().map(SysNotifyMessage::getMessageId).toList();
    }

    /** 批量取消息主体,避免收件行逐条回查 */
    private Map<Long, SysNotifyMessage> messagesOf(List<SysNotifyRecipient> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> messageIds = rows.stream().map(SysNotifyRecipient::getMessageId).distinct().toList();
        return messageMapper.selectBatchIds(messageIds).stream()
                .collect(Collectors.toMap(SysNotifyMessage::getMessageId, Function.identity()));
    }

    /** 取本人收件行;他人行与不存在同口径拒绝——存在性即隐私,不区分"无权"与"没有" */
    private SysNotifyRecipient requireOwnRecipient(Long recipientId) {
        Long userId = requireLoginUser();
        SysNotifyRecipient recipient = recipientMapper.selectById(recipientId);
        if (recipient == null || !Objects.equals(recipient.getUserId(), userId)) {
            throw BizException.badRequest("通知不存在");
        }
        return recipient;
    }

    /** 服务层再兜一次登录态:控制器虽已要求认证,内部直调与未来的非 HTTP 入口不受它保护 */
    private Long requireLoginUser() {
        Long userId = LoginUserContextHolder.userId();
        if (userId == null) {
            throw BizException.unauthorized("未认证");
        }
        return userId;
    }
}
