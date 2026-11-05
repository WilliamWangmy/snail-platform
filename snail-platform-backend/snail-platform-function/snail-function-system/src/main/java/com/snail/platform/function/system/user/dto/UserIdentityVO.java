package com.snail.platform.function.system.user.dto;

import com.snail.platform.function.system.user.entity.SysUserIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:用户第三方绑定出参(只读)——来源、厂商身份与最近登录快照;
 * openId 原样展示不打码(管理员要与厂商后台核对排查易主/错绑)。解绑/换绑二期。
 * 上游:UserService.identitiesOf;下游:用户详情「第三方绑定」卡片。
 */
@Schema(description = "用户第三方绑定")
public record UserIdentityVO(

        @Schema(description = "绑定ID")
        Long id,

        @Schema(description = "来源:dingtalk/feishu/wecom")
        String source,

        @Schema(description = "厂商内稳定标识:钉钉unionId/飞书openId/企微userid")
        String openId,

        @Schema(description = "厂商跨应用标识,可为空")
        String unionId,

        @Schema(description = "厂商昵称快照,可为空")
        String nickname,

        @Schema(description = "最近一次经此身份登录时间,可为空")
        LocalDateTime lastLoginTime,

        @Schema(description = "绑定时间")
        LocalDateTime createTime) {

    public static UserIdentityVO of(SysUserIdentity identity) {
        return new UserIdentityVO(identity.getId(), identity.getSource(), identity.getOpenId(),
                identity.getUnionId(), identity.getNickname(), identity.getLastLoginTime(),
                identity.getCreateTime());
    }
}
