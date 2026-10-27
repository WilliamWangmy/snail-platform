package com.snail.platform.framework.workflow.support;

import com.snail.platform.framework.core.exception.BizException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 职责:流程变量的准入闸门——网关读写变量都只经过本类,是全平台唯一的变量口径。
 * 上游:WorkflowEngineGateway 的发起/办理入参;下游:Flowable 变量表(会被 JSON 化进历史与轨迹)。
 * 口径:只收标量。流程变量是跨线程、跨版本、跨序列化器的数据,POJO/record 一旦入流程,
 * 引擎历史就与某个类结构绑死,改一个字段就可能在途实例读不出旧值(平台已踩过 Hutool 不序列化 record 的坑);
 * 日期同样不收对象,业务要传时间就传 ISO 字符串,显示口径由业务侧统一。
 * 上限:条数与字符串长度都钉死,防止有人把整张单据正文塞进变量把 ACT_HI_DETAIL 撑爆。
 */
public final class WorkflowVariablePolicy {

    /** 单次调用的变量条数上限:审批只需要标题、发起人、金额等少量判定字段 */
    private static final int MAX_VARIABLES = 16;

    /** 字符串值长度上限:超限说明在塞业务正文,应当留在业务表里 */
    private static final int MAX_STRING_LENGTH = 512;

    /** 变量名形态:字母开头,允许字母数字下划线,长度 1..64(与引擎列 NAME_ varchar(255) 相比更紧) */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** 允许的标量类型白名单 */
    private static final Class<?>[] ALLOWED_TYPES =
            {String.class, Integer.class, Long.class, Double.class, BigDecimal.class, Boolean.class};

    private WorkflowVariablePolicy() {
    }

    /**
     * 校验并返回不可变副本;入参为 null 视作无变量。
     * 拒绝信息点名具体键,便于前端直接定位到设计器里配错的那一处。
     */
    public static Map<String, Object> sanitize(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        if (variables.size() > MAX_VARIABLES) {
            throw BizException.badRequest("流程变量最多 " + MAX_VARIABLES + " 个,当前 " + variables.size() + " 个");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        variables.forEach((name, value) -> copy.put(requireName(name), requireValue(name, value)));
        return Map.copyOf(copy);
    }

    private static String requireName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw BizException.badRequest("流程变量名不合法:" + name);
        }
        return name;
    }

    private static Object requireValue(String name, Object value) {
        if (value == null) {
            throw BizException.badRequest("流程变量不允许为空值:" + name);
        }
        for (Class<?> allowed : ALLOWED_TYPES) {
            if (allowed.isInstance(value)) {
                if (value instanceof String text && text.length() > MAX_STRING_LENGTH) {
                    throw BizException.badRequest("流程变量超过 " + MAX_STRING_LENGTH + " 字符:" + name);
                }
                return value;
            }
        }
        throw BizException.badRequest("流程变量只允许标量类型,不接受 "
                + value.getClass().getSimpleName() + ":" + name);
    }
}
