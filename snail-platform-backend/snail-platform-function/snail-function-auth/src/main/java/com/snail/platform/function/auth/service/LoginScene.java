package com.snail.platform.function.auth.service;

import java.util.Set;

/**
 * 职责:登录端(scene)枚举常量集——"租户开通了哪些系统"的契约侧坐标,登录闸与统计分端的共同词汇。
 * scene 与 sys_tenant 五开关的映射:admin→admin_enabled、workflow→workflow_enabled、
 * app/miniapp→mobile_enabled(同一位产品位)、dashboard→dashboard_enabled、portal→portal_enabled;
 * oauth 链本期不加 scene,缺省按 admin 校验(ASM-3),移动端/门户接入 oauth 时再透传。
 * 上游:LoginRequest/SmsLoginRequest/SmsPickRequest 的 @Pattern 白名单;下游:AuthService 的 scene 闸与登录日志。
 * 口径:非法 scene 在参数校验层就拒(400「非法的登录端」,不涉账号、不进登录日志);
 *      合法但未开通走 LoginRejects.Scene——scene 闸在凭证验真之后(密码链在密码比对后,
 *      短信/OAuth 链在码验真后),「租户未开通{端名}权限,请联系平台管理员」文案可对外透传
 *      (2026-11-12 拍板改口径,原 D-02 统一改写已废),同时照旧落 sys_login_log.msg。
 */
public final class LoginScene {

    /** 管理系统(frontend-admin):请求不带 scene 时的缺省端 */
    public static final String ADMIN = "admin";

    /** 流程系统(frontend-workflow) */
    public static final String WORKFLOW = "workflow";

    /** 移动端 App(与 miniapp 共用 mobile_enabled 开关) */
    public static final String APP = "app";

    /** 移动端小程序(与 app 共用 mobile_enabled 开关) */
    public static final String MINIAPP = "miniapp";

    /** 数据大屏(frontend-dashboard) */
    public static final String DASHBOARD = "dashboard";

    /** 企业门户(门户立项前无调用方,枚举预留) */
    public static final String PORTAL = "portal";

    /** 全部合法 scene:闸内归一与日志归属都以此为准 */
    public static final Set<String> ALL =
            Set.of(ADMIN, WORKFLOW, APP, MINIAPP, DASHBOARD, PORTAL);

    /**
     * Bean 校验用正则(编译期常量,@Pattern 引用):白名单外一律 400「非法的登录端」。
     * 写成正则而不是 @Pattern 定制校验器,是为了让非法值死在参数校验层——
     * 进了 service 的 try/catch 会被准入改写吞掉文案,违背"不涉账号"的口径。
     */
    public static final String PATTERN = "admin|workflow|app|miniapp|dashboard|portal";

    private LoginScene() {
    }

    /**
     * 归一:请求未带 scene 时按管理系统处理(契约 5.1:admin 是缺省端)。
     * DTO 的 @Pattern 放过 null(可缺省),空白串已被正则拦下,这里只兜 null 一条路。
     */
    public static String normalize(String scene) {
        return scene == null ? ADMIN : scene;
    }

    /**
     * scene 的中文端名,登录日志 msg 用——「租户未开通{端名}」的平台管理员排障口径;
     * app/miniapp 同属"移动端"产品位(D-06),端名不细分。
     */
    public static String label(String scene) {
        return switch (scene) {
            case ADMIN -> "管理系统";
            case WORKFLOW -> "流程系统";
            case APP, MINIAPP -> "移动端";
            case DASHBOARD -> "数据大屏";
            case PORTAL -> "企业门户";
            default -> scene;
        };
    }
}
