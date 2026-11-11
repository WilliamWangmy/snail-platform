<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';

onLaunch(() => {
  console.log('App Launch');
});
</script>

<style>
/* 全局主题:wot-design-uni 主色对齐品牌色,与 docs/demos 三套 demo 一致 */
page {
  --wot-color-theme: #1d9e75;
  background-color: #f4f5f7;
  font-size: 14px;
  color: #2c2c2a;
}
</style>
