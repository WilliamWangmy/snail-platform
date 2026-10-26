package com.snail.platform.framework.storage;

import java.util.Map;
import java.util.Optional;

/**
 * 职责:存储后端注册表——当前写入后端(active)与全部已配置后端的只读视图,按元数据记录的类型路由历史对象。
 * 上游:SnailStorageConfig 装配;下游:function-storage 的上传、下载与删除。
 * 口径:activeType 为空表示存储整体关闭;按类型取不到端口返回空,由消费方翻译为业务错误,
 * 绝不静默回退当前后端——切换后端后把旧文件读错、删错比明确报错更糟。
 */
public final class StoragePortRegistry {

    /** 当前写入后端类型;空表示存储整体关闭 */
    private final String activeType;

    /** 已配置后端:类型 → 端口,构造后只读 */
    private final Map<String, StoragePort> ports;

    public StoragePortRegistry(String activeType, Map<String, StoragePort> ports) {
        this.activeType = activeType;
        this.ports = Map.copyOf(ports);
    }

    /** 当前写入端口;type 为空或对应端口缺席时返回空 */
    public Optional<StoragePort> active() {
        return activeType == null ? Optional.empty() : Optional.ofNullable(ports.get(activeType));
    }

    /** 当前写入类型;空表示存储整体关闭 */
    public String activeType() {
        return activeType;
    }

    /** 按元数据记录的类型取端口;未配置该类型返回空 */
    public Optional<StoragePort> of(String type) {
        return type == null ? Optional.empty() : Optional.ofNullable(ports.get(type));
    }
}
