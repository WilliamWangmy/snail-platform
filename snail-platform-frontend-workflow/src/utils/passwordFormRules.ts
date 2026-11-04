import type { FormRules } from 'element-plus';
import { validatePassword } from './password';

/**
 * 改密表单的口令两条规则:强度校验走 validatePassword 单点(与后端 PasswordPolicy 同值),
 * 确认密码对齐"当前表单里的新密码"——getCurrent 由调用方闭包提供,规则对象保持无状态。
 * 上游:ChangePasswordDialog / ForceChangePasswordDialog;下游:element-plus 表单校验。
 */
export function passwordConfirmRules(getCurrent: () => string | undefined): FormRules {
  return {
    newPassword: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      {
        // 密码强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载
        validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
          const message = validatePassword(value);
          callback(message ? new Error(message) : undefined);
        },
        trigger: 'blur',
      },
    ],
    confirmPassword: [
      { required: true, message: '请再次输入新密码', trigger: 'blur' },
      {
        validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
          if (value !== getCurrent()) callback(new Error('两次输入的密码不一致'));
          else callback();
        },
        trigger: 'blur',
      },
    ],
  };
}
