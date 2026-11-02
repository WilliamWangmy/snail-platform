package com.snail.platform.framework.storage;

import com.snail.platform.framework.core.exception.BizException;
import java.util.regex.Pattern;

/**
 * 职责:对象键的统一安全闸门——两个端口实现(SFTP/S3)的每个公开方法入口都必须先过这一道。
 * 上游:StoragePort 的实现;下游:无。
 * 口径:键的构成要素(文件名)最终来自用户上传,框架不能把"业务侧会清洗"当防线,
 * 路径穿越必须在这里结构性挡死,而不是寄希望于某一处调用点记得校验。
 * S3 的键虽无 .. 语义,但两端口口径不一致本身就是坑,统一收紧;
 * 确需特殊键名的实现可显式绕过本工具(需注释说明理由),默认实现不允许。
 */
public final class StorageKeys {

    /** 键字符白名单:字母数字、正斜杠、点、下划线、连字符——服务端生成的 {tenantId}/{yyyyMM}/{uuid}.{ext} 全在其内 */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9/._-]+");

    /** 键长度上限:UUID 分段键远小于此,超限视为攻击或误用 */
    private static final int MAX_KEY_LENGTH = 512;

    private StorageKeys() {
    }

    /**
     * 校验对象键并原样返回;不合法即 BizException.badRequest,文案点名原因。
     * 规则:非空、≤512 字符、无反斜杠、不以 / 开头(绝对路径)、无空段(//)、无 .. 段(路径穿越)、
     * 字符全部落在白名单内(同时挡掉中文文件名等未收口的形态——键由服务端生成,不收用户原始输入)。
     */
    public static String requireSafe(String key) {
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("对象键不能为空");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw BizException.badRequest("对象键长度超过上限 " + MAX_KEY_LENGTH);
        }
        if (key.indexOf('\\') >= 0) {
            throw BizException.badRequest("对象键不允许包含反斜杠:" + key);
        }
        if (key.startsWith("/")) {
            throw BizException.badRequest("对象键不允许是绝对路径:" + key);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty()) {
                throw BizException.badRequest("对象键不允许出现空路径段:" + key);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw BizException.badRequest("对象键不允许出现 . 或 .. 路径段:" + key);
            }
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            throw BizException.badRequest("对象键只允许字母数字与 / . _ - :" + key);
        }
        return key;
    }
}
