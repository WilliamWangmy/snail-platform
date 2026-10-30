/**
 * 职责:一次性口令回传的传输解密——后端 PasswordTransferCipher 以 AES-256-GCM 加密,
 * 报文为 Base64(iv ‖ 密文 ‖ tag);这里切出前 12 字节 IV 解出明文,交给结果弹窗展示。
 * 口径:密钥与后端 snail.security.password-transfer.key 同值;生产构建用 VITE_PASSWORD_TRANSFER_KEY 注入,
 * 未注入时落到 dev 占位(与 application-dev.yml 同值)。两侧报文格式变更必须同改。
 * 边界:密钥随前端包一起分发,挡的是链路上的明文暴露;crypto.subtle 需要安全上下文(localhost 或 https)。
 * 解密失败只可能来自密钥不一致或非安全上下文,统一在此提示,调用方不必各自 catch。
 */
import { ElMessage } from 'element-plus';

/** 开发占位密钥,须与 application-dev.yml 的 password-transfer.key 同值 */
const DEV_KEY = '+boKbssh0dSJgi2wEIJbWRBWzL9Y5CDuIN9ZDxvcJVs=';

const KEY_BASE64: string = import.meta.env.VITE_PASSWORD_TRANSFER_KEY ?? DEV_KEY;

/** 与后端 GCMParameterSpec 的 12 字节 IV 对齐 */
const IV_LENGTH = 12;

let keyPromise: Promise<CryptoKey> | null = null;

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function aesKey(): Promise<CryptoKey> {
  if (!keyPromise) {
    keyPromise = crypto.subtle.importKey('raw', base64ToBytes(KEY_BASE64), 'AES-GCM', false, ['decrypt']);
  }
  return keyPromise;
}

/** 解密口令密文;失败时提示并抛出,调用方据此放弃展示而不是弹空口令 */
export async function decryptPassword(ciphertext: string): Promise<string> {
  try {
    const packed = base64ToBytes(ciphertext);
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
