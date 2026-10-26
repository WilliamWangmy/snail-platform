import { del, download, get, post } from './http';
import type { PageParams, PageResult } from './types';

/** 文件信息,与后端 FileVO 同口径。 */
export interface FileVO {
  fileId: number;
  tenantId: number;
  /** 已清洗路径与控制字符的原始文件名 */
  fileName: string;
  ext: string | null;
  contentType: string | null;
  size: number;
  /** sftp | s3 */
  storageType: string;
  bizType: string | null;
  bizId: number | null;
  uploadBy: number | null;
  uploadByName: string | null;
  createTime: string | null;
}

export interface FileQuery extends PageParams {
  /** 文件名,模糊匹配 */
  fileName?: string;
  /** 业务域标签,精确匹配 */
  bizType?: string;
}

export function pageFiles(query: FileQuery): Promise<PageResult<FileVO>> {
  return get<PageResult<FileVO>>('/system/file/page', query);
}

export function pagePlatformFiles(query: FileQuery): Promise<PageResult<FileVO>> {
  return get<PageResult<FileVO>>('/platform/file/page', query);
}

/** FormData 走唯一 axios 实例:实例未设全局 JSON 头,axios 会按 multipart 自动带 boundary。 */
export function uploadFile(file: File, bizType?: string, bizId?: number): Promise<FileVO> {
  const form = new FormData();
  form.append('file', file);
  if (bizType) form.append('bizType', bizType);
  if (bizId !== undefined) form.append('bizId', String(bizId));
  return post<FileVO>('/system/file/upload', form);
}

export function deleteFile(fileId: number): Promise<void> {
  return del<void>(`/system/file/${fileId}`);
}

/**
 * 下载走 GET raw:blob 场景复用 http.ts 已有的 Content-Disposition 解析与 JSON 信封回退判断,
 * 不自建 fetch 直连。
 */
export function downloadFile(file: FileVO): Promise<void> {
  return download(`/system/file/${file.fileId}/raw`, {}, file.fileName);
}
