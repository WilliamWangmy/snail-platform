import { describe, expect, it, vi } from 'vitest';
import { usePageList } from '@/composables/usePageList';
import type { PageResult } from '@/api/types';

function page<T>(rows: T[], total: number): PageResult<T> {
  return { rows, total };
}

describe('usePageList', () => {
  it('refresh 按当前页码取数并回填 rows/total', async () => {
    const fetcher = vi.fn().mockResolvedValue(page(['a', 'b'], 12));
    const list = usePageList<string>(fetcher, 10);

    list.pageNum.value = 3;
    await list.refresh();

    expect(fetcher).toHaveBeenCalledWith({ pageNum: 3, pageSize: 10 });
    expect(list.rows.value).toEqual(['a', 'b']);
    expect(list.total.value).toBe(12);
    expect(list.loading.value).toBe(false);
  });

  it('search 无论当前在第几页都回第一页', async () => {
    const fetcher = vi.fn().mockResolvedValue(page([], 0));
    const list = usePageList<string>(fetcher);
    list.pageNum.value = 5;

    await list.search();

    expect(fetcher).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 10 });
  });

  it('慢的旧响应不覆盖新响应(竞态守卫)', async () => {
    // 第一次请求挂起不返回,第二次立即返回;旧请求随后返回过期数据必须被丢弃
    let releaseOld: (v: PageResult<string>) => void = () => {};
    const fetcher = vi.fn()
      .mockImplementationOnce(() => new Promise<PageResult<string>>((resolve) => { releaseOld = resolve; }))
      .mockResolvedValueOnce(page(['new'], 1));
    const list = usePageList<string>(fetcher);

    const first = list.refresh();
    list.pageNum.value = 2;
    await list.refresh();
    releaseOld(page(['stale'], 99));
    await first;

    expect(list.rows.value).toEqual(['new']);
    expect(list.total.value).toBe(1);
    expect(list.loading.value).toBe(false);
  });

  it('fetch 抛错时 loading 复位且异常向上冒泡', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('boom'));
    const list = usePageList<string>(fetcher);

    await expect(list.refresh()).rejects.toThrow('boom');
    expect(list.loading.value).toBe(false);
  });
});
