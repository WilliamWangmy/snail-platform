package com.snail.platform.function.workflow.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.function.workflow.task.entity.SysWorkflowTaskRecord;
import com.snail.platform.function.workflow.task.mapper.SysWorkflowTaskRecordMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 职责:审批台账的单点写入与读取(方案 §4.3)——流程侧一切"谁在什么时候对这单做了什么"都从这里落。
 * 上游:task 子域的办理动作与 instance 子域的发起/撤销;下游:sys_workflow_task_record 与轨迹页。
 * 口径:操作人五列在本类内部从登录人快照取,调用方无权自报——留痕一旦能被调用方填,就不是证据了;
 * 时刻同理由服务端时钟给出。写入与引擎动作同事务(调用方的 @Transactional 覆盖到这里),
 * 引擎写成功而台账失败会整体回滚,不允许出现"流程走了但没记录"。
 * 表只增不更不删:没有 update 入口,查询只给按实例与按业务单两条既定路径。
 */
@Service
public class WorkflowTaskRecordService {

    /** 动作:发起 */
    public static final String ACTION_START = "start";

    /** 动作:签收 */
    public static final String ACTION_CLAIM = "claim";

    /** 动作:通过 */
    public static final String ACTION_APPROVE = "approve";

    /** 动作:驳回 */
    public static final String ACTION_REJECT = "reject";

    /** 动作:转办 */
    public static final String ACTION_TRANSFER = "transfer";

    /** 动作:撤销 */
    public static final String ACTION_WITHDRAW = "withdraw";

    /** 台账动作白名单:越界即调用方写错,不接受字符串直通 */
    private static final List<String> ACTIONS =
            List.of(ACTION_START, ACTION_CLAIM, ACTION_APPROVE, ACTION_REJECT, ACTION_TRANSFER, ACTION_WITHDRAW);

    /** 台账表写 */
    private final SysWorkflowTaskRecordMapper recordMapper;

    public WorkflowTaskRecordService(SysWorkflowTaskRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /**
     * 落一条台账:动作 + 引擎坐标 + 业务关联 + 意见(转办再带目标人),操作人与时刻由本层补齐。
     * 发起与撤销没有任务坐标,taskId/taskDefinitionKey/taskName 留空是既定形态,不是缺数据。
     */
    public void record(Trace trace) {
        if (!ACTIONS.contains(trace.action())) {
            throw BizException.badRequest("台账动作不合法:" + trace.action());
        }
        LoginUser operator = requireOperator();
        SysWorkflowTaskRecord row = new SysWorkflowTaskRecord();
        row.setProcessInstanceId(trace.processInstanceId());
        row.setTaskId(trace.taskId());
        row.setTaskDefinitionKey(trace.taskDefinitionKey());
        row.setTaskName(trace.taskName());
        row.setBusinessType(trace.businessType());
        row.setBusinessKey(trace.businessKey());
        row.setAction(trace.action());
        row.setOperatorId(operator.userId());
        row.setOperatorUsername(operator.username());
        row.setOperatorNickname(operator.nickname());
        row.setOperatorOrgId(operator.orgId());
        row.setOperatorOrgName(operator.orgName());
        row.setTargetUserId(trace.targetUserId());
        row.setTargetUserNickname(trace.targetNickname());
        row.setComment(trace.comment());
        row.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(row);
    }

    /** 某实例的台账,按时刻正序——轨迹页画时间轴用 */
    public List<SysWorkflowTaskRecord> listByInstance(String processInstanceId) {
        return recordMapper.selectList(Wrappers.<SysWorkflowTaskRecord>lambdaQuery()
                .eq(SysWorkflowTaskRecord::getProcessInstanceId, processInstanceId)
                .orderByAsc(SysWorkflowTaskRecord::getCreatedAt, SysWorkflowTaskRecord::getRecordId));
    }

    /** 某业务单的台账,按时刻正序:businessType + businessKey 是方案 §7 的唯一关联键 */
    public List<SysWorkflowTaskRecord> listByBusiness(String businessType, String businessKey) {
        return recordMapper.selectList(Wrappers.<SysWorkflowTaskRecord>lambdaQuery()
                .eq(SysWorkflowTaskRecord::getBusinessType, businessType)
                .eq(SysWorkflowTaskRecord::getBusinessKey, businessKey)
                .orderByAsc(SysWorkflowTaskRecord::getCreatedAt, SysWorkflowTaskRecord::getRecordId));
    }

    /**
     * 实例的业务域标签:取该实例最早一条台账(发起行)上记的值,没有台账即这条流程不挂业务单,返回 null。
     * 之所以回查台账而不是加引擎变量:businessType 是平台侧的关联键成分,引擎不需要它就能跑,
     * 把它塞进流程变量等于让业务标签参与流程执行。
     */
    public String businessTypeOf(String processInstanceId) {
        return listByInstance(processInstanceId).stream()
                .map(SysWorkflowTaskRecord::getBusinessType)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 实例最后一个"能定结果"的动作:通过/驳回/撤销三选一,没有则返回 null。
     * 已结束实例的业务结果从这里反推而不是读引擎变量——历史级别是 AUDIT,变量明细不入表(方案 §4.1),
     * 而台账本来就是平台自己的事实记录,用它当状态口径才是自洽的。
     * 签收/转办不参与:它们改变任务归属,不改变流程结果。
     */
    public String lastOutcomeActionOf(String processInstanceId) {
        return lastOutcomeActionsOf(List.of(processInstanceId)).get(processInstanceId);
    }

    /** 一批实例的定结果动作,一次查完:列表页逐行回查台账就是 N+1(约定 §7) */
    public Map<String, String> lastOutcomeActionsOf(Collection<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Map.of();
        }
        List<SysWorkflowTaskRecord> rows = recordMapper.selectList(Wrappers.<SysWorkflowTaskRecord>lambdaQuery()
                .in(SysWorkflowTaskRecord::getProcessInstanceId, processInstanceIds)
                .in(SysWorkflowTaskRecord::getAction, List.of(ACTION_APPROVE, ACTION_REJECT, ACTION_WITHDRAW))
                .orderByAsc(SysWorkflowTaskRecord::getCreatedAt, SysWorkflowTaskRecord::getRecordId));
        // 正序遍历、后者覆盖前者,留下的就是每个实例最后一次定局
        Map<String, String> latest = new LinkedHashMap<>();
        rows.forEach(row -> latest.put(row.getProcessInstanceId(), row.getAction()));
        return latest;
    }

    /** 操作人快照缺失即拒绝:没有人的动作不构成台账(引擎异步线程的处境由调用方避开) */
    private LoginUser requireOperator() {
        LoginUser operator = LoginUserContextHolder.get();
        if (operator == null || operator.userId() == null) {
            throw BizException.forbidden("缺少登录人上下文,拒绝写审批台账");
        }
        return operator;
    }

    /**
     * 台账入参:一次动作的全部事实,字段与表列一一对应。
     * 操作人与时刻不在这里——那两个由服务层按上下文与服务器时钟钉死,外部填不了。
     */
    public record Trace(
            /** 动作,取本类的 ACTION_* 常量 */
            String action,
            /** 流程实例 ID */
            String processInstanceId,
            /** 引擎任务 ID;发起/撤销为 null */
            String taskId,
            /** 节点标识;发起/撤销为 null */
            String taskDefinitionKey,
            /** 节点名称快照;发起/撤销为 null */
            String taskName,
            /** 业务域标签;未接业务的裸流程为 null */
            String businessType,
            /** 业务单号;未接业务的裸流程为 null */
            String businessKey,
            /** 审批意见/撤销原因 */
            String comment,
            /** 转办目标用户 ID;仅 transfer 有值 */
            Long targetUserId,
            /** 转办目标显示名快照;仅 transfer 有值 */
            String targetNickname) {

        /** 任务侧动作:从引擎任务视图取坐标 */
        public static Trace ofTask(String action, WorkflowTask task,
                                   String businessType, String businessKey, String comment) {
            return new Trace(action, task.instanceId(), task.id(), task.taskDefinitionKey(), task.name(),
                    businessType, businessKey, comment, null, null);
        }

        /** 转办:目标人坐标与快照一起带 */
        public static Trace ofTransfer(WorkflowTask task,
                                       String businessType, String businessKey,
                                       Long targetUserId, String targetNickname, String comment) {
            return new Trace(ACTION_TRANSFER, task.instanceId(), task.id(), task.taskDefinitionKey(), task.name(),
                    businessType, businessKey, comment, targetUserId, targetNickname);
        }

        /** 实例侧动作(发起/撤销):没有任务坐标 */
        public static Trace ofInstance(String action, String processInstanceId,
                                       String businessType, String businessKey, String comment) {
            return new Trace(action, processInstanceId, null, null, null,
                    businessType, businessKey, comment, null, null);
        }
    }
}
