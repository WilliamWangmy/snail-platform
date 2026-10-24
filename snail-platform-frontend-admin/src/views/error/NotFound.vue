<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <ElResult
    icon="warning"
    title="404"
    sub-title="菜单未配置对应页面，或该路径不在当前账号的授权范围内"
  >
    <template #extra>
      <ElButton
        type="primary"
        @click="router.push('/home')"
      >
        返回工作台
      </ElButton>
    </template>
  </ElResult>
</template>
