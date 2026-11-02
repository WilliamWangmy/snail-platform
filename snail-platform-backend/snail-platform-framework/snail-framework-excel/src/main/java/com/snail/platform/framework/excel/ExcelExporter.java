package com.snail.platform.framework.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.converters.string.StringStringConverter;
import cn.idev.excel.metadata.data.WriteCellData;
import cn.idev.excel.metadata.property.ExcelContentProperty;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 职责:fastexcel 的唯一出口——把行数据写成 xlsx 直接挂到 HTTP 响应上,表头由头类的
 * cn.idev.excel.annotation.ExcelProperty 声明;业务侧不许再碰 FastExcel API,换库只改这一处。
 * 上游:各导出功能(Service/Controller);下游:HttpServletResponse 输出流。
 * 口径:文件名 = 标题 + 时间戳,.xlsx 扩展名走 UTF-8 编码的 Content-Disposition;
 * 写出失败(响应已提交一半)不吞——异常交给全局处理器记应用日志,浏览器侧拿到残缺文件自担重试。
 * 公式注入(CSV Injection 对 xlsx 同样成立):单元格文本以 = + - @ 制表符回车开头时,
 * Excel/WPS 打开即按公式执行(=HYPERLINK、DDE 等),导出数据里的用户可控字段是标准攻击面——
 * 全平台导出只走本出口,转义在这里做一次就全覆盖;命中即前置单引号强制按文本处理。
 */
public final class ExcelExporter {

    /** xlsx 的 MIME;下载即存盘,不内联预览 */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 公式前缀特征:以此开头的单元格文本会被电子表格软件当公式/命令求值 */
    private static final Pattern FORMULA_PREFIX = Pattern.compile("^[=+\\-@\t\r]");

    /** sheet 名非法字符:Excel 禁止 : \ / ? * [ ],出现即替换为下划线 */
    private static final Pattern SHEET_ILLEGAL = Pattern.compile("[:\\\\/?*\\[\\]]");

    /** sheet 名长度上限:Excel 规范 31 字符 */
    private static final int SHEET_NAME_LIMIT = 31;

    /** 文件名时间戳口径,与前端展示解耦 */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ExcelExporter() {
    }

    /**
     * 写 xlsx 到响应流。headType 是带 @ExcelProperty 注解的导出行类(普通 JavaBean,非 record——
     * fastexcel 按 getter/setter 反射装配),rows 已按业务口径筛好,这里不做任何过滤。
     * 额外补 nosniff:响应中途失败时浏览器不得对残缺内容做 MIME 嗅探。
     */
    public static <T> void write(HttpServletResponse response, String title,
                                 Class<T> headType, List<T> rows) throws IOException {
        String fileName = title + "_" + LocalDateTime.now().format(FILE_STAMP) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setContentType(XLSX_MIME);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("X-Content-Type-Options", "nosniff");
        FastExcel.write(response.getOutputStream(), headType)
                .registerConverter(new FormulaSafeStringConverter())
                .sheet(sanitizeSheetName(title))
                .doWrite(rows);
    }

    /**
     * 字符串单元格的公式注入转义:以 = + - @ 制表符回车开头的文本会被电子表格软件当公式求值,
     * 前置单引号强制按文本处理。注册在本出口一次,全平台导出统一生效。
     */
    static final class FormulaSafeStringConverter extends StringStringConverter {

        @Override
        public WriteCellData<?> convertToExcelData(String value, ExcelContentProperty contentProperty,
                                                   cn.idev.excel.metadata.GlobalConfiguration globalConfiguration) {
            if (value != null && FORMULA_PREFIX.matcher(value).find()) {
                value = "'" + value;
            }
            return new WriteCellData<>(value);
        }
    }

    /** sheet 名清洗:非法字符替换为下划线并截断到 31 字符,保证任何业务标题都能开成合法 sheet */
    private static String sanitizeSheetName(String title) {
        String cleaned = SHEET_ILLEGAL.matcher(title == null ? "sheet" : title).replaceAll("_");
        if (cleaned.isBlank()) {
            cleaned = "sheet";
        }
        return cleaned.length() > SHEET_NAME_LIMIT ? cleaned.substring(0, SHEET_NAME_LIMIT) : cleaned;
    }
}
