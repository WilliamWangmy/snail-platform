package com.snail.platform.framework.excel;

import cn.idev.excel.FastExcel;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 职责:fastexcel 的唯一出口——把行数据写成 xlsx 直接挂到 HTTP 响应上,表头由头类的
 * cn.idev.excel.annotation.ExcelProperty 声明;业务侧不许再碰 FastExcel API,换库只改这一处。
 * 上游:各导出功能(Service/Controller);下游:HttpServletResponse 输出流。
 * 口径:文件名 = 标题 + 时间戳,.xlsx 扩展名走 UTF-8 编码的 Content-Disposition;
 * 写出失败(响应已提交一半)不吞——异常交给全局处理器记应用日志,浏览器侧拿到残缺文件自担重试。
 */
public final class ExcelExporter {

    /** xlsx 的 MIME;下载即存盘,不内联预览 */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 文件名时间戳口径,与前端展示解耦 */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ExcelExporter() {
    }

    /**
     * 写 xlsx 到响应流。headType 是带 @ExcelProperty 注解的导出行类(普通 JavaBean,非 record——
     * fastexcel 按 getter/setter 反射装配),rows 已按业务口径筛好,这里不做任何过滤。
     */
    public static <T> void write(HttpServletResponse response, String title,
                                 Class<T> headType, List<T> rows) throws IOException {
        String fileName = title + "_" + LocalDateTime.now().format(FILE_STAMP) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setContentType(XLSX_MIME);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        FastExcel.write(response.getOutputStream(), headType)
                .sheet(title)
                .doWrite(rows);
    }
}
