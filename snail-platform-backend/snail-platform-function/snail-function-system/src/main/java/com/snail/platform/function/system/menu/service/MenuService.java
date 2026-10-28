package com.snail.platform.function.system.menu.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.system.menu.dto.MenuCreateRequest;
import com.snail.platform.function.system.menu.dto.MenuNodeVO;
import com.snail.platform.function.system.menu.dto.MenuUpdateRequest;
import com.snail.platform.function.system.menu.entity.SysMenu;
import com.snail.platform.function.system.menu.entity.SysRoleMenu;
import com.snail.platform.function.system.menu.mapper.SysMenuMapper;
import com.snail.platform.function.system.menu.mapper.SysRoleMenuMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:全局菜单目录的平台维护面——目录树查询、新增、修改、删除;三元组(类型/展示端/归属面)与
 * path/component/perm_code 的搭配口径全部收在本服务,任何写入路径不得绕过。
 * 不可改口径:menu_type/target/scope/perm_code 建后不可改;内置节点不可删、不可停用;有下级不可删。
 * 缓存:menuTree 全量目录树随写失效;userPerms 因权限码/状态变化一并清空。
 * 上游:MenuController;下游:sys_menu 读写、sys_role_menu 删节点时清授权。
 */
@Service
public class MenuService {

    /** 类型:目录 */
    private static final int TYPE_DIR = 1;

    /** 类型:菜单 */
    private static final int TYPE_MENU = 2;

    /** 类型:按钮 */
    private static final int TYPE_BUTTON = 3;

    /** 展示端:站内 */
    private static final int TARGET_ADMIN = 1;

    /** 展示端:数据大屏 */
    private static final int TARGET_DASHBOARD = 2;

    /** 展示端:外链 */
    private static final int TARGET_EXTERNAL = 3;

    /** 归属面:平台 */
    private static final int SCOPE_PLATFORM = 1;

    /** 权限码三段式:面:模块:动作,动作段允许连字符 */
    private static final Pattern PERM_CODE = Pattern.compile(
            "^(platform|system):[a-z0-9]+(?:-[a-z0-9]+)*:[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** 外链地址:只允许 http/https,挡掉 javascript: 一类可执行 scheme 被当成链接渲染 */
    private static final Pattern EXTERNAL_URL = Pattern.compile("^https?://[^\\s/?#]+[^\\s]*$",
            Pattern.CASE_INSENSITIVE);

    /** 站内路由:绝对路径,允许前端路由参数的冒号段 */
    private static final Pattern INTERNAL_PATH = Pattern.compile("^/[^\\s]*$");

    /** 菜单目录读写 */
    private final SysMenuMapper menuMapper;

    /** 角色授权读,删菜单时同事务清引用 */
    private final SysRoleMenuMapper roleMenuMapper;

    public MenuService(SysMenuMapper menuMapper, SysRoleMenuMapper roleMenuMapper) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    /** 全量目录树(含平台面与停用节点),平台管理页配置用;树体量有限,不分页 */
    @Cacheable(cacheNames = "menuTree")
    public List<MenuNodeVO> tree() {
        List<SysMenu> all = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .orderByAsc(SysMenu::getSort).orderByAsc(SysMenu::getMenuId));
        // 平台配置面要看全量:停用链不剪(剪了就没法在原地把它改回启用),按钮也照列,与 currentRoutes 的口径相反
        // 两遍走:先摊平成 ID->节点,再挂 children,顺序直接继承查询的 sort 升序
        Map<Long, MenuNodeVO> nodes = new LinkedHashMap<>();
        for (SysMenu menu : all) {
            nodes.put(menu.getMenuId(), MenuNodeVO.of(menu));
        }
        List<MenuNodeVO> roots = new ArrayList<>();
        for (SysMenu menu : all) {
            MenuNodeVO node = nodes.get(menu.getMenuId());
            MenuNodeVO parent = menu.getParentId() == null ? null : nodes.get(menu.getParentId());
            // 取不到父的三种情形(本就无父 / 父已被删 / parentId 自指的脏数据)同按根层收口
            if (parent != null && !Objects.equals(parent.menuId(), node.menuId())) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    /**
     * 新增:parentId 缺省即根(记 0),先过父约束再判形态——面与展示端能不能这么挂,只有拿到父才知道。
     * path/component/permCode/icon/remark 一律 trimToNull 落库:库里只留 null 与有值两种形态,
     * validateShape 的必填判定和唯一键的空值语义才都对得上。
     * status 与 is_system 不进契约:建出来恒为正常、恒为非内置,内置标记只有种子能置。
     */
    @Transactional
    @CacheEvict(cacheNames = {"menuTree", "userPerms"}, allEntries = true)
    public Long create(MenuCreateRequest request) {
        long parentId = request.parentId() == null ? 0L : request.parentId();
        SysMenu parent = parentId == 0L ? null : requireMenu(parentId);
        validateParent(parent, request.menuType(), request.target(), request.scope());

        int visible = request.visible() == null ? 0 : request.visible();
        String path = trimToNull(request.path());
        String component = trimToNull(request.component());
        String permCode = trimToNull(request.permCode());
        validateShape(request.menuType(), request.target(), request.scope(), path, component, permCode, visible);

        SysMenu menu = new SysMenu();
        menu.setParentId(parentId);
        menu.setMenuName(request.menuName().trim());
        menu.setMenuType(request.menuType());
        menu.setTarget(request.target());
        menu.setScope(request.scope());
        menu.setPath(path);
        menu.setComponent(component);
        menu.setPermCode(permCode);
        menu.setIcon(trimToNull(request.icon()));
        menu.setSort(request.sort() == null ? 0 : request.sort());
        menu.setVisible(visible);
        menu.setStatus(0);
        menu.setIsSystem(0);
        menu.setRemark(trimToNull(request.remark()));
        try {
            menuMapper.insert(menu);
        } catch (DuplicateKeyException e) {
            // deleted 行仍占唯一键,权限码终身占用;数据库拦下时统一转业务拒绝
            throw BizException.badRequest("权限码已存在");
        }
        return menu.getMenuId();
    }

    /**
     * 修改:三元组与 perm_code 不在契约里,validateShape 用节点现值的三元组去配新 path/component,
     * 于是"不改类型就不许改成类型不允许的形态"。
     * "没传"有两副面孔:sort/visible/status 不传沿用现值,path/component/icon/remark 不传即清空——
     * 管理页本来就是整份回填后提交,清空是唯一说得通的语义。
     * 按钮的 visible 恒压回 0:它不入导航树,给了也没人消费。
     * 停用闸只在这条路拦(内置节点不可停用);停一个节点等于整棵子树撤出导航与权限解析,故 userPerms 一并全清。
     */
    @Transactional
    @CacheEvict(cacheNames = {"menuTree", "userPerms"}, allEntries = true)
    public void update(Long menuId, MenuUpdateRequest request) {
        SysMenu menu = requireMenu(menuId);
        int status = request.status() == null ? menu.getStatus() : request.status();
        if (Integer.valueOf(1).equals(menu.getIsSystem()) && status == 1) {
            throw BizException.badRequest("内置菜单不可停用");
        }
        int visible = request.visible() == null ? menu.getVisible() : request.visible();
        if (Objects.equals(menu.getMenuType(), TYPE_BUTTON)) {
            visible = 0;
        }
        String path = trimToNull(request.path());
        String component = trimToNull(request.component());
        validateShape(menu.getMenuType(), menu.getTarget(), menu.getScope(), path, component,
                menu.getPermCode(), visible);

        menuMapper.update(new SysMenu(), Wrappers.<SysMenu>lambdaUpdate()
                .set(SysMenu::getMenuName, request.menuName().trim())
                .set(SysMenu::getPath, path)
                .set(SysMenu::getComponent, component)
                .set(SysMenu::getIcon, trimToNull(request.icon()))
                .set(request.sort() != null, SysMenu::getSort, request.sort())
                .set(SysMenu::getVisible, visible)
                .set(SysMenu::getStatus, status)
                .set(SysMenu::getRemark, trimToNull(request.remark()))
                .eq(SysMenu::getMenuId, menuId));
    }

    /**
     * 删除:内置拒;有下级拒——不做连坐删子树,一次点击抹掉一整片导航的代价不该由一个删除按钮承担。
     * 已被角色授权并不拦:同事务物理清掉 sys_role_menu 的引用行,菜单都没了,留着授权就是悬空引用。
     * 逻辑删后 perm_code 仍占全局唯一键 uk_menu_perm_code(不含 deleted),同码重开会被数据库拦下;
     * 目录与外链的码为 NULL,不受该键约束,可以无限个不配码。
     */
    @Transactional
    @CacheEvict(cacheNames = {"menuTree", "userPerms"}, allEntries = true)
    public void delete(Long menuId) {
        SysMenu menu = requireMenu(menuId);
        if (Integer.valueOf(1).equals(menu.getIsSystem())) {
            throw BizException.badRequest("内置菜单不可删除");
        }
        if (menuMapper.selectCount(Wrappers.<SysMenu>lambdaQuery()
                .eq(SysMenu::getParentId, menuId)) > 0) {
            throw BizException.badRequest("存在下级菜单,不能删除");
        }
        roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getMenuId, menuId));
        menuMapper.deleteById(menuId);
    }

    /** 存在性闸:菜单是全局表(无租户列、不在租户拦截范围内),查不到就是真不存在 */
    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw BizException.badRequest("菜单不存在");
        }
        return menu;
    }

    /** 父节点约束:根只能是目录;按钮与外链不能挂下级;导航节点子承父面;大屏与站内两棵树不互相嵌套。
     *  按钮例外——它不入导航树,只决定"哪个动作要哪个码",所以允许挂在其服务的页面下:
     *  站内页面上的跨面动作(如平台授权、跨租户查看)本就该长在同一个位置,授权树按节点自身的面判权。 */
    private void validateParent(SysMenu parent, int menuType, int target, int scope) {
        if (parent == null) {
            if (menuType != TYPE_DIR) {
                throw BizException.badRequest("根节点只能是目录");
            }
            return;
        }
        if (Objects.equals(parent.getMenuType(), TYPE_BUTTON)) {
            throw BizException.badRequest("按钮不能挂下级");
        }
        if (Objects.equals(parent.getTarget(), TARGET_EXTERNAL)) {
            throw BizException.badRequest("外链不能挂下级");
        }
        if (menuType != TYPE_BUTTON && !Objects.equals(parent.getScope(), scope)) {
            throw BizException.badRequest("子菜单归属面必须与父菜单一致");
        }
        if (isDashboard(parent.getTarget()) != isDashboard(target)) {
            throw BizException.badRequest("数据大屏菜单与站内菜单不能相互嵌套");
        }
    }

    /** 三元组决定 path/component/perm_code 的必填口径;权限码前缀必须与归属面一致 */
    private void validateShape(int menuType, int target, int scope, String path, String component,
                               String permCode, int visible) {
        if (hasText(path)) {
            if (target == TARGET_EXTERNAL) {
                if (!EXTERNAL_URL.matcher(path).matches()) {
                    throw BizException.badRequest("外链地址须以 http:// 或 https:// 开头");
                }
            } else if (!INTERNAL_PATH.matcher(path).matches()) {
                throw BizException.badRequest("站内路由须以 / 开头");
            }
        }
        if (menuType == TYPE_DIR) {
            if (hasText(permCode)) {
                throw BizException.badRequest("目录不能配置权限码");
            }
            if (hasText(component)) {
                throw BizException.badRequest("目录不能配置前端组件");
            }
        } else if (menuType == TYPE_MENU) {
            if (!hasText(path)) {
                throw BizException.badRequest("菜单必须配置路由/地址");
            }
            if (target == TARGET_EXTERNAL) {
                if (hasText(permCode)) {
                    throw BizException.badRequest("外链不能配置权限码");
                }
                if (hasText(component)) {
                    throw BizException.badRequest("外链不能配置前端组件");
                }
            } else if (!hasText(permCode)) {
                throw BizException.badRequest("菜单必须配置权限码");
            } else if (!hasText(component)) {
                throw BizException.badRequest("菜单必须配置前端组件");
            }
        } else {
            if (!hasText(permCode)) {
                throw BizException.badRequest("按钮必须配置权限码");
            }
            if (hasText(path) || hasText(component)) {
                throw BizException.badRequest("按钮不能配置路由与组件");
            }
            if (target != TARGET_ADMIN) {
                throw BizException.badRequest("按钮只能属于站内菜单");
            }
            if (visible != 0) {
                throw BizException.badRequest("按钮固定为显示状态");
            }
        }
        if (hasText(permCode)) {
            if (!PERM_CODE.matcher(permCode).matches()) {
                throw BizException.badRequest("权限码须为 面:模块:动作 三段式");
            }
            String expected = scope == SCOPE_PLATFORM ? "platform:" : "system:";
            if (!permCode.startsWith(expected)) {
                throw BizException.badRequest("权限码前缀必须与归属面一致");
            }
        }
    }

    private static boolean isDashboard(int target) {
        return target == TARGET_DASHBOARD;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
