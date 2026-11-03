<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';

import { getCaptcha } from '@/api/auth';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

const formRef = ref<FormInstance>();
const submitting = ref(false);
const captchaLoading = ref(false);

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
});

const captchaImage = ref('');
const year = new Date().getFullYear();

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

async function refreshCaptcha(): Promise<void> {
  captchaLoading.value = true;
  try {
    const captcha = await getCaptcha();
    captchaImage.value = captcha.image;
    form.captchaKey = captcha.captchaKey;
    form.captchaCode = '';
  } finally {
    captchaLoading.value = false;
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    await auth.login({ ...form });
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    await router.push(redirect);
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  void refreshCaptcha();
});
</script>

<template>
  <main class="login-page">
    <section
      class="login-brand"
      aria-label="产品介绍"
    >
      <div class="brand">
        <span class="brand-mark">
          <svg
            width="23"
            height="23"
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
        <span class="brand-copy">
          <span class="brand-name">Snail<span>.</span>Flow</span>
          <span class="brand-sub">FLOW ORCHESTRATION</span>
        </span>
      </div>

      <div class="login-copy">
        <div class="login-kicker">PROCESS CONTROL ROOM / 01</div>
        <h1>让每一次流转<br>都<em>有迹可循</em>。</h1>
        <p>从模型设计、版本发布到实例执行与任务办理，流程资产在同一张图上被看见、被校验、被追溯。</p>

        <div
          class="login-visual"
          aria-label="流程节点示意图"
        >
          <div class="login-visual-head">
            <span>LIVE PROCESS MAP · 采购审批</span>
            <span class="live-dot">RUNNING</span>
          </div>
          <svg
            class="flow-preview"
            viewBox="0 0 580 132"
            role="img"
            aria-label="开始、部门审批、金额判断、财务复核、归档的流程示意"
          >
            <defs>
              <marker
                id="lpArrow"
                viewBox="0 0 8 8"
                refX="7"
                refY="4"
                markerWidth="6"
                markerHeight="6"
                orient="auto"
              >
                <path
                  d="M0 0 8 4 0 8Z"
                  fill="#5b7c7e"
                />
              </marker>
            </defs>
            <path
              class="lp-line hot"
              d="M54 66 H92"
            />
            <path
              class="lp-line"
              d="M202 66 H252"
              marker-end="url(#lpArrow)"
            />
            <path
              class="lp-line"
              d="M316 66 H362"
              marker-end="url(#lpArrow)"
            />
            <path
              class="lp-line"
              d="M472 66 H518"
              marker-end="url(#lpArrow)"
            />
            <circle
              cx="38"
              cy="66"
              r="13"
              fill="#0c222c"
              stroke="#1ec9a5"
              stroke-width="1.6"
            />
            <circle
              cx="38"
              cy="66"
              r="4"
              fill="#1ec9a5"
            />
            <rect
              x="92"
              y="43"
              width="110"
              height="46"
              rx="8"
              fill="#0c222c"
              stroke="#607f80"
              stroke-width="1.2"
            />
            <text
              x="147"
              y="63"
              text-anchor="middle"
              fill="#d8e8e5"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >部门审批</text>
            <text
              x="147"
              y="77"
              text-anchor="middle"
              fill="#718d8a"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >zhang.li</text>
            <path
              d="M284 42 316 66 284 90 252 66Z"
              fill="rgba(232,173,85,.08)"
              stroke="#e8ad55"
              stroke-width="1.3"
            />
            <text
              x="284"
              y="70"
              text-anchor="middle"
              fill="#e8c891"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >金额</text>
            <text
              x="284"
              y="104"
              text-anchor="middle"
              fill="#8ea6a3"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >amount &gt; 5000</text>
            <rect
              x="362"
              y="43"
              width="110"
              height="46"
              rx="8"
              fill="rgba(30,201,165,.07)"
              stroke="#1ec9a5"
              stroke-width="1.4"
            />
            <text
              x="417"
              y="63"
              text-anchor="middle"
              fill="#eafffb"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >财务复核</text>
            <text
              x="417"
              y="77"
              text-anchor="middle"
              fill="#78bdb1"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >wang.min</text>
            <circle
              cx="532"
              cy="66"
              r="13"
              fill="#0c222c"
              stroke="#607f80"
              stroke-width="1.5"
            />
            <circle
              cx="532"
              cy="66"
              r="5"
              fill="none"
              stroke="#607f80"
              stroke-width="1.4"
            />
            <text
              x="147"
              y="112"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >STEP 01</text>
            <text
              x="284"
              y="118"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >GATEWAY</text>
            <text
              x="417"
              y="112"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >STEP 03</text>
          </svg>
        </div>
      </div>

      <div
        class="login-stats"
        aria-label="平台能力"
      >
        <div class="login-stat"><b>01</b><span>模型设计</span></div>
        <div class="login-stat"><b>02</b><span>版本发布</span></div>
        <div class="login-stat"><b>03</b><span>实例执行</span></div>
        <div class="login-stat"><b>04</b><span>任务办理</span></div>
      </div>
    </section>

    <section class="login-panel-wrap">
      <div class="login-panel">
        <div class="login-panel-top">
          <span>SECURE ACCESS</span>
          <span class="login-status">系统运行正常</span>
        </div>

        <h2>欢迎回来</h2>
        <p class="login-lead">登录 Snail Flow，继续处理你的流程资产与待办任务。</p>

        <el-form
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="handleSubmit"
        >
          <el-form-item
            prop="username"
            class="login-field"
          >
            <template #label>
              <span class="field-label">账号</span>
            </template>
            <el-input
              v-model="form.username"
              placeholder="请输入账号"
              autocomplete="username"
            >
              <template #prefix><AppIcon name="user" /></template>
            </el-input>
          </el-form-item>

          <el-form-item
            prop="password"
            class="login-field"
          >
            <template #label>
              <span class="field-label">密码</span>
            </template>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            >
              <template #prefix><AppIcon name="lock" /></template>
            </el-input>
          </el-form-item>

          <el-form-item
            prop="captchaCode"
            class="login-field"
          >
            <template #label>
              <span class="field-label">验证码</span>
            </template>
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                maxlength="6"
                placeholder="请输入验证码"
                autocomplete="off"
              >
                <template #prefix><AppIcon name="shield" /></template>
              </el-input>
              <button
                class="captcha-box"
                type="button"
                title="点击刷新验证码"
                aria-label="验证码图片，点击刷新"
                :disabled="captchaLoading"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captchaImage"
                  :src="captchaImage"
                  alt="验证码"
                >
                <span
                  v-else
                  class="captcha-loading"
                >加载中</span>
              </button>
            </div>
          </el-form-item>

          <el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="submitting"
            :disabled="submitting"
          >
            {{ submitting ? '正在验证…' : '登录控制台' }}
            <AppIcon
              v-if="!submitting"
              name="arrow-right"
            />
          </el-button>
        </el-form>

        <div class="login-foot">
          <span>© {{ year }} Snail Flow</span>
          <span class="mono">WORKFLOW PLATFORM</span>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
.lp-line {
  fill: none;
  stroke: #3e5d61;
  stroke-width: 1.3;
}

.lp-line.hot {
  stroke: var(--teal);
  stroke-dasharray: 5 5;
  animation: dash-flow 16s linear infinite;
}

.login-field {
  margin-bottom: 0;

  :deep(.el-form-item__label) {
    height: auto;
    padding: 0 0 6px;
    line-height: 1.4;
  }

  :deep(.el-form-item__content) {
    line-height: 1.4;
  }

  :deep(.el-input__wrapper) {
    padding: 0 12px;
    border-radius: 9px;
  }

  :deep(.el-input__inner) {
    height: 40px;
    font-size: 12.5px;
  }

  :deep(.el-input__prefix) {
    margin-right: 8px;
  }

  :deep(.el-input__prefix .ic) {
    width: 15px;
    height: 15px;
  }

  :deep(.el-form-item__error) {
    padding-top: 3px;
    font-size: 10.5px;
  }
}

.captcha-row {
  display: flex;
  width: 100%;
  gap: 10px;

  .el-input {
    min-width: 0;
    flex: 1;
  }
}

.captcha-box {
  display: grid;
  width: 104px;
  height: 42px;
  flex: none;
  place-items: center;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  background: #0b222b;
  cursor: pointer;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.captcha-loading {
  font-size: 10.5px;
  color: var(--text-3);
}

.login-submit {
  width: 100%;
  margin-top: 5px;

  .ic {
    width: 15px;
    height: 15px;
  }
}

@media (max-width: 560px) {
  .captcha-box {
    width: 100%;
  }
}
</style>
