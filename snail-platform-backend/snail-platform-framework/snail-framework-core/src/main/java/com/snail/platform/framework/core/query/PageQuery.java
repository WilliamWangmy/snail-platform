package com.snail.platform.framework.core.query;

/**
 * 职责:分页查询入参的型抽象——只约定"有页码、有每页条数",不规定字段放哪、默认值多少;
 * 各域 XxxQuery 仍自带字段与紧凑构造器默认值(型前瞻、形就地)。
 * 上游:各域 dto 的分页契约 implements 本接口;下游:framework-db 的 PageAssembler 按本接口装配 MP 分页对象,
 * 深翻保护、上限调整等后续分页机制只挂装配点一处,不回改各域契约。
 * 约定:accessor 用包装类型,与 record 绑定口径一致(不传即 null,紧凑构造器落默认值)。
 */
public interface PageQuery {

    /** 页码,从 1 起 */
    Integer pageNum();

    /** 每页条数,各域默认 10 或 20,上限校验 200 */
    Integer pageSize();
}
