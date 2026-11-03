import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from './App.vue';
import router from './router';

import 'element-plus/theme-chalk/dark/css-vars.css';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import '@/styles/flow-ui.css';
import '@/styles/flow-el-theme.css';
import '@/styles/index.scss';

// 大屏深色主题：html 挂 dark class 激活 Element 暗色变量
document.documentElement.classList.add('dark');

const app = createApp(App);

app.use(createPinia());
app.use(router);

app.mount('#app');
