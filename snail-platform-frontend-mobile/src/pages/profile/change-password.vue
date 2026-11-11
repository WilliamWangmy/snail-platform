<template>
  <wd-config-provider :theme="theme.wotTheme">
    <view class="page" :class="{ 'page--dark': theme.isDark }">
      <view v-if="force" class="force-tip">
        <wd-icon name="warning" size="16px" color="#ef9f27" />
        <text class="force-t">首次登录须设置新密码，完成后请重新登录</text>
      </view>

      <view class="form-card">
        <wd-input
          v-if="!force"
          v-model="form.oldPassword"
          label="原密码"
          show-password
          clearable
          placeholder="请输入原密码"
        />
        <wd-input
          v-model="form.newPassword"
          label="新密码"
          show-password
          clearable
          placeholder="至少 6 位，建议字母 + 数字组合"
        />
        <wd-input
          v-model="form.confirm"
          label="确认新密码"
          show-password
          clearable
          placeholder="请再次输入新密码"
        />
      </view>

      <view class="tips">
        <text class="tip">· 修改成功后当前会话立即失效，需重新登录</text>
        <text class="tip">· 原密码连续错误 5 次将锁定 10 分钟</text>
      </view>

      <view class="submit-wrap">
        <wd-button block custom-class="submit-btn" :loading="submitting" @click="onSubmit">
          {{ force ? '设置新密码' : '确认修改' }}
        </wd-button>
      </view>

      <wd-toast />
    </view>
  </wd-config-provider>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { useToast } from 'wot-design-uni';
import { useThemeStore } from '@/stores/theme';
import { useAuthStore } from '@/stores/auth';
import { changePassword, forceChangePassword } from '@/api/profile';

const theme = useThemeStore();
const auth = useAuthStore();
const toast = useToast();

/** force 模式:首次登录强制改密,只录新密码(对齐 admin ForceChangePasswordDialog 语义)。 */
const force = ref(false);
const submitting = ref(false);
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' });

onLoad((options) => {
  force.value = options?.mode === 'force';
});

function validate(): string | null {
  if (!force.value && !form.oldPassword) return '请输入原密码';
  if (form.newPassword.length < 6) return '新密码至少 6 位';
  if (form.newPassword !== form.confirm) return '两次输入的新密码不一致';
  if (form.newPassword === form.oldPassword && !force.value) return '新密码不能与原密码相同';
  return null;
}

async function onSubmit(): Promise<void> {
  const problem = validate();
  if (problem) {
    toast.show({ msg: problem, iconName: 'warning' });
    return;
  }
  submitting.value = true;
  try {
    if (force.value) {
      await forceChangePassword({ newPassword: form.newPassword });
    } else {
      await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword });
    }
    // 改密成功后令牌版本自增,旧会话已失效:清本地并引导重登
    auth.clearLocal();
    toast.show({ msg: '密码已修改，请重新登录' });
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/login/login' });
    }, 800);
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '修改失败', iconName: 'warning' });
  } finally {
    submitting.value = false;
  }
}
</script>

<style lang="scss" src="./change-password.scss" scoped></style>
