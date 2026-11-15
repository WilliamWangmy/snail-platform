package com.snail.platform.function.monitor.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:总览 trends 出参契约——登录趋势(按日,缺失日补 0)与当前在线数。
 */
public record TrendsVO(

        @Schema(description = "登录趋势(日序升序,缺失日补 0)")
        List<DayTrend> loginTrend,

        @Schema(description = "当前在线用户数(与 collectOnline 同源)")
        long onlineNow) {

    /**
     * 单日趋势:date(yyyy-MM-dd)/total/fail。
     */
    public record DayTrend(
            /** 日期 yyyy-MM-dd */
            String date,
            /** 登录总数 */
            long total,
            /** 失败数 */
            long fail) {
    }
}
