/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 天地图浏览器端密钥（https://console.tianditu.gov.cn/api/key 申请） */
  readonly VITE_TIANDITU_KEY?: string;
  /** 地图初始中心点经度（十进制度） */
  readonly VITE_MAP_CENTER_LON?: string;
  /** 地图初始中心点纬度（十进制度） */
  readonly VITE_MAP_CENTER_LAT?: string;
  /** 地图初始相机高度（米） */
  readonly VITE_MAP_CENTER_HEIGHT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
