package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:租户使用统计出参——详情抽屉"系统使用 + 分端使用率"两区的数据面(统计层设计 §5.4,契约冻结)。
 * 口径:users 为 sys_user 单条条件聚合(withoutTenant+显式租户条件,平台侧上下文陷阱见 AC-9);
 * scenes.loggedIn 为 Valkey Set SCARD,键丢失走 sys_login_log lazy 自愈回填,累计不清零(D-03);
 * rate=loggedIn/total 保留两位小数,total=0 时 rate=0;portal 两字段门户立项前恒 null(BR-8 占位)。
 * 上游:TenantService.usageStats;下游:平台管理端。
 */
@Schema(description = "租户使用统计")
public record UsageStatsVO(

        @Schema(description = "用户区:租户内用户五项计数")
        Users users,

        @Schema(description = "分端区:五端登录/使用率")
        Scenes scenes) {

    @Schema(description = "用户五项计数")
    public record Users(

            @Schema(description = "总用户数(deleted=0)")
            long total,

            @Schema(description = "启用用户数(status=0)")
            long enabled,

            @Schema(description = "禁用用户数(status=1)")
            long disabled,

            @Schema(description = "已使用数(password_modify_time 非空;重置密码置 null 后退回\"未使用\"是特性——旧口令已作废)")
            long used,

            @Schema(description = "在线用户数(复用心跳索引,与在线用户列表页同口径)")
            long online) {
    }

    @Schema(description = "单端登录统计")
    public record SceneStat(

            @Schema(description = "该端登录过的去重用户数")
            long loggedIn,

            @Schema(description = "使用率=loggedIn/租户总用户数,两位小数;total=0 时为 0")
            double rate) {
    }

    @Schema(description = "企业门户访问统计(门户立项前恒 null 占位,BR-8:访问日志随门户立项实现)")
    public record PortalStat(

            @Schema(description = "访问人数,门户立项前恒 null")
            Long visitors,

            @Schema(description = "访问 IP 数,门户立项前恒 null")
            Long ipCount) {
    }

    @Schema(description = "五端统计")
    public record Scenes(

            @Schema(description = "管理系统(scene=admin)")
            SceneStat admin,

            @Schema(description = "流程系统(scene=workflow)")
            SceneStat workflow,

            @Schema(description = "移动端(scene=app/miniapp 合并去重,D-06 单开关双 scene)")
            SceneStat mobile,

            @Schema(description = "数据大屏(scene=dashboard)")
            SceneStat dashboard,

            @Schema(description = "企业门户(null 占位)")
            PortalStat portal) {
    }
}
