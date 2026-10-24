import 'vue-router';

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录可达 */
    public?: boolean;
    /** 页面标题,同时用于文档标题与面包屑末级 */
    title?: string;
    /** 侧栏图标名(AppIcon) */
    icon?: string;
    /** 祖先链标题,面包屑用 */
    breadcrumb?: string[];
  }
}
