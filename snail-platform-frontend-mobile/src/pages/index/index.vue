<template>
  <wd-config-provider :theme="theme.wotTheme">
    <view class="page" :class="{ 'page--dark': theme.isDark }">
      <view class="hero">
        <view class="topbar">
          <view class="brand">
            <view class="logo"><wd-icon name="home" size="15px" color="#ffffff" /></view>
            <text class="brand-name">Snail 蜗牛平台</text>
          </view>
          <view class="top-actions">
            <view class="icon-btn" @click="soon('扫码入口')">
              <wd-icon name="qrcode" size="18px" color="#ffffff" />
            </view>
            <view class="icon-btn" @click="goMessages">
              <wd-icon name="notification" size="18px" color="#ffffff" />
              <view v-if="notify.unreadCount > 0" class="badge">{{ badgeText }}</view>
            </view>
          </view>
        </view>

        <view class="hello-row">
          <view class="avatar"><text>{{ avatarChar }}</text></view>
          <view class="hello">
            <view class="h1">
              <text class="h1-text">{{ greeting }}</text>
              <text v-if="tenantName" class="tag">{{ tenantName }}</text>
            </view>
            <view class="h2">账号 {{ username }}</view>
          </view>
        </view>

        <view class="stats">
          <view class="stat" @click="goMessages">
            <view class="num">{{ notify.unreadCount }}</view>
            <view class="lbl">未读消息</view>
          </view>
          <view class="stat" @click="soon('在线用户')">
            <view class="num">{{ onlineText }}</view>
            <view class="lbl">在线用户</view>
          </view>
          <view class="stat" @click="soon('待我审批')">
            <view class="num">—</view>
            <view class="lbl">待我审批</view>
          </view>
        </view>
      </view>

      <view class="search-float" @click="soon('全局搜索')">
        <wd-icon name="search" size="16px" color="#b4b2a9" />
        <text class="s-txt">搜索功能 / 用户 / 消息</text>
      </view>

      <view class="body">
        <view class="card">
          <view class="grid">
            <view v-for="item in gridItems" :key="item.name" class="g-item" @click="onGrid(item)">
              <view class="g-ico" :class="item.cls">
                <wd-icon :name="item.icon" size="21px" :color="item.color" />
                <view v-if="item.badge && notify.unreadCount > 0" class="g-badge">{{ badgeText }}</view>
              </view>
              <text class="g-name">{{ item.name }}</text>
            </view>
          </view>
        </view>

        <view v-if="announcement" class="card" @click="annShow = true">
          <view class="notice">
            <view class="n-ico"><wd-icon name="notification" size="16px" color="#1D9E75" /></view>
            <view class="n-main">
              <view class="n-t">{{ announcement.title }}</view>
              <view class="n-d">{{ announcementLine }}</view>
            </view>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
        </view>

        <view class="card">
          <view class="card-hd"><text class="hd-t">待我处理</text></view>
          <view class="todo-empty">
            <wd-icon name="check-circle" size="34px" color="#d5d4cc" />
            <text class="todo-hint">审批流对接中，暂无待办</text>
          </view>
        </view>
      </view>

      <wd-popup v-model="annShow" position="bottom" closable custom-class="ann-sheet">
        <view class="ann-body">
          <text class="ann-title">{{ announcement?.title }}</text>
          <text class="ann-meta">{{ announcementLine }}</text>
          <text class="ann-content">{{ announcement?.content }}</text>
        </view>
      </wd-popup>

      <wd-toast />
    </view>
  </wd-config-provider>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useToast } from 'wot-design-uni';
import { useAuthStore } from '@/stores/auth';
import { useNotifyStore } from '@/stores/notify';
import { useThemeStore } from '@/stores/theme';
import { pageAnnouncements, type AnnouncementItem } from '@/api/notify';
import { pageOnline } from '@/api/online';

const auth = useAuthStore();
const notify = useNotifyStore();
const theme = useThemeStore();
const toast = useToast();

const announcement = ref<AnnouncementItem | null>(null);
const annShow = ref(false);
const onlineTotal = ref<number | null>(null);

interface GridItem {
  name: string;
  icon: string;
  cls: string;
  color: string;
  action: 'messages' | 'profile' | 'soon';
  badge?: boolean;
}

const gridItems: GridItem[] = [
  { name: '用户管理', icon: 'user', cls: 'c1', color: '#1D9E75', action: 'soon' },
  { name: '角色权限', icon: 'secured', cls: 'c2', color: '#378ADD', action: 'soon' },
  { name: '组织架构', icon: 'usergroup', cls: 'c4', color: '#7a6ff0', action: 'soon' },
  { name: '消息通知', icon: 'notification', cls: 'c3', color: '#ef9f27', action: 'messages', badge: true },
  { name: '文件中心', icon: 'file', cls: 'c6', color: '#2b9db4', action: 'soon' },
  { name: '数据字典', icon: 'books', cls: 'c5', color: '#e24b4a', action: 'soon' },
  { name: '操作日志', icon: 'history', cls: 'c7', color: '#5a6b80', action: 'soon' },
  { name: '个人中心', icon: 'user-avatar', cls: 'c8', color: '#3fae55', action: 'profile' },
];

const greeting = computed(() => {
  const h = new Date().getHours();
  const word = h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好';
  return auth.user ? `${word}，${auth.user.nickname}` : 'Snail 工作台';
});

const tenantName = computed(() => auth.user?.tenantName ?? '');
const username = computed(() => auth.user?.username ?? '');
const avatarChar = computed(() => (auth.user?.nickname ?? '蜗').slice(0, 1));
const onlineText = computed(() => (onlineTotal.value === null ? '—' : String(onlineTotal.value)));
const badgeText = computed(() => (notify.unreadCount > 99 ? '99+' : String(notify.unreadCount)));
const announcementLine = computed(() => {
  if (!announcement.value) return '';
  const time = announcement.value.sendTime ?? '';
  const sender = announcement.value.senderName ?? '';
  return [time, sender].filter(Boolean).join(' · ');
});

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  auth.loadSession();
  notify.refresh();
  loadOnline();
  loadAnnouncement();
});

/** 在线数拉取失败(权限/网络)时降级为占位符,不阻塞页面。 */
async function loadOnline(): Promise<void> {
  try {
    onlineTotal.value = (await pageOnline({ page: 1, pageSize: 1 })).total;
  } catch {
    onlineTotal.value = null;
  }
}

async function loadAnnouncement(): Promise<void> {
  try {
    announcement.value = (await pageAnnouncements({ page: 1, pageSize: 1 })).list[0] ?? null;
  } catch {
    announcement.value = null;
  }
}

function onGrid(item: GridItem): void {
  if (item.action === 'messages') {
    uni.switchTab({ url: '/pages/messages/messages' });
    return;
  }
  if (item.action === 'profile') {
    uni.switchTab({ url: '/pages/profile/profile' });
    return;
  }
  soon(item.name);
}

function goMessages(): void {
  uni.switchTab({ url: '/pages/messages/messages' });
}

function soon(name: string): void {
  toast.show({ msg: `${name}功能建设中`, iconName: 'warning' });
}
</script>

<style lang="scss" src="./index.scss" scoped></style>
