<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemNotify' });
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import RowActions, { type RowAction } from '@/components/RowActions.vue';
import {
  fetchUnreadCount,
  markAllRead,
  markRead,
  pageInbox,
  removeInboxItem,
  type NotifyInboxItem,
  type NotifyInboxQuery,
} from '@/api/notify';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';

const query = reactive<NotifyInboxQuery>({ readStatus: null, bizType: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<NotifyInboxItem>(
  (page) => pageInbox({ ...query, ...page }),
  20,
);

/** 本人未读数,进页与动作后刷新 */
const unread = ref(0);

async function reloadUnread(): Promise<void> {
  unread.value = await fetchUnreadCount();
}

/** 推送到达:停留在第一页且无筛选时静默刷新列表,否则只刷未读数(翻页/筛选态不动) */
function onPushed(): void {
  if (pageNum.value === 1 && !query.readStatus && !query.bizType) {
    void refresh();
  }
  void reloadUnread();
}

onMounted(() => {
  window.addEventListener('notify:pushed', onPushed);
  void refresh();
  void reloadUnread();
});

onBeforeUnmount(() => {
  window.removeEventListener('notify:pushed', onPushed);
});

/** 读态筛选:radio-group 胶囊,''=全部,与 query.readStatus(null)互转 */
const readFilter = ref<'' | 0 | 1>('');

function onReadFilter(): void {
  query.readStatus = readFilter.value === '' ? null : readFilter.value;
  void search();
}

function reset(): void {
  readFilter.value = '';
  query.readStatus = null;
  query.bizType = '';
  void search();
}

/** 统计行:已读 = 总数 - 未读(仅筛选态为全部时口径完整,恒显示) */
const readCount = computed(() => Math.max(0, total.value - unread.value));

const detailVisible = ref(false);
const detail = ref<NotifyInboxItem | null>(null);

/** 看详情即视为已读:未读行先标已读再展开 */
async function openDetail(row: NotifyInboxItem): Promise<void> {
  detail.value = row;
  detailVisible.value = true;
  if (row.readStatus === 0) {
    await markRead(row.recipientId);
    row.readStatus = 1;
    await reloadUnread();
  }
}

/** 行操作:未读才出"标为已读",移出恒在。 */
function actionsOf(row: NotifyInboxItem): RowAction[] {
  return [
    {
      key: 'read',
      label: '标为已读',
      show: row.readStatus === 0,
      handler: () => void handleRead(row),
    },
    {
      key: 'remove',
      label: '移出',
      tone: 'danger',
      handler: () => void handleRemove(row),
    },
  ];
}

async function handleRead(row: NotifyInboxItem): Promise<void> {
  await markRead(row.recipientId);
  row.readStatus = 1;
  await reloadUnread();
}

async function handleRemove(row: NotifyInboxItem): Promise<void> {
  await ElMessageBox.confirm('移出后将从收件箱消失,确定移出该条通知?', '移出通知', { type: 'warning' });
  await removeInboxItem(row.recipientId);
  ElMessage.success('已移出');
  if (detail.value?.recipientId === row.recipientId) {
    detailVisible.value = false;
  }
  await refresh();
  await reloadUnread();
}

const markingAll = ref(false);

async function handleMarkAll(): Promise<void> {
  markingAll.value = true;
  try {
    await markAllRead();
    ElMessage.success('已全部标记为已读');
    await refresh();
    await reloadUnread();
  } finally {
    markingAll.value = false;
  }
}

// ---------- 类型化视觉:bizType → 图标/色板(命中规则外回落 bell) ----------

type Tone = 'primary' | 'info' | 'warning' | 'danger' | 'accent' | 'muted';

interface BizMeta {
  icon: string;
  tone: Tone;
  label: string;
}

function bizMetaOf(row: NotifyInboxItem): BizMeta {
  const t = (row.bizType ?? '').toLowerCase();
  if (t.includes('announce') || t.includes('notice') || t.includes('公告')) {
    return { icon: 'spark', tone: 'accent', label: '公告' };
  }
  if (t.includes('alert') || t.includes('warn') || t.includes('告警')) {
    return { icon: 'alert', tone: 'danger', label: '告警' };
  }
  if (t.includes('task') || t.includes('flow') || t.includes('workflow') || t.includes('审批')) {
    return { icon: 'flow', tone: 'info', label: '流程' };
  }
  if (t.includes('system') || t.includes('sys')) {
    return { icon: 'settings', tone: 'muted', label: '系统' };
  }
  if (!t) {
    return { icon: 'bell', tone: 'primary', label: '通知' };
  }
  return { icon: 'bell', tone: 'warning', label: row.bizType! };
}

/** 内容摘要:压平空白后单行截断,空内容占位 */
function excerptOf(row: NotifyInboxItem): string {
  const flat = (row.content ?? '').replace(/\s+/g, ' ').trim();
  return flat || '（无正文）';
}

/** 相对时间:刚收到的用"刚刚/x 分钟前",更早回落完整格式 */
function relTime(t: string | null): string {
  if (!t) {
    return '—';
  }
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) {
    return dateTime(t);
  }
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) {
    return '刚刚';
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`;
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`;
  }
  return dateTime(t);
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          NOTIFICATIONS
        </div>
        <h1>通知中心</h1>
        <p>系统公告、告警与流程消息统一收件箱,点开即视为已读</p>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'system:notify:list'"
          plain
          :disabled="unread === 0"
          :loading="markingAll"
          @click="handleMarkAll"
        >
          <AppIcon
            name="check"
            :size="15"
          />
          全部已读{{ unread > 0 ? `(${unread})` : '' }}
        </ElButton>
      </div>
    </div>

    <!-- 统计行:总数/未读/已读,复用全局 stat-grid + mini-stat -->
    <div class="stat-grid">
      <ElCard
        shadow="never"
        class="mini-stat"
      >
        <span class="mini-icon tone-primary"><AppIcon name="mail" /></span>
        <div>
          <b>{{ total }}</b>
          <span>收件箱总数</span>
        </div>
      </ElCard>
      <ElCard
        shadow="never"
        class="mini-stat"
      >
        <span class="mini-icon tone-danger"><AppIcon name="bell" /></span>
        <div>
          <b>{{ unread }}</b>
          <span>未读消息</span>
        </div>
      </ElCard>
      <ElCard
        shadow="never"
        class="mini-stat"
      >
        <span class="mini-icon tone-success"><AppIcon name="check" /></span>
        <div>
          <b>{{ readCount }}</b>
          <span>已读消息</span>
        </div>
      </ElCard>
    </div>

    <!-- 收件箱:单卡消息流,筛选收进卡片头,替代原表格 + 独立筛选卡 -->
    <ElCard
      shadow="never"
      class="inbox-card"
      :body-style="{ padding: 0 }"
    >
      <template #header>
        <div class="inbox-head">
          <div class="inbox-head-title">
            收件箱 <span>共 {{ total }} 条</span>
          </div>
          <div class="inbox-head-filter">
            <ElRadioGroup
              v-model="readFilter"
              size="small"
              @change="onReadFilter"
            >
              <ElRadioButton value="">
                全部
              </ElRadioButton>
              <ElRadioButton :value="0">
                未读{{ unread > 0 ? ` ${unread}` : '' }}
              </ElRadioButton>
              <ElRadioButton :value="1">
                已读
              </ElRadioButton>
            </ElRadioGroup>
            <ElInput
              v-model="query.bizType"
              clearable
              placeholder="业务标签筛选,如 announcement"
              size="small"
              style="width: 200px"
              @keyup.enter="search"
              @clear="search"
            >
              <template #prefix>
                <AppIcon
                  name="search"
                  :size="13"
                />
              </template>
            </ElInput>
            <ElButton
              size="small"
              plain
              @click="reset"
            >
              <AppIcon
                name="refresh"
                :size="13"
              />
              重置
            </ElButton>
          </div>
        </div>
      </template>

      <div
        v-loading="loading"
        class="inbox-list"
      >
        <template v-if="rows.length > 0">
          <div
            v-for="row in rows"
            :key="row.recipientId"
            class="inbox-item"
            :class="{ unread: row.readStatus === 0 }"
            @click="openDetail(row)"
          >
            <span
              class="item-ico"
              :class="`tone-${bizMetaOf(row).tone}`"
            >
              <AppIcon
                :name="bizMetaOf(row).icon"
                :size="17"
              />
            </span>
            <div class="item-body">
              <div class="item-title-row">
                <span
                  v-if="row.readStatus === 0"
                  class="unread-dot"
                ></span>
                <b class="item-title">{{ row.title }}</b>
                <span
                  v-if="row.bizType"
                  class="item-chip"
                >{{ bizMetaOf(row).label }}</span>
              </div>
              <p class="item-excerpt">
                {{ excerptOf(row) }}
              </p>
              <div class="item-meta">
                <span class="meta-sender">
                  <AppIcon
                    name="user"
                    :size="11"
                  />
                  {{ row.senderName || '系统通知' }}
                </span>
                <span class="meta-time">{{ relTime(row.sendTime) }}</span>
                <span
                  v-if="row.readStatus === 1 && row.readTime"
                  class="meta-read"
                >读于 {{ dateTime(row.readTime) }}</span>
              </div>
            </div>
            <div
              class="item-side"
              @click.stop
            >
              <span
                class="status-pill"
                :class="row.readStatus === 1 ? 'info' : 'warning'"
              >{{ row.readStatus === 1 ? '已读' : '未读' }}</span>
              <RowActions :actions="actionsOf(row)" />
            </div>
          </div>
        </template>

        <div
          v-else-if="!loading"
          class="inbox-empty"
        >
          <span class="empty-ico"><AppIcon
            name="bell"
            :size="26"
          /></span>
          <b>{{ query.readStatus === 0 ? '没有未读消息' : '收件箱是空的' }}</b>
          <span>{{ query.readStatus === 0 ? '有新消息会实时推送到这里' : '有新通知时将自动出现在列表中' }}</span>
          <ElButton
            v-if="query.readStatus !== null || query.bizType"
            size="small"
            plain
            @click="reset"
          >
            查看全部
          </ElButton>
        </div>
      </div>

      <div class="table-footer">
        <span>每页 {{ pageSize }} 条 · 第 {{ pageNum }} 页</span>
        <ElPagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100, 200]"
          layout="sizes, prev, pager, next"
          @change="refresh"
        />
      </div>
    </ElCard>

    <!-- 详情抽屉:类型化头部 + 元信息网格 + 正文内衬卡 + 底部动作 -->
    <ElDrawer
      v-model="detailVisible"
      size="520px"
    >
      <template #header>
        <div
          v-if="detail"
          class="nd-head"
        >
          <span
            class="item-ico lg"
            :class="`tone-${bizMetaOf(detail).tone}`"
          >
            <AppIcon
              :name="bizMetaOf(detail).icon"
              :size="22"
            />
          </span>
          <div class="nd-head-text">
            <span class="nd-kind">{{ bizMetaOf(detail).label }}</span>
            <b class="nd-title">{{ detail.title }}</b>
          </div>
          <span
            class="status-pill"
            :class="detail.readStatus === 1 ? 'info' : 'warning'"
          >{{ detail.readStatus === 1 ? '已读' : '未读' }}</span>
        </div>
      </template>

      <div
        v-if="detail"
        class="notify-detail"
      >
        <div class="nd-meta">
          <div class="nd-meta-row">
            <span class="nd-meta-label">
              <AppIcon
                name="user"
                :size="12"
              />
              发送人
            </span>
            <span class="nd-meta-val">{{ detail.senderName || '系统通知' }}</span>
          </div>
          <div class="nd-meta-row">
            <span class="nd-meta-label">
              <AppIcon
                name="clock"
                :size="12"
              />
              发送时间
            </span>
            <span class="nd-meta-val mono">{{ detail.sendTime ? dateTime(detail.sendTime) : '—' }}</span>
          </div>
          <div
            v-if="detail.readTime"
            class="nd-meta-row"
          >
            <span class="nd-meta-label">
              <AppIcon
                name="eye"
                :size="12"
              />
              阅读时间
            </span>
            <span class="nd-meta-val mono">{{ dateTime(detail.readTime) }}</span>
          </div>
          <div
            v-if="detail.bizType"
            class="nd-meta-row"
          >
            <span class="nd-meta-label">
              <AppIcon
                name="receipt"
                :size="12"
              />
              业务标签
            </span>
            <span class="nd-meta-val">
              <span class="item-chip">{{ detail.bizType }}</span>
              <template v-if="detail.bizId">
                <span class="mono nd-biz-id">#{{ detail.bizId }}</span>
              </template>
            </span>
          </div>
        </div>

        <div class="nd-content">
          {{ detail.content }}
        </div>

        <div class="nd-actions">
          <ElButton
            v-if="detail.readStatus === 0"
            type="primary"
            plain
            @click="handleRead(detail)"
          >
            <AppIcon
              name="check"
              :size="14"
            />
            标为已读
          </ElButton>
          <ElButton
            plain
            @click="handleRemove(detail)"
          >
            <AppIcon
              name="trash"
              :size="14"
            />
            移出收件箱
          </ElButton>
        </div>
      </div>
    </ElDrawer>
  </div>
</template>

<style scoped>
/* 色板:类型徽章与统计图标同源,全部走主题令牌 */
.tone-primary {
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.12);
}

.tone-accent {
  color: var(--app-accent);
  background: rgba(var(--app-accent-rgb), 0.14);
}

.tone-info {
  color: var(--app-info);
  background: rgba(var(--app-info-rgb), 0.12);
}

.tone-warning {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.13);
}

.tone-danger {
  color: var(--app-danger);
  background: rgba(var(--app-danger-rgb), 0.12);
}

.tone-muted {
  color: var(--app-muted);
  background: var(--app-soft-2);
}

/* 统计图标沿用 mini-icon 尺寸口径,色板覆写 */
.mini-icon.tone-primary,
.mini-icon.tone-danger,
.mini-icon.tone-success {
  color: inherit;
}

.mini-icon.tone-success {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.12);
}

/* ---------- 收件箱卡片头:标题 + 胶囊筛选 ---------- */
.inbox-card {
  margin-bottom: 15px;
}

.inbox-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.inbox-head-title {
  color: var(--app-ink);
  font-size: 14px;
  font-weight: 650;
}

.inbox-head-title span {
  margin-left: 6px;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 400;
}

.inbox-head-filter {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}

/* ---------- 消息流列表 ---------- */
.inbox-list {
  min-height: 220px;
}

.inbox-item {
  display: flex;
  align-items: flex-start;
  gap: 13px;
  position: relative;
  padding: 15px 18px 15px 16px;
  cursor: pointer;
  transition: background 0.16s;
}

.inbox-item + .inbox-item {
  border-top: 1px solid var(--app-line);
}

.inbox-item:hover {
  background: var(--app-soft);
}

/* 未读条目:左侧主色竖条 + 淡主色衬底,与 hover 叠加仍可辨 */
.inbox-item.unread {
  background: rgba(var(--app-primary-rgb), 0.045);
}

.inbox-item.unread::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--app-primary);
}

.inbox-item.unread:hover {
  background: rgba(var(--app-primary-rgb), 0.08);
}

.item-ico {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  margin-top: 2px;
  border-radius: 11px;
}

.item-ico.lg {
  width: 46px;
  height: 46px;
  border-radius: 13px;
}

.item-body {
  flex: 1;
  min-width: 0;
}

.item-title-row {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.unread-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--app-danger);
  box-shadow: 0 0 0 3px rgba(var(--app-danger-rgb), 0.18);
}

.item-title {
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inbox-item.unread .item-title {
  font-weight: 650;
}

.item-chip {
  flex: none;
  padding: 2px 8px;
  border-radius: 99px;
  color: var(--app-muted);
  background: var(--app-soft-2);
  font-size: 9.5px;
  font-weight: 600;
  white-space: nowrap;
}

.item-excerpt {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--app-muted);
  font-size: 11.5px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inbox-item.unread .item-excerpt {
  color: color-mix(in srgb, var(--app-muted) 72%, var(--app-ink));
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 7px;
  color: color-mix(in srgb, var(--app-muted) 82%, transparent);
  font-size: 10px;
}

.meta-sender,
.meta-time,
.meta-read {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-time {
  font-family: var(--app-font-mono);
}

/* 右侧:状态 + 行操作,默认弱化、hover 整行时浮现 */
.item-side {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  margin-top: 2px;
}

.inbox-item .item-side {
  opacity: 0.82;
}

.inbox-item:hover .item-side {
  opacity: 1;
}

/* ---------- 空态 ---------- */
.inbox-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 46px 20px 42px;
  text-align: center;
}

.empty-ico {
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 4px;
  border-radius: 16px;
  color: var(--app-muted);
  background: var(--app-soft);
}

.inbox-empty b {
  color: var(--app-ink);
  font-size: 13.5px;
}

.inbox-empty > span {
  color: var(--app-muted);
  font-size: 11.5px;
}

.inbox-empty .el-button {
  margin-top: 8px;
}

/* ---------- 详情抽屉 ---------- */
.nd-head {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}

.nd-head-text {
  flex: 1;
  min-width: 0;
}

.nd-kind {
  display: block;
  color: var(--app-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.nd-title {
  display: block;
  overflow: hidden;
  margin-top: 3px;
  color: var(--app-ink);
  font-size: 15.5px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notify-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.nd-meta {
  padding: 4px 14px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-soft);
}

.nd-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  font-size: 12px;
}

.nd-meta-row + .nd-meta-row {
  border-top: 1px dashed var(--app-line);
}

.nd-meta-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-muted);
  font-size: 11px;
}

.nd-meta-val {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-ink);
  font-weight: 550;
  text-align: right;
}

.nd-meta-val.mono {
  font-family: var(--app-font-mono);
  font-size: 11.5px;
  font-weight: 450;
}

.nd-biz-id {
  color: var(--app-muted);
  font-size: 10.5px;
}

/* 正文:内衬卡 + 舒适行高,保留原文换行 */
.nd-content {
  margin: 0;
  padding: 16px 18px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-card);
  color: var(--app-ink);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}

.nd-actions {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 2px;
}

@media (max-width: 760px) {
  .inbox-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .inbox-head-filter {
    width: 100%;
  }

  .inbox-head-filter .el-input {
    flex: 1;
  }

  .item-side {
    display: none;
  }

  .item-meta {
    flex-wrap: wrap;
  }
}
</style>
