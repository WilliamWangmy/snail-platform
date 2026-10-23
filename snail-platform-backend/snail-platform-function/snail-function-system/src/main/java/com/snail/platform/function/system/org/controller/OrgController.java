package com.snail.platform.function.system.org.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.org.dto.OrgCreateRequest;
import com.snail.platform.function.system.org.dto.OrgMoveRequest;
import com.snail.platform.function.system.org.dto.OrgNodeVO;
import com.snail.platform.function.system.org.dto.OrgUpdateRequest;
import com.snail.platform.function.system.org.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:组织树管理入口——整棵查询、子树 ID 清单、新增子节点、改属性、移动、删除;作用域永远是当前登录者所属租户。
 * 上游:组织管理页面;下游:OrgService。租户圈定由拦截器完成,本层不出现 tenant_id 参数。
 */
@Tag(name = "组织管理")
@RestController
@RequestMapping("/system/org")
public class OrgController {

    /** 组织树业务服务 */
    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @Operation(summary = "组织整棵树")
    @GetMapping("/tree")
    public R<List<OrgNodeVO>> tree() {
        return R.ok(orgService.tree());
    }

    @Operation(summary = "组织子树 ID 清单(含自身,档位三的内部查询面)")
    @GetMapping("/{orgId}/subtree")
    public R<List<Long>> subtree(@PathVariable Long orgId) {
        return R.ok(orgService.subtreeIds(orgId));
    }

    @Operation(summary = "新增子节点")
    @OperLog(module = "组织管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody OrgCreateRequest request) {
        return R.ok(orgService.create(request));
    }

    @Operation(summary = "修改组织(改名/排序/停用启用)")
    @OperLog(module = "组织管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{orgId}")
    public R<Void> update(@PathVariable Long orgId, @Valid @RequestBody OrgUpdateRequest request) {
        orgService.update(orgId, request);
        return R.ok(null);
    }

    @Operation(summary = "移动节点(子树 ancestors 整体重写)")
    @OperLog(module = "组织管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{orgId}/move")
    public R<Void> move(@PathVariable Long orgId, @Valid @RequestBody OrgMoveRequest request) {
        orgService.move(orgId, request.newParentId());
        return R.ok(null);
    }

    @Operation(summary = "删除组织(有下级或挂用户即拒)")
    @OperLog(module = "组织管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{orgId}")
    public R<Void> delete(@PathVariable Long orgId) {
        orgService.delete(orgId);
        return R.ok(null);
    }
}
