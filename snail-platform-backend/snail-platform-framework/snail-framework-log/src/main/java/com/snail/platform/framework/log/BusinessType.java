package com.snail.platform.framework.log;

/**
 * 职责:操作日志的业务类型枚举,落库为 TINYINT 码,口径与 sys_oper_log.business_type 注释一致。
 * 上游:@OperLog 注解属性;下游:framework-log 切面、日志查询与导出展示。
 */
public enum BusinessType {

    /** 新增 */
    INSERT(1),

    /** 修改(含状态变更、移动、授权覆盖这类写操作) */
    UPDATE(2),

    /** 删除 */
    DELETE(3),

    /** 导出——导出自身是操作,必须留痕 */
    EXPORT(4),

    /** 导入 */
    IMPORT(5),

    /** 其他写操作 */
    OTHER(9);

    /** 落库码值 */
    private final int code;

    BusinessType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
