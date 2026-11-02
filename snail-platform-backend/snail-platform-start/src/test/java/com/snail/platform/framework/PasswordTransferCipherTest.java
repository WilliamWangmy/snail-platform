package com.snail.platform.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.security.crypto.PasswordTransferCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 职责:口令回传加密的报文格式守门——密文必须能按 Base64(iv ‖ 密文 ‖ tag) 还原,IV 每条随机,篡改即解不开。
 * 前端 src/utils/passwordTransfer.ts 按同一分帧切 iv,这里用 JCE 手工解一遍等价于前端的解密动作。
 */
class PasswordTransferCipherTest {

    /** 与 application-dev.yml 的开发占位密钥同值,仅测试环境使用 */
    private static final String KEY = "+boKbssh0dSJgi2wEIJbWRBWzL9Y5CDuIN9ZDxvcJVs=";

    @Test
    void roundTripsAndNeverRepeatsIv() throws Exception {
        PasswordTransferCipher cipher = new PasswordTransferCipher(KEY);
        String first = cipher.encrypt("Abc@12345678");
        String second = cipher.encrypt("Abc@12345678");

        assertTrue(first.startsWith(PasswordTransferCipher.VERSION_PREFIX),
                "密文必须携带版本前缀供前端识别报文格式");
        assertEquals("Abc@12345678", decrypt(first), "密文应按 前缀+iv‖密文‖tag 还原出原文");
        assertNotEquals(first, second, "同一明文两次加密应因随机 IV 产出不同密文");
        assertFalse(first.contains("Abc@12345678"), "密文中不得出现口令片段");
    }

    @Test
    void rejectsTamperedPayload() {
        PasswordTransferCipher cipher = new PasswordTransferCipher(KEY);
        String sealed = cipher.encrypt("Abc@12345678");
        byte[] packed = Base64.getDecoder().decode(sealed.substring(PasswordTransferCipher.VERSION_PREFIX.length()));
        packed[packed.length - 1] ^= 0x01;

        assertThrows(Exception.class, () -> decrypt(PasswordTransferCipher.VERSION_PREFIX
                + Base64.getEncoder().encodeToString(packed)),
                "GCM 认证标签应拦住被篡改的密文");
    }

    @Test
    void rejectsMissingOrWrongSizedKey() {
        assertThrows(IllegalStateException.class, () -> new PasswordTransferCipher(null), "缺密钥应拒绝启动");
        assertThrows(IllegalStateException.class, () -> new PasswordTransferCipher("not-base64"), "非法 Base64 应拒绝启动");
        assertThrows(IllegalStateException.class,
                () -> new PasswordTransferCipher(Base64.getEncoder().encodeToString(new byte[16])),
                "16 字节密钥不是 AES-256,应拒绝启动");
    }

    /** 按前端分帧解密:剥掉 v1: 前缀,前 12 字节 IV,其余为 GCM 密文与 128 位标签 */
    private static String decrypt(String packed) throws Exception {
        String payload64 = packed.substring(PasswordTransferCipher.VERSION_PREFIX.length());
        byte[] payload = Base64.getDecoder().decode(payload64);
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload, 0, 12));
        return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
    }
}
