package com.snail.platform.function.external.api;

import java.util.Optional;

/**
 * 职责:IP 归属地定位端口——消费方要"把 IP 变成省市",不感知高德与 key。
 * 契约口径:归属地是装饰信息,失败、未配置、QPS 超限一律返回 Optional.empty() 静默降级,
 * 消费方(如登录日志)拿不到就写"未知"——降级策略住端口契约,不藏在实现里。
 * 上游:system 登录日志(M3 接入);下游:AmapIpClient。
 */
public interface IpGeoPort {

    /** 定位能力是否可用:未配置 amap key 时为 false,调用方直接写"未知",不必发起调用 */
    boolean available();

    /** 按 IP 查归属地;任何失败返回 empty,不抛异常 */
    Optional<IpLocation> locate(String ip);

    /** 归属地结果:省/市/行政区划码,字段可能为空(高德对内网 IP 等返回不完整) */
    record IpLocation(
            /** 省份 */
            String province,
            /** 城市 */
            String city,
            /** 行政区划码 */
            String adcode) {
    }
}
