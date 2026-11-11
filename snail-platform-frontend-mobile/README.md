# snail-platform-frontend-mobile

多端移动应用骨架（uni-app + Vue 3 + TypeScript + wot-design-uni + Pinia），一套代码编译到：

- **H5**（手机 / 平板浏览器）
- **微信小程序**（含 iPad）
- **支付宝小程序**
- **iOS / Android App**

与 `snail-platform-frontend-admin` 同源约定：Vue 3.5 + TS 5.9 + pnpm 9 + node ≥ 22，请求层对齐后端统一信封 `{code, message, data}` 与 `Bearer` 鉴权。

## 快速开始

```bash
pnpm install
pnpm dev:h5            # H5 开发
pnpm dev:mp-weixin     # 微信小程序（产物在 dist/dev/mp-weixin，用微信开发者工具导入）
pnpm dev:mp-alipay     # 支付宝小程序
pnpm dev:app           # App（HBuilderX 导入 dist/dev/app 运行）
pnpm typecheck         # vue-tsc 类型检查
pnpm lint              # eslint
```

## 目录结构

```
src/
├── adapter/request.ts   # 唯一请求出口:信封解析、Bearer 头、401 统一回登录页
├── api/                 # 端无关 API 层(types 对齐 admin 的契约)
├── stores/auth.ts       # Pinia 会话:token 持久化(storage)、登录/登出
├── utils/token.ts       # uni storage 令牌存取
├── pages/login/         # 登录(账号密码 + 图形验证码,SSO 占位)
├── pages/index/         # 工作台(会话守卫 + 功能入口占位)
├── pages.json           # 路由 + wot-design-uni easycom
└── manifest.json        # 各端 appid/模块配置(发布前补 appid)
```

## 约定

1. **平台差异一律收敛在 `adapter/` 层**，业务代码禁止散落 `#ifdef` 条件编译。
2. **请求只走 `adapter/request.ts`**，业务代码不得直用 `uni.request`。
3. wot-design-uni 通过 easycom 自动引入，主色在 `App.vue` 的 `--wot-color-theme` 统一改（`#1D9E75`）。
4. 设计稿视觉基准见 `docs/demos/mobile-demo-0*.html` 三套 demo。

## 上线前置

- 补 `manifest.json` 各端 appid；小程序后台备案后端 API 域名（HTTPS）。
- `.env` 的 `VITE_API_BASE_URL` 按环境切换。
