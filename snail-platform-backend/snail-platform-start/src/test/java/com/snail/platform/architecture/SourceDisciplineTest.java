package com.snail.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 职责:源码文本层的审计纪律——写库不得用 update(null, wrapper) 旁路自动填充。
 * ArchUnit 判不了这条:违规形态是"调用的第一个实参是 null 字面量",字节码里没有可读的口径;
 * 后果还是静默的(行改了、update_time/update_by 停在旧值,审计断链无人报警),所以必须在构建期拦。
 * 上游:snail-platform-backend 各模块的 src/main/java;下游:mvn test 常规构建即拦截。
 */
class SourceDisciplineTest {

    /** MyBatis-Plus 的 update(null, wrapper):实体为空,SnailAuditMetaObjectHandler 的 updateFill 不触发 */
    private static final Pattern BYPASSED_AUDIT_UPDATE = Pattern.compile("\\.update\\(\\s*null\\s*,");

    /** 聚合根目录下的后端模块目录,用它定位源码树 */
    private static final String FUNCTION_MODULE = "snail-platform-function";

    @Test
    void noAuditFillBypass() throws IOException {
        Path root = backendRoot();
        List<String> violations = new ArrayList<>();
        for (Path file : mainSources(root)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (BYPASSED_AUDIT_UPDATE.matcher(lines.get(i)).find()) {
                    violations.add(root.relativize(file) + ":" + (i + 1) + " " + lines.get(i).trim());
                }
            }
        }
        assertFalse(violations.isEmpty() && mainSources(root).isEmpty(),
                "未定位到后端源码树,本纪律会空跑:请确认从聚合根目录执行构建");
        assertTrue(violations.isEmpty(),
                "写库必须传实体让 updateFill 补 update_time/update_by,不得用 update(null, wrapper):\n"
                        + String.join("\n", violations));
    }

    /** 从测试工作目录(模块 basedir)逐级上溯,找到同时含 function 与 framework 模块的聚合根 */
    private static Path backendRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && cursor != null; depth++, cursor = cursor.getParent()) {
            if (Files.isDirectory(cursor.resolve(FUNCTION_MODULE))
                    && Files.isDirectory(cursor.resolve("snail-platform-framework"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("未能从 " + Path.of("").toAbsolutePath()
                + " 上溯定位到后端聚合根,SourceDisciplineTest 需要在 Maven 构建内运行");
    }

    /** 后端全部模块的主源码 */
    private static List<Path> mainSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> path.contains(Path.of("src", "main", "java")))
                    .forEach(files::add);
        }
        return files;
    }
}
