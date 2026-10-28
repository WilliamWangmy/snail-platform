package com.snail.platform.function.system.log.dto;

import com.snail.platform.function.system.log.entity.SysLoginLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:登录日志列表行,表体本身没有大字段,整行即最小面。
 * 上游:LoginLogService 分页投影;下游:登录日志列表页。
 * 口径:status 1 成功 0 失败;失败行的 tenantId 可能是 0(登录链尚未定位到用户),展示按"未定位"处理。
 */
@Schema(description = "登录日志行")
public record LoginLogVO(
        @Schema(description = "日志ID") Long id,
        @Schema(description = "归属租户,0=登录链未定位到用户") Long tenantId,
        @Schema(description = "登录名快照") String username,
        @Schema(description = "用户ID,成功时回填") Long userId,
        @Schema(description = "客户端IP") String ip,
        @Schema(description = "浏览器") String browser,
        @Schema(description = "操作系统") String os,
        @Schema(description = "结果:1成功 0失败") Integer status,
        @Schema(description = "结果说明") String msg,
        @Schema(description = "登录时间") LocalDateTime loginTime) {

    public static LoginLogVO of(SysLoginLog log) {
        return new LoginLogVO(log.getId(), log.getTenantId(), log.getUsername(), log.getUserId(),
                log.getIp(), log.getBrowser(), log.getOs(), log.getStatus(), log.getMsg(), log.getLoginTime());
    }
}
