package com.snail.platform.framework.workflow.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 职责:引擎时间到平台时间形态的唯一换算处。
 * 上游:WorkflowEngineGateway 读模型(引擎一律给 java.util.Date);下游:业务侧 VO 与前端展示。
 * 口径:引擎表的时间列与平台业务表同为 DATETIME(3),按平台既有约定用 LocalDateTime + 系统时区呈现,
 * 换算只在这一处发生,避免网关、service、前端三套时区理解。
 * 约束:流程变量里不放日期对象(见 WorkflowVariablePolicy),要传时间就传 ISO 字符串。
 */
public final class WorkflowTimes {

    private WorkflowTimes() {
    }

    /** 引擎时间转平台时间;引擎列为空(未结束、未到期)时返回 null,由调用方决定呈现 */
    public static LocalDateTime ofEngine(Date engineTime) {
        if (engineTime == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(engineTime.getTime()), ZoneId.systemDefault());
    }
}
