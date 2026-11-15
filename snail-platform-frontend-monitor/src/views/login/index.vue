<script setup lang="ts">
import { computed, onMounted, reactive, ref, useTemplateRef } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { fetchCaptcha, type Captcha } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

/** 登录页照 admin/workflow 分离式设计:左品牌区(监控主题指标曲线预览)+ 右表单区;
 *  业务失败文案由 http 拦截器统一弹出(含「监控系统仅限平台管理员使用」),页内不再重复内联报错。 */

const REMEMBER_KEY = 'snail_monitor_remember_username';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const formRef = useTemplateRef<FormInstance>('formRef');
const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
});

/** 记住账号:仅记用户名,不落密码 */
const remember = ref(false);

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

/** 验证码三态:ready/loading/failed,右上角状态灯与图片占位共用 */
const captcha = ref<Captcha | null>(null);
const captchaState = ref<'loading' | 'ready' | 'failed'>('loading');
const statusLabel = computed(() =>
  captchaState.value === 'ready' ? '验证码就绪' : captchaState.value === 'loading' ? '正在获取验证码' : '验证码加载失败',
);

const submitting = ref(false);
const year = new Date().getFullYear();

/** 验证码一次即失效:刷新后清空已输入的图形码 */
async function refreshCaptcha(): Promise<void> {
  captchaState.value = 'loading';
  try {
    captcha.value = await fetchCaptcha();
    form.captchaKey = captcha.value.captchaKey;
    form.captchaCode = '';
    captchaState.value = 'ready';
  } catch {
    captchaState.value = 'failed';
  }
}

function redirectTarget(): string {
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview';
  return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/overview';
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    await auth.signIn({
      username: form.username.trim(),
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode.trim(),
    });
    if (remember.value) {
      try {
        localStorage.setItem(REMEMBER_KEY, form.username.trim());
      } catch {
        /* ignore */
      }
    } else {
      try {
        localStorage.removeItem(REMEMBER_KEY);
      } catch {
        /* ignore */
      }
    }
    // scene=monitor 由 api 层固定携带;非平台租户由后端 400 拒绝并弹专属文案
    await router.replace(redirectTarget());
  } catch {
    // 失败文案已由拦截器弹出;验证码可能已被消费,换新码
    await refreshCaptcha();
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  try {
    const remembered = localStorage.getItem(REMEMBER_KEY);
    if (remembered) {
      form.username = remembered;
      remember.value = true;
    }
  } catch {
    /* ignore */
  }
  void refreshCaptcha();
});
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区(照 admin 结构 + workflow 主题可视化手法) -->
    <aside class="login-brand">
      <div class="brand-row">
        <span class="brand-mark">
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M17.8 6.2A7.2 7.2 0 1 0 18 17.4"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
            />
            <circle
              cx="7.6"
              cy="9.2"
              r="2.1"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <circle
              cx="14.4"
              cy="14.6"
              r="1.6"
              fill="currentColor"
            />
          </svg>
        </span>
        <div>
          <div class="brand-name">
            Snail<span>.</span>Monitor
          </div>
          <div class="brand-sub">
            OBSERVABILITY CONSOLE
          </div>
        </div>
      </div>

      <div class="login-copy">
        <div class="login-kicker">
          <i></i>系统可观测控制台
        </div>
        <h1>让每一次异常<br />都<em>无处遁形</em>。</h1>
        <p>指标、日志、告警与在线诊断统一收口；25 项核心指标实时采样，越阈告警自动通知，问题在告警页与诊断页之间一步闭环。</p>

        <div
          class="login-visual"
          aria-label="指标曲线示意图"
        >
          <div class="login-visual-head">
            <span>LIVE METRICS · system.cpu.usage</span>
            <span class="live-dot"><i></i>SAMPLING</span>
          </div>
          <svg
            class="metric-preview"
            viewBox="0 0 580 132"
            role="img"
            aria-label="CPU 使用率曲线与告警阈值示意"
          >
            <line
              class="mp-grid"
              x1="16"
              y1="34"
              x2="564"
              y2="34"
            />
            <line
              class="mp-grid"
              x1="16"
              y1="62"
              x2="564"
              y2="62"
            />
            <line
              class="mp-grid"
              x1="16"
              y1="90"
              x2="564"
              y2="90"
            />
            <line
              class="mp-axis"
              x1="16"
              y1="112"
              x2="564"
              y2="112"
            />
            <!-- 阈值线 + 告警区 -->
            <line
              class="mp-threshold"
              x1="16"
              y1="46"
              x2="564"
              y2="46"
            />
            <rect
              class="mp-alert"
              x="474"
              y="8"
              width="64"
              height="18"
              rx="9"
            />
            <text
              class="mp-alert-text"
              x="506"
              y="20"
              text-anchor="middle"
            >ALERT</text>
            <text
              class="mp-label"
              x="16"
              y="42"
            >threshold 80%</text>
            <!-- 常规序列(历史段,静态) -->
            <path
              class="mp-line"
              d="M16 96 44 92 72 98 100 88 128 94 156 86 184 92 212 80 240 88 268 78"
            />
            <!-- 实时序列(热段,虚线滚动) + 面积 -->
            <path
              class="mp-area"
              d="M268 78 300 66 332 72 364 58 396 64 428 52 460 58 492 44 524 50 556 38 L556 112 268 112Z"
            />
            <path
              class="mp-line hot"
              d="M268 78 300 66 332 72 364 58 396 64 428 52 460 58 492 44 524 50 556 38"
            />
            <circle
              cx="556"
              cy="38"
              r="4"
              fill="var(--app-primary-tint)"
            />
            <circle
              cx="556"
              cy="38"
              r="9"
              fill="none"
              stroke="rgba(255,255,255,.25)"
              stroke-width="1"
            />
            <text
              class="mp-label"
              x="268"
              y="126"
            >-60min</text>
            <text
              class="mp-label"
              x="556"
              y="126"
              text-anchor="end"
            >now</text>
          </svg>
        </div>
      </div>

      <div class="login-stats">
        <div><b>25 指标</b><span>实时采样落库</span></div>
        <div><b>越阈告警</b><span>连续命中自动通知</span></div>
        <div><b>在线诊断</b><span>体检与线程快照</span></div>
      </div>
    </aside>

    <!-- 右侧表单区(照 admin 表单结构) -->
    <section class="login-panel-wrap">
      <div class="login-panel">
        <div class="login-mobile-brand">
          <span class="brand-mark">
            <svg
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <path
                d="M17.8 6.2A7.2 7.2 0 1 0 18 17.4"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
              />
              <circle
                cx="7.6"
                cy="9.2"
                r="2.1"
                stroke="currentColor"
                stroke-width="1.5"
              />
              <circle
                cx="14.4"
                cy="14.6"
                r="1.6"
                fill="currentColor"
              />
            </svg>
          </span>
          <div>
            <div class="brand-name">
              Snail<span>.</span>Monitor
            </div>
            <div class="brand-sub">
              OBSERVABILITY CONSOLE
            </div>
          </div>
        </div>

        <div class="login-panel-top">
          <span>SECURE ACCESS</span>
          <span
            class="online"
            :class="{ warning: captchaState !== 'ready' }"
          ><i></i>{{ statusLabel }}</span>
        </div>

        <h2>欢迎回来</h2>
        <p class="login-lead">
          登录 <b>Snail Monitor</b>，按你的授权查看系统运行状态。
        </p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submit"
        >
          <el-form-item
            label="账号"
            prop="username"
          >
            <el-input
              v-model="form.username"
              size="large"
              autocomplete="username"
              placeholder="请输入账号"
              @keydown.enter.prevent="submit"
            >
              <template #prefix>
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <circle
                    cx="12"
                    cy="8"
                    r="3.6"
                    stroke="currentColor"
                    stroke-width="1.6"
                  />
                  <path
                    d="M5 20c.8-3.6 3.6-5.4 7-5.4s6.2 1.8 7 5.4"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                  />
                </svg>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item
            label="密码"
            prop="password"
          >
            <el-input
              v-model="form.password"
              type="password"
              show-password
              size="large"
              autocomplete="current-password"
              placeholder="请输入密码"
              @keydown.enter.prevent="submit"
            >
              <template #prefix>
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <rect
                    x="5"
                    y="10.5"
                    width="14"
                    height="9.5"
                    rx="2.2"
                    stroke="currentColor"
                    stroke-width="1.6"
                  />
                  <path
                    d="M8.4 10.5V8a3.6 3.6 0 0 1 7.2 0v2.5"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                  />
                  <circle
                    cx="12"
                    cy="15.2"
                    r="1.4"
                    fill="currentColor"
                  />
                </svg>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item
            label="验证码"
            prop="captchaCode"
          >
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                size="large"
                maxlength="4"
                autocomplete="off"
                placeholder="输入右侧 4 位验证码"
                @keydown.enter.prevent="submit"
              >
                <template #prefix>
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      d="M12 3.6 19 6.3v5.2c0 4.4-2.9 7.5-7 9-4.1-1.5-7-4.6-7-9V6.3L12 3.6Z"
                      stroke="currentColor"
                      stroke-width="1.6"
                      stroke-linejoin="round"
                    />
                    <path
                      d="m9.2 12.2 2 2 3.8-4"
                      stroke="currentColor"
                      stroke-width="1.6"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </template>
              </el-input>
              <button
                type="button"
                class="captcha-box"
                title="点击刷新验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captcha"
                  :src="captcha.image"
                  alt="验证码"
                />
                <span
                  v-else
                  class="captcha-loading"
                >{{ captchaState === 'failed' ? '点击重试' : '加载中' }}</span>
              </button>
            </div>
            <div class="captcha-hint">
              看不清？<button
                type="button"
                @click="refreshCaptcha"
              >
                换一张
              </button>
            </div>
          </el-form-item>

          <div class="login-options">
            <el-checkbox v-model="remember">
              记住账号
            </el-checkbox>
            <span>仅限平台管理员</span>
          </div>

          <el-button
            type="primary"
            size="large"
            native-type="button"
            class="login-submit"
            :loading="submitting"
            :disabled="!captcha"
            @click="submit"
          >
            进入监控控制台
          </el-button>
        </el-form>

        <div class="login-foot">
          © {{ year }} Snail Platform
        </div>
      </div>
    </section>
  </div>
</template>

<style
  src="./login.css"
  scoped
></style>
