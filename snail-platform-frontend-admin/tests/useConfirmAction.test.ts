import { describe, expect, it, vi } from 'vitest';
import { confirmAction } from '@/composables/useConfirmAction';

// element-plus 的弹窗按"取消"拒romise 的约定 mock:resolve 即确认,reject 即取消
vi.mock('element-plus', () => ({
  ElMessageBox: {
    confirm: vi
      .fn()
      .mockResolvedValueOnce('confirm') // 第 1 次调用:用户确认
      .mockRejectedValueOnce('cancel') // 第 2 次调用:用户取消
      .mockResolvedValue('confirm'), // 第 3 次起:默认确认(动作抛错用例落到这里)
  },
  ElMessage: { success: vi.fn() },
}));

describe('confirmAction', () => {
  it('确认后执行动作并提示成功', async () => {
    const action = vi.fn().mockResolvedValue(undefined);
    const done = await confirmAction('删除?', action, { title: '删除确认', successText: '已删除' });

    expect(done).toBe(true);
    expect(action).toHaveBeenCalledTimes(1);
  });

  it('取消时不执行动作且不提示', async () => {
    const action = vi.fn().mockResolvedValue(undefined);
    const done = await confirmAction('删除?', action);

    expect(done).toBe(false);
    expect(action).not.toHaveBeenCalled();
  });

  it('动作抛错向上冒泡,不吞业务异常', async () => {
    const action = vi.fn().mockRejectedValue(new Error('boom'));
    await expect(confirmAction('删除?', action)).rejects.toThrow('boom');
  });
});
