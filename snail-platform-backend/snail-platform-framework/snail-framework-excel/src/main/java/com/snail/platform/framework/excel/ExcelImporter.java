package com.snail.platform.framework.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.exception.ExcelAnalysisException;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import com.snail.platform.framework.core.exception.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 职责:fastexcel 的唯一入口——把上传的表格流按 @ExcelProperty 头类读成行 Bean 列表;
 * 业务侧不许再碰 FastExcel API,换库只改这一处,与 {@link ExcelExporter} 构成读写两个单点。
 * 上游:各导入功能(Service);下游:multipart 文件输入流的解析。
 * 口径:只读第一个 sheet,表头按 1 行处理;行数上限在此单点把守(防恶意大文件把解析堆撑爆),
 * 超限与解析失败统一转业务拒绝,不让 ExcelAnalysisException 这类实现词汇漏进调用方;
 * 监听器抛出的业务拒绝可能被包装成 ExcelAnalysisException 回传,解包后原样上抛保住文案。
 */
public final class ExcelImporter {

    private ExcelImporter() {
    }

    /**
     * 读全部数据行。headType 是带 @ExcelProperty 注解的行类(普通 JavaBean,非 record——
     * fastexcel 按字段反射装配);maxRows 是本批行数上限,由调用方按业务口径传入,超限即拒。
     * 空表返回空列表,"没有数据"算不算错误由业务侧裁决,这里不做语义判断。
     */
    public static <T> List<T> read(InputStream input, Class<T> headType, int maxRows) throws IOException {
        List<T> rows = new ArrayList<>();
        try {
            FastExcel.read(input, headType, new RowCollector<>(rows, maxRows)).sheet(0).doRead();
        } catch (ExcelAnalysisException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw BizException.badRequest("文件解析失败,请按导入模板填写后上传 xlsx 文件");
        }
        return rows;
    }

    /**
     * 收集监听器:逐行收进列表,超过上限即抛业务拒绝中止解析——导入是低频管理操作,
     * 全量收集内存可控,不值得为此引入分批消费的复杂度。
     */
    private static final class RowCollector<T> extends AnalysisEventListener<T> {

        private final List<T> rows;
        private final int maxRows;

        private RowCollector(List<T> rows, int maxRows) {
            this.rows = rows;
            this.maxRows = maxRows;
        }

        @Override
        public void invoke(T row, AnalysisContext context) {
            if (rows.size() >= maxRows) {
                throw BizException.badRequest("单次导入不超过 " + maxRows + " 行,请分批上传");
            }
            rows.add(row);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 全表读完,无需收尾
        }
    }
}
