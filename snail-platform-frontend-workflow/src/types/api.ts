/**
 * 与后端 snail-framework-core 的 R / PageResult / PageQuery 一一镜像。
 * 后端字段变更时对照修改即可，禁止在本文件之外再定义响应信封。
 */

/** 统一响应信封：code=0 成功，非 0 为业务码（message 可直接展示） */
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

/** 全库唯一成功码（R.SUCCESS 镜像） */
export const SUCCESS_CODE = 0;

/** 分页数据载体：塞在 R 的 data 里 */
export interface PageResult<T> {
  rows: T[];
  total: number;
}

/** 分页查询入参：pageNum 从 1 起，pageSize 上限 200 */
export interface PageQueryParam {
  pageNum?: number;
  pageSize?: number;
}
