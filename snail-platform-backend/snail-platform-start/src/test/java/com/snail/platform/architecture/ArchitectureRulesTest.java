package com.snail.platform.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 职责:一页纸开发约定的可执行化身——平台纪律的唯一机械裁判，约定与规则必须同步改。
 * 上游:docs/development-conventions.md 的各条纪律;下游:mvn package 常规构建即拦截，无需额外开关。
 * 口径:跨子域只放行登记在案的存量边(数据权限读角色、用户前置校验读组织/角色/租户、开通复制种子,
 * 以及 workflow 里的"模型读模板""办理与撤销把结果交给派发单点""实例域复用台账与提醒出口"),
 * 新边必须先补进白名单并说明理由——"先改名单再改代码"，这就是防杂乱的闸。
 * 跨模块边不看子域看模块(moduleOf):同模块的子域互访归上面的白名单管，只有落到别的模块才要求 api 包。
 * system 与 workflow 两个模块的子域都参与这条判定;后建的模块按目标态直接入网,不再留豁免。
 * auth→system 是唯一跨模块边，模块级依赖由 Maven POM 显式声明，子域级白名单对它整体放行；
 * 防"第二条边悄悄长出来"靠约定 §9.6 的人工裁决。
 * 引擎两条新闸:Flowable 词汇关在 framework-workflow 里，引擎租户旁路只准租户桥调用——
 * 两者都是"绕一次就静默失效"的隔离，编译期不拦就没人拦。
 */
@AnalyzeClasses(packages = "com.snail.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String SYSTEM = "com.snail.platform.function.system.";
    private static final String AUTH = "com.snail.platform.function.auth";
    private static final String NOTIFY = "com.snail.platform.function.notify";
    private static final String EXTERNAL = "com.snail.platform.function.external";
    private static final String STORAGE = "com.snail.platform.function.storage";
    private static final String WORKFLOW = "com.snail.platform.function.workflow.";

    /**
     * 第三方 SDK 包前缀:厂商/协议 SDK 只准住在 external 网关(设计 §8)——
     * "所有第三方接口都经过这个模块"的机械形态;新厂商接入把前缀加进本清单。
     * storage 的对象存储 SDK 暂列豁免之外的例外:framework-storage 的抽象自成一体,
     * 收编进网关是登记在案的独立决策,拍板前不堵它的存量路径。
     */
    private static final List<String> THIRD_PARTY_SDK_PACKAGES = List.of(
            "org.springframework.mail.", "jakarta.mail.",
            "com.aliyun.", "com.tencentcloudapi.", "com.amap.");

    /**
     * 流程引擎包前缀:引擎词汇只准出现在 framework-workflow 内(方案 §2 边界)。
     * 业务模块要用流程,只能经 function-workflow 的 WorkflowApi——Flowable 的类型一旦渗进 service,
     * 租户过滤、身份注入、变量白名单这三道闸门就有绕过路径,而这类绕过在编译期看不出来。
     */
    private static final String FLOWABLE = "org.flowable.";

    /** 引擎门面的宿主包:除它以外任何类引用 Flowable 都是边界突破 */
    private static final String WORKFLOW_FRAMEWORK = "com.snail.platform.framework.workflow.";

    /** 租户旁路的唯一合法调用包:runAsEngineTenant 是约定 §6 的第四类旁路,只准租户桥用 */
    private static final String WORKFLOW_TENANT_BRIDGE = "com.snail.platform.framework.workflow.tenant";

    /** 登录租户上下文持有器全限定名:旁路方法按宿主类点名,不靠方法名撞库 */
    private static final String TENANT_CONTEXT_HOLDER =
            "com.snail.platform.framework.core.context.TenantContextHolder";

    /**
     * 按"跨模块只经 api 包"目标态写的模块:notify 是第一个,workflow 从建模块起就按目标态写
     * (办理人显示名只经 UserBriefPort、通知只经 NotifyApi),两家的出边都被本规则钉在 api 包上。
     * auth 的整体豁免与 storage 的豁免是另一笔登记在案的债务。
     * storage 暂不列入:它直读 sys_tenant 的 SysTenantMapper 取存储配额,与 auth 同款登记例外
     * (storage→tenant 的单一只读边),变密时收敛 system.api 包,再补进本清单。
     */
    private static final Set<String> API_PACKAGE_ENFORCED = Set.of(NOTIFY, EXTERNAL, AUTH, WORKFLOW);

    /** 功能模块包根:跨模块依赖的判定范围,落在此根内且不是目标模块 api 包的引用即为越界 */
    private static final String FUNCTION_ROOT = "com.snail.platform.function.";

    /** MP 分页对象全限定名:业务层禁止直接 new，分页只能经 framework-db 的 PageAssembler 单点装配 */
    private static final String MP_PAGE = "com.baomidou.mybatisplus.extension.plugins.pagination.Page";

    /** system 内登记在案的跨子域读边:引用方子域 -> 被引子域。
     * user->role 数据权限判定与授权读写;user->org/user->tenant 归属与上限校验;role->user 删角色引用计数;
     * role->org 档位三取子树(只准经 OrgQueryService 的缓存只读方法);role->menu 解析权限码与授权维护;
     * org->role 组织写侧的可见性闸门(消费 DataScopeService 的判定,不反向产谓词);
     * org->user 删组织挂人前置检查;tenant->* 开通复制种子 */
    private static final Set<String> ALLOWED_SYSTEM_EDGES = Set.of(
            "user->role", "user->org", "user->tenant",
            "role->user", "role->org", "role->menu",
            "org->user", "org->role",
            "tenant->org", "tenant->role", "tenant->user",
            // tenant->log 租户详情统计的分端登录回填(租户管理增强 §5.6:usage-stats lazy 自愈
            // 按 sys_login_log 事实源回填,单条只读 SELECT;先改名单再改代码)
            "tenant->log");

    /**
     * workflow 内登记在案的跨子域边:
     * model->template 只有"从模板建模型"这一条读边(取上架模板原文);
     * task->support 与 instance->support 是办理收尾与撤销把结果交给回调派发单点(单点装配,别处不准自建派发);
     * instance->task 是发起与撤销复用审批台账写入与待办提醒出口——两者同住 task/,不另开第二套留痕与通知。
     */
    private static final Set<String> ALLOWED_WORKFLOW_EDGES =
            Set.of("model->template", "task->support", "instance->support", "instance->task");

    /** MP BaseMapper 全限定名:审计填充只认实体,方法级写库纪律按它的方法名判 */
    private static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";

    /** MP BaseMapper 的写方法名 */
    private static final Set<String> MAPPER_WRITE_METHODS =
            Set.of("insert", "update", "updateById", "delete", "deleteById", "deleteByMap", "insertOrUpdate", "upsert");

    /**
     * last() 白名单:该方法把原文拼进 SQL 末尾,属注入面——只准已登记的使用,
     * 且参数必须是编译期常量("FOR UPDATE"/"LIMIT " + 静态常量);新增使用先来这儿登记再写代码。
     * 日志清除(OperLog/LoginLog/ExternalCallLog 三个 clean)用 "LIMIT " + CLEAN_BATCH 分批删大表,
     * 与导出的 LIMIT 口径同源:常量在各自 Service 内单点定义。
     */
    private static final Set<String> LAST_ALLOWED = Set.of(
            "com.snail.platform.function.storage.service.FileQuotaService",
            "com.snail.platform.function.system.log.service.LoginLogService",
            "com.snail.platform.function.system.log.service.OperLogService",
            "com.snail.platform.function.system.user.service.UserService",
            "com.snail.platform.function.external.service.ExternalCallLogService");

    /** slf4j Logger 的输出口径方法名:落 password 字段的日志都从这些口出去 */
    private static final Set<String> LOGGER_METHODS =
            Set.of("trace", "debug", "info", "warn", "error");

    /** 全部纪律一次执行:装配纪律(事务/启动类/框架回望/分页单点)、跨子域白名单、契约不互借、散参阈值、引擎边界与租户旁路 */
    @ArchTest
    static void allRules(JavaClasses classes) {
        Set<String> violations = new LinkedHashSet<>();

        for (JavaClass owner : classes) {
            String ownerPackage = owner.getPackageName();
            boolean inServiceLayer = ownerPackage.startsWith("com.snail.platform.function")
                    && ownerPackage.contains(".service");

            if (ownerPackage.startsWith("com.snail.platform.framework") && declaresTransaction(owner)) {
                violations.add(owner.getFullName() + ":framework 层出现 @Transactional(约定 §5:横切只能单点装配)");
            }
            if (!ownerPackage.equals("com.snail.platform")
                    && owner.isAnnotatedWith(SpringBootApplication.class)) {
                violations.add(owner.getFullName() + ":启动类只准住在 start 根包(约定 §1)");
            }
            if (declaresTransaction(owner) && !inServiceLayer) {
                violations.add(owner.getFullName() + ":@Transactional 只允许出现在 service 包(约定 §3)");
            }

            if (owner.isAnnotatedWith(RestController.class)) {
                for (JavaMethod method : owner.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        continue;
                    }
                    if (isMapped(method) && !method.isAnnotatedWith(PreAuthorize.class)) {
                        violations.add(method.getFullName() + ":公开端点缺 @PreAuthorize(权限全量收口)");
                    }
                    if (isMapped(method) && returnsEntity(method)) {
                        violations.add(method.getFullName()
                                + ":Controller 出参引用 .entity 持久化对象,只准出 VO(契约不外泄)");
                    }
                    long count = method.getParameterAnnotations().stream()
                            .filter(params -> params.stream().map(JavaAnnotation::getRawType)
                                    .anyMatch(raw -> raw.getName().equals(RequestParam.class.getName())))
                            .count();
                    if (count > 3) {
                        violations.add(method.getFullName() + ":@RequestParam 散参 " + count
                                + " 个，>3 必须收敛为 XxxQuery(约定 §7)");
                    }
                }
            }

            owner.getMethodCallsFromSelf().forEach(call -> {
                // last() 注入面:仅白名单类可用,且参数须为编译期常量(人工纪律,名单锁范围)
                if (call.getName().equals("last") && !LAST_ALLOWED.contains(owner.getFullName())) {
                    violations.add(owner.getFullName() + " -> " + call.getTarget().getFullName()
                            + ":last() 把原文拼进 SQL 末尾,属注入面,只准白名单类以编译期常量使用(约定 §7)");
                }
                if (isPasswordLogging(owner, call)) {
                    violations.add(owner.getFullName() + ":日志输出方法出现在触及 password 字段的方法 "
                            + call.getOrigin().getName() + " 内(口令禁止进日志)");
                }
            });

            owner.getDirectDependenciesFromSelf().forEach(dependency ->
                    checkCrossSubdomain(owner, dependency, violations));

            // 第三方 SDK 边界:厂商/协议 SDK 只准住在 external 网关(外部网关设计 §8)
            violations.addAll(thirdPartySdkViolations(owner));

            // 引擎边界:Flowable 类型只准住在 framework-workflow(方案 §2、§9 裁决 D)
            if (!ownerPackage.startsWith(WORKFLOW_FRAMEWORK)) {
                owner.getDirectDependenciesFromSelf().forEach(dependency -> {
                    if (dependency.getTargetClass().getPackageName().startsWith(FLOWABLE)) {
                        violations.add(owner.getFullName() + " -> "
                                + dependency.getTargetClass().getFullName()
                                + ":引擎词汇越出 framework-workflow(约定 §1:业务只经流程 API 门面)");
                    }
                });
            }

            // 引擎租户旁路:第四个合法旁路口径,调用点锁死在租户桥,别处出现即跨租户读写
            if (!ownerPackage.startsWith(WORKFLOW_TENANT_BRIDGE)) {
                owner.getMethodCallsFromSelf().forEach(call -> {
                    if (call.getName().equals("runAsEngineTenant")
                            && call.getTargetOwner().getFullName().equals(TENANT_CONTEXT_HOLDER)) {
                        violations.add(owner.getFullName() + ":调用 runAsEngineTenant 越出租户桥"
                                + "(约定 §6:引擎租户旁路只准 WorkflowTenantBridge 使用)");
                    }
                });
            }

            // 按"跨模块只经 api 包"目标态写的模块(见 API_PACKAGE_ENFORCED 注释)机械执法。
            // 同模块内的子域互访不归这条管,由 checkCrossSubdomain 的白名单边判定。
            if (API_PACKAGE_ENFORCED.stream().anyMatch(ownerPackage::startsWith)) {
                String ownerModule = moduleOf(ownerPackage);
                owner.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (!targetPackage.startsWith(FUNCTION_ROOT)
                            || ownerModule.equals(moduleOf(targetPackage))
                            || isModuleApiPackage(targetPackage)
                            || isWholeModuleEdge(ownerModule, moduleOf(targetPackage))) {
                        return;
                    }
                    violations.add(owner.getFullName() + " -> "
                            + dependency.getTargetClass().getFullName()
                            + ":跨模块依赖只准落到目标模块的 api 包(目标态 §3)");
                });
            }

            if (ownerPackage.startsWith("com.snail.platform.function")) {
                owner.getConstructorCallsFromSelf().forEach(call -> {
                    if (call.getTarget().getOwner().getFullName().equals(MP_PAGE)) {
                        violations.add(owner.getFullName() + ":业务层直接构造 MP 分页对象，"
                                + "必须走 PageAssembler.from 单点装配(约定 §7)");
                    }
                });
            }

            if (inServiceLayer) {
                checkServiceWritesAreTransactional(owner, violations);
            }
        }

        if (!violations.isEmpty()) {
            throw new AssertionError("架构纪律被突破:\n" + String.join("\n", violations));
        }
    }

    /**
     * 第三方 SDK 边界检查:external 模块之外的类依赖厂商/协议 SDK 即违规。
     * 独立成方法以便样例测试直接对手工导入的类集复用(违规样例类住测试源码,
     * 主规则按 DoNotIncludeTests 跑不会看见,这里用类文件导入器定点喂给它)。
     */
    static List<String> thirdPartySdkViolations(JavaClasses classes) {
        List<String> violations = new java.util.ArrayList<>();
        for (JavaClass owner : classes) {
            violations.addAll(thirdPartySdkViolations(owner));
        }
        return violations;
    }

    /** 单类形态:owner 在 external 模块内豁免,否则任一依赖落到 SDK 包前缀即立案 */
    private static List<String> thirdPartySdkViolations(JavaClass owner) {
        List<String> violations = new java.util.ArrayList<>();
        if (owner.getPackageName().startsWith(EXTERNAL)) {
            return violations;
        }
        owner.getDirectDependenciesFromSelf().forEach(dependency -> {
            String targetPackage = dependency.getTargetClass().getPackageName();
            if (THIRD_PARTY_SDK_PACKAGES.stream().anyMatch(targetPackage::startsWith)) {
                violations.add(owner.getFullName() + " -> " + dependency.getTargetClass().getFullName()
                        + ":第三方 SDK 依赖越出 external 网关(外部网关设计 §8)");
            }
        });
        return violations;
    }

    /**
     * 方法级事务纪律:service 方法里出现两处以上 BaseMapper 写调用(insert/update/delete 系列)时,
     * 该方法自身或所在类必须标 @Transactional。单条写语句自提交即原子,不必挂事务;
     * 两处以上才有"前半提交、后半失败"的半态——这正是类级注解挡不住的口子(某个公开写方法被直调时,
     * 类上的注解并不会给它开事务)。
     * 私有/静态helper 自己不立案:Spring 代理进不去,它压根不是事务入口,写句只会落在调用方的事务里;
     * 但要求它的每一个同类的调用方都被事务覆盖(是事务入口,或同样被覆盖的helper)——
     * 这样既不会冤枉 replaceRoles 这类被两个 @Transactional 入口共用的helper,
     * 也不给"公开非事务方法绕一层私有方法写两笔"留漏网口子。
     */
    private static void checkServiceWritesAreTransactional(JavaClass owner, Set<String> violations) {
        for (JavaMethod method : owner.getMethods()) {
            if (isTransactional(method)) {
                continue;
            }
            long writes = method.getCallsFromSelf().stream().filter(ArchitectureRulesTest::isMapperWrite).count();
            if (writes < 2) {
                continue;
            }
            if (isProxyEntry(method) || !callersCovered(owner, method, new LinkedHashSet<>())) {
                violations.add(method.getFullName() + ":方法内 " + writes
                        + " 处写库调用却无 @Transactional(约定 §3:多写必须成事务)");
            }
        }
    }

    /** 该方法的每个同类调用方是否都已落在某个事务边界内 */
    private static boolean callersCovered(JavaClass owner, JavaMethod callee, Set<JavaMethod> visiting) {
        if (!visiting.add(callee)) {
            return true;
        }
        for (JavaMethod caller : owner.getMethods()) {
            boolean callsCallee = caller.getCallsFromSelf().stream().anyMatch(call ->
                    call.getTargetOwner().equals(owner)
                            && call.getName().equals(callee.getName())
                            && call.getTarget().getRawParameterTypes().size()
                                    == callee.getRawParameterTypes().size());
            if (!callsCallee) {
                continue;
            }
            // 调用方是代理入口却没标事务,或它也是没被覆盖的helper,这条链就漏了
            if (!isTransactional(caller)
                    && (isProxyEntry(caller) || !callersCovered(owner, caller, visiting))) {
                return false;
            }
        }
        return true;
    }

    /** 代理入口:能被 Spring AOP 拦到的方法才谈得上"自己就是事务边界" */
    private static boolean isProxyEntry(JavaMethod method) {
        return !method.getModifiers().contains(JavaModifier.PRIVATE)
                && !method.getModifiers().contains(JavaModifier.STATIC);
    }

    /** 是否是对 MP BaseMapper(或其业务子接口)的写调用 */
    private static boolean isMapperWrite(com.tngtech.archunit.core.domain.JavaCall<?> call) {
        if (!MAPPER_WRITE_METHODS.contains(call.getTarget().getName())) {
            return false;
        }
        String targetOwner = call.getTarget().getOwner().getFullName();
        return targetOwner.equals(BASE_MAPPER) || call.getTarget().getOwner().isAssignableTo(BASE_MAPPER);
    }

    /** 方法级或所在类级的 @Transactional 都算数:类级注解对整个类的方法生效,是 Spring 的语义 */
    private static boolean isTransactional(JavaMethod method) {
        return method.isAnnotatedWith(Transactional.class)
                || method.getOwner().isAnnotatedWith(Transactional.class);
    }

    /**
     * 类级或任一方法级 @Transactional 都算"这个类声明了事务"。Spring 里方法级注解同样生效,
     * 只认类级会让两条包位纪律永不命中(整改线 Phase 5 修正 P1-2:原规则一半是空转)。
     */
    private static boolean declaresTransaction(JavaClass owner) {
        if (owner.isAnnotatedWith(Transactional.class) || owner.isMetaAnnotatedWith(Transactional.class)) {
            return true;
        }
        return owner.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(Transactional.class)
                || method.isMetaAnnotatedWith(Transactional.class));
    }

    /**
     * 跨子域三条禁令:dto 契约任何方向都不互借；实现面只准走白名单边；框架类不得引用业务类
     */
    private static void checkCrossSubdomain(JavaClass owner,
                                            com.tngtech.archunit.core.domain.Dependency dependency,
                                            Set<String> violations) {
        String targetPackage = dependency.getTargetClass().getPackageName();
        if (owner.getPackageName().startsWith("com.snail.platform.framework")
                && targetPackage.startsWith("com.snail.platform.function")) {
            violations.add(owner.getFullName() + " -> " + dependency.getTargetClass().getFullName()
                    + ":框架层回望业务层(约定 §1)");
            return;
        }
        String from = subdomainOf(owner.getPackageName());
        String to = subdomainOf(targetPackage);
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        String description = owner.getFullName() + " -> " + dependency.getTargetClass().getFullName();
        if (targetPackage.endsWith(".dto")) {
            violations.add(description + ":借用其它子域的 dto 契约(约定 §3)");
        } else if (isImplementationLayer(targetPackage) && !edgeAllowed(from, to)) {
            violations.add(description + ":跨子域边 " + from + "->" + to + " 未登记白名单(约定 §3)");
        }
    }

    /** 子域宿主段:system 与 workflow 取模块名后第一段(user/org、model/template...),auth 模块整体视为一个子域;其余(start/framework)不参与 */
    private static String subdomainOf(String packageName) {
        if (packageName.startsWith(SYSTEM)) {
            return firstSegment(packageName.substring(SYSTEM.length()));
        }
        if (packageName.startsWith(WORKFLOW)) {
            return firstSegment(packageName.substring(WORKFLOW.length()));
        }
        return packageName.equals(AUTH) || packageName.startsWith(AUTH + ".") ? "auth" : null;
    }

    /** 取子域宿主段的第一段;模块根包(无子段)不参与判定 */
    private static String firstSegment(String rest) {
        if (rest.isEmpty()) {
            return null;
        }
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /**
     * 功能模块段:函数模块根包后的第一段(system / auth / notify / storage / workflow...)。
     * 跨模块判定要用它,不能用包前缀——workflow.model 与 workflow.template 前缀不同却是同一模块,
     * 按前缀判会把模块内的子域互访误报成跨模块越界。
     */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(FUNCTION_ROOT)) {
            return null;
        }
        return firstSegment(packageName.substring(FUNCTION_ROOT.length()));
    }

    /**
     * 目标包是否落在某个功能模块的 api 包(如 system.api、notify.api)——跨模块依赖的唯一合法落点。
     * 判定按包形态而不是枚举 api 常量:模块还会长出第三个、第四个 api 包,规则不该跟着改。
     */
    private static boolean isModuleApiPackage(String packageName) {
        String rest = packageName.substring(FUNCTION_ROOT.length());
        return rest.endsWith(".api") || rest.contains(".api.");
    }

    /**
     * 整模块放行的存量边:auth→system 是 POM 显式声明的唯一跨模块边(用户/租户/登录日志三张表的
     * PO/Mapper 归 system),子域级白名单对它整体放行(约定 §9.6);此豁免不再扩员,
     * 新边(如 auth→external)一律只准落 api 包。
     */
    private static boolean isWholeModuleEdge(String ownerModule, String targetModule) {
        return "auth".equals(ownerModule) && "system".equals(targetModule);
    }

    /** 是否为 MVC 映射方法:每个映射端点都必须有 @PreAuthorize,公开端点写 permitAll */
    private static boolean isMapped(JavaMethod method) {
        return method.isAnnotatedWith(GetMapping.class)
                || method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(RequestMapping.class);
    }

    /** 出参全类型(含泛型实参)里是否出现 .entity 持久化对象——R&lt;PageResult&lt;SysFile&gt;&gt; 也逃不掉 */
    private static boolean returnsEntity(JavaMethod method) {
        return method.getReturnType().getAllInvolvedRawTypes().stream()
                .anyMatch(raw -> raw.getPackageName().endsWith(".entity"));
    }

    /**
     * 口令进日志的启发式闸:同一方法内既触及名字含 password 的字段又调用日志输出口,
     * 十有八九是把口令拼进了日志——逐例人工复核的信号灯,宁误报不放行。
     */
    private static boolean isPasswordLogging(JavaClass owner, com.tngtech.archunit.core.domain.JavaMethodCall call) {
        String target = call.getTarget().getFullName();
        boolean logOutput = (target.startsWith("org.slf4j.Logger") && LOGGER_METHODS.contains(call.getName()))
                || target.startsWith("java.io.PrintStream.");
        if (!logOutput) {
            return false;
        }
        return call.getOrigin().getFieldAccesses().stream().anyMatch(access ->
                access.getTarget().getName().toLowerCase(java.util.Locale.ROOT).contains("password"));
    }

    private static boolean isImplementationLayer(String targetPackage) {
        return targetPackage.endsWith(".mapper") || targetPackage.endsWith(".entity")
                || targetPackage.endsWith(".service") || targetPackage.endsWith(".converter")
                || targetPackage.endsWith(".sink") || targetPackage.endsWith(".controller");
    }

    private static boolean edgeAllowed(String from, String to) {
        if ("auth".equals(from)) {
            return true;
        }
        String edge = from + "->" + to;
        return ALLOWED_SYSTEM_EDGES.contains(edge) || ALLOWED_WORKFLOW_EDGES.contains(edge);
    }
}
