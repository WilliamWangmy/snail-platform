/**
 * 职责:一次性口令回传的传输解密——后端 PasswordTransferCipher 以 AES-256-GCM 加密,
 * 报文为 "v1:" 前缀 + Base64(iv ‖ 密文 ‖ tag);这里切出前 12 字节 IV 解出明文,交给结果弹窗展示。
 * 口径:密钥与后端 snail.security.password-transfer.key 同值,构建期必须用 VITE_PASSWORD_TRANSFER_KEY 注入,
 * 未注入时构建直接失败(vite.config.ts 守门),仓库里不存在任何占位密钥。
 * 两侧报文格式变更必须同改;"v1:" 是格式版本前缀,后端换钥/换格式时前缀递增,本端按前缀识别。
 * 边界(重要):密钥随前端包一起分发,这不是机密性控制——它只防链路明文被日志/代理误记录,
 * 链路机密性由 HTTPS 保证;任何拿到前端产物的人都可解开这段密文,不得把它当作传输保护依赖。
 * crypto.subtle 需要安全上下文(localhost 或 https)。
 * 解密失败只可能来自密钥不一致或非安全上下文,统一在此提示,调用方不必各自 catch。
 */
import { ElMessage } from 'element-plus';

/** 报文格式版本前缀:与后端 PasswordTransferCipher 的 VERSION_PREFIX 同值同改 */
const VERSION_PREFIX = 'v1:';

/** 与后端 GCMParameterSpec 的 12 字节 IV 对齐 */
const IV_LENGTH = 12;

const KEY_BASE64: string = import.meta.env.VITE_PASSWORD_TRANSFER_KEY ?? '';

let keyPromise: Promise<CryptoKey> | null = null;

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function aesKey(): Promise<CryptoKey> {
  if (!keyPromise) {
    if (!KEY_BASE64) {
      // 构建期已有 vite.config.ts 守门,这里兜底拦住"产物缺钥"的最后一种到达方式
      return Promise.reject(new Error('VITE_PASSWORD_TRANSFER_KEY 未注入,口令解密不可用'));
    }
    keyPromise = crypto.subtle.importKey('raw', base64ToBytes(KEY_BASE64), 'AES-GCM', false, ['decrypt']);
  }
  return keyPromise;
}

/** 解密口令密文;失败时提示并抛出,调用方据此放弃展示而不是弹空口令 */
export async function decryptPassword(ciphertext: string): Promise<string> {
  try {
    if (!ciphertext.startsWith(VERSION_PREFIX)) {
      throw new Error(`口令密文缺少 ${VERSION_PREFIX} 版本前缀,前后端报文格式不一致`);
    }
    const packed = base64ToBytes(ciphertext.slice(VERSION_PREFIX.length));
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: packed.subarray(0, IV_LENGTH), tagLength: 128 },
      await aesKey(),
      packed.subarray(IV_LENGTH),
    );
    return new TextDecoder().decode(plain);
  } catch (error) {
    ElMessage.error('口令解密失败，请检查前后端加密密钥是否一致');
    throw error;
  }
}
