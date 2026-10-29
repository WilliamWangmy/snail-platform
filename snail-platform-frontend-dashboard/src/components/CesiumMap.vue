<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { Cartesian3, Viewer } from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { MAP_CENTER, TIANDITU_KEY, createTiandituImagery } from '@/config/tianditu';
import MapToolbar from '@/components/MapToolbar.vue';

const containerRef = ref<HTMLDivElement>();
const keyMissing = ref(false);

let viewer: Viewer | null = null;

onMounted(() => {
  if (!containerRef.value) return;

  if (!TIANDITU_KEY) {
    keyMissing.value = true;
    return;
  }

  // 隐藏 Cesium 自带版权容器（左下角 "Cesium ion" 等文字），
  // 天地图来源标注改由模板中的自定义角标展示
  const hiddenCreditContainer = document.createElement('div');
  hiddenCreditContainer.style.display = 'none';

  viewer = new Viewer(containerRef.value, {
    // 底图由天地图提供，禁用 Cesium Ion 相关控件（Ion 需要 token 且默认底图为国外服务）
    baseLayer: false,
    baseLayerPicker: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    navigationHelpButton: false,
    animation: false,
    timeline: false,
    fullscreenButton: false,
    infoBox: false,
    selectionIndicator: false,
    creditContainer: hiddenCreditContainer,
    // 性能优化：无变化时不渲染
    requestRenderMode: true,
    maximumRenderTimeChange: Infinity,
  });

  // 影像底图 + 中文注记
  viewer.imageryLayers.addImageryProvider(createTiandituImagery('img'));
  viewer.imageryLayers.addImageryProvider(createTiandituImagery('cia'));

  // 初始定位到配置的中心点（默认成都）
  viewer.camera.setView({
    destination: Cartesian3.fromDegrees(MAP_CENTER.lon, MAP_CENTER.lat, MAP_CENTER.height),
  });
});

onBeforeUnmount(() => {
  viewer?.destroy();
  viewer = null;
});
</script>

<template>
  <div class="cesium-map">
    <div
      ref="containerRef"
      class="cesium-container"
    ></div>
    <!-- 天地图版权标注（天地图服务条款要求展示，请勿删除） -->
    <div class="map-credit">
      © 国家地理信息公共服务平台 天地图
    </div>
    <MapToolbar />
    <div
      v-if="keyMissing"
      class="key-tip"
    >
      缺少天地图密钥：请在项目根目录创建 <code>.env</code> 文件，
      配置 <code>VITE_TIANDITU_KEY=你的密钥</code>，然后重启开发服务。
      <br />
      申请地址：<a
        href="https://console.tianditu.gov.cn/api/key"
        target="_blank"
        rel="noreferrer"
      >
        console.tianditu.gov.cn/api/key</a>（注册 → 创建应用 → 类型选「浏览器端」）
    </div>
  </div>
</template>

<style scoped>
.cesium-map {
  position: relative;
  width: 100%;
  height: 100%;
}

.cesium-container {
  width: 100%;
  height: 100%;
}

/* 覆盖 Cesium 默认样式，确保容器铺满 */
.cesium-container :deep(.cesium-widget),
.cesium-container :deep(.cesium-widget canvas) {
  width: 100%;
  height: 100%;
}

.key-tip {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  max-width: 420px;
  padding: 16px 20px;
  border: 1px solid #d9a441;
  border-radius: 8px;
  background: #fff8e6;
  color: #5b4a1f;
  font-size: 14px;
  line-height: 1.8;
  text-align: left;
}

.key-tip a {
  color: #1a6faa;
}

/* 自定义版权角标（替代 Cesium 默认 credit 容器） */
.map-credit {
  position: absolute;
  bottom: 4px;
  left: 8px;
  z-index: 1;
  padding: 2px 6px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.35);
  color: rgba(255, 255, 255, 0.85);
  font-size: 12px;
  line-height: 1.5;
  pointer-events: none;
  user-select: none;
}
</style>
