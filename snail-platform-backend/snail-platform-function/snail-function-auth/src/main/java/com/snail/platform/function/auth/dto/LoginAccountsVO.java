package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:短信登录多租户命中时的候选列表出参——pickKey 一次性凭证 + 可选账号集。
 * 候选只展示租户名/用户名/昵称,跨租户信息最小暴露;停用/到期的候选已在服务端静默剔除。
 * 上游:AuthService.loginBySms 多命中分支;下游:登录页租户选择对话框。
 */
@Schema(description = "多租户账号候选列表")
public record LoginAccountsVO(

        @Schema(description = "一次性选择凭证,2 分钟内有效,选完即废")
        String pickKey,

        @Schema(description = "可选账号集(按用户 ID 升序)")
        List<Account> accounts) {

    /** 一个可选账号:最小暴露面,不含手机号/邮箱等档案字段 */
    @Schema(description = "候选账号")
    public record Account(

            @Schema(description = "用户 ID,pick 时回传")
            Long userId,

            @Schema(description = "归属租户 ID")
            Long tenantId,

            @Schema(description = "租户名称")
            String tenantName,

            @Schema(description = "登录名")
            String username,

            @Schema(description = "显示名")
            String nickname) {
    }
}
