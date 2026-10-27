package com.snail.platform.function.workflow.instance.dto;

import com.snail.platform.framework.core.query.PageQuery;

/**
 * 职责:"我发起的"列表的分页入参契约。
 * 上游:/workflow/instance/started 的 GET 绑定;下游:WorkflowInstanceService 换算成引擎 offset/limit。
 * 口径:发起人永远取当前登录人,不作为入参——把 userId 放进 Query 就等于开了一条看别人单据的口子。
 * definitionKey 是唯一的筛选维度:状态筛选(在途/办结)靠引擎的 ended 语义,列表页先用前端过滤,
 * 等真出现"只看被驳回的"这类需求再下沉到查询,不预先铺一套没人用的枚举筛选。
 */
public record WorkflowInstanceQuery(
        /** 页码,从 1 起 */
        Integer pageNum,
        /** 每页条数,缺省 10,上限 200 */
        Integer pageSize,
        /** 只看某一条流程;留空看全部 */
        String definitionKey) implements PageQuery {

    public WorkflowInstanceQuery {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 200) {
            pageSize = 200;
        }
    }
}
