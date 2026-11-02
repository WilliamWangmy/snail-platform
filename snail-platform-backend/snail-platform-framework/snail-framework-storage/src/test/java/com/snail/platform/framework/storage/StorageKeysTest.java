package com.snail.platform.framework.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.exception.BizException;
import org.junit.jupiter.api.Test;

/**
 * 职责:对象键安全闸门的离线单测——穿越/绝对路径/反斜杠/空段/白名单外字符的正反例钉死,不连存储。
 * 上游:StorageKeys;口径:每条负例断言到文案关键词,"被拒的原因"是契约的一部分。
 */
class StorageKeysTest {

    /** 服务端生成的标准键形态必须放行:数字/日期/UUID 分段 */
    @Test
    void acceptsServerGeneratedKeys() {
        assertEquals("7/202610/3f2a9c1e-8b4d-4e2f-9a01-55c0b7d8e3f2.jpg",
                StorageKeys.requireSafe("7/202610/3f2a9c1e-8b4d-4e2f-9a01-55c0b7d8e3f2.jpg"));
        assertEquals("single.txt", StorageKeys.requireSafe("single.txt"));
        assertEquals("a_b-c.d/e_f", StorageKeys.requireSafe("a_b-c.d/e_f"));
    }

    /** 路径穿越与逃逸形态一律拒绝,文案点名原因 */
    @Test
    void rejectsTraversalShapes() {
        assertRejected("../../etc/cron.d/x", "..");
        assertRejected("7/../../x", "..");
        assertRejected("7/..%2Fx", "只允许");
        assertRejected("a/../b", "..");
        assertRejected("./x", ".");
        assertRejected("a//b", "空路径段");
        assertRejected("/abs/path.txt", "绝对路径");
        assertRejected("a\\..\\b.txt", "反斜杠");
        assertRejected("a/b c.txt", "只允许");
        assertRejected("中文/文件名.txt", "只允许");
        assertRejected("a/b%2e%2e/c", "只允许");
    }

    /** 空键与超长键拒绝 */
    @Test
    void rejectsBlankAndOversizedKeys() {
        assertThrows(BizException.class, () -> StorageKeys.requireSafe(null));
        assertThrows(BizException.class, () -> StorageKeys.requireSafe(""));
        assertThrows(BizException.class, () -> StorageKeys.requireSafe("   "));
        String oversized = "a".repeat(513);
        BizException error = assertThrows(BizException.class, () -> StorageKeys.requireSafe(oversized));
        assertTrue(error.getMessage().contains("长度超过上限"), error.getMessage());
    }

    private void assertRejected(String key, String expectedKeyword) {
        BizException error = assertThrows(BizException.class, () -> StorageKeys.requireSafe(key),
                "应拒绝:" + key);
        assertEquals(400, error.getCode(), error.getMessage());
        assertTrue(error.getMessage().contains(expectedKeyword),
                "拒绝文案应包含 [" + expectedKeyword + "],实际:" + error.getMessage());
    }
}
