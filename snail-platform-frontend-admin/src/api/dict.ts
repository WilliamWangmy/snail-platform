import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

/** 字典类型行:租户各自维护,平台不内置也没有跨租户并集。 */
export interface DictTypeVO {
  dictId: number;
  dictName: string;
  /** 程序取值坐标,租户内唯一且含已删记录,建后不可改 */
  dictType: string;
  /** 0 正常 1 停用;停用后本类型下拉整体取不到 */
  status: number;
  remark: string | null;
  createTime: string;
}

/** 类型列表条件:两个 like 字段留空后端按不限处理。 */
export interface DictTypeQuery extends PageParams {
  dictName?: string;
  dictType?: string;
  status?: number | null;
}

/** 新增才给 dictType(小写字母开头的下划线串),状态不在契约里——建出来恒为正常。 */
export interface DictTypeCreatePayload {
  dictName: string;
  dictType: string;
  remark?: string | null;
}

/** dictType 建后不可改;停用类型不会级联停用其数据项。 */
export interface DictTypeUpdatePayload {
  dictName: string;
  status?: number;
  remark?: string | null;
}

/** 字典项行:归属一个类型,值与标签分开——值落业务库,标签只作展示。 */
export interface DictDataVO {
  dictDataId: number;
  /** 归属不可迁移,修改契约里没有这一列 */
  dictId: number;
  dictLabel: string;
  /** 同类型内唯一且含已删记录,建后不可改 */
  dictValue: string;
  /** 同类型内显示顺序,允许并列 */
  sort: number;
  /** 0 正常 1 停用;停用只把自己从下拉里摘掉 */
  status: number;
  remark: string | null;
  createTime: string;
}

/** 数据列表必须钉住类型,后端不提供跨类型全量翻页。 */
export interface DictDataQuery extends PageParams {
  dictId: number;
  dictLabel?: string;
  status?: number | null;
}

export interface DictDataCreatePayload {
  dictId: number;
  dictLabel: string;
  dictValue: string;
  /** 不传后端按 0 */
  sort?: number;
  remark?: string | null;
}

/** dictValue 建后不可改;remark 不传即清空,status/sort 不传即沿用。 */
export interface DictDataUpdatePayload {
  dictLabel: string;
  sort?: number;
  status?: number;
  remark?: string | null;
}

/** 下拉项:只有渲染要用的两列,管理字段不透出。 */
export interface DictItem {
  label: string;
  value: string;
}

/** 类型分页,新建立的排最前。 */
export function pageDictTypes(query: DictTypeQuery): Promise<PageResult<DictTypeVO>> {
  return get<PageResult<DictTypeVO>>('/system/dict/type/page', query);
}

/** 新增类型:标识同租户内撞库(含已删)即业务拒绝。 */
export function createDictType(payload: DictTypeCreatePayload): Promise<number> {
  return post<number>('/system/dict/type', payload);
}

/** 修改类型:整表单提交,备注留空即清空。 */
export function updateDictType(dictId: number, payload: DictTypeUpdatePayload): Promise<void> {
  return put<void>(`/system/dict/type/${dictId}`, payload);
}

/** 删除类型:同事务逻辑删其全部数据项;历史业务行保留原值不回写,标识终身占用不可重建。 */
export function deleteDictType(dictId: number): Promise<void> {
  return del<void>(`/system/dict/type/${dictId}`);
}

/** 数据分页:dictId 指向别的租户的类型时,后端按"字典类型不存在"拒。 */
export function pageDictData(query: DictDataQuery): Promise<PageResult<DictDataVO>> {
  return get<PageResult<DictDataVO>>('/system/dict/data/page', query);
}

/** 新增数据:同类型内值重复即业务拒绝(含已删行)。 */
export function createDictData(payload: DictDataCreatePayload): Promise<number> {
  return post<number>('/system/dict/data', payload);
}

/** 修改数据:值与归属都不在契约里,能改的只有标签、顺序、状态、备注。 */
export function updateDictData(dictDataId: number, payload: DictDataUpdatePayload): Promise<void> {
  return put<void>(`/system/dict/data/${dictDataId}`, payload);
}

/** 删除数据:不查业务引用;删掉的值在同类型下不能原样重建。 */
export function deleteDictData(dictDataId: number): Promise<void> {
  return del<void>(`/system/dict/data/${dictDataId}`);
}

/**
 * 业务侧按下拉框用的字典项,只回启用中的键值;类型不存在或已停用是业务拒绝,不会退化成空列表。
 * 后端按 (租户, 标识) 缓存,任何字典写操作全量失效——只要动过字典,下拉就重新回源。
 * 本应用内暂无调用点:字典管理页只维护配置,业务页面尚未接下拉。
 */
export function fetchDictItems(dictType: string): Promise<DictItem[]> {
  return get<DictItem[]>(`/system/dict/items/${dictType}`);
}
