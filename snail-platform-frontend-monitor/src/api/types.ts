/** 后端统一响应信封 R{code,message,data}。 */
export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 分页出参 PageResult{rows,total}。 */
export interface PageResult<T> {
  rows: T[];
  total: number;
}
