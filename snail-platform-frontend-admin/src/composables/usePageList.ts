import { ref, type Ref } from 'vue';
import type { PageParams, PageResult } from '@/api/types';

/**
 * 职责:列表页分页状态单点——页码、每页、总数、加载态与两次取数动作只在这里维护。
 * 上游:各管理页;下游:领域 API 的分页函数(由页面以闭包带入自己的筛选条件)。
 * 口径:rows 用深响应 ref,页面可就地改一行状态;search 必回第一页。
 */
export interface PageList<T> {
  rows: Ref<T[]>;
  total: Ref<number>;
  loading: Ref<boolean>;
  pageNum: Ref<number>;
  pageSize: Ref<number>;
  refresh: () => Promise<void>;
  search: () => Promise<void>;
}

export function usePageList<T>(
  fetch: (params: PageParams) => Promise<PageResult<T>>,
  pageSize = 10,
): PageList<T> {
  const rows = ref<T[]>([]) as Ref<T[]>;
  const total = ref(0);
  const loading = ref(false);
  const pageNum = ref(1);
  const size = ref(pageSize);

  async function refresh(): Promise<void> {
    loading.value = true;
    try {
      const page = await fetch({ pageNum: pageNum.value, pageSize: size.value });
      rows.value = page.rows;
      total.value = page.total;
    } finally {
      loading.value = false;
    }
  }

  /** 筛选变了却停在第 N 页,会看到空表——回第一页再取数。 */
  async function search(): Promise<void> {
    pageNum.value = 1;
    await refresh();
  }

  return { rows, total, loading, pageNum, pageSize: size, refresh, search };
}
