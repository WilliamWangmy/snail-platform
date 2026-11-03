import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from './App.vue';
import router from './router';

import 'element-plus/theme-chalk/dark/css-vars.css';
// ElMessage / ElMessageBox 为函数式调用，unplugin 不会注入样式，需手动引入
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import '@/styles/flow-ui.css';
import '@/styles/flow-el-theme.css';
import '@/styles/index.scss';

import { initTheme } from '@/utils/theme';

// 主题：localStorage 记忆，缺省深色；html 挂 dark/light class 分别激活两套变量
initTheme();

const app = createApp(App);

app.use(createPinia());
app.use(router);

app.mount('#app');
