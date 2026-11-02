/**
 * 分页列表通用状态承接：rows / total / loading / 查询触发。
 * api 层分页函数统一签名 (query) => Promise<PageResult<TRow>>，页面侧只管传查询参数。
 */
import { ref, shallowRef, type Ref } from 'vue';

import type { PageQueryParam, PageResult } from '@/types/api';

export function useTable<TRow, TQuery extends Record<string, unknown>>(
  fetcher: (query: TQuery & PageQueryParam) => Promise<PageResult<TRow>>,
  baseQuery: TQuery,
) {
  const rows = shallowRef<TRow[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = ref<TQuery & PageQueryParam>({ ...baseQuery }) as Ref<TQuery & PageQueryParam>;

  async function search(): Promise<void> {
    loading.value = true;
    try {
      const result = await fetcher(query.value);
      rows.value = result.rows;
      total.value = result.total;
    } finally {
      loading.value = false;
    }
  }

  return { rows, total, loading, query, search };
}
