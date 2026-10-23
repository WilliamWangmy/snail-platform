package com.snail.platform.function.system.user.dto;

import com.snail.platform.function.system.user.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:用户出参对象。password 字段在类型层面就不存在——密文禁止出任何端点;
 * roleIds 仅详情回查时填充,列表投影为 null 不触发额外查询。
 * 上游:UserService;下游:用户管理列表与详情。
 */
@Schema(description = "用户信息")
public record UserVO(

        @Schema(description = "用户ID")
        Long userId,

        @Schema(description = "登录名")
        String username,

        @Schema(description = "显示名")
        String nickname,

        @Schema(description = "邮箱")
        String email,

        @Schema(description = "手机号")
        String phone,

        @Schema(description = "所属组织ID")
        Long orgId,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime,

        @Schema(description = "授权角色ID集,仅详情接口填充")
        List<Long> roleIds) {

    public static UserVO of(SysUser user, List<Long> roleIds) {
        return new UserVO(user.getUserId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhone(), user.getOrgId(), user.getStatus(), user.getRemark(),
                user.getCreateTime(), roleIds);
    }
}
