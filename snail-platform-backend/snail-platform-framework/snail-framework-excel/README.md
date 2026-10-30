# snail-framework-excel

Excel 导出单点：把已筛好的行数据写成 xlsx，直接挂到 HTTP 响应。

业务侧不碰 fastexcel API，换库只改这一处；导入通道尚未实现。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.excel`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 这个模块负责什么

**负责**：

- xlsx 写出：表头由导出行类的 `@ExcelProperty` 声明，行数据原样写入；
- 响应头：`Content-Type` 为 xlsx MIME，文件名 `标题_yyyyMMddHHmmss.xlsx`，UTF-8 编码的
  `Content-Disposition`（含 `filename*=UTF-8''` 供现代浏览器使用）；
- 写出失败不吞：异常交给全局处理器记日志，浏览器侧拿到残缺文件自行重试。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 导出数据筛选、权限、分页/全量口径 | 各业务 Service |
| 导出操作留痕 | `@OperLog(businessType = EXPORT)` + `snail-framework-log` |
| 导入、模板下载、单元格样式定制 | 当前不提供 |
| 文件存储 | `snail-framework-storage` |

## 在平台中的位置

```
Controller（导出端点）
   ├─ Service 查数、筛权限、组装导出行
   └─ ExcelExporter.write(response, title, ExportRow.class, rows)
          └─ fastexcel ──▶ HttpServletResponse 输出流
```

## 包结构

```
com.snail.platform.framework.excel
└── ExcelExporter.java   唯一导出入口（静态工具类）
```

## 整体设计

### 调用契约

```java
public static <T> void write(HttpServletResponse response, String title,
                             Class<T> headType, List<T> rows) throws IOException
```

- `headType`：带 `cn.idev.excel.annotation.ExcelProperty` 注解的普通 JavaBean（**不是 record**——
  fastexcel 按 getter/setter 反射装配）；
- `rows`：已按业务口径筛好，本模块不做任何过滤、排序或截断；
- `title`：同时作为文件名前缀与 sheet 名；
- 每次导出独立写响应流，不支持多次追加。

### 与留痕的关系

导出自身是操作，端点必须标 `@OperLog(businessType = BusinessType.EXPORT)`。
`OperLogAspect` 对 `byte[]`/`Resource` 响应只记占位文案，不会把文件内容写进 `sys_oper_log`。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`ExcelExporter`](src/main/java/com/snail/platform/framework/excel/ExcelExporter.java) | 写 xlsx 到响应 | 静态工具类；`headType` 必须是 JavaBean；行集已筛好；文件名时间戳 `yyyyMMddHHmmss` |

## 配置项

本模块无配置项。

## 使用方式

```java
@OperLog(module = "操作日志", businessType = BusinessType.EXPORT)
@PreAuthorize("hasAuthority('system:log:export')")
@GetMapping("/export")
public void export(OperLogQuery query, HttpServletResponse response) throws IOException {
    List<OperLogExportRow> rows = operLogService.exportRows(query);
    ExcelExporter.write(response, "操作日志", OperLogExportRow.class, rows);
}

// 导出行类必须是 JavaBean，字段由 @ExcelProperty 声明表头
public class OperLogExportRow {
    @ExcelProperty("操作时间")
    private LocalDateTime operTime;
    // getter/setter ...
}
```

## 验证手段

- 本模块无独立单测；
- 导出链路由 `OperLogFlowTest` 覆盖：`GET /system/log/oper/export` 与 `GET /system/log/login/export`
  验证响应与导出留痕；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **没有导入通道**：README 旧描述中的"分批导入"不存在；xlsx 读取能力尚未接入；
- **导出行不能用 record**：fastexcel 按 getter/setter 反射，record 无法作为表头类；
- **不支持样式定制**：列宽、颜色、合并单元格等未封装；需要时先扩本模块，不允许业务直接引 fastexcel；
- **全量导出有内存风险**：`rows` 为内存列表，超大导出应先做分页/分批策略；
- **写出失败不可回滚**：响应已提交一半时只能让浏览器侧重试，服务端记录为未预期异常；
- **文件名已做 UTF-8 编码**：中文标题无需调用方再处理。
