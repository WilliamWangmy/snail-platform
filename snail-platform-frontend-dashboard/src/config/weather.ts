/**
 * 顶栏天气快照。接入第三方气象接口后只改本文件:换成异步取数,字段口径保持不变。
 * live=false 表示这是内置静态快照,卡片会标「静态」,避免投屏时被当成实时读数。
 */
export interface WeatherSnapshot {
  city: string;
  /** 现象描述,直接取接口文案 */
  text: string;
  tempC: number;
  /** 风向 + 风力级数,不含「风」前缀 */
  wind: string;
  /** 相对湿度百分数 */
  humidity: number;
  aqi: number;
  aqiLevel: string;
  /** 数据更新时间 HH:mm */
  updatedAt: string;
  live: boolean;
}

export const weather: WeatherSnapshot = {
  city: '成都',
  text: '多云转阴',
  tempC: 21,
  wind: '西南 3 级',
  humidity: 62,
  aqi: 47,
  aqiLevel: '优',
  updatedAt: '19:36',
  live: false,
};
