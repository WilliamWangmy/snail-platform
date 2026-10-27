package com.snail.platform.function.workflow.task.dto;

import com.snail.platform.framework.core.query.PageQuery;

/**
 * 职责:待办/已办列表的分页入参契约。
 * 上游:/workflow/task/todo 与 /done 的 GET 绑定;下游:WorkflowTaskService 换算成引擎的 offset/limit。
 * 口径:刻意只有页码与条数——待办列表按定义/时间筛是检索功能,归 P4 实例列表一起做,
 * 现在加字段就等于把"我的工作台"做成查询台。
 */
public record WorkflowTaskQuery(
        /** 页码,从 1 起 */
        Integer pageNum,
        /** 每页条数,缺省 10,上限 200 */
        Integer pageSize) implements PageQuery {

    public WorkflowTaskQuery {
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
