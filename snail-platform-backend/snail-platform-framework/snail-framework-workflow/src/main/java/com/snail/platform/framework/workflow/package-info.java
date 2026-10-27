/**
 * 职责:Flowable 流程引擎接入层——引擎装配、统一网关、租户桥、BPMN 安全校验的唯一归属地。
 * 上游:snail-platform-start 的装配与配置;下游:Flowable 8 process 引擎。
 * 约束:全仓只有本构件允许 import org.flowable.*;不建 ACT_* Mapper,不提供 REST,不依赖 function 模块。
 */
package com.snail.platform.framework.workflow;
