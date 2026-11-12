import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import App from './App.vue';
import router from '@/router';
import { permission } from '@/directives/permission';
import '@/styles/index.css';
/* 副作用引入:模块加载即按 localStorage 已存偏好着色,登录页与任意首屏都不会闪默认浅色。 */
import '@/stores/theme';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });
app.directive('permission', permission);
app.mount('#app');
