import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import dayjs from 'dayjs';
import { pageOperLogs, type OperLogListVO } from '@/api/log';
import type { MenuRoute } from '@/api/menu';
import { useAuthStore } from '@/stores/auth';

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

const BIZ_LABEL: Record<number, string> = {
  1: '新增',
  2: '修改',
  3: '删除',
  4: '导出',
  5: '导入',
  9: '其他',
};

/** 工作台:只聚合登录人已有授权中的真实数据,不伪造指标与趋势。 */
export function useHome() {
  const auth = useAuthStore();
  const router = useRouter();

  const user = computed(() => auth.user);
  const nickname = computed(() => user.value?.nickname ?? user.value?.username ?? '—');

  /** 授权导航里的可跳转叶子,后端已按角色裁剪。 */
  const leaves = computed<MenuRoute[]>(() => {
    const result: MenuRoute[] = [];
    const walk = (nodes: MenuRoute[]): void => {
      for (const node of nodes) {
        if (node.menuType === 2 && node.path && node.target !== 3 && node.visible === 0) result.push(node);
        if (node.children?.length) walk(node.children);
      }
    };
    walk(auth.menus);
    return result;
  });

  const menuCount = computed(() => leaves.value.length);
  const entries = computed(() => leaves.value.slice(0, 8));

  /** 四项指标全部来自登录会话,不做趋势或增减估算。 */
  const kpis = computed(() => [
    { label: '可访问菜单', value: String(menuCount.value), foot: '授权内可跳转页面', icon: 'grid' },
    { label: '生效权限', value: String(user.value?.permissions.length ?? 0), foot: '会话内权限码', icon: 'shield' },
    { label: '生效角色', value: String(user.value?.roles.length ?? 0), foot: '多角色并集', icon: 'users' },
    { label: '登录账号', value: user.value?.username ?? '—', foot: '当前会话身份', icon: 'user' },
  ]);

  const now = dayjs();
  const dateLabel = `${now.format('YYYY年M月D日')} ${WEEK[now.day()]}`;
  const greeting = computed(() => {
    const hour = new Date().getHours();
    if (hour < 6) return '夜深了';
    if (hour < 12) return '上午好';
    if (hour < 18) return '下午好';
    return '晚上好';
  });

  const canViewOper = computed(() => auth.has('system:operlog:list'));
  const opers = ref<OperLogListVO[]>([]);
  const operLoading = ref(false);

  async function loadOpers(): Promise<void> {
    if (!canViewOper.value) return;
    operLoading.value = true;
    try {
      opers.value = (await pageOperLogs({ pageNum: 1, pageSize: 5 })).rows;
    } finally {
      operLoading.value = false;
    }
  }

  function bizLabel(type: number): string {
    return BIZ_LABEL[type] ?? '其他';
  }

  function go(path: string): void {
    void router.push(path);
  }

  onMounted(() => void loadOpers());

  return {
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
  };
}
