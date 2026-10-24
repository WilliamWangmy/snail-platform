/** 菜单表存的是 Element Plus 图标名,工程用内联 SVG 图标集,这里做一次名称映射。 */
const ICON_MAP: Record<string, string> = {
  Setting: 'settings',
  Settings: 'settings',
  User: 'user',
  UserFilled: 'users',
  OfficeBuilding: 'columns',
  Collection: 'box',
  Key: 'lock',
  Document: 'receipt',
  Platform: 'grid',
  School: 'shield',
  Menu: 'menu',
};

export function menuIcon(name?: string | null): string {
  if (!name) return 'grid';
  return ICON_MAP[name] ?? 'grid';
}
