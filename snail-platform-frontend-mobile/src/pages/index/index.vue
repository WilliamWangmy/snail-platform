<template>
  <view class="page">
    <view class="hero">
      <text class="hello">{{ greeting }}</text>
      <text class="tenant">{{ tenantLine }}</text>
    </view>

    <wd-cell-group border>
      <wd-cell title="用户管理" value="敬请期待" is-link @click="soon('用户管理')" />
      <wd-cell title="消息通知" value="敬请期待" is-link @click="soon('消息通知')" />
      <wd-cell title="组织与角色" value="敬请期待" is-link @click="soon('组织与角色')" />
      <wd-cell title="关于" :value="apiBase || '未配置 API 地址'" />
    </wd-cell-group>

    <wd-button block plain custom-class="logout-btn" @click="onLogout">退出登录</wd-button>

    <wd-toast />
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useToast } from 'wot-design-uni';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const toast = useToast();

const apiBase: string = import.meta.env.VITE_API_BASE_URL ?? '';

const greeting = computed(() =>
  auth.user ? `你好，${auth.user.nickname}` : 'Snail 工作台',
);

const tenantLine = computed(() =>
  auth.user ? `租户：${auth.user.tenantName}` : '未登录',
);

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  auth.loadSession();
});

function soon(name: string): void {
  toast.show({ msg: `${name}页面建设中`, iconName: 'warning' });
}

async function onLogout(): Promise<void> {
  await auth.logout();
  uni.reLaunch({ url: '/pages/login/login' });
}
</script>


<style lang="scss" src="./index.scss" scoped></style>
