package com.snail.platform.framework.storage;

import java.io.InputStream;

/**
 * 职责:对象存储的统一端口——放/取/删/存在性,键由业务侧生成,实现不解析键语义。
 * 上游:function-storage 按文件元数据从 StoragePortRegistry 取用;下游:SftpStoragePort/S3StoragePort。
 * 口径:snail.storage.type 为空时注册表没有写入端口,消费方按缺席降级;get 返回的流由调用方负责关闭;
 * 存在性判断不作为并发保证,业务侧存在性一律以元数据表为准,本端口只服务物理层。
 */
public interface StoragePort {

    /**
     * 放入对象;size 必须与流的真实字节数一致,实现据此预声明长度(部分后端必填)。
     * 键不存在则创建,已存在则覆盖。
     */
    void put(String key, InputStream in, long size, String contentType);

    /** 取对象内容流,调用方负责关闭;键不存在由实现抛出运行时异常,业务侧应先经元数据确认 */
    InputStream get(String key);

    /** 删除对象;键不存在视为已删,保持幂等不报错 */
    void delete(String key);

    /** 存在性探测 */
    boolean exists(String key);
}
