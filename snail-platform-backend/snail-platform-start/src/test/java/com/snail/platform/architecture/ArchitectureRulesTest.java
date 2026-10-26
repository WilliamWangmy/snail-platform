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
import java.util.Set;

/**
 * 职责:一页纸开发约定的可执行化身——平台纪律的唯一机械裁判，约定与规则必须同步改。
 * 上游:docs/development-conventions.md 的各条纪律;下游:mvn package 常规构建即拦截，无需额外开关。
 * 口径:跨子域只放行登记在案的存量边(数据权限读角色、用户前置校验读组织/角色/租户、开通复制种子)，
 * 新边必须先补进白名单并说明理由——"先改名单再改代码"，这就是防杂乱的闸。
 * auth→system 是唯一跨模块边，模块级依赖由 Maven POM 显式声明，子域级白名单对它整体放行；
 * 防"第二条边悄悄长出来"靠约定 §9.6 的人工裁决。
 */
@AnalyzeClasses(packages = "com.snail.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String SYSTEM = "com.snail.platform.function.system.";
    private static final String SYSTEM_API = SYSTEM + "api";
    private static final String AUTH = "com.snail.platform.function.auth";
    private static final String NOTIFY = "com.snail.platform.function.notify";
    private static final String STORAGE = "com.snail.platform.function.storage";

    /**
     * 对 system 依赖强制只经 api 包的模块:notify 是第一个按"跨模块只经 api 包"目标态写的模块,
     * 后续模块按同款规则逐个登记;auth 的整体豁免与 storage 的豁免是另一笔登记在案的债务。
     * storage 暂不列入:它直读 sys_tenant 的 SysTenantMapper 取存储配额,与 auth 同款登记例外
     * (storage→tenant 的单一只读边),变密时收敛 system.api 包,再补进本清单。
     */
    private static final Set<String> API_PACKAGE_ENFORCED = Set.of(NOTIFY);

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
            "tenant->org", "tenant->role", "tenant->user");

    /** MP BaseMapper 全限定名:审计填充只认实体,方法级写库纪律按它的方法名判 */
    private static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";

    /** MP BaseMapper 的写方法名 */
    private static final Set<String> MAPPER_WRITE_METHODS =
            Set.of("insert", "update", "updateById", "delete", "deleteById", "deleteByMap", "insertOrUpdate", "upsert");

    /** 全部纪律一次执行:装配纪律(事务/启动类/框架回望/分页单点)、跨子域白名单、契约不互借、散参阈值 */
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

            owner.getDirectDependenciesFromSelf().forEach(dependency ->
                    checkCrossSubdomain(owner, dependency, violations));

            // 按"跨模块只经 api 包"目标态写的模块(见 API_PACKAGE_ENFORCED 注释)机械执法
            if (API_PACKAGE_ENFORCED.stream().anyMatch(ownerPackage::startsWith)) {
                owner.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (targetPackage.startsWith(SYSTEM) && !targetPackage.startsWith(SYSTEM_API)) {
                        violations.add(owner.getFullName() + " -> "
                                + dependency.getTargetClass().getFullName()
                                + ":跨模块对 system 的依赖只准经 api 包(目标态 §3)");
                    }
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
                owner.getMethods().forEach(method ->
                        checkMapperWritesInsideTransaction(owner, method, violations));
            }
        }

        if (!violations.isEmpty()) {
            throw new AssertionError("架构纪律被突破:\n" + String.join("\n", violations));
        }
    }

    /**
     * 方法级事务纪律:service 方法里出现两处以上 BaseMapper 写调用(insert/update/delete 系列)时,
     * 该方法自身或所在类必须标 @Transactional。单条写语句自提交即原子,不必挂事务;
     * 两处以上才有"前半提交、后半失败"的半态——这正是类级注解挡不住的口子(某个公开写方法被直调时,
     * 类上的注解并不会给它开事务)。
     */
    private static void checkMapperWritesInsideTransaction(JavaClass owner, JavaMethod method,
                                                           Set<String> violations) {
        if (isTransactional(method)) {
            return;
        }
        long writes = method.getCallsFromSelf().stream().filter(ArchitectureRulesTest::isMapperWrite).count();
        if (writes >= 2) {
            violations.add(method.getFullName() + ":方法内 " + writes
                    + " 处写库调用却无 @Transactional(约定 §3:多写必须成事务)");
        }
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

    /** 子域宿主段:system 取模块名后第一段(user/org...)，auth 模块整体视为一个子域；其余(start/framework)不参与 */
    private static String subdomainOf(String packageName) {
        if (packageName.startsWith(SYSTEM)) {
            String rest = packageName.substring(SYSTEM.length());
            int dot = rest.indexOf('.');
            return dot < 0 ? rest : rest.substring(0, dot);
        }
        return packageName.equals(AUTH) || packageName.startsWith(AUTH + ".") ? "auth" : null;
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

    private static boolean isImplementationLayer(String targetPackage) {
        return targetPackage.endsWith(".mapper") || targetPackage.endsWith(".entity")
                || targetPackage.endsWith(".service") || targetPackage.endsWith(".converter")
                || targetPackage.endsWith(".sink") || targetPackage.endsWith(".controller");
    }

    private static boolean edgeAllowed(String from, String to) {
        if ("auth".equals(from)) {
            return true;
        }
        return ALLOWED_SYSTEM_EDGES.contains(from + "->" + to);
    }
}
