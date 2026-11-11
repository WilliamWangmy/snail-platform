<template>
  <wd-config-provider :theme="theme.wotTheme">
    <view class="page" :class="{ 'page--dark': theme.isDark }">
      <view class="hero">
        <view class="hero-top">
          <text class="ttl">我的</text>
          <view class="setting" @click="soon('设置中心')">
            <wd-icon name="setting" size="18px" color="#ffffff" />
          </view>
        </view>

        <view class="me-row">
          <view class="avatar"><text>{{ avatarChar }}</text></view>
          <view class="me-info">
            <view class="nm">
              <text class="nm-text">{{ displayName }}</text>
              <text v-if="tenantName" class="tag">{{ tenantName }}</text>
            </view>
            <view class="ds">{{ contactLine }}</view>
          </view>
          <button class="edit" @click="openEdit">编辑资料</button>
        </view>
      </view>

      <view class="id-card">
        <view class="id-line">
          <text class="k">登录账号</text>
          <text class="v">{{ profile?.username ?? '—' }}</text>
        </view>
        <view class="id-line">
          <text class="k">所属组织</text>
          <text class="v">{{ profile?.orgName ?? '—' }}</text>
        </view>
        <view class="id-line">
          <text class="k">邮箱</text>
          <text class="v">{{ profile?.email ?? '未绑定' }}</text>
        </view>
        <view class="id-line">
          <text class="k">手机号</text>
          <text class="v">{{ profile?.phone ?? '未绑定' }}</text>
        </view>
      </view>

      <view class="body">
        <view class="stats">
          <view class="stat" @click="goMessages">
            <view class="num">{{ notify.unreadCount }}</view>
            <view class="lbl">未读消息</view>
          </view>
          <view class="stat" @click="soon('我的文件')">
            <view class="num">—</view>
            <view class="lbl">我的文件</view>
          </view>
          <view class="stat" @click="soon('账号与安全')">
            <view class="num">{{ joinDate }}</view>
            <view class="lbl">加入平台</view>
          </view>
        </view>

        <view class="card">
          <view class="cell" @click="openEdit">
            <view class="ico i1"><wd-icon name="user" size="17px" color="#1D9E75" /></view>
            <view class="m"><text class="a">个人资料</text><text class="b">昵称 / 邮箱 / 手机号</text></view>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
          <view class="cell" @click="goChangePassword">
            <view class="ico i2"><wd-icon name="lock-on" size="17px" color="#378ADD" /></view>
            <view class="m"><text class="a">修改密码</text><text class="b">修改后原密码与全部会话失效</text></view>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
          <view class="cell" @click="soon('第三方账号绑定')">
            <view class="ico i3"><wd-icon name="link" size="17px" color="#ef9f27" /></view>
            <view class="m"><text class="a">第三方账号</text><text class="b">钉钉 / 飞书 / 企业微信绑定</text></view>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
          <view class="cell" @click="goMessages">
            <view class="ico i4"><wd-icon name="notification" size="17px" color="#7a6ff0" /></view>
            <view class="m"><text class="a">消息通知</text><text class="b">前往消息中心查看</text></view>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
        </view>

        <view class="card">
          <view class="cell">
            <view class="ico i2"><wd-icon name="view" size="17px" color="#378ADD" /></view>
            <view class="m"><text class="a">深色模式</text><text class="b">跟随偏好本地记忆</text></view>
            <view class="switch" :class="{ on: theme.isDark }" @click="theme.toggle()" />
          </view>
          <view class="cell" @click="soon('多语言')">
            <view class="ico i6"><wd-icon name="translate-bold" size="17px" color="#2b9db4" /></view>
            <view class="m"><text class="a">语言</text><text class="b">界面显示语言</text></view>
            <text class="val">简体中文</text>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
          <view class="cell" @click="showAbout = true">
            <view class="ico i1"><wd-icon name="info-circle" size="17px" color="#1D9E75" /></view>
            <view class="m"><text class="a">关于</text><text class="b">版本 / 开源协议</text></view>
            <text class="val">v2.3.0</text>
            <wd-icon name="arrow-right" size="15px" color="#b4b2a9" />
          </view>
        </view>

        <button class="logout" @click="onLogout">
          <wd-icon name="poweroff" size="17px" color="#e24b4a" />
          <text>退出登录</text>
        </button>
        <view class="ver"><text>Snail Platform Mobile · v2.3.0</text></view>
      </view>

      <wd-popup v-model="editShow" position="bottom" closable custom-class="edit-sheet">
        <view class="e-body">
          <text class="e-title">编辑资料</text>
          <wd-input v-model="editForm.nickname" label="昵称" placeholder="请输入昵称" clearable />
          <wd-input v-model="editForm.email" label="邮箱" placeholder="请输入邮箱" clearable />
          <wd-input v-model="editForm.phone" label="手机号" placeholder="请输入手机号" clearable />
          <view class="e-ops">
            <wd-button plain custom-class="e-btn" @click="editShow = false">取消</wd-button>
            <wd-button custom-class="e-btn" @click="saveProfile">保存</wd-button>
          </view>
        </view>
      </wd-popup>

      <wd-popup v-model="showAbout" position="bottom" closable custom-class="edit-sheet">
        <view class="e-body">
          <text class="e-title">关于</text>
          <text class="about-line">Snail Platform Mobile v2.3.0</text>
          <text class="about-line">基于 uni-app + wot-design-uni 构建</text>
          <text class="about-line">支持 H5 / 微信小程序 / 支付宝小程序 / App</text>
        </view>
      </wd-popup>

      <wd-toast />
      <wd-message-box />
    </view>
  </wd-config-provider>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useMessage, useToast } from 'wot-design-uni';
import { useAuthStore } from '@/stores/auth';
import { useNotifyStore } from '@/stores/notify';
import { useThemeStore } from '@/stores/theme';
import { fetchProfile, updateProfile, type Profile } from '@/api/profile';

const auth = useAuthStore();
const notify = useNotifyStore();
const theme = useThemeStore();
const toast = useToast();
const message = useMessage();

const profile = ref<Profile | null>(null);
const editShow = ref(false);
const showAbout = ref(false);
const editForm = reactive({ nickname: '', email: '', phone: '' });

const displayName = computed(() => profile.value?.nickname ?? auth.user?.nickname ?? '未登录');
const tenantName = computed(() => profile.value?.tenantName ?? auth.user?.tenantName ?? '');
const avatarChar = computed(() => displayName.value.slice(0, 1));
const contactLine = computed(() => {
  const email = profile.value?.email;
  const phone = profile.value?.phone;
  if (email && phone) return `${email} · ${phone}`;
  return email ?? phone ?? '账号 ' + (profile.value?.username ?? auth.user?.username ?? '');
});
const joinDate = computed(() => (profile.value?.createTime ?? '').slice(0, 10).replaceAll('-', '/') || '—');

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  notify.refresh();
  loadProfile();
});

async function loadProfile(): Promise<void> {
  try {
    profile.value = await fetchProfile();
  } catch {
    /* 拉取失败时用登录快照兜底展示 */
  }
}

function openEdit(): void {
  editForm.nickname = profile.value?.nickname ?? '';
  editForm.email = profile.value?.email ?? '';
  editForm.phone = profile.value?.phone ?? '';
  editShow.value = true;
}

async function saveProfile(): Promise<void> {
  if (!editForm.nickname.trim()) {
    toast.show({ msg: '昵称不能为空', iconName: 'warning' });
    return;
  }
  try {
    await updateProfile({
      nickname: editForm.nickname.trim(),
      email: editForm.email.trim() || null,
      phone: editForm.phone.trim() || null,
    });
    editShow.value = false;
    toast.show({ msg: '资料已更新' });
    await loadProfile();
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '保存失败', iconName: 'warning' });
  }
}

function goChangePassword(): void {
  uni.navigateTo({ url: '/pages/profile/change-password' });
}

function goMessages(): void {
  uni.switchTab({ url: '/pages/messages/messages' });
}

function soon(name: string): void {
  toast.show({ msg: `${name}功能建设中`, iconName: 'warning' });
}

async function onLogout(): Promise<void> {
  try {
    await message.confirm({
      title: '确认退出登录？',
      msg: '退出后本设备会话将被吊销，再次使用需要重新登录。',
    });
  } catch {
    return;
  }
  await auth.logout();
  uni.reLaunch({ url: '/pages/login/login' });
}
</script>

<style lang="scss" src="./profile.scss" scoped></style>
