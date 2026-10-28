package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:在线用户出参对象——来自 Redis 会话快照,除显示名外不含角色等档案信息。
 * 上游:OnlineSessionService.page;下游:在线用户页面。
 */
@Schema(description = "在线用户")
public record OnlineSessionVO(

        @Schema(description = "用户 ID")
        Long userId,

        @Schema(description = "登录名")
        String username,

        @Schema(description = "显示名,取自心跳当时的登录人快照;改名后要等下一次心跳才刷新")
        String nickname,

        @Schema(description = "最近活跃 IP")
        String ip,

        @Schema(description = "浏览器 UA")
        String userAgent,

        @Schema(description = "本次在线起点")
        LocalDateTime firstSeen,

        @Schema(description = "最近活跃时间")
        LocalDateTime lastActive) {
}
