package com.snail.platform.framework.workflow.tenant;

import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;

/**
 * 职责:平台租户身份(Long)与引擎租户字段(String)之间的唯一换算处。
 * 上游:WorkflowEngineGateway(写侧取当前线程租户)、WorkflowTenantBridge(异步线程反查);
 * 下游:Flowable 的 TENANT_ID_ 列(varchar)。
 * 口径:引擎表绕过平台租户拦截器(方案 §3.1),租户只能在这两处显式进出,
 * 因此换算必须集中——散落各处的 String.valueOf 会让"无租户即拒绝"这条闸门前 later 失效。
 */
public final class WorkflowTenantIds {

    private WorkflowTenantIds() {
    }

    /**
     * 当前线程的租户身份,转成引擎侧字符串。
     * 无租户或正处于租户旁路窗口都直接拒绝:旁路窗口里写引擎表等于写一条没有归属的流程。
     */
    public static String requireEngineTenant() {
        Long tenantId = TenantContextHolder.get();
        if (tenantId == null || TenantContextHolder.isBypassed()) {
            throw BizException.forbidden("缺少租户上下文,拒绝流程引擎调用");
        }
        return String.valueOf(tenantId);
    }

    /**
     * 引擎侧租户字符串转回平台租户 ID。
     * 空串(引擎未部署到本租户)与非数字都是脏数据,拒绝而不是回退默认值——
     * 回退会让异步回调把数据写进一个不存在的租户,比报错更难排查。
     */
    public static Long toPlatformTenant(String engineTenantId) {
        if (engineTenantId == null || engineTenantId.isBlank()) {
            throw BizException.badRequest("流程对象缺少租户归属,拒绝执行");
        }
        try {
            return Long.valueOf(engineTenantId.trim());
        } catch (NumberFormatException e) {
            throw BizException.badRequest("流程对象租户标识非法:" + engineTenantId);
        }
    }
}
