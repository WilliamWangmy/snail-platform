import { describe, expect, it } from 'vitest';
import {
  PASSWORD_BLACKLIST,
  generatePassword,
  passwordChecklist,
  validatePassword,
} from '@/utils/password';

const STRONG = 'Xk9#mQ2$vL8@';

describe('validatePassword', () => {
  it('长度不足/超长返回长度文案', () => {
    expect(validatePassword('Aa1!')).toBe('密码长度需 8-20 位');
    expect(validatePassword(`${'Aa1!'.repeat(6)}Aa`)).toBe('密码长度需 8-20 位');
    expect(validatePassword(null)).toBe('密码长度需 8-20 位');
  });

  it('含空白返回空白文案', () => {
    expect(validatePassword('Aa1! Aa1!')).toBe('密码不能包含空格等空白字符');
  });

  it('黑名单口令返回黑名单文案(大小写不敏感)', () => {
    for (const weak of PASSWORD_BLACKLIST.slice(0, 3)) {
      expect(validatePassword(weak)).toBe('密码过于常见，请更换');
      expect(validatePassword(weak.toUpperCase())).toBe('密码过于常见，请更换');
    }
  });

  it('类别不足返回类别文案,四类齐全通过', () => {
    expect(validatePassword('abcdefgh1!')).toBe('密码必须包含大写字母、小写字母、数字和特殊符号');
    expect(validatePassword(STRONG)).toBeNull();
  });
});

describe('passwordChecklist', () => {
  it('逐条判定与 validatePassword 同口径', () => {
    const ok = passwordChecklist(STRONG);
    expect(ok).toEqual({
      length: true, upper: true, lower: true, digit: true,
      symbol: true, noWhitespace: true, notBlacklisted: true,
    });
    const weak = passwordChecklist('password');
    expect(weak.notBlacklisted).toBe(false);
    expect(weak.upper).toBe(false);
    // 空白不算符号,也不算 noWhitespace
    const spaces = passwordChecklist('Aa1! Aa1!');
    expect(spaces.noWhitespace).toBe(false);
  });
});

describe('generatePassword', () => {
  it('生成值必过 validatePassword,且长度为 12', () => {
    for (let i = 0; i < 50; i++) {
      const password = generatePassword();
      expect(password).toHaveLength(12);
      expect(validatePassword(password)).toBeNull();
    }
  });
});
