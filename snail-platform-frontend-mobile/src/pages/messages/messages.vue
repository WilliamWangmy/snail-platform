<template>
  <wd-config-provider :theme="theme.wotTheme">
    <view class="page" :class="{ 'page--dark': theme.isDark }">
      <view class="header">
        <view class="h-left">
          <text class="h-title">消息</text>
          <text v-if="notify.unreadCount > 0" class="h-cnt">{{ notify.unreadCount }} 未读</text>
        </view>
        <view class="readall" @click="onReadAll">
          <wd-icon name="check" size="15px" color="#1D9E75" />
          <text class="readall-t">全部已读</text>
        </view>
      </view>

      <view class="chips">
        <view
          v-for="c in chips"
          :key="c.key"
          class="chip"
          :class="{ on: filter === c.key }"
          @click="setFilter(c.key)"
        >
          {{ c.label }}
        </view>
      </view>

      <view v-if="groups.length === 0 && !loading" class="empty">
        <wd-icon name="chat" size="52px" color="#d5d4cc" />
        <text class="empty-t">暂无消息</text>
      </view>

      <view v-for="group in groups" :key="group.day" class="group">
        <view class="day"><text>{{ group.day }}</text></view>
        <view
          v-for="item in group.items"
          :key="item.recipientId"
          class="msg"
          :class="{ 'is-read': item.readStatus === 1 }"
          @click="openDetail(item)"
        >
          <view class="m-ico" :class="typeCls(item.bizType)">
            <wd-icon :name="typeIcon(item.bizType)" size="20px" :color="typeColor(item.bizType)" />
          </view>
          <view class="m-main">
            <view class="m-t">
              <text class="m-title">{{ item.title }}</text>
              <text class="tt" :class="typeCls(item.bizType)">{{ typeLabel(item.bizType) }}</text>
            </view>
            <view class="m-b">{{ item.content }}</view>
            <view class="m-time">{{ timeLine(item) }}</view>
          </view>
          <view v-if="item.readStatus === 0" class="unread" />
        </view>
      </view>

      <view v-if="loading" class="loading"><text>加载中…</text></view>
      <view v-else-if="finished && groups.length > 0" class="loading"><text>没有更多了</text></view>

      <wd-popup v-model="detailShow" position="bottom" closable custom-class="detail-sheet">
        <view class="d-body">
          <view class="d-tag" :class="typeCls(current?.bizType)">{{ typeLabel(current?.bizType) }}</view>
          <text class="d-title">{{ current?.title }}</text>
          <text class="d-meta">{{ current ? timeLine(current) : '' }}</text>
          <text class="d-content">{{ current?.content }}</text>
          <view class="d-ops">
            <wd-button plain custom-class="d-btn" @click="onDelete">删除</wd-button>
            <wd-button custom-class="d-btn" @click="onMarkRead">
              {{ current?.readStatus === 0 ? '标记已读并关闭' : '关闭' }}
            </wd-button>
          </view>
        </view>
      </wd-popup>

      <wd-toast />
    </view>
  </wd-config-provider>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app';
import { useToast } from 'wot-design-uni';
import { useThemeStore } from '@/stores/theme';
import { useNotifyStore } from '@/stores/notify';
import {
  markAllRead,
  markRead,
  pageInbox,
  removeInboxItem,
  type NotifyInboxItem,
} from '@/api/notify';

type FilterKey = 'all' | 'unread' | 'read';

const theme = useThemeStore();
const notify = useNotifyStore();
const toast = useToast();

const chips: Array<{ key: FilterKey; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'unread', label: '未读' },
  { key: 'read', label: '已读' },
];

const filter = ref<FilterKey>('all');
const items = ref<NotifyInboxItem[]>([]);
const page = ref(1);
const total = ref(0);
const loading = ref(false);
const detailShow = ref(false);
const current = ref<NotifyInboxItem | null>(null);

const PAGE_SIZE = 10;

const finished = computed(() => items.value.length >= total.value);

/** bizType 展示映射;未知标签兜底为"消息"。 */
const TYPE_MAP: Record<string, { label: string; icon: string; color: string; cls: string }> = {
  NOTICE: { label: '公告', icon: 'notification', color: '#1D9E75', cls: 't-notice' },
  FLOW: { label: '审批', icon: 'edit', color: '#378ADD', cls: 't-flow' },
  SYSTEM: { label: '系统', icon: 'file', color: '#5a6b80', cls: 't-sys' },
  ALARM: { label: '告警', icon: 'warning', color: '#e24b4a', cls: 't-alarm' },
};

function typeOf(bizType: string | null | undefined) {
  return TYPE_MAP[bizType ?? ''] ?? { label: '消息', icon: 'notification', color: '#888780', cls: 't-default' };
}

function typeLabel(bizType: string | null | undefined): string {
  return typeOf(bizType).label;
}
function typeIcon(bizType: string | null | undefined): string {
  return typeOf(bizType).icon;
}
function typeColor(bizType: string | null | undefined): string {
  return typeOf(bizType).color;
}
function typeCls(bizType: string | null | undefined): string {
  return typeOf(bizType).cls;
}

function timeLine(item: NotifyInboxItem): string {
  return [item.sendTime ?? '', item.senderName ?? ''].filter(Boolean).join(' · ');
}

/** 按天分组:今天/昨天/日期。 */
const groups = computed<Array<{ day: string; items: NotifyInboxItem[] }>>(() => {
  const fmt = (d: Date): string =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const today = new Date();
  const yesterday = new Date(today.getTime() - 86400000);
  const map = new Map<string, NotifyInboxItem[]>();
  for (const item of items.value) {
    const day = (item.sendTime ?? '').slice(0, 10);
    const label = day === fmt(today) ? '今天' : day === fmt(yesterday) ? '昨天' : day || '';
    const bucket = map.get(label);
    if (bucket) bucket.push(item);
    else map.set(label, [item]);
  }
  return [...map.entries()].map(([day, list]) => ({ day, items: list }));
});

onShow(() => {
  notify.refresh();
  if (items.value.length === 0) void reload();
});

function readStatusParam(): number | null {
  if (filter.value === 'unread') return 0;
  if (filter.value === 'read') return 1;
  return null;
}

async function reload(): Promise<void> {
  loading.value = true;
  try {
    const result = await pageInbox({ page: 1, pageSize: PAGE_SIZE, readStatus: readStatusParam() });
    items.value = result.list;
    total.value = result.total;
    page.value = 1;
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '加载失败', iconName: 'warning' });
  } finally {
    loading.value = false;
  }
}

async function loadMore(): Promise<void> {
  if (loading.value || finished.value) return;
  loading.value = true;
  try {
    const next = page.value + 1;
    const result = await pageInbox({ page: next, pageSize: PAGE_SIZE, readStatus: readStatusParam() });
    items.value = items.value.concat(result.list);
    total.value = result.total;
    page.value = next;
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '加载失败', iconName: 'warning' });
  } finally {
    loading.value = false;
  }
}

function setFilter(key: FilterKey): void {
  if (filter.value === key) return;
  filter.value = key;
  void reload();
}

onPullDownRefresh(async () => {
  try {
    await reload();
  } finally {
    uni.stopPullDownRefresh();
  }
});

onReachBottom(() => {
  void loadMore();
});

function openDetail(item: NotifyInboxItem): void {
  current.value = item;
  detailShow.value = true;
}

async function onMarkRead(): Promise<void> {
  const item = current.value;
  detailShow.value = false;
  if (!item || item.readStatus === 1) return;
  try {
    await markRead(item.recipientId);
    item.readStatus = 1;
    notify.refresh();
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '操作失败', iconName: 'warning' });
  }
}

async function onDelete(): Promise<void> {
  const item = current.value;
  detailShow.value = false;
  if (!item) return;
  try {
    await removeInboxItem(item.recipientId);
    items.value = items.value.filter((x) => x.recipientId !== item.recipientId);
    total.value = Math.max(0, total.value - 1);
    notify.refresh();
    toast.show({ msg: '消息已删除' });
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '操作失败', iconName: 'warning' });
  }
}

async function onReadAll(): Promise<void> {
  try {
    await markAllRead();
    items.value = items.value.map((x) => ({ ...x, readStatus: 1 }));
    notify.refresh();
    toast.show({ msg: '全部消息已读' });
  } catch (error) {
    toast.show({ msg: error instanceof Error ? error.message : '操作失败', iconName: 'warning' });
  }
}
</script>

<style lang="scss" src="./messages.scss" scoped></style>
