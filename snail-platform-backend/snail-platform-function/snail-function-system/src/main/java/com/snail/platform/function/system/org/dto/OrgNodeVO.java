package com.snail.platform.function.system.org.dto;

import com.snail.platform.function.system.org.entity.SysOrg;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 职责:组织树节点出参;children 由服务层按 parent_id 组好,前端拿来即渲染。
 * 上游:OrgService.tree;下游:组织管理页面、选人控件。
 */
@Schema(description = "组织树节点")
public record OrgNodeVO(

        @Schema(description = "组织ID")
        Long orgId,

        @Schema(description = "父组织ID,根为 0")
        Long parentId,

        @Schema(description = "组织名称")
        String name,

        @Schema(description = "祖级路径,如 0,1,5")
        String ancestors,

        @Schema(description = "同级显示顺序")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "子节点")
        List<OrgNodeVO> children) {

    public static OrgNodeVO of(SysOrg org) {
        return new OrgNodeVO(org.getOrgId(), org.getParentId(), org.getName(), org.getAncestors(),
                org.getSort(), org.getStatus(), org.getRemark(), new ArrayList<>());
    }
}
