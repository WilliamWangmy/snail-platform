import dayjs from 'dayjs';

/**
 * 职责:后端数据到展示态的唯一换算口——ISO 局部日期时间与字节大小,页面不得各自 cut/split/除 1024。
 * 上游:各列表/详情时间与大小列;下游:dayjs。
 * 口径:空值一律破折号;解析失败原样回显,不掩盖后端数据形态。
 */
export function dateTime(value?: string | null): string {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/** 二进制千进单位,与操作系统文件管理器一致。 */
const SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** 字节数到人类可读大小;空值破折号,B 级不带小数,其余保留一位。 */
export function fileSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined) return '—';
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < SIZE_UNITS.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${SIZE_UNITS[unitIndex]}`;
}
