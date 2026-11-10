/**
 * 顶栏看板菜单的唯一配置源。
 * side 决定落在中央台的左翼还是右翼;built 为 false 的看板只登记了入口,
 * 大屏当前没有任何业务接口,点击给出「建设中」提示,不渲染任何占位数据。
 */
export interface BoardTab {
  key: 'flight' | 'flow' | 'admin' | 'device';
  label: string;
  side: 'left' | 'right';
  built: boolean;
}

export const boards: BoardTab[] = [
  { key: 'flight', label: '飞行看板', side: 'left', built: true },
  { key: 'flow', label: '流程看板', side: 'left', built: false },
  { key: 'admin', label: '管理看板', side: 'right', built: false },
  { key: 'device', label: '设备看板', side: 'right', built: false },
];
