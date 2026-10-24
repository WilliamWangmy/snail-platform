/** 后端唯一响应信封 R{code,message,data},成功 code=0。 */
export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 分页载体,塞在 Envelope.data 里,页码从 1 起。 */
export interface PageResult<T> {
  rows: T[];
  total: number;
}

/** 分页入参基座,与各 Query record 的 pageNum/pageSize 对齐。 */
export interface PageParams {
  pageNum?: number;
  pageSize?: number;
}
