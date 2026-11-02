package com.snail.platform.framework.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 职责:一次性口令回传的传输加密——AES-256-GCM,每条随机 12 字节 IV,输出 Base64(iv ‖ 密文 ‖ tag)。
 * 方向:服务端加密、管理端解密展示,响应体里不再出现口令明文;操作日志落库拿到的同样是这段密文。
 * 口径:密钥来自 snail.security.password-transfer.key(Base64,解码后必须 32 字节),缺失或长度不符拒绝启动;
 * 前端实现见 snail-platform-frontend-admin/src/utils/passwordTransfer.ts,两侧报文格式必须同改。
 * 报文以 "v1:" 版本前缀开头:密钥轮换或格式演进时前缀递增,消费端按前缀识别,
 * 避免换钥后新旧密文无法区分、解密失败无从归因。
 * 边界(重要):密钥随前端包一起分发,这不是机密性控制——它只防链路明文被日志/代理误记录,
 * 链路机密性由 HTTPS 保证;不得把本机制当作传输保护依赖,轮换须与前端构建同步进行。
 */
public class PasswordTransferCipher {

    /**
     * 报文格式版本前缀:前端 passwordTransfer.ts 的 VERSION_PREFIX 与此同值同改。
     * 轮换流程:新钥上线时前缀升为 v2: 并与前端构建同步发布,旧密文按旧前缀归因。
     */
    public static final String VERSION_PREFIX = "v1:";

    /** GCM 推荐 IV 长度:12 字节,与前端 subarray 切分口径一致 */
    private static final int IV_LENGTH = 12;

    /** 认证标签位数:GCM 默认 128 位,前端 tagLength 同值 */
    private static final int TAG_BITS = 128;

    /** AES 密钥规格 */
    private final SecretKeySpec key;

    /** IV 一次性随机源 */
    private final SecureRandom random = new SecureRandom();

    /** 解码并校验密钥:Base64 非法、缺失或不是 32 字节都在启动期拒绝,不等到加密时才炸 */
    public PasswordTransferCipher(String base64Key) {
        byte[] raw;
        try {
            raw = base64Key == null || base64Key.isBlank() ? null : Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("snail.security.password-transfer.key 不是合法 Base64", e);
        }
        if (raw == null || raw.length != 32) {
            throw new IllegalStateException("snail.security.password-transfer.key 缺失或解码后不是 32 字节,拒绝启动");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密一次性口令,返回值可直接挂到 password 键出参(键名保持原样供审计脱敏) */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[IV_LENGTH + sealed.length];
            System.arraycopy(iv, 0, payload, 0, IV_LENGTH);
            System.arraycopy(sealed, 0, payload, IV_LENGTH, sealed.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("口令传输加密失败", e);
        }
    }
}
