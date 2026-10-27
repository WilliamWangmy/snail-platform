package com.snail.platform.function.workflow.task.service;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.function.notify.api.NotifyApi;
import com.snail.platform.function.notify.api.NotifyApi.NotifySendCommand;
import com.snail.platform.function.system.api.UserBriefPort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 职责:流程侧的通知出口——办理动作产生的提醒一律经 NotifyApi 发,自己不留第二套消息表(方案 §7)。
 * 上游:WorkflowTaskService 的签收/办理/转办;下游:notify 模块的收件箱与出站通道。
 * 口径:发送排在事务提交之后。办理动作与通知不是同一件事:审批已经成立,通知渠道挂了也不该把单子退回未办,
 * 所以这里只登记 afterCommit 回调,失败落日志不回滚、不重试(重试与死信属 P5 观测线)。
 * 收件人在登记时就算好:afterCommit 里线程上下文正在清理,那时再去查角色成员会撞上"无租户上下文"。
 */
@Service
public class WorkflowNotifyService {

    /** 通知业务域标签,与收件箱筛选对齐 */
    private static final String BIZ_TYPE = "workflow";

    /** 日志:通知失败只留痕,不打断已提交的审批 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowNotifyService.class);

    /** 通知发送唯一入口 */
    private final NotifyApi notifyApi;

    /** 用户档案端口:候选角色要展开成具体收件人 */
    private final UserBriefPort userBriefPort;

    public WorkflowNotifyService(NotifyApi notifyApi, UserBriefPort userBriefPort) {
        this.notifyApi = notifyApi;
        this.userBriefPort = userBriefPort;
    }

    /**
     * 给一张任务找收件人并提醒:已签收的提醒办理人,未签收的提醒候选角色的全部启用成员。
     * 无人可提醒(角色空挂)即静默返回——这不是错误,是没人在等这张单子。
     * 文案里带业务单号:收件人要能从通知跳回单据,只知道"有个流程"不够。
     */
    public void remindTodo(WorkflowTask task, String instanceName, String businessKey) {
        Set<Long> recipients = recipientsOf(task);
        if (recipients.isEmpty()) {
            return;
        }
        String title = "待办提醒:" + blankTo(instanceName, "流程任务");
        String content = "节点「" + blankTo(task.name(), task.taskDefinitionKey()) + "」等待您办理。"
                + coordinate(businessKey);
        sendAfterCommit(title, content, List.copyOf(recipients));
    }

    /** 转办后提醒接手人;办理完成后提醒发起人(结果告知) */
    public void notifyUsers(String title, String content, List<Long> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        sendAfterCommit(title, content, recipients);
    }

    /** 单号片段:裸流程(没有业务单)就不带这句,通知里不写"单号:null" */
    public static String coordinate(String businessKey) {
        return businessKey == null || businessKey.isBlank() ? "" : "业务单号:" + businessKey + "。";
    }

    /**
     * 撤销后给当时在册任务的收件人一句话:待办会随实例一起消失,不说明就是凭空蒸发的一张单。
     * 收件人跨任务去重——同一个人卡在两个节点上,不该收到两条一模一样的撤销通知。
     */
    public void notifyWithdrawn(List<WorkflowTask> openTasks, String instanceName, String businessKey) {
        Set<Long> recipients = new LinkedHashSet<>();
        openTasks.forEach(task -> recipients.addAll(recipientsOf(task)));
        if (recipients.isEmpty()) {
            return;
        }
        notifyUsers("审批已撤销:" + blankTo(instanceName, "流程"),
                "「" + blankTo(instanceName, "流程") + "」已被撤销,无需办理。" + coordinate(businessKey),
                List.copyOf(recipients));
    }

    private static String blankTo(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw;
    }

    /** 收件人展开:办理人优先,否则候选角色成员并集 */
    private Set<Long> recipientsOf(WorkflowTask task) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (task.assignee() != null) {
            addIfNumeric(recipients, task.assignee());
            return recipients;
        }
        for (String roleId : task.candidateGroups()) {
            try {
                recipients.addAll(userBriefPort.enabledUserIdsOfRole(Long.valueOf(roleId.trim())));
            } catch (NumberFormatException e) {
                LOG.warn("流程候选组不是角色 ID,跳过提醒:{}", roleId);
            }
        }
        return recipients;
    }

    private static void addIfNumeric(Set<Long> target, String raw) {
        try {
            target.add(Long.valueOf(raw.trim()));
        } catch (NumberFormatException e) {
            LOG.warn("流程办理人不是平台用户 ID,跳过提醒:{}", raw);
        }
    }

    /** 登记提交后发送;当前不在事务里(如测试直调)就立即发 */
    private void sendAfterCommit(String title, String content, List<Long> recipients) {
        Long tenantId = currentTenantId();
        Long senderId = currentUserId();
        String senderName = currentNickname();
        NotifySendCommand command = new NotifySendCommand(tenantId, title, content, BIZ_TYPE, null,
                senderId, senderName == null ? "系统通知" : senderName, recipients);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(command);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(command);
            }
        });
    }

    private void dispatch(NotifySendCommand command) {
        try {
            notifyApi.send(command);
        } catch (RuntimeException e) {
            LOG.warn("流程通知发送失败,已提交的动作不受影响:{}", e.getMessage());
        }
    }

    private Long currentTenantId() {
        LoginUser user = LoginUserContextHolder.get();
        return user == null ? null : user.tenantId();
    }

    private Long currentUserId() {
        LoginUser user = LoginUserContextHolder.get();
        return user == null ? null : user.userId();
    }

    private String currentNickname() {
        LoginUser user = LoginUserContextHolder.get();
        return user == null ? null : user.nickname();
    }
}
