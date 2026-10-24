<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <ElResult
    icon="error"
    title="403"
    sub-title="当前账号没有访问该功能的权限码"
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
