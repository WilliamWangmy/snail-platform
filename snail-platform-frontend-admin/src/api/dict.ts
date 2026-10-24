import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface DictTypeVO {
  dictId: number;
  dictName: string;
  dictType: string;
  status: number;
  remark: string | null;
  createTime: string;
}

export interface DictTypeQuery extends PageParams {
  dictName?: string;
  dictType?: string;
  status?: number | null;
}

export interface DictTypeCreatePayload {
  dictName: string;
  dictType: string;
  remark?: string | null;
}

/** dictType 建后不可改。 */
export interface DictTypeUpdatePayload {
  dictName: string;
  status?: number;
  remark?: string | null;
}

export interface DictDataVO {
  dictDataId: number;
  dictId: number;
  dictLabel: string;
  dictValue: string;
  sort: number;
  status: number;
  remark: string | null;
  createTime: string;
}

export interface DictDataQuery extends PageParams {
  dictId: number;
  dictLabel?: string;
  status?: number | null;
}

export interface DictDataCreatePayload {
  dictId: number;
  dictLabel: string;
  dictValue: string;
  sort?: number;
  remark?: string | null;
}

/** dictValue 建后不可改。 */
export interface DictDataUpdatePayload {
  dictLabel: string;
  sort?: number;
  status?: number;
  remark?: string | null;
}

export interface DictItem {
  label: string;
  value: string;
}

export function pageDictTypes(query: DictTypeQuery): Promise<PageResult<DictTypeVO>> {
  return get<PageResult<DictTypeVO>>('/system/dict/type/page', query);
}

export function createDictType(payload: DictTypeCreatePayload): Promise<number> {
  return post<number>('/system/dict/type', payload);
}

export function updateDictType(dictId: number, payload: DictTypeUpdatePayload): Promise<void> {
  return put<void>(`/system/dict/type/${dictId}`, payload);
}

export function deleteDictType(dictId: number): Promise<void> {
  return del<void>(`/system/dict/type/${dictId}`);
}

export function pageDictData(query: DictDataQuery): Promise<PageResult<DictDataVO>> {
  return get<PageResult<DictDataVO>>('/system/dict/data/page', query);
}

export function createDictData(payload: DictDataCreatePayload): Promise<number> {
  return post<number>('/system/dict/data', payload);
}

export function updateDictData(dictDataId: number, payload: DictDataUpdatePayload): Promise<void> {
  return put<void>(`/system/dict/data/${dictDataId}`, payload);
}

export function deleteDictData(dictDataId: number): Promise<void> {
  return del<void>(`/system/dict/data/${dictDataId}`);
}

/** 业务侧按下拉框用的字典项,只回启用中的键值。 */
export function fetchDictItems(dictType: string): Promise<DictItem[]> {
  return get<DictItem[]>(`/system/dict/items/${dictType}`);
}
