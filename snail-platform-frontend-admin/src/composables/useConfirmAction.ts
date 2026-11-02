import { ElMessage, ElMessageBox } from 'element-plus';

interface ConfirmActionOptions {
  /** 弹窗标题,默认"操作确认" */
  title?: string;
  /** 确认按钮文案,默认"确定" */
  confirmText?: string;
  /** 取消按钮文案,默认"取消" */
  cancelText?: string;
  /** 动作成功后的提示文案;缺省不提示(动作内部自行处理反馈) */
  successText?: string;
  /** 弹窗类型,默认 warning */
  type?: 'warning' | 'info' | 'success' | 'error';
}

/**
 * 危险操作确认的统一出口:确认了才执行动作,成功按需提示。
 * 把各页面"confirm → then(true)/catch(false) → if(!confirmed) return"的同构样板收敛到一处,
 * 调用方只剩语义:确认文案、动作、成功提示。取消返回 false,异常向上抛(动作自身的错误照常冒泡)。
 */
export async function confirmAction(
  message: string,
  action: () => Promise<unknown>,
  options: ConfirmActionOptions = {},
): Promise<boolean> {
  const confirmed = await ElMessageBox.confirm(message, options.title ?? '操作确认', {
    type: options.type ?? 'warning',
    confirmButtonText: options.confirmText ?? '确定',
    cancelButtonText: options.cancelText ?? '取消',
  })
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return false;
  await action();
  if (options.successText) ElMessage.success(options.successText);
  return true;
}
