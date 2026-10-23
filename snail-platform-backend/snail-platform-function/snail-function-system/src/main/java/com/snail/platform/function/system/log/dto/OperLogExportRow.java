package com.snail.platform.function.system.log.dto;

import cn.idev.excel.annotation.ExcelProperty;
import com.snail.platform.function.system.log.entity.SysOperLog;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:操作日志导出的 xlsx 行头定义——列序即 @ExcelProperty 声明序,码值在这里翻成中文标签,
 * 导出件给审计人看,不让人回去查枚举表。
 * 上游:OperLogService 导出投影;下游:ExcelExporter/fastexcel。
 * 约束:普通 JavaBean(fastexcel 按 getter/setter 反射装配,record 不适用于此);不含 param/result 全文。
 */
@Getter
@Setter
public class OperLogExportRow {

    /** 日志 ID */
    @ExcelProperty("日志ID")
    private Long id;

    /** 归属租户 */
    @ExcelProperty("租户")
    private Long tenantId;

    /** 功能模块 */
    @ExcelProperty("模块")
    private String module;

    /** 业务类型中文标签 */
    @ExcelProperty("业务类型")
    private String businessTypeLabel;

    /** Java 方法 */
    @ExcelProperty("方法")
    private String method;

    /** 操作人登录名 */
    @ExcelProperty("操作人")
    private String operator;

    /** 客户端 IP */
    @ExcelProperty("IP")
    private String ip;

    /** 请求地址 */
    @ExcelProperty("请求地址")
    private String url;

    /** 结果中文标签 */
    @ExcelProperty("结果")
    private String statusLabel;

    /** 失败原因 */
    @ExcelProperty("失败原因")
    private String errorMsg;

    /** 耗时毫秒 */
    @ExcelProperty("耗时(ms)")
    private Long costTime;

    /** 链路 ID */
    @ExcelProperty("链路ID")
    private String traceId;

    /** 操作时间 */
    @ExcelProperty("操作时间")
    private LocalDateTime operTime;

    public static OperLogExportRow of(SysOperLog log) {
        OperLogExportRow row = new OperLogExportRow();
        row.setId(log.getId());
        row.setTenantId(log.getTenantId());
        row.setModule(log.getModule());
        row.setBusinessTypeLabel(businessTypeLabel(log.getBusinessType()));
        row.setMethod(log.getMethod());
        row.setOperator(log.getOperator());
        row.setIp(log.getIp());
        row.setUrl(log.getUrl());
        row.setStatusLabel(Integer.valueOf(1).equals(log.getStatus()) ? "成功" : "失败");
        row.setErrorMsg(log.getErrorMsg());
        row.setCostTime(log.getCostTime());
        row.setTraceId(log.getTraceId());
        row.setOperTime(log.getOperTime());
        return row;
    }

    private static String businessTypeLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "新增";
            case 2 -> "修改";
            case 3 -> "删除";
            case 4 -> "导出";
            case 5 -> "导入";
            default -> "其他";
        };
    }
}
