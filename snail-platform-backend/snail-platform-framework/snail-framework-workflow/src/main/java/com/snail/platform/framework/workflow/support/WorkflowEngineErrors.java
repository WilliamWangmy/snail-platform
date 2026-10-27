package com.snail.platform.framework.workflow.support;

import com.snail.platform.framework.core.exception.BizException;
import java.util.function.Supplier;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;

/**
 * 职责:引擎异常到平台业务码的唯一映射处——网关每个动作都包在本类里执行。
 * 上游:WorkflowEngineGateway;下游:GlobalExceptionHandler(只认识 BizException 与未预期异常)。
 * 口径:引擎异常类不得越过 framework-workflow 这道边界(方案 §2:业务面不认识 Flowable)。
 * 分类:对象不存在/参数非法是调用方的错,翻 400 给前端可读文案;其余 FlowableException 是引擎自身故障,
 * 归为未预期异常走 500 并留原始 cause 供日志定位,不拿引擎英文文案直接回给使用者。
 */
public final class WorkflowEngineErrors {

    private WorkflowEngineErrors() {
    }

    /** 包一层执行有返回值的引擎调用 */
    public static <T> T supply(String action, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            throw translate(action, e);
        }
    }

    /** 包一层执行无返回值的引擎调用 */
    public static void run(String action, Runnable body) {
        supply(action, () -> {
            body.run();
            return null;
        });
    }

    /** BizException 直通:网关自己的闸门(租户、办理人、变量)已经给过可读文案,不再二次包装 */
    private static RuntimeException translate(String action, RuntimeException e) {
        if (e instanceof BizException) {
            return e;
        }
        if (e instanceof FlowableObjectNotFoundException) {
            return BizException.badRequest(action + "失败:目标流程对象不存在或已被办理");
        }
        if (e instanceof FlowableIllegalArgumentException) {
            return BizException.badRequest(action + "失败:流程参数不合法");
        }
        if (e instanceof FlowableException) {
            return new IllegalStateException("流程引擎执行异常:" + action, e);
        }
        return e;
    }
}
