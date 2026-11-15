<template>
  <div class="error-page">
    <p class="code">404</p>
    <p class="msg">页面不存在或已被移除</p>
    <el-button type="primary" @click="router.replace('/overview')">返回总览</el-button>
    <el-button @click="router.back()">返回上一页</el-button>
  </div>
</template>

<script lang="ts" setup>
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<style scoped>
.error-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: var(--app-bg);
}

.code {
  font-size: 64px;
  font-weight: 700;
  margin: 0;
  color: var(--primary-color);
}

.msg {
  color: var(--text-secondary);
  margin: 0 0 16px;
}
</style>
