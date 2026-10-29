/**
 * 职责:口令强度的前端单点口径与随机生成——校验文案、黑名单、字符类别判定与后端 PasswordPolicy 完全同值;
 * 前端校验只是"先行拦截"的 UX 层,后端仍是最终裁决。
 * 口径:改黑名单或文案必须同步后端 user/service/PasswordPolicy.java,两侧文件头互指;
 * 生成与校验纯内存执行,不触后端、不落 console。
 */

/** 与后端 COMMON_WEAK 同值的 24 条高频弱口令,命中即拒 */
export const PASSWORD_BLACKLIST: readonly string[] = [
  '12345678', '123456789', '1234567890', '00000000', '11111111', '88888888',
  'password', 'password1', 'passw0rd', 'p@ssw0rd', 'abc12345', 'a123456',
  'admin123', 'admin888', 'administrator', 'qwerty123', '1q2w3e4r', '1qaz2wsx',
  'iloveyou', 'test1234', 'guest1234', 'letmein1', 'monkey123', 'football1'];

/** 文案与后端 PasswordPolicy 冻结稿逐字一致,表单不得另写 */
export const PASSWORD_MESSAGES = {
  LENGTH: '密码长度需 8-20 位',
  CLASS: '密码必须包含大写字母、小写字母、数字和特殊符号',
  WHITESPACE: '密码不能包含空格等空白字符',
  BLACKLIST: '密码过于常见，请更换',
} as const;

const MIN_LENGTH = 8;
const MAX_LENGTH = 20;
const GENERATED_LENGTH = 12;

const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const UPPER = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const DIGIT = '0123456789';
/** 生成用的符号集:不含空白,全部落在后端"其余字符算符号"的判定域内 */
const SYMBOL = '!@#$%^&*()-_=+[]{};:,.?';

function randomInt(maxExclusive: number): number {
  const buf = new Uint32Array(1);
  const limit = Math.floor(0xffffffff / maxExclusive) * maxExclusive;
  let value = 0;
  do {
    crypto.getRandomValues(buf);
    value = buf[0];
  } while (value >= limit);
  return value % maxExclusive;
}

/** 命中的字符类别数:小写、大写、数字、其余各算一类,与后端 charClasses 同口径 */
function charClasses(password: string): number {
  let lower = false;
  let upper = false;
  let digit = false;
  let symbol = false;
  for (const c of password) {
    if (c >= 'a' && c <= 'z') lower = true;
    else if (c >= 'A' && c <= 'Z') upper = true;
    else if (c >= '0' && c <= '9') digit = true;
    else symbol = true;
  }
  return [lower, upper, digit, symbol].filter(Boolean).length;
}

/** 校验口令强度,不合格返回冻结文案,合格返回 null;短路顺序与后端一致:长度→空白→黑名单→类别 */
export function validatePassword(password: string | null | undefined): string | null {
  if (!password || password.length < MIN_LENGTH || password.length > MAX_LENGTH) {
    return PASSWORD_MESSAGES.LENGTH;
  }
  if (/\s/.test(password)) {
    return PASSWORD_MESSAGES.WHITESPACE;
  }
  if (PASSWORD_BLACKLIST.includes(password.toLowerCase())) {
    return PASSWORD_MESSAGES.BLACKLIST;
  }
  if (charClasses(password) < 4) {
    return PASSWORD_MESSAGES.CLASS;
  }
  return null;
}

/** 改密表单实时清单的逐条判定结果 */
export interface PasswordChecklist {
  length: boolean;
  upper: boolean;
  lower: boolean;
  digit: boolean;
  symbol: boolean;
  noWhitespace: boolean;
  notBlacklisted: boolean;
}

/** 逐条判定口令满足项,判定口径与 validatePassword 同源;供改密表单渲染实时满足条件 */
export function passwordChecklist(password: string): PasswordChecklist {
  return {
    length: password.length >= MIN_LENGTH && password.length <= MAX_LENGTH,
    upper: /[A-Z]/.test(password),
    lower: /[a-z]/.test(password),
    digit: /\d/.test(password),
    // 符号判定不含空白:空白由 WHITESPACE 规则单独拒绝,不在这里充当"特殊符号"
    symbol: [...password].some((c) => !/[a-zA-Z0-9]/.test(c) && !/\s/.test(c)),
    noWhitespace: password.length > 0 && !/\s/.test(password),
    notBlacklisted: password.length > 0 && !PASSWORD_BLACKLIST.includes(password.toLowerCase()),
  };
}

/** 随机生成 12 位口令:四类各至少 1、无空白、不命中黑名单,不合格即整条重生成 */
export function generatePassword(): string {
  const pools = [LOWER, UPPER, DIGIT, SYMBOL];
  for (;;) {
    const chars: string[] = pools.map((pool) => pool[randomInt(pool.length)]);
    const all = pools.join('');
    while (chars.length < GENERATED_LENGTH) {
      chars.push(all[randomInt(all.length)]);
    }
    // Fisher-Yates 洗牌,避免四类固定落在前四位
    for (let i = chars.length - 1; i > 0; i--) {
      const j = randomInt(i + 1);
      [chars[i], chars[j]] = [chars[j], chars[i]];
    }
    const password = chars.join('');
    if (validatePassword(password) === null) {
      return password;
    }
  }
}

/** 一键复制:仅写剪贴板,生成值不落 console;失败返回 false 由调用方提示 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
