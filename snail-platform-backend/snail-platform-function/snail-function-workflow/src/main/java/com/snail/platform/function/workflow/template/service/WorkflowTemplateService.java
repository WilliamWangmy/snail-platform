package com.snail.platform.function.workflow.template.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.workflow.bpmn.BpmnSecurityValidator;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateCreateRequest;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateOptionVO;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateQuery;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateUpdateRequest;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateVO;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateXmlVO;
import com.snail.platform.function.workflow.template.entity.SysWorkflowTemplate;
import com.snail.platform.function.workflow.template.mapper.SysWorkflowTemplateMapper;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:流程模板目录的全部读写(平台面)——分页、新增、修改、删除、原文读取,以及给租户侧的上架模板列表。
 * 守护口径:模板是全局一份的目录表(在租户 ignore 清单内,与 sys_menu 同款),所以本服务不切租户、不开 bypass;
 * template_key 建后不可改且全局唯一含已删记录;入库前先过 BPMN 白名单校验——
 * 存一份发不出去的模板等于把校验成本推给每个租户,而模板的价值正是"拿来就能用"。
 * 上游:WorkflowTemplateController(平台面)与 model 子域的"从模板建模型";
 * 下游:sys_workflow_template 读写、BpmnSecurityValidator。
 */
@Service
public class WorkflowTemplateService {

    /** 上架:租户可选 */
    private static final int STATUS_ONLINE = 0;

    /** 停用:不出现在租户模板列表 */
    private static final int STATUS_OFFLINE = 1;

    /** 模板读写 */
    private final SysWorkflowTemplateMapper templateMapper;

    /** BPMN 白名单闸门:模板内容与租户草稿用同一个校验器,口径不分叉 */
    private final BpmnSecurityValidator bpmnSecurityValidator;

    public WorkflowTemplateService(SysWorkflowTemplateMapper templateMapper,
                                  BpmnSecurityValidator bpmnSecurityValidator) {
        this.templateMapper = templateMapper;
        this.bpmnSecurityValidator = bpmnSecurityValidator;
    }

    public PageResult<WorkflowTemplateVO> page(WorkflowTemplateQuery query) {
        Page<SysWorkflowTemplate> page = PageAssembler.from(query);
        String name = trimToNull(query.name());
        String templateKey = trimToNull(query.templateKey());
        String category = trimToNull(query.category());
        IPage<SysWorkflowTemplate> result = templateMapper.selectPage(page,
                Wrappers.<SysWorkflowTemplate>lambdaQuery()
                        .like(name != null, SysWorkflowTemplate::getName, name)
                        .like(templateKey != null, SysWorkflowTemplate::getTemplateKey, templateKey)
                        .eq(category != null, SysWorkflowTemplate::getCategory, category)
                        .eq(query.status() != null, SysWorkflowTemplate::getStatus, query.status())
                        .orderByDesc(SysWorkflowTemplate::getTemplateId));
        return PageResult.of(result.getRecords().stream().map(WorkflowTemplateVO::from).toList(),
                result.getTotal());
    }

    /** 租户侧可选模板:只列上架项,全量返回(目录规模由平台控制,不分页) */
    public List<WorkflowTemplateOptionVO> options() {
        return templateMapper.selectList(Wrappers.<SysWorkflowTemplate>lambdaQuery()
                        .select(SysWorkflowTemplate::getTemplateId, SysWorkflowTemplate::getTemplateKey,
                                SysWorkflowTemplate::getName, SysWorkflowTemplate::getCategory,
                                SysWorkflowTemplate::getRemark)
                        .eq(SysWorkflowTemplate::getStatus, STATUS_ONLINE)
                        .orderByAsc(SysWorkflowTemplate::getTemplateKey))
                .stream().map(WorkflowTemplateOptionVO::from).toList();
    }

    public WorkflowTemplateXmlVO xml(Long templateId) {
        SysWorkflowTemplate template = requireTemplate(templateId);
        return new WorkflowTemplateXmlVO(template.getTemplateId(), template.getTemplateKey(),
                template.getName(), template.getBpmnXml());
    }

    public WorkflowTemplateVO detail(Long templateId) {
        return WorkflowTemplateVO.from(requireTemplate(templateId));
    }

    @Transactional
    public Long create(WorkflowTemplateCreateRequest request) {
        bpmnSecurityValidator.validate(request.bpmnXml());
        SysWorkflowTemplate template = new SysWorkflowTemplate();
        template.setTemplateKey(request.templateKey().trim());
        template.setName(request.name().trim());
        template.setCategory(trimToNull(request.category()));
        template.setRemark(trimToNull(request.remark()));
        template.setBpmnXml(request.bpmnXml());
        template.setStatus(STATUS_ONLINE);
        try {
            templateMapper.insert(template);
        } catch (DuplicateKeyException e) {
            // 已删记录仍占唯一键,标识终身占用;数据库拦下时统一转业务拒绝
            throw BizException.badRequest("模板标识已存在");
        }
        return template.getTemplateId();
    }

    @Transactional
    public void update(Long templateId, WorkflowTemplateUpdateRequest request) {
        SysWorkflowTemplate template = requireTemplate(templateId);
        String xml = trimToNull(request.bpmnXml());
        if (xml != null) {
            bpmnSecurityValidator.validate(xml);
        }
        int status = request.status() == null ? template.getStatus() : request.status();
        templateMapper.update(new SysWorkflowTemplate(), Wrappers.<SysWorkflowTemplate>lambdaUpdate()
                .set(SysWorkflowTemplate::getName, request.name().trim())
                .set(SysWorkflowTemplate::getCategory, trimToNull(request.category()))
                .set(SysWorkflowTemplate::getRemark, trimToNull(request.remark()))
                .set(xml != null, SysWorkflowTemplate::getBpmnXml, xml)
                .set(SysWorkflowTemplate::getStatus, status)
                .eq(SysWorkflowTemplate::getTemplateId, templateId));
    }

    /**
     * 删除模板:只删平台侧目录行,不回写已建模型的 template_id(该列仅溯源)。
     * 停用是更轻的收口手段:想让存量模型继续用、只挡住新建,走 status=1 而不是删。
     */
    @Transactional
    public void delete(Long templateId) {
        requireTemplate(templateId);
        templateMapper.deleteById(templateId);
    }

    /**
     * 取上架模板整行(含原文),供 model 子域的"从模板建模型"。
     * 停用与不存在同样答复:模板可见性不该泄露"存在但下架"。
     */
    public SysWorkflowTemplate requireOnline(Long templateId) {
        SysWorkflowTemplate template = templateMapper.selectById(templateId);
        if (template == null || !Integer.valueOf(STATUS_ONLINE).equals(template.getStatus())) {
            throw BizException.badRequest("流程模板不存在或已下架");
        }
        return template;
    }

    private SysWorkflowTemplate requireTemplate(Long templateId) {
        SysWorkflowTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.badRequest("流程模板不存在");
        }
        return template;
    }

    /** 空白串按 null 处理:未分类与"分类为空串"在库里必须同形,否则筛选会漏 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
