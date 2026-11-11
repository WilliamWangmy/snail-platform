<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';

onLaunch(() => {
  console.log('App Launch');
});
</script>

<style>
/* 全局主题:wot-design-uni 主色对齐品牌色,与 docs/demos 六套 demo 一致。
   颜色统一走 CSS 变量,深色模式由各页根节点 .page--dark 覆盖变量实现。 */
page {
  --wot-color-theme: #1d9e75;
  --bg-page: #f4f5f7;
  --bg-card: #ffffff;
  --text-1: #2c2c2a;
  --text-2: #888780;
  --text-3: #b4b2a9;
  --border: rgba(0, 0, 0, 0.08);
  background-color: #f4f5f7;
  font-size: 14px;
  color: #2c2c2a;
}

.page--dark {
  --bg-page: #121417;
  --bg-card: #1d2023;
  --text-1: #e9e9e7;
  --text-2: #9a9994;
  --text-3: #6f6e69;
  --border: rgba(255, 255, 255, 0.09);
  background-color: #121417;
}
</style>
