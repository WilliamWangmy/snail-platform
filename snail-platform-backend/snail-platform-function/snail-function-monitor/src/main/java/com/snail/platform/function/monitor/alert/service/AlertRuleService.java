package com.snail.platform.function.monitor.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.function.monitor.alert.AlertNotifyService;
import com.snail.platform.function.monitor.alert.dto.RuleCreateRequest;
import com.snail.platform.function.monitor.alert.dto.RuleQuery;
import com.snail.platform.function.monitor.alert.dto.RuleToggleRequest;
import com.snail.platform.function.monitor.alert.dto.RuleUpdateRequest;
import com.snail.platform.function.monitor.alert.dto.RuleVO;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertRuleMapper;
import com.snail.platform.function.monitor.metric.MetricKey;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 职责:告警规则 CRUD——分页/创建/修改/删除/启停;内置规则只可改阈值/持续/级别/启停;
 * 停用与删除把开放告警置 resolved,恢复通知走 afterCommit(DD-17/DD-18)。
 * 上游:AlertRuleController;下游:SysMonitorAlertRuleMapper、SysMonitorAlertMapper、AlertNotifyService。
 * 口径:@Transactional 只挂写方法(包位在 .service,DD-25);规则物理删除,名称唯一键删后释放(DD-14);
 * 名称唯一性重名校验(uk 兜底);评估输入与展示口径一致性由 AlertEvaluator 保证。
 */
@Service
public class AlertRuleService {

    private final SysMonitorAlertRuleMapper ruleMapper;
    private final SysMonitorAlertMapper alertMapper;
    private final AlertNotifyService notifyService;

    public AlertRuleService(SysMonitorAlertRuleMapper ruleMapper, SysMonitorAlertMapper alertMapper,
                            AlertNotifyService notifyService) {
        this.ruleMapper = ruleMapper;
        this.alertMapper = alertMapper;
        this.notifyService = notifyService;
    }

    /** 分页:keyword 模糊 name、enabled 精确,rule_id 升序 */
    public PageResult<RuleVO> page(RuleQuery query) {
        LambdaQueryWrapper<SysMonitorAlertRule> wrapper = new LambdaQueryWrapper<SysMonitorAlertRule>()
                .like(query.keyword() != null && !query.keyword().isBlank(), SysMonitorAlertRule::getName, query.keyword())
                .eq(query.enabled() != null, SysMonitorAlertRule::getEnabled, query.enabled())
                .orderByAsc(SysMonitorAlertRule::getRuleId);
        var page = ruleMapper.selectPage(PageAssembler.from(query), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::toVO).toList(), page.getTotal());
    }

    /** 创建:重名 400;外置规则 isSystem=0 */
    @Transactional
    public RuleVO create(RuleCreateRequest request) {
        if (!Double.isFinite(request.threshold())) {
            throw BizException.badRequest("阈值必须是有限数");
        }
        if (MetricKey.fromKey(request.metric()).isEmpty()) {
            throw BizException.badRequest("指标键不在键表内");
        }
        if (countByName(request.name()) > 0) {
            throw BizException.badRequest("规则名称已存在");
        }
        SysMonitorAlertRule rule = new SysMonitorAlertRule();
        rule.setName(request.name().trim());
        rule.setMetric(request.metric());
        rule.setComparator(request.comparator());
        rule.setThreshold(request.threshold());
        rule.setDurationPoints(request.durationPoints());
        rule.setLevel(request.level());
        rule.setEnabled(request.enabled());
        rule.setIsSystem(0);
        rule.setRemark(request.remark());
        ruleMapper.insert(rule);
        return toVO(rule);
    }

    /** 修改:内置规则改 name/metric/comparator 即 400;外置重名校验排除自身 */
    @Transactional
    public RuleVO update(Long ruleId, RuleUpdateRequest request) {
        SysMonitorAlertRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw BizException.badRequest("规则不存在");
        }
        boolean builtin = rule.getIsSystem() != null && rule.getIsSystem() == 1;
        if (builtin && (!rule.getName().equals(request.name()) || !rule.getMetric().equals(request.metric())
                || !rule.getComparator().equals(request.comparator()))) {
            throw BizException.badRequest("内置规则只能修改阈值/持续点数/级别/启停");
        }
        if (!Double.isFinite(request.threshold())) {
            throw BizException.badRequest("阈值必须是有限数");
        }
        if (MetricKey.fromKey(request.metric()).isEmpty()) {
            throw BizException.badRequest("指标键不在键表内");
        }
        Long sameName = ruleMapper.selectCount(new LambdaQueryWrapper<SysMonitorAlertRule>()
                .eq(SysMonitorAlertRule::getName, request.name())
                .ne(SysMonitorAlertRule::getRuleId, ruleId));
        if (sameName != null && sameName > 0) {
            throw BizException.badRequest("规则名称已存在");
        }
        rule.setName(request.name().trim());
        rule.setMetric(request.metric());
        rule.setComparator(request.comparator());
        rule.setThreshold(request.threshold());
        rule.setDurationPoints(request.durationPoints());
        rule.setLevel(request.level());
        rule.setRemark(request.remark());
        ruleMapper.updateById(rule);
        return toVO(rule);
    }

    /** 删除:内置拒删;外置物理删;开放告警先置 resolved,恢复通知 afterCommit(DD-17/DD-18) */
    @Transactional
    public void delete(Long ruleId) {
        SysMonitorAlertRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw BizException.badRequest("规则不存在");
        }
        if (rule.getIsSystem() != null && rule.getIsSystem() == 1) {
            throw BizException.badRequest("内置规则不可删除");
        }
        resolveOpenAlertsAfterCommit(rule);
        ruleMapper.deleteById(ruleId);
    }

    /** 启停:0/1;置 1(停用)时开放告警置 resolved + 恢复通知一次(DD-17) */
    @Transactional
    public void toggle(Long ruleId, RuleToggleRequest request) {
        SysMonitorAlertRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw BizException.badRequest("规则不存在");
        }
        if (request.enabled() == 1 && rule.getEnabled() != null && rule.getEnabled() == 1) {
            return;
        }
        if (request.enabled() == 1) {
            resolveOpenAlertsAfterCommit(rule);
        }
        rule.setEnabled(request.enabled());
        ruleMapper.updateById(rule);
    }

    /** 开放告警置 resolved;恢复通知在事务提交后发(回滚不误发,DD-18) */
    private void resolveOpenAlertsAfterCommit(SysMonitorAlertRule rule) {
        SysMonitorAlert open = alertMapper.selectOpenByRuleId(rule.getRuleId());
        if (open == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        SysMonitorAlert patch = new SysMonitorAlert();
        patch.setAlertId(open.getAlertId());
        patch.setStatus("resolved");
        patch.setResolveTime(now);
        alertMapper.updateById(patch);
        AtomicReference<SysMonitorAlert> snapshot = new AtomicReference<>(open);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot.get().setResolveTime(now);
                    notifyService.resolve(rule, snapshot.get());
                }
            });
        }
    }

    private long countByName(String name) {
        Long count = ruleMapper.selectCount(new LambdaQueryWrapper<SysMonitorAlertRule>()
                .eq(SysMonitorAlertRule::getName, name));
        return count == null ? 0 : count;
    }

    private RuleVO toVO(SysMonitorAlertRule rule) {
        return new RuleVO(rule.getRuleId(), rule.getName(), rule.getMetric(), rule.getComparator(),
                rule.getThreshold(), rule.getDurationPoints(), rule.getLevel(), rule.getEnabled(),
                rule.getIsSystem(), rule.getSilenceUntil(), rule.getRemark(), rule.getCreateBy(),
                rule.getCreateTime(), rule.getUpdateTime());
    }
}
