package com.snail.platform.function.system.log.dto;

import cn.idev.excel.annotation.ExcelProperty;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:登录日志导出的 xlsx 行头定义,口径与操作日志导出行一致(码值翻中文、列序即声明序)。
 * 上游:LoginLogService 导出投影;下游:ExcelExporter/fastexcel。
 */
@Getter
@Setter
public class LoginLogExportRow {

    /** 日志 ID */
    @ExcelProperty("日志ID")
    private Long id;

    /** 归属租户,0=归属不明 */
    @ExcelProperty("租户")
    private Long tenantId;

    /** 登录名快照 */
    @ExcelProperty("登录名")
    private String username;

    /** 客户端 IP */
    @ExcelProperty("IP")
    private String ip;

    /** 浏览器 */
    @ExcelProperty("浏览器")
    private String browser;

    /** 操作系统 */
    @ExcelProperty("操作系统")
    private String os;

    /** 结果中文标签 */
    @ExcelProperty("结果")
    private String statusLabel;

    /** 结果说明 */
    @ExcelProperty("说明")
    private String msg;

    /** 登录时间 */
    @ExcelProperty("登录时间")
    private LocalDateTime loginTime;

    public static LoginLogExportRow of(SysLoginLog log) {
        LoginLogExportRow row = new LoginLogExportRow();
        row.setId(log.getId());
        row.setTenantId(log.getTenantId());
        row.setUsername(log.getUsername());
        row.setIp(log.getIp());
        row.setBrowser(log.getBrowser());
        row.setOs(log.getOs());
        row.setStatusLabel(Integer.valueOf(1).equals(log.getStatus()) ? "成功" : "失败");
        row.setMsg(log.getMsg());
        row.setLoginTime(log.getLoginTime());
        return row;
    }
}
