import dayjs from 'dayjs';

/**
 * 职责:后端 ISO 局部日期时间串到展示态的唯一换算口,页面不得各自 cut/split 字符串。
 * 上游:各列表/详情时间列;下游:dayjs。
 * 口径:空值一律破折号;解析失败原样回显,不掩盖后端数据形态。
 */
export function dateTime(value?: string | null): string {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}
