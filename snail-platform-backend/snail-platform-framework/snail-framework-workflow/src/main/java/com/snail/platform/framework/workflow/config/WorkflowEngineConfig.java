package com.snail.platform.framework.workflow.config;

import java.util.Arrays;
import org.flowable.engine.ProcessEngineConfiguration;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 职责:流程引擎在平台侧的装配单点——把"引擎必须晚于 Flyway 建表"和"配置基线不许漂"两件事钉在启动期。
 * 上游:application.yml 的 flowable 段;下游:Flowable 自动配置出来的 processEngineConfiguration 等引擎构件。
 * 口径:建表归 Flyway(V4),引擎 database-schema-update=false(方案 §4.1、§9 裁决 C)。
 * 这个顺序不能靠 Boot 默认装配保证:Boot 只给自家 JPA/JDBC 构件挂 Flyway 前置依赖,
 * Flowable 的引擎配置 bean 不在其列——空库上真跑一次,引擎会赶在建表前校验版本行,抛
 * FlowableWrongDbException,看着像迁移没跑,实际是装配顺序。故在此显式补 dependsOn。
 * 引擎不许自动建表/升级:一旦开着,生产库结构就成了运行期副产物,与"迁移脚本只增不改"冲突,所以启动期断死。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineConfig {

    /** Boot 的 Flyway 执行器 bean 名:引擎装配必须排在它之后 */
    private static final String FLYWAY_INITIALIZER = "flywayInitializer";

    /** 基线:引擎不建表、不升级,只校验既有表 */
    private static final String SCHEMA_UPDATE_DISABLED = "false";

    /**
     * static 方法:BeanFactoryPostProcessor 必须在配置类实例化之前就绪,
     * 非 static 会让本配置类提前实例化、其余 @Bean 失效。
     */
    @Bean
    static BeanFactoryPostProcessor flowableSchemaOwnershipPostProcessor() {
        return beanFactory -> {
            String[] engineConfigs = engineConfigurationBeanNames(beanFactory);
            if (engineConfigs.length == 0) {
                // 引擎未装配(切片测试或本应用不启用流程),没有顺序要保证
                return;
            }
            if (!beanFactory.containsBeanDefinition(FLYWAY_INITIALIZER)) {
                throw new IllegalStateException("流程引擎已装配但容器里没有 " + FLYWAY_INITIALIZER
                        + ":引擎表的建表责任人缺失,拒绝启动——请确认 spring.flyway 未被禁用"
                        + "(建表归 Flyway V4,引擎不得自动建表)");
            }
            for (String beanName : engineConfigs) {
                appendFlywayDependency(beanFactory, beanName);
            }
        };
    }

    /**
     * 启动期配置基线校验:任何一项漂移都拒绝启动。
     * 放在这里而不是靠 review yml——配置漂移是静默的,而流程引擎的静默漂移代价是生产库结构被改。
     */
    @Bean
    public WorkflowEngineBaselineValidator workflowEngineBaselineValidator(
            ProcessEngineConfiguration processEngineConfiguration, Environment environment) {
        return new WorkflowEngineBaselineValidator(processEngineConfiguration, environment);
    }

    /** 容器里所有流程引擎配置 bean:按类型认不认名字,免得随 Boot/Flowable 的 bean 命名漂移 */
    private static String[] engineConfigurationBeanNames(ConfigurableListableBeanFactory beanFactory) {
        return Arrays.stream(beanFactory.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = beanFactory.getType(name);
                    return type != null && ProcessEngineConfiguration.class.isAssignableFrom(type);
                })
                .toArray(String[]::new);
    }

    /** 追加 dependsOn:原有依赖保留——别人的顺序保证不能被我抹掉 */
    private static void appendFlywayDependency(ConfigurableListableBeanFactory beanFactory, String beanName) {
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        String[] existing = definition.getDependsOn();
        if (existing == null) {
            definition.setDependsOn(new String[]{FLYWAY_INITIALIZER});
            return;
        }
        for (String dependency : existing) {
            if (FLYWAY_INITIALIZER.equals(dependency)) {
                return;
            }
        }
        String[] dependsOn = Arrays.copyOf(existing, existing.length + 1);
        dependsOn[existing.length] = FLYWAY_INITIALIZER;
        definition.setDependsOn(dependsOn);
    }

    /**
     * 职责:把方案 §4.1 的运行期口径固化成启动断言。
     * 上游:WorkflowEngineConfig 装配;下游:无(只读数,不改行为)。
     * 构造即校验:创建这个 bean 时引擎配置已就绪,校验不过就抛,容器起不来。
     */
    public static class WorkflowEngineBaselineValidator {

        WorkflowEngineBaselineValidator(ProcessEngineConfiguration processEngineConfiguration,
                                       Environment environment) {
            String schemaUpdate = processEngineConfiguration.getDatabaseSchemaUpdate();
            if (!SCHEMA_UPDATE_DISABLED.equals(schemaUpdate)) {
                throw new IllegalStateException("flowable.database-schema-update 必须是 false(建表归 Flyway V4),"
                        + "当前值: " + schemaUpdate + ",拒绝启动");
            }
            requireFalse(environment, "flowable.check-process-definitions",
                    "classpath 自动部署必须关闭:流程定义只能经网关按租户部署,否则租户归属无从谈起");
            requireFalse(environment, "flowable.idm.enabled",
                    "Flowable 自带身份必须关闭:用户与角色只认平台 sys_ 表,双身份源必然串号");
        }

        /** 显式 false 才算过关:缺属性说明基线不在主配置里,可能正被某个 profile 改动 */
        private static void requireFalse(Environment environment, String key, String why) {
            String value = environment.getProperty(key);
            if (!"false".equalsIgnoreCase(value)) {
                throw new IllegalStateException(key + " 必须显式配 false——" + why + ";当前值: " + value
                        + ",拒绝启动");
            }
        }
    }
}
