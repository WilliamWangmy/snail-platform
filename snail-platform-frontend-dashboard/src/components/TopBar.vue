<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { fetchMe, type Me } from '@/api/auth';
import BoardTabsNav from '@/components/BoardTabsNav.vue';
import WeatherCard from '@/components/WeatherCard.vue';
import { boards, type BoardTab } from '@/config/boards';
import { weather } from '@/config/weather';
import { getToken, signOutEverywhere } from '@/stores/auth';

/**
 * 数据大屏驾驶舱顶栏:纯浮层叠在 Cesium 之上(地图保持全高)。
 * 几何=中央凸起托架:正中系统标识 84px 高于两侧 56px 轨道;左翼天气 + 看板菜单,右翼看板菜单 + 会话元信息。
 * 图层开关仍归右下角 MapToolbar,顶栏不放。
 */

const router = useRouter();

const ADMIN_URL = import.meta.env.VITE_ADMIN_URL ?? 'http://localhost:8000/';
const FLOW_URL = import.meta.env.VITE_FLOW_URL ?? 'http://localhost:8002/';

const systems = [
  { key: 'admin', name: '管理端', desc: '多租户运营控制台', url: ADMIN_URL },
  { key: 'flow', name: '流程平台', desc: '流程控制台', url: FLOW_URL },
] as const;

const env = import.meta.env.PROD ? 'PROD' : 'DEV';

type MenuName = 'system' | 'account';

const rootRef = ref<HTMLDivElement>();
const openMenu = ref<MenuName | null>(null);
const me = ref<Me | null>(null);
const clockYear = ref('');
const clockMonthDay = ref('');
const clockTime = ref('');
const isFullscreen = ref(false);
const activeBoard = ref<BoardTab['key']>('flight');
const hint = ref('');
let clockTimer = 0;
let hintTimer = 0;

const leftTabs = computed(() => boards.filter((board) => board.side === 'left'));
const rightTabs = computed(() => boards.filter((board) => board.side === 'right'));

const displayName = computed(() => me.value?.nickname || me.value?.username || '');
/** 多词名取词首双字母、单词名取前两字，与 admin/流程端 avatarText 同规则。 */
const avatarText = computed(() => {
  const name = displayName.value.trim();
  if (!name) return 'SS';
  const parts = name.split(/\s+/);
  if (parts.length > 1) return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase();
  return name.slice(0, 2).toUpperCase();
});
const roleText = computed(() => me.value?.roles.map((role) => role.roleName).join('、') || '');

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

function tick(): void {
  const now = new Date();
  clockYear.value = `${now.getFullYear()}-`;
  clockMonthDay.value = `${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  clockTime.value = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

/** 未建看板只是登记的入口:点它保持高亮在当前看板,提示挂在中央台底沿下方。 */
function onBoardSelect(tab: BoardTab): void {
  if (!tab.built) {
    hint.value = `${tab.label}建设中`;
    window.clearTimeout(hintTimer);
    hintTimer = window.setTimeout(() => (hint.value = ''), 2200);
    return;
  }
  activeBoard.value = tab.key;
}

/** 交接直接复用本站会话令牌:目标端守卫落地后立即剥参。 */
function goSystem(url: string): void {
  openMenu.value = null;
  const joiner = url.includes('?') ? '&' : '?';
  window.open(`${url}${joiner}ssotoken=${encodeURIComponent(getToken())}`, '_blank', 'noopener');
}

/** 令牌失效时 http 层已 401 踢回登录页，其余异常不打断看大屏，可在面板内重试。 */
async function loadMe(): Promise<void> {
  try {
    const fetched = await fetchMe();
    // 开通闸(租户管理增强 T10):租户未开通数据大屏 → 吊销服务端令牌并回登录页,
    // denied 参数由登录页转成内联提示。tenantFeatures 是 /me 实时值(不进 JWT/登录快照),
    // 平台管理员收回开关后刷新页面即被拦;字段缺失(旧后端)视为不拦,向后兼容。
    if (fetched.tenantFeatures && !fetched.tenantFeatures.dashboardEnabled) {
      me.value = null;
      await signOutEverywhere();
      await router.push({ name: 'login', query: { redirect: window.location.pathname, denied: 'dashboard' } });
      return;
    }
    me.value = fetched;
  } catch {
    me.value = null;
  }
}

function toggleMenu(name: MenuName): void {
  openMenu.value = openMenu.value === name ? null : name;
}

function toggleFullscreen(): void {
  const pending = document.fullscreenElement
    ? document.exitFullscreen()
    : document.documentElement.requestFullscreen();
  // 嵌在 iframe 里或被策略禁用时会 reject，全屏失败不需要告知用户
  pending.catch(() => undefined);
}

function onFullscreenChange(): void {
  isFullscreen.value = document.fullscreenElement !== null;
}

function onDocumentMousedown(e: MouseEvent): void {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    openMenu.value = null;
  }
}

function onDocumentKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') openMenu.value = null;
}

/** 退出登录:先吊销服务端令牌再清本地,登录后凭 redirect 回到大屏。 */
async function onLogout(): Promise<void> {
  openMenu.value = null;
  await signOutEverywhere();
  await router.push({ name: 'login', query: { redirect: window.location.pathname } });
}

onMounted(() => {
  tick();
  clockTimer = window.setInterval(tick, 1000);
  document.addEventListener('fullscreenchange', onFullscreenChange);
  document.addEventListener('mousedown', onDocumentMousedown);
  document.addEventListener('keydown', onDocumentKeydown);
  void loadMe();
});

onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearTimeout(hintTimer);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  document.removeEventListener('mousedown', onDocumentMousedown);
  document.removeEventListener('keydown', onDocumentKeydown);
});
</script>

<template>
  <div
    ref="rootRef"
    class="ck-bar"
  >
    <!-- 左翼:天气 + 看板菜单 -->
    <div class="ck-side ck-side-l">
      <WeatherCard :snapshot="weather" />
      <BoardTabsNav
        :tabs="leftTabs"
        :active="activeBoard"
        label="看板菜单（左）"
        @select="onBoardSelect"
      />
    </div>

    <!-- 中央凸起托架:系统标识 -->
    <div class="ck-crest">
      <span
        class="ck-bracket ck-bracket-l"
        aria-hidden="true"
      ></span>
      <span
        class="ck-bracket ck-bracket-r"
        aria-hidden="true"
      ></span>
      <h1 class="ck-title">
        <b><i>Snail</i> 数据驾驶舱</b>
        <small>SNAIL PLATFORM · DATA COCKPIT</small>
      </h1>
    </div>

    <!-- 右翼:看板菜单 + 会话元信息 -->
    <div class="ck-side ck-side-r">
      <BoardTabsNav
        :tabs="rightTabs"
        :active="activeBoard"
        label="看板菜单（右）"
        @select="onBoardSelect"
      />

      <div class="ck-meta">
        <span class="ck-clock" role="timer"><span class="ck-clock-year">{{ clockYear }}</span>{{ clockMonthDay }} {{ clockTime }}</span>
        <span
          class="ck-env"
          :title="`运行环境 ${env}`"
        >
          {{ env }}
        </span>

        <div class="ck-slot">
          <button
            type="button"
            class="ck-icon-btn"
            :class="{ active: openMenu === 'system' }"
            aria-haspopup="menu"
            :aria-expanded="openMenu === 'system'"
            title="切换系统"
            aria-label="切换系统"
            @click="toggleMenu('system')"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M4 7h12" />
              <path d="M4 7l3-3" />
              <path d="M4 7l3 3" />
              <path d="M20 17H8" />
              <path d="M20 17l-3-3" />
              <path d="M20 17l-3 3" />
            </svg>
          </button>
          <Transition name="pop">
            <div
              v-if="openMenu === 'system'"
              class="ck-menu"
              role="menu"
              aria-label="切换系统"
            >
              <div class="menu-title">
                切换系统
              </div>
              <button
                v-for="item in systems"
                :key="item.key"
                type="button"
                class="menu-row"
                role="menuitem"
                @click="goSystem(item.url)"
              >
                <span class="menu-row-name">{{ item.name }}</span>
                <span class="menu-row-desc">{{ item.desc }}</span>
              </button>
            </div>
          </Transition>
        </div>

        <button
          type="button"
          class="ck-icon-btn"
          :class="{ active: isFullscreen }"
          :aria-pressed="isFullscreen"
          :title="isFullscreen ? '退出全屏' : '全屏'"
          :aria-label="isFullscreen ? '退出全屏' : '全屏'"
          @click="toggleFullscreen"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M4 9V4h5" />
            <path d="M20 9V4h-5" />
            <path d="M4 15v5h5" />
            <path d="M20 15v5h-5" />
          </svg>
        </button>

        <div class="ck-slot">
          <button
            type="button"
            class="ck-user"
            :class="{ active: openMenu === 'account' }"
            aria-haspopup="menu"
            :aria-expanded="openMenu === 'account'"
            :aria-label="`账号菜单${displayName ? '，当前：' + displayName : ''}`"
            @click="toggleMenu('account')"
          >
            <span class="ck-avatar">{{ avatarText }}</span>
            <span class="ck-user-name">{{ displayName || '账号信息未获取' }}</span>
            <svg
              class="ck-caret"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          <Transition name="pop">
            <div
              v-if="openMenu === 'account'"
              class="ck-menu"
              role="menu"
              aria-label="账号"
            >
              <div class="menu-title">
                当前账号
              </div>
              <dl
                v-if="me"
                class="menu-facts"
              >
                <div class="fact">
                  <dt>登录名</dt>
                  <dd>{{ me.username }}</dd>
                </div>
                <div class="fact">
                  <dt>租户</dt>
                  <dd>{{ me.tenantName || '—' }}</dd>
                </div>
                <div class="fact">
                  <dt>角色</dt>
                  <dd>{{ roleText || '—' }}</dd>
                </div>
              </dl>
              <button
                v-else
                type="button"
                class="menu-row"
                role="menuitem"
                @click="loadMe"
              >
                <span class="menu-row-name">账号信息获取失败，点击重试</span>
              </button>
              <button
                type="button"
                class="menu-row danger"
                role="menuitem"
                @click="onLogout"
              >
                <span class="menu-row-name">退出登录</span>
              </button>
            </div>
          </Transition>
        </div>
      </div>
    </div>

    <!-- 未建看板提示:挂在中央台底沿下方,不打断看大屏 -->
    <Transition name="hint">
      <div
        v-if="hint"
        class="ck-hint"
        role="status"
      >
        {{ hint }}
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.ck-bar {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 30;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  height: 84px;
  pointer-events: none;
  color: #e2e8f0;
}

.ck-bar > * {
  pointer-events: auto;
}

.ck-side {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(34, 211, 238, 0.22);
  background: linear-gradient(180deg, rgba(2, 6, 23, 0.86), rgba(2, 6, 23, 0.5));
  backdrop-filter: blur(10px);
}

/* 中央凸起：梯形台 + 两侧斜托架 + 底沿光束 */
.ck-crest {
  position: relative;
  flex: none;
  display: grid;
  place-items: center;
  width: 380px;
  height: 84px;
  clip-path: polygon(58px 0, calc(100% - 58px) 0, 100% 100%, 0 100%);
  background: linear-gradient(180deg, rgba(8, 47, 73, 0.95), rgba(2, 6, 23, 0.75));
  filter: drop-shadow(0 12px 22px rgba(2, 6, 23, 0.55));
}

.ck-crest::before {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, #22d3ee 30%, #67e8f9 50%, #22d3ee 70%, transparent);
}

.ck-bracket {
  position: absolute;
  top: 0;
  width: 58px;
  height: 100%;
  background: linear-gradient(180deg, rgba(34, 211, 238, 0.28), transparent 70%);
}

.ck-bracket-l {
  left: 0;
  clip-path: polygon(100% 0, 0 0, 0 100%);
}

.ck-bracket-r {
  right: 0;
  clip-path: polygon(0 0, 100% 0, 100% 100%);
}

.ck-title {
  margin: 0;
  line-height: 1.15;
  text-align: center;
}

.ck-title b {
  display: block;
  color: #e0f2fe;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.22em;
  text-shadow: 0 0 18px rgba(34, 211, 238, 0.65), 0 0 42px rgba(34, 211, 238, 0.25);
}

.ck-title b i {
  color: #22d3ee;
  font-style: normal;
}

.ck-title small {
  display: block;
  margin-top: 4px;
  color: #7dd3fc;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 8.5px;
  letter-spacing: 0.32em;
  opacity: 0.75;
}

/* 右侧元信息 */
.ck-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ck-clock {
  color: #94a3b8;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0.05em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ck-env {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border: 1px dashed rgba(148, 163, 184, 0.45);
  border-radius: 5px;
  color: #94a3b8;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 9px;
  letter-spacing: 0.14em;
}

.ck-slot {
  position: relative;
}

.ck-icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 9px;
  background: rgba(15, 23, 42, 0.6);
  color: #cbd5e1;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease, color 0.18s ease;
}

.ck-icon-btn svg {
  width: 15px;
  height: 15px;
}

.ck-icon-btn:hover {
  border-color: rgba(34, 211, 238, 0.6);
  background: rgba(8, 51, 68, 0.85);
  color: #bae6fd;
}

.ck-icon-btn.active {
  border-color: #22d3ee;
  background: rgba(14, 116, 144, 0.4);
  color: #67e8f9;
}

.ck-user {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 6px 0 3px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease;
}

.ck-user:hover,
.ck-user.active {
  border-color: rgba(34, 211, 238, 0.45);
  background: rgba(8, 51, 68, 0.8);
}

.ck-user:focus-visible,
.ck-icon-btn:focus-visible {
  outline: 2px solid #22d3ee;
  outline-offset: 2px;
}

.ck-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: linear-gradient(150deg, #22d3ee, #0ea5e9);
  color: #04121f;
  font-size: 10.5px;
  font-weight: 700;
}

.ck-user-name {
  max-width: 120px;
  overflow: hidden;
  color: #e2e8f0;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ck-caret {
  flex: none;
  width: 12px;
  height: 12px;
  color: #94a3b8;
}

/* 下拉菜单 */
.ck-menu {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 40;
  min-width: 208px;
  padding: 6px;
  border: 1px solid rgba(34, 211, 238, 0.28);
  border-radius: 12px;
  background: rgba(4, 14, 28, 0.94);
  box-shadow: 0 14px 36px rgba(2, 6, 23, 0.65);
}

.menu-title {
  padding: 6px 9px 4px;
  color: #67e8f9;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 10px;
  letter-spacing: 0.14em;
}

.menu-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  padding: 6px 9px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #cbd5e1;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s ease;
}

.menu-row:hover {
  background: rgba(34, 211, 238, 0.16);
  color: #e0f2fe;
}

.menu-row:focus-visible {
  outline: 2px solid #22d3ee;
  outline-offset: -2px;
}

.menu-row-name {
  font-size: 12.5px;
}

.menu-row-desc {
  margin-top: 1px;
  color: #7d8ba1;
  font-size: 10.5px;
}

.menu-row.danger {
  margin-top: 2px;
  color: #f87171;
}

.menu-row.danger:hover {
  background: rgba(127, 29, 29, 0.5);
  color: #fecaca;
}

.menu-facts {
  margin: 0 0 4px;
  padding: 0 9px 6px;
}

.fact {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  font-size: 11.5px;
  line-height: 20px;
}

.fact dt {
  flex: none;
  color: #7d8ba1;
}

.fact dd {
  margin: 0;
  overflow: hidden;
  color: #cbd5e1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 建设中提示:整条落在中央台之下,不压台沿光束 */
.ck-hint {
  position: absolute;
  top: 92px;
  left: 50%;
  padding: 5px 14px;
  border: 1px solid rgba(34, 211, 238, 0.35);
  border-radius: 999px;
  background: rgba(4, 14, 28, 0.92);
  color: #a5f3fc;
  font-size: 11.5px;
  letter-spacing: 0.06em;
  transform: translateX(-50%);
  pointer-events: none;
  white-space: nowrap;
}

.pop-enter-active,
.pop-leave-active,
.hint-enter-active,
.hint-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.hint-enter-from,
.hint-leave-to {
  opacity: 0;
  transform: translate(-50%, -6px);
}

@media (prefers-reduced-motion: reduce) {
  .ck-icon-btn,
  .ck-user,
  .menu-row,
  .pop-enter-active,
  .pop-leave-active,
  .hint-enter-active,
  .hint-leave-active {
    transition: none;
  }
}

/* 窄屏阶梯:先让位(气象明细/用户名/年份),再收中央台宽度,最后只留图标 */
@media (max-width: 1700px) {
  .ck-clock-year,
  .ck-user-name {
    display: none;
  }
}

@media (max-width: 1500px) {
  .ck-crest {
    width: 320px;
  }

  .ck-title b {
    font-size: 19px;
  }
}

@media (max-width: 1180px) {
  .ck-bar {
    gap: 8px;
  }

  .ck-side {
    gap: 8px;
    padding: 0 12px;
  }

  .ck-crest {
    width: 268px;
  }

  .ck-title b {
    font-size: 17px;
    letter-spacing: 0.14em;
  }

  .ck-title small {
    letter-spacing: 0.2em;
  }

  .ck-clock,
  .ck-env {
    display: none;
  }
}

@media (max-width: 900px) {
  .ck-crest {
    width: 210px;
  }

  .ck-title small {
    display: none;
  }
}
</style>
