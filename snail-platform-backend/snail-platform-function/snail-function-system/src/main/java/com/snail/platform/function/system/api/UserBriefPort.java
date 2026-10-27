package com.snail.platform.function.system.api;

import java.util.Collection;
import java.util.List;

/**
 * 职责:用户简要信息查询端口——跨模块取收件人/展示名的唯一合法出口,跨模块不得再触达 user 子域内部。
 * 返回的 UserBrief 是发送时刻的快照原料,消费方自行落快照列,不回查用户档案。
 * 上游:notify 等业务模块;下游:user 子域实现。
 * 口径:briefs 对不存在的/跨租户的 ID 直接缺席返回,不报错——由调用方决定部分成功的语义。
 */
public interface UserBriefPort {

    /** 按用户 ID 集取简要信息,当前租户口径;入参去重由实现处理 */
    List<UserBrief> briefs(Collection<Long> userIds);

    /** 指定租户的全部启用用户(平台公告扇出用);租户不存在/停用抛业务拒绝 */
    List<UserBrief> enabledUsersOfTenant(Long tenantId);

    /**
     * 当前租户内某个角色的启用成员 ID(流程待办按候选角色扇出用);无人即空集。
     * 口径:授权行不带租户列,成员按 sys_user 的租户过滤现出——所以"他租户的同一角色"天然查不到人。
     */
    List<Long> enabledUserIdsOfRole(Long roleId);

    /**
     * 用户简要快照:认证展示名与出站通道地址的最小集合;
     * email/phone 供出站通道寻址,属平台内部流转,不进任何对外接口出参。
     */
    record UserBrief(
            /** 用户 ID */
            Long userId,
            /** 登录名 */
            String username,
            /** 显示名 */
            String nickname,
            /** 邮箱,出站邮件通道寻址 */
            String email,
            /** 状态:0 正常 1 停用 */
            Integer status) {
    }
}
