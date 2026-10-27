package com.snail.platform.function.workflow.model.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.workflow.bpmn.BpmnKeyRewriter;
import com.snail.platform.framework.workflow.bpmn.BpmnSecurityValidator;
import com.snail.platform.framework.workflow.engine.WorkflowDefinition;
import com.snail.platform.framework.workflow.engine.WorkflowDeployment;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionVO;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionXmlVO;
import com.snail.platform.function.workflow.model.dto.WorkflowDraftVO;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelQuery;
import com.snail.platform.function.workflow.model.dto.WorkflowModelUpdateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelVO;
import com.snail.platform.function.workflow.model.entity.SysWorkflowModel;
import com.snail.platform.function.workflow.model.mapper.SysWorkflowModelMapper;
import com.snail.platform.function.workflow.template.entity.SysWorkflowTemplate;
import com.snail.platform.function.workflow.template.service.WorkflowTemplateService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:流程模型的全部读写——分页、新建(含从平台模板落地)、改名改备注、草稿存取、
 * 发布(校验 + 部署 + 回写版本指针)、版本列表、历史版本原文、停用/激活、删除。
 * 守护口径(方案 §4.2、§5):
 * 1) model_key 就是 BPMN 的 process id,建后不可改、租户内终身占用;
 *    草稿保存时把 XML 里的 process id 对齐到 model_key(bpmn-js 新建默认给 Process_1,不对齐发布必然歧义);
 * 2) 草稿与已发布版本分离:改草稿不动在途实例,发布才产生新版本;坏版本靠挂起处理,不做回滚部署;
 * 3) 发布是"部署代码",权限码独立( workflow:model:publish),且整体在一个事务里——
 *    引擎写入与指针回写共用 DataSource + 事务管理器(P0 实证),任一步失败都不留半态;
 * 4) 删除只删平台侧记录,但前提是该 key 下没有在途实例,否则等于把在跑的流程变成孤儿。
 * 上游:WorkflowModelController;下游:sys_workflow_model 读写、WorkflowEngineGateway、WorkflowTemplateService。
 */
@Service
public class WorkflowModelService {

    /** 状态:草稿(尚未发布过,或发布后又回到编辑) */
    private static final int STATUS_DRAFT = 0;

    /** 状态:已发布,当前指针可发起 */
    private static final int STATUS_PUBLISHED = 1;

    /** 状态:停用,当前定义已挂起,不能发起新实例 */
    private static final int STATUS_SUSPENDED = 2;

    /** 模型读写 */
    private final SysWorkflowModelMapper modelMapper;

    /** 引擎门面:部署、定义查询与挂起都从这里走 */
    private final WorkflowEngineGateway engineGateway;

    /** BPMN 白名单闸门:发布前必过,草稿不做(草稿允许是半成品) */
    private final BpmnSecurityValidator bpmnSecurityValidator;

    /** 流程标识改名器:模板与 bpmn-js 默认 id 都要落到 model_key */
    private final BpmnKeyRewriter bpmnKeyRewriter;

    /** 模板目录:仅"从模板建模型"时读取 */
    private final WorkflowTemplateService templateService;

    public WorkflowModelService(SysWorkflowModelMapper modelMapper, WorkflowEngineGateway engineGateway,
                                BpmnSecurityValidator bpmnSecurityValidator, BpmnKeyRewriter bpmnKeyRewriter,
                                WorkflowTemplateService templateService) {
        this.modelMapper = modelMapper;
        this.engineGateway = engineGateway;
        this.bpmnSecurityValidator = bpmnSecurityValidator;
        this.bpmnKeyRewriter = bpmnKeyRewriter;
        this.templateService = templateService;
    }

    public PageResult<WorkflowModelVO> page(WorkflowModelQuery query) {
        Page<SysWorkflowModel> page = PageAssembler.from(query);
        String name = trimToNull(query.name());
        String modelKey = trimToNull(query.modelKey());
        String category = trimToNull(query.category());
        IPage<SysWorkflowModel> result = modelMapper.selectPage(page, Wrappers.<SysWorkflowModel>lambdaQuery()
                .like(name != null, SysWorkflowModel::getName, name)
                .like(modelKey != null, SysWorkflowModel::getModelKey, modelKey)
                .eq(category != null, SysWorkflowModel::getCategory, category)
                .eq(query.status() != null, SysWorkflowModel::getStatus, query.status())
                .orderByDesc(SysWorkflowModel::getModelId));
        return PageResult.of(result.getRecords().stream().map(WorkflowModelVO::from).toList(),
                result.getTotal());
    }

    public WorkflowModelVO detail(Long modelId) {
        return WorkflowModelVO.from(requireModel(modelId));
    }

    /**
     * 新建模型:标识必填且终身占用,初始草稿二选一——直接给 XML,或给平台模板(拷原文并把流程标识改成自己的 key)。
     * 两条路都先过改名器:入库的草稿从第一刻起就与 model_key 对齐,不留"能存不能发"的口子。
     */
    @Transactional
    public Long create(WorkflowModelCreateRequest request) {
        String modelKey = request.modelKey().trim();
        String draft = trimToNull(request.draftXml());
        Long templateId = request.templateId();
        if (draft == null && templateId != null) {
            SysWorkflowTemplate template = templateService.requireOnline(templateId);
            draft = template.getBpmnXml();
        }
        requireDraftSize(draft);
        String normalized = draft == null ? null : bpmnKeyRewriter.rewrite(draft, modelKey);

        SysWorkflowModel model = new SysWorkflowModel();
        model.setModelKey(modelKey);
        model.setName(request.name().trim());
        model.setCategory(trimToNull(request.category()));
        model.setRemark(trimToNull(request.remark()));
        model.setStatus(STATUS_DRAFT);
        model.setDraftXml(normalized);
        model.setTemplateId(templateId);
        try {
            modelMapper.insert(model);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("流程标识已存在(含已删除的模型),请换一个 key");
        }
        return model.getModelId();
    }

    /** 只改展示面字段;model_key 是发布指针与 BPMN 标识的锚点,不在修改契约里 */
    @Transactional
    public void update(Long modelId, WorkflowModelUpdateRequest request) {
        requireModel(modelId);
        modelMapper.update(new SysWorkflowModel(), Wrappers.<SysWorkflowModel>lambdaUpdate()
                .set(SysWorkflowModel::getName, request.name().trim())
                .set(SysWorkflowModel::getCategory, trimToNull(request.category()))
                .set(SysWorkflowModel::getRemark, trimToNull(request.remark()))
                .eq(SysWorkflowModel::getModelId, modelId));
    }

    /**
     * 删除模型:先过在途实例闸,再逻辑删。
     * 引擎侧的定义与历史一律保留——已办、轨迹与审计要能一直查下去,删的是"可编辑的模型",不是流程事实。
     */
    @Transactional
    public void delete(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        if (engineGateway.hasRunningInstancesOfKey(model.getModelKey())) {
            throw BizException.badRequest("该流程存在在途实例,不能删除;如需阻止新发起请先停用");
        }
        modelMapper.deleteById(modelId);
    }

    public WorkflowDraftVO draft(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        return new WorkflowDraftVO(model.getModelId(), model.getModelKey(), model.getName(),
                model.getStatus(), model.getDraftXml());
    }

    /**
     * 保存草稿:整份覆盖,并把 XML 里的 process id 对齐到 model_key。
     * 这里刻意不过安全校验器——半成品(缺结束事件、审批人还没绑)也要能存,校验的闸门在发布。
     * 改名器仍会拒掉非 BPMN 结构与多 process 的入参,这类输入不是半成品,是坏输入。
     */
    @Transactional
    public void saveDraft(Long modelId, String xml) {
        SysWorkflowModel model = requireModel(modelId);
        String draft = trimToNull(xml);
        requireDraftSize(draft);
        if (draft == null) {
            throw BizException.badRequest("流程内容不能为空");
        }
        String normalized = bpmnKeyRewriter.rewrite(draft, model.getModelKey());
        modelMapper.update(new SysWorkflowModel(), Wrappers.<SysWorkflowModel>lambdaUpdate()
                .set(SysWorkflowModel::getDraftXml, normalized)
                .eq(SysWorkflowModel::getModelId, modelId));
    }

    /**
     * 发布:草稿 → 安全校验 → 带租户部署 → 回写指针并置为已发布。
     * 校验器返回的 key 必须等于 model_key,不等就拒绝(同一事务里引擎写入随异常回滚,不留孤儿定义)。
     */
    @Transactional
    public WorkflowDefinitionVO publish(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        String draft = trimToNull(model.getDraftXml());
        if (draft == null) {
            throw BizException.badRequest("尚无草稿内容,无法发布");
        }
        String processKey = bpmnSecurityValidator.validate(draft);
        if (!processKey.equals(model.getModelKey())) {
            throw BizException.badRequest("草稿里的流程标识与模型不一致,请重新保存草稿");
        }
        WorkflowDeployment deployment = engineGateway.deploy(model.getName(), draft);
        LoginUser operator = LoginUserContextHolder.get();
        modelMapper.update(new SysWorkflowModel(), Wrappers.<SysWorkflowModel>lambdaUpdate()
                .set(SysWorkflowModel::getStatus, STATUS_PUBLISHED)
                .set(SysWorkflowModel::getDeploymentId, deployment.deploymentId())
                .set(SysWorkflowModel::getProcessDefinitionId, deployment.processDefinitionId())
                .set(SysWorkflowModel::getDeployedVersion, deployment.version())
                .set(SysWorkflowModel::getPublishedBy, operator == null ? null : operator.userId())
                .set(SysWorkflowModel::getPublishTime, LocalDateTime.now())
                .eq(SysWorkflowModel::getModelId, modelId));
        return WorkflowDefinitionVO.from(engineGateway.requireDefinition(deployment.processDefinitionId()),
                deployment.processDefinitionId());
    }

    /** 版本列表:同 key 的全部已发布版本,当前指针标 deployed */
    public List<WorkflowDefinitionVO> definitions(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        return engineGateway.findDefinitions(model.getModelKey(), false).stream()
                .map(definition -> WorkflowDefinitionVO.from(definition, model.getProcessDefinitionId()))
                .toList();
    }

    public WorkflowDefinitionXmlVO definitionXml(String definitionId) {
        WorkflowDefinition definition = engineGateway.requireDefinition(definitionId);
        return WorkflowDefinitionXmlVO.of(definition, engineGateway.findDefinitionXml(definitionId));
    }

    /** 停用:挂起当前定义,新实例发不起来;在途实例不受影响(网关用的是单参挂起重载) */
    @Transactional
    public void suspend(Long modelId) {
        SysWorkflowModel model = requirePublished(modelId);
        engineGateway.suspendDefinition(model.getProcessDefinitionId());
        updateStatus(modelId, STATUS_SUSPENDED);
    }

    /** 激活:与停用对称,恢复可发起 */
    @Transactional
    public void activate(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        if (!Integer.valueOf(STATUS_SUSPENDED).equals(model.getStatus()) || model.getProcessDefinitionId() == null) {
            throw BizException.badRequest("模型未处于停用状态");
        }
        engineGateway.activateDefinition(model.getProcessDefinitionId());
        updateStatus(modelId, STATUS_PUBLISHED);
    }

    private void updateStatus(Long modelId, int status) {
        modelMapper.update(new SysWorkflowModel(), Wrappers.<SysWorkflowModel>lambdaUpdate()
                .set(SysWorkflowModel::getStatus, status)
                .eq(SysWorkflowModel::getModelId, modelId));
    }

    private SysWorkflowModel requirePublished(Long modelId) {
        SysWorkflowModel model = requireModel(modelId);
        if (!Integer.valueOf(STATUS_PUBLISHED).equals(model.getStatus())
                || model.getProcessDefinitionId() == null) {
            throw BizException.badRequest("流程尚未发布或已处于停用状态");
        }
        return model;
    }

    private SysWorkflowModel requireModel(Long modelId) {
        SysWorkflowModel model = modelId == null ? null : modelMapper.selectById(modelId);
        if (model == null) {
            throw BizException.badRequest("流程模型不存在");
        }
        return model;
    }

    /** 体积闸与发布同口径,尺子由校验器单点持有:草稿超限只会白白占库,到发布仍会被拒 */
    private static void requireDraftSize(String draft) {
        if (draft != null && draft.getBytes(StandardCharsets.UTF_8).length > BpmnSecurityValidator.MAX_XML_BYTES) {
            throw BizException.badRequest("流程内容超过 2MB,请精简画布");
        }
    }

    /** 空白串按 null 处理:未填与填了空格在库里必须同形,否则筛选与"是否已发布草稿"判定会分叉 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
