<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue';
import { dateTime } from '@/utils/format';
import { menuIcon } from '@/utils/icons';
import { useHome } from './useHome';

const {
  auth,
  user,
  nickname,
  menuCount,
  entries,
  kpis,
  dateLabel,
  greeting,
  canViewOper,
  opers,
  operLoading,
  bizLabel,
  go,
  loadOpers,
} = useHome();
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <div class="eyebrow">
          OVERVIEW
        </div>
        <h1>工作台</h1>
        <p>{{ dateLabel }} · {{ greeting }}，{{ nickname }}</p>
      </div>
      <div class="head-actions">
        <ElButton
          plain
          :loading="operLoading"
          @click="loadOpers"
        >
          <AppIcon
            name="refresh"
            :size="15"
          />
          刷新数据
        </ElButton>
      </div>
    </div>

    <ElCard
      shadow="never"
      class="welcome-card"
    >
      <div class="welcome-copy">
        <div class="welcome-date">
          {{ dateLabel }}
        </div>
        <h2>{{ greeting }}，{{ nickname }}</h2>
        <p>已授权 {{ menuCount }} 个页面入口 · 生效 {{ user?.permissions.length ?? 0 }} 项权限</p>
      </div>
      <div class="welcome-side">
        <div class="welcome-fact">
          <span>租户</span>
          <b>{{ user?.tenantName ?? '—' }}</b>
        </div>
        <div class="welcome-fact">
          <span>所属组织</span>
          <b>{{ user?.orgName ?? '未归属' }}</b>
        </div>
        <div class="welcome-fact">
          <span>生效角色</span>
          <b>{{ auth.roleNames }}</b>
        </div>
      </div>
    </ElCard>

    <ElRow
      :gutter="16"
      class="kpi-grid"
    >
      <ElCol
        v-for="(kpi, index) in kpis"
        :key="kpi.label"
        :xs="24"
        :sm="12"
        :lg="6"
      >
        <ElCard
          shadow="never"
          class="kpi-card"
        >
          <div class="kpi-top">
            <span>{{ kpi.label }}</span>
            <span
              class="kpi-icon"
              :style="{ color: `var(--app-chart-${index + 1})` }"
            >
              <AppIcon
                :name="kpi.icon"
                :size="16"
              />
            </span>
          </div>
          <div class="kpi-value">
            {{ kpi.value }}
          </div>
          <div class="kpi-foot">
            {{ kpi.foot }}
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <div class="dashboard-grid">
      <ElCard
        v-if="canViewOper"
        shadow="never"
        class="table-card"
      >
        <template #header>
          <div class="table-card-head">
            <h3>最近操作</h3>
            <ElButton
              link
              type="primary"
              size="small"
              @click="go('/system/operlog')"
            >
              查看全部
            </ElButton>
          </div>
        </template>
        <ElTable
          v-loading="operLoading"
          :data="opers"
          size="small"
        >
          <ElTableColumn
            label="时间"
            width="160"
          >
            <template #default="{ row }">
              {{ dateTime(row.operTime) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            prop="module"
            label="模块"
            min-width="120"
            show-overflow-tooltip
          />
          <ElTableColumn
            label="操作"
            width="86"
          >
            <template #default="{ row }">
              {{ bizLabel(row.businessType) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            prop="operator"
            label="操作人"
            width="110"
          />
          <ElTableColumn
            label="状态"
            width="88"
          >
            <template #default="{ row }">
              <span
                class="status-pill"
                :class="row.status === 1 ? 'success' : 'danger'"
              >
                {{ row.status === 1 ? '成功' : '失败' }}
              </span>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>

      <ElCard
        v-else
        shadow="never"
      >
        <template #header>
          <div class="table-card-head">
            <h3>当前登录人</h3>
          </div>
        </template>
        <ElDescriptions
          :column="1"
          border
        >
          <ElDescriptionsItem label="显示名">
            {{ user?.nickname ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="登录名">
            {{ user?.username ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="租户">
            {{ user?.tenantName ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="所属组织">
            {{ user?.orgName ?? '未归属' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="生效角色">
            {{ auth.roleNames }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="权限码">
            {{ user?.permissions.length ?? 0 }} 项
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <div class="table-card-head">
            <h3>快捷入口</h3>
          </div>
        </template>
        <ElEmpty
          v-if="!entries.length"
          description="暂无已授权菜单"
          :image-size="60"
        />
        <div
          v-else
          class="entry-grid"
        >
          <button
            v-for="item in entries"
            :key="item.menuId"
            type="button"
            class="entry"
            @click="go(item.path!)"
          >
            <AppIcon
              :name="menuIcon(item.icon)"
              :size="18"
            />
            <span>{{ item.menuName }}</span>
          </button>
        </div>
      </ElCard>
    </div>
  </div>
</template>

<style src="./home.css" scoped></style>
