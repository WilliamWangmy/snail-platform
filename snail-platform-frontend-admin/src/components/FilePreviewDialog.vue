<script setup lang="ts">
import { computed } from 'vue';
import { FileViewer } from '@file-viewer/vue3';
import { setDefaultFileViewerAssetBaseUrl, type FileViewerOptions } from '@file-viewer/core';
import officePreset from '@file-viewer/preset-office';
import litePreset from '@file-viewer/preset-lite';
import { useThemeStore } from '@/stores/theme';
import '@file-viewer/vue3/dist/file-viewer3.css';

defineProps<{
  visible: boolean;
  fileName: string;
  /** 带文件名的 File:渲染器靠扩展名选链路,裸 Blob 会识别不出格式。 */
  file: File | null;
  loading?: boolean;
}>();

const emit = defineEmits<{ (e: 'closed'): void }>();

const theme = useThemeStore();

/* 资产基址必须显式固定:默认按当前文档 URL 解析相对路径,深层路由(/system/file)会找成 /system/vendor/ 而 404。 */
setDefaultFileViewerAssetBaseUrl(`${import.meta.env.BASE_URL}file-viewer/`);

const options = computed<FileViewerOptions>(() => ({
  preset: [officePreset, litePreset],
  rendererMode: 'replace',
  theme: theme.current.mode,
  locale: 'zh-CN',
  // 主题跟随 admin 主题store,不留预览器自己的浅色/深色切换造成两套观感
  toolbar: { theme: false },
  onDiagnostic: (diagnostic) => {
    if (diagnostic.level === 'info') return;
    console.warn('[file-viewer]', diagnostic.code, diagnostic.message, diagnostic.detail);
  },
}));
</script>

<template>
  <ElDialog
    :model-value="visible"
    width="min(1080px, calc(100vw - 32px))"
    top="6vh"
    destroy-on-close
    @update:model-value="emit('closed')"
  >
    <template #header>
      <h3 class="fp-title">
        {{ fileName || '文件预览' }}
      </h3>
    </template>

    <div
      v-loading="loading"
      class="fp-body"
    >
      <FileViewer
        v-if="file"
        :file="file"
        :options="options"
      />
    </div>
  </ElDialog>
</template>

<style scoped>
.fp-title {
  margin: 0;
  color: var(--app-ink);
  font-size: 17px;
  letter-spacing: -0.02em;
}

.fp-body {
  height: 72vh;
  min-height: 320px;
}
</style>