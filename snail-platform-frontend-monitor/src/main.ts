import { createPinia } from 'pinia';
import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

import App from './App.vue';
import router from './router';
import { permission } from './directives/permission';

import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles/index.scss';

import { useThemeStore } from '@/stores/theme';

const app = createApp(App);

app.use(createPinia());
app.use(router);
// 全量注册(与 admin 同式):模板 el-* 组件直接可用;权限指令按钮级隐藏
app.use(ElementPlus, { locale: zhCn });
app.directive('permission', permission);

// 主题:模块加载即着色(theme store 副作用),这里激活 pinia 便于组件取用
useThemeStore();

app.mount('#app');
