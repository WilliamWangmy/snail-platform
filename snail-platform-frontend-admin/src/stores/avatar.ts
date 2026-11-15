import { ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchMyAvatar } from '@/api/file';

/**
 * 本人头像:后端以 Blob 返回,CSP 只放行 data:/blob:,所以由 store 统一持有 object URL 并在替换/登出时回收。
 * 顶栏与个人中心共用同一份,上传成功后 refresh 一次两边同步更新。
 */
export const useAvatarStore = defineStore('avatar', () => {
  const url = ref<string | null>(null);
  const loadedFor = ref<number | null>(null);
  let objectUrl: string | null = null;
  let requestSeq = 0;

  function release(): void {
    requestSeq += 1;
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl);
      objectUrl = null;
    }
    url.value = null;
  }

  async function load(userId: number): Promise<void> {
    if (loadedFor.value === userId) return;
    release();
    loadedFor.value = userId;
    const seq = requestSeq;
    try {
      const blob = await fetchMyAvatar();
      // 登出/换号/重新拉取会让旧请求作废:避免慢请求回来后把上一账号的头像贴上屏幕
      if (seq !== requestSeq) return;
      if (blob.size > 0) {
        objectUrl = URL.createObjectURL(blob);
        url.value = objectUrl;
      }
    } catch {
      // 头像属于辅助信息:取不到就回退姓名缩写,不打断页面;放开标记让下次进入页面可重试
      if (seq === requestSeq) loadedFor.value = null;
    }
  }

  async function refresh(userId: number): Promise<void> {
    loadedFor.value = null;
    await load(userId);
  }

  function clear(): void {
    release();
    loadedFor.value = null;
  }

  return { url, load, refresh, clear };
});
