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
        Map<Long, MenuNodeVO> nodes = new LinkedHashMap<>();
        for (SysMenu menu : all) {
            nodes.put(menu.getMenuId(), MenuNodeVO.of(menu));
        }
        List<MenuNodeVO> roots = new ArrayList<>();
        for (SysMenu menu : all) {
            MenuNodeVO node = nodes.get(menu.getMenuId());
            MenuNodeVO parent = menu.getParentId() == null ? null : nodes.get(menu.getParentId());
            if (parent != null && !Objects.equals(parent.menuId(), node.menuId())) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

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
            // 唯一键含已删记录,预查会漏;错误口径仍走业务拒绝
            throw BizException.badRequest("权限码已存在");
        }
        return menu.getMenuId();
    }

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

        menuMapper.update(null, Wrappers.<SysMenu>lambdaUpdate()
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

    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw BizException.badRequest("菜单不存在");
        }
        return menu;
    }

    /** 父节点约束:根只能是目录;按钮与外链不能挂下级;子承父面;大屏与站内两棵树不互相嵌套 */
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
        if (!Objects.equals(parent.getScope(), scope)) {
            throw BizException.badRequest("子菜单归属面必须与父菜单一致");
        }
        if (isDashboard(parent.getTarget()) != isDashboard(target)) {
            throw BizException.badRequest("数据大屏菜单与站内菜单不能相互嵌套");
        }
    }

    /** 三元组决定 path/component/perm_code 的必填口径;权限码前缀必须与归属面一致 */
    private void validateShape(int menuType, int target, int scope, String path, String component,
                               String permCode, int visible) {
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
