import { Credit, UrlTemplateImageryProvider } from 'cesium';

/**
 * 天地图密钥：请在项目根目录创建 .env 文件并配置
 *   VITE_TIANDITU_KEY=你的天地图浏览器端密钥
 * 申请入口：https://console.tianditu.gov.cn/api/key （注册 → 创建应用 → 类型选“浏览器端”）
 *
 * 注意：实测本 key 走 DataServer 接口可正常取图；WMTS 接口（/img_w/wmts）会被
 * 服务端以“此级别下无影像”占位图软拦截，因此这里统一使用 DataServer 瓦片模板。
 */
export const TIANDITU_KEY: string = import.meta.env.VITE_TIANDITU_KEY ?? '';

/** 地图初始视角配置（经纬度中心点 + 相机高度） */
export interface MapCenter {
  /** 经度（十进制度） */
  lon: number;
  /** 纬度（十进制度） */
  lat: number;
  /** 相机高度（米） */
  height: number;
}

function parseNumber(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

/** 初始中心点：默认成都（天府广场），可通过 .env.local 的 VITE_MAP_CENTER_LON/LAT/HEIGHT 覆盖 */
export const MAP_CENTER: MapCenter = {
  lon: parseNumber(import.meta.env.VITE_MAP_CENTER_LON, 104.065735),
  lat: parseNumber(import.meta.env.VITE_MAP_CENTER_LAT, 30.659462),
  height: parseNumber(import.meta.env.VITE_MAP_CENTER_HEIGHT, 200000),
};

/** 天地图 DataServer 图层类型（_w = Web 墨卡托投影） */
export type TiandituLayerType =
  | 'vec' // 矢量底图
  | 'cva' // 矢量注记（中文地名）
  | 'img' // 影像底图
  | 'cia' // 影像注记
  | 'ter' // 地形晕渲
  | 'cta'; // 地形注记

/**
 * 创建天地图影像图层（DataServer 接口，EPSG:3857 / Web 墨卡托）
 * @param type 图层类型，如 img + cia（影像+注记）或 vec + cva（矢量+注记）
 * @param maximumLevel 最大缩放级别（天地图支持到 18 级）
 */
export function createTiandituImagery(
  type: TiandituLayerType,
  maximumLevel = 18,
): UrlTemplateImageryProvider {
  if (!TIANDITU_KEY) {
    throw new Error(
      '未配置天地图密钥：请在项目根目录创建 .env 文件，设置 VITE_TIANDITU_KEY=你的密钥（https://console.tianditu.gov.cn/api/key 申请）',
    );
  }

  return new UrlTemplateImageryProvider({
    url: `https://t{s}.tianditu.gov.cn/DataServer?T=${type}_w&x={x}&y={y}&l={z}&tk=${TIANDITU_KEY}`,
    subdomains: ['0', '1', '2', '3', '4', '5', '6', '7'],
    maximumLevel,
    credit: new Credit('© 国家地理信息公共服务平台 天地图'),
  });
}
