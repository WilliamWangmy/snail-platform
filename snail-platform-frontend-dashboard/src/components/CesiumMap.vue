<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  CallbackProperty,
  Cartesian3,
  Cartographic,
  Color,
  ColorMaterialProperty,
  CustomDataSource,
  GeoJsonDataSource,
  ImageryLayer,
  JulianDate,
  Math as CesiumMath,
  PolygonHierarchy,
  Rectangle,
  SingleTileImageryProvider,
  Viewer,
} from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { MAP_CENTER, TIANDITU_KEY, createTiandituImagery } from '@/config/tianditu';
import MapToolbar from '@/components/MapToolbar.vue';

const containerRef = ref<HTMLDivElement>();
const keyMissing = ref(false);

let viewer: Viewer | null = null;
/** 行政区边界数据源（默认隐藏，由工具栏「行政区」开关控制） */
let adminDataSource: GeoJsonDataSource | null = null;
/** 非行政区压暗遮罩影像层（默认隐藏） */
let maskImageryLayer: ImageryLayer | null = null;
/** 科技感动效数据源（扩散波纹 + 区县光柱，随行政区开关显隐） */
let techDataSource: CustomDataSource | null = null;
/** 动效渲染循环句柄 */
let rafId = 0;

onMounted(async () => {
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

  // dev 模式暴露 viewer 便于调试（生产构建不注入）
  if (import.meta.env.DEV) {
    (window as unknown as Record<string, unknown>).__cesiumViewer = viewer;
  }

  // 加载成都行政区 GeoJSON（public/510100-area.geojson），默认隐藏。
  // 不贴地（clampToGround: false），改为立体挤出多面体，呈现 3D 楼块效果
  try {
    const dataSource = await GeoJsonDataSource.load('/510100-area.geojson', {
      fill: Color.fromCssColorString('rgba(34, 211, 238, 0.30)'),
      stroke: Color.fromCssColorString('#22d3ee'),
      strokeWidth: 1,
      clampToGround: false,
    });
    if (!viewer) {
      dataSource.entities.removeAll();
      return;
    }
    dataSource.show = false;
    dataSource.name = '成都行政区';
    applyExtrusion(dataSource);
    adminDataSource = dataSource;
    viewer.dataSources.add(dataSource);

    // 非行政区压暗遮罩（canvas 蒙版影像层），随行政区开关一起显隐
    maskImageryLayer = await buildAdminMaskLayer(viewer, dataSource);

    // 数字孪生科技感动效（波纹 + 光柱），随行政区开关一起显隐
    techDataSource = buildTechEffects(dataSource);
    viewer.dataSources.add(techDataSource);

    viewer.scene.requestRender();
  } catch (err) {
    console.error('行政区边界加载失败:', err);
  }
});

/** 立体挤出高度范围（米）：按区县名称哈希取值，形成高低起伏的 3D 天际线 */
const ADMIN_EXTRUDE_MIN = 6000;
const ADMIN_EXTRUDE_MAX = 12000;
/**
 * 打开行政区图层时的斜视相机。
 * 实测几何：取景框中心 ≈ 相机位置向北偏移 height/tan(|pitch|)，
 * 220km 高、-60° 俯仰、相机 29.54N 时成都全域完整入画且居中。
 */
const ADMIN_VIEW = { lon: 104.0, lat: 29.54, height: 220000, pitchDeg: -60 };

/** 由区县名称生成确定性伪高度（后续可替换为真实业务数据，如无人机数量） */
function extrudeHeightFor(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  const t = (hash % 1000) / 1000;
  return ADMIN_EXTRUDE_MIN + t * (ADMIN_EXTRUDE_MAX - ADMIN_EXTRUDE_MIN);
}

/**
 * 把行政区平面多边形改造成立体挤出多面体：
 * - 每个区县设置 extrudedHeight（从地面立起来的“楼块”）
 * - 顶面边缘加亮色描边（polyline），凸显体块轮廓
 */
function applyExtrusion(ds: GeoJsonDataSource) {
  const now = JulianDate.now();
  // 先快照再改（entities.values 是活数组，遍历时新增 polyline 会干扰迭代）
  const polygonEntities = [...ds.entities.values].filter((e) => e.polygon);
  for (const entity of polygonEntities) {
    const polygon = entity.polygon;
    if (!polygon?.hierarchy) continue;

    const name = String(
      entity.name ?? entity.properties?.name?.getValue(now) ?? entity.id,
    );
    const height = extrudeHeightFor(name);

    polygon.height = 0 as never;
    polygon.extrudedHeight = height as never;
    // 关闭多边形自带描边（顶边高光由 addTopEdges 的 polyline 负责）
    polygon.outline = false as never;
    polygon.material = new ColorMaterialProperty(
      Color.fromCssColorString('rgba(34, 211, 238, 0.16)'),
    );

    addTopEdges(ds, polygon.hierarchy.getValue(now), height);
  }
}

/** 在挤出体顶面边缘加亮色描边，增强体块感 */
function addTopEdges(
  ds: GeoJsonDataSource,
  hierarchy: PolygonHierarchy,
  height: number,
) {
  const rings = [hierarchy.positions, ...hierarchy.holes.map((h) => h.positions)];
  for (const ring of rings) {
    if (ring.length < 2) continue;
    const positions = ring.map((p) => {
      const c = Cartographic.fromCartesian(p);
      return Cartesian3.fromRadians(c.longitude, c.latitude, height);
    });
    ds.entities.add({
      polyline: {
        positions,
        width: 2,
        material: new ColorMaterialProperty(
          Color.fromCssColorString('rgba(125, 211, 252, 0.7)'),
        ),
      },
    });
  }
}

/** ===== 数字孪生科技感动效 ===== */

/** 扩散波纹中心（成都市中心）与节奏 */
const RIPPLE_CENTER = { lon: 104.066, lat: 30.66 };
const RIPPLE_MAX_RADIUS = 55000; // 米
const RIPPLE_PERIOD = 3600; // 单圈毫秒数
const RIPPLE_COUNT = 3;
/** 区县光柱 */
const PILLAR_HEIGHT = 18000;

/**
 * 科技感动效：中心扩散波纹（雷达扫描式涟漪）+ 区县中心光柱。
 * 波纹每帧状态统一在渲染循环里算好（radius/alpha 缓存），
 * CallbackProperty 只读缓存 —— 避免 major/minor 两次取时出现不一致。
 */
const rippleState = Array.from({ length: RIPPLE_COUNT }, () => ({
  radius: 800,
  alpha: 0,
}));

function updateRippleState() {
  const now = performance.now();
  for (let i = 0; i < RIPPLE_COUNT; i++) {
    const t = ((now + (i * RIPPLE_PERIOD) / RIPPLE_COUNT) % RIPPLE_PERIOD) / RIPPLE_PERIOD;
    const state = rippleState[i];
    if (!state) continue;
    state.radius = 800 + t * RIPPLE_MAX_RADIUS;
    state.alpha = 0.45 * (1 - t);
  }
}

function buildTechEffects(adminDs: GeoJsonDataSource): CustomDataSource {
  const ds = new CustomDataSource('admin-tech');
  ds.show = false;

  // 1. 中心扩散波纹（3 圈相位错开）
  const rippleCenter = Cartesian3.fromDegrees(RIPPLE_CENTER.lon, RIPPLE_CENTER.lat);
  for (let i = 0; i < RIPPLE_COUNT; i++) {
    const phase = i;
    ds.entities.add({
      position: rippleCenter,
      ellipse: {
        semiMajorAxis: new CallbackProperty(() => rippleState[phase]!.radius, false),
        semiMinorAxis: new CallbackProperty(() => rippleState[phase]!.radius, false),
        material: new ColorMaterialProperty(
          new CallbackProperty(
            () => Color.fromCssColorString('#22d3ee').withAlpha(rippleState[phase]!.alpha),
            false,
          ),
        ),
        height: 60 as never,
        outline: false as never,
      },
    });
  }

  // 2. 区县中心光柱（取 GeoJSON 的 center 属性，半透明光束 + Bloom 发光）
  const now = JulianDate.now();
  for (const entity of adminDs.entities.values) {
    const center = entity.properties?.center?.getValue(now) as number[] | undefined;
    if (!Array.isArray(center) || center.length < 2) continue;
    const [lon = 0, lat = 0] = center;
    ds.entities.add({
      position: Cartesian3.fromDegrees(lon, lat, PILLAR_HEIGHT / 2),
      cylinder: {
        length: PILLAR_HEIGHT,
        topRadius: 180,
        bottomRadius: 900,
        material: new ColorMaterialProperty(
          Color.fromCssColorString('rgba(125, 211, 252, 0.30)'),
        ),
      },
    });
  }

  return ds;
}

/** 动效渲染循环：仅在行政区图层开启时驱动逐帧渲染 */
function startEffectLoop() {
  stopEffectLoop();
  const tick = () => {
    updateRippleState();
    viewer?.scene.requestRender();
    rafId = requestAnimationFrame(tick);
  };
  rafId = requestAnimationFrame(tick);
}

function stopEffectLoop() {
  if (rafId) cancelAnimationFrame(rafId);
  rafId = 0;
}

/** 遮罩覆盖范围（经纬度），边缘尽量落在偏远区域 */
const MASK_RECT = { west: 60, south: 0, east: 150, north: 60 };
/** 蒙版 canvas 宽度（像素），决定抠图边缘精度 */
const MASK_CANVAS_WIDTH = 4096;

/**
 * 构建非行政区压暗遮罩：canvas 上铺半透明黑色，
 * 用 destination-out 把成都各区县轮廓“抠掉”（保持原始亮度），
 * 再整体叠一层行政区淡青色高光，作为单幅影像贴回球面。
 * 相比“带洞多边形图元”，影像层无批处理冲突，性能也更稳。
 */
async function buildAdminMaskLayer(
  mapViewer: Viewer,
  adminDs: GeoJsonDataSource,
): Promise<ImageryLayer | null> {
  const { west, south, east, north } = MASK_RECT;
  const width = MASK_CANVAS_WIDTH;
  const height = Math.round((width * (north - south)) / (east - west));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  const toPixel = (lonDeg: number, latDeg: number): [number, number] => [
    ((lonDeg - west) / (east - west)) * width,
    ((north - latDeg) / (north - south)) * height,
  ];

  const carto = new Cartographic();
  const drawHierarchy = (hierarchy: {
    positions: import('cesium').Cartesian3[];
    holes: { positions: import('cesium').Cartesian3[] }[];
  }) => {
    const drawRing = (positions: import('cesium').Cartesian3[]) => {
      positions.forEach((p, i) => {
        const c = Cartographic.fromCartesian(p, undefined, carto);
        const [x, y] = toPixel(
          (c.longitude * 180) / Math.PI,
          (c.latitude * 180) / Math.PI,
        );
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.closePath();
    };
    ctx.beginPath();
    drawRing(hierarchy.positions);
    hierarchy.holes.forEach((hole) => drawRing(hole.positions));
    ctx.fill('evenodd');
  };

  // 1. 铺半透明黑底
  ctx.fillStyle = 'rgba(0, 0, 0, 0.5)';
  ctx.fillRect(0, 0, width, height);

  // 2. 抠掉行政区（外环挖洞，洞中洞再填回）
  ctx.globalCompositeOperation = 'destination-out';
  for (const entity of adminDs.entities.values) {
    const polygon = entity.polygon;
    if (!polygon?.hierarchy) continue;
    const hierarchy = polygon.hierarchy.getValue(JulianDate.now());
    if (hierarchy?.positions?.length) drawHierarchy(hierarchy);
  }

  // 3. 行政区内部叠淡青色高光（调亮）
  ctx.globalCompositeOperation = 'source-over';
  ctx.fillStyle = 'rgba(125, 211, 252, 0.14)';
  for (const entity of adminDs.entities.values) {
    const polygon = entity.polygon;
    if (!polygon?.hierarchy) continue;
    const hierarchy = polygon.hierarchy.getValue(JulianDate.now());
    if (hierarchy?.positions?.length) drawHierarchy(hierarchy);
  }

  const provider = await SingleTileImageryProvider.fromUrl(
    canvas.toDataURL('image/png'),
    { rectangle: Rectangle.fromDegrees(west, south, east, north) },
  );
  const layer = mapViewer.imageryLayers.addImageryProvider(provider);
  layer.show = false;
  return layer;
}

/** 工具栏图层开关回调：目前接入行政区，其余图层待接入 */
function onLayerToggle(key: string, value: boolean) {
  if (key === 'admin') {
    if (adminDataSource) adminDataSource.show = value;
    if (maskImageryLayer) maskImageryLayer.show = value;
    if (techDataSource) techDataSource.show = value;

    // Bloom 泛光：让楼块顶边/光柱/波纹发亮，营造数字孪生氛围
    if (viewer) {
      const bloom = viewer.scene.postProcessStages.bloom;
      bloom.enabled = value;
      if (value) {
        bloom.uniforms.glowOnly = false;
        bloom.uniforms.contrast = 110;
        bloom.uniforms.brightness = -0.25;
        bloom.uniforms.delta = 1.2;
        bloom.uniforms.sigma = 2.5;
      }
    }

    // 波纹动画需要逐帧渲染
    if (value) startEffectLoop();
    else stopEffectLoop();

    if (value && viewer) {
      // 开启图层时飞到斜视角度，呈现 3D 透视效果
      viewer.camera.flyTo({
        destination: Cartesian3.fromDegrees(
          ADMIN_VIEW.lon,
          ADMIN_VIEW.lat,
          ADMIN_VIEW.height,
        ),
        orientation: {
          heading: 0,
          pitch: CesiumMath.toRadians(ADMIN_VIEW.pitchDeg),
          roll: 0,
        },
        duration: 1.5,
      });
    } else {
      viewer?.scene.requestRender();
    }
  }
}

onBeforeUnmount(() => {
  stopEffectLoop();
  viewer?.destroy();
  viewer = null;
  adminDataSource = null;
  maskImageryLayer = null;
  techDataSource = null;
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
    <MapToolbar @layer-toggle="onLayerToggle" />
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
