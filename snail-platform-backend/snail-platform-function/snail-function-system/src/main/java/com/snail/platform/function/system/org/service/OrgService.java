package com.snail.platform.function.system.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.system.org.dto.OrgCreateRequest;
import com.snail.platform.function.system.org.dto.OrgNodeVO;
import com.snail.platform.function.system.org.dto.OrgUpdateRequest;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:组织树管理——整棵返回、新增子节点、改属性、移动(子树 ancestors 前缀整体替换+环校验)、删除前置检查,
 * 以及子树 ID 清单查询(orgSubtree 缓存的唯一生产与失效处)。
 * ancestors 是硬约束口径:新增/移动都在本服务内维护,任何写入路径不许绕过;它同时是数据权限 scope3
 * 与业务库单据子树查询(档位 A)的地基,subtreeIds 即那个内部查询的接口面。
 * 上游:OrgController 与 role 侧数据权限判定;下游:sys_org 读写(租户拦截器天然圈定本租户的树)、sys_user 删除前检查。
 */
@Service
public class OrgService {

    /** 组织表读写,拦截器保证只触达本租户的树 */
    private final SysOrgMapper orgMapper;

    /** 用户表读,删除前置检查用 */
    private final SysUserMapper userMapper;

    public OrgService(SysOrgMapper orgMapper, SysUserMapper userMapper) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
    }

    /** 整棵返回:树体量有限,分页在这里没有意义;parentId 指向已消失节点的孤儿按根层展示,不让数据凭空消失 */
    public List<OrgNodeVO> tree() {
        List<SysOrg> all = orgMapper.selectList(Wrappers.<SysOrg>lambdaQuery()
                .orderByAsc(SysOrg::getSort).orderByAsc(SysOrg::getOrgId));
        Map<Long, OrgNodeVO> nodes = new LinkedHashMap<>();
        for (SysOrg org : all) {
            nodes.put(org.getOrgId(), OrgNodeVO.of(org));
        }
        List<OrgNodeVO> roots = new ArrayList<>();
        for (SysOrg org : all) {
            OrgNodeVO node = nodes.get(org.getOrgId());
            OrgNodeVO parent = org.getParentId() == null ? null : nodes.get(org.getParentId());
            if (parent != null && !Objects.equals(parent.orgId(), node.orgId())) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public Long create(OrgCreateRequest request) {
        if (request.parentId() == 0L) {
            throw BizException.badRequest("根组织由租户开通模板生成,不支持新增");
        }
        SysOrg parent = requireOrg(request.parentId());
        if (Objects.equals(parent.getStatus(), 1)) {
            throw BizException.badRequest("父组织已停用,不能作为新组织归属");
        }
        SysOrg org = new SysOrg();
        org.setParentId(parent.getOrgId());
        org.setName(request.name().trim());
        org.setAncestors(parent.getAncestors() + "," + parent.getOrgId());
        org.setSort(request.sort() == null ? 0 : request.sort());
        org.setStatus(0);
        org.setRemark(request.remark());
        orgMapper.insert(org);
        return org.getOrgId();
    }

    @Transactional
    public void update(Long orgId, OrgUpdateRequest request) {
        SysOrg org = requireOrg(orgId);
        orgMapper.update(null, Wrappers.<SysOrg>lambdaUpdate()
                .set(SysOrg::getName, request.name().trim())
                .set(request.sort() != null, SysOrg::getSort, request.sort())
                .set(request.status() != null, SysOrg::getStatus, request.status())
                .set(SysOrg::getRemark, request.remark())
                .eq(SysOrg::getOrgId, org.getOrgId()));
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public void move(Long orgId, Long newParentId) {
        SysOrg node = requireOrg(orgId);
        if (node.getParentId() == 0L) {
            throw BizException.badRequest("根组织不可移动");
        }
        if (Objects.equals(node.getParentId(), newParentId)) {
            return;
        }
        SysOrg newParent = requireOrg(newParentId);
        if (Objects.equals(newParent.getOrgId(), orgId) || ancestorIds(newParent.getAncestors()).contains(orgId)) {
            throw BizException.badRequest("不能把组织移入自己的子树");
        }

        String oldPrefix = node.getAncestors() + "," + orgId;
        String newAncestors = newParent.getAncestors() + "," + newParent.getOrgId();
        String newPrefix = newAncestors + "," + orgId;

        orgMapper.update(null, Wrappers.<SysOrg>lambdaUpdate()
                .set(SysOrg::getParentId, newParent.getOrgId())
                .set(SysOrg::getAncestors, newAncestors)
                .eq(SysOrg::getOrgId, orgId));

        List<SysOrg> subtree = orgMapper.selectList(descendantsOf(oldPrefix));
        for (SysOrg descendant : subtree) {
            String rewritten = newPrefix + descendant.getAncestors().substring(oldPrefix.length());
            orgMapper.update(null, Wrappers.<SysOrg>lambdaUpdate()
                    .set(SysOrg::getAncestors, rewritten)
                    .eq(SysOrg::getOrgId, descendant.getOrgId()));
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public void delete(Long orgId) {
        requireOrg(orgId);
        if (orgMapper.selectCount(Wrappers.<SysOrg>lambdaQuery().eq(SysOrg::getParentId, orgId)) > 0) {
            throw BizException.badRequest("存在下级组织,不能删除");
        }
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getOrgId, orgId)) > 0) {
            throw BizException.badRequest("组织下存在用户,不能删除");
        }
        orgMapper.deleteById(orgId);
    }

    /**
     * 子树 ID 清单(含自身):档位 A 的数据源,orgSubtree 缓存按 (租户, 组织) 双段键。
     * 树变更即全量失效——组织体量小,全清比逐键跟踪便宜且不会漏;TTL 10 分钟兜底外部直改库。
     */
    @Cacheable(cacheNames = "orgSubtree",
            key = "T(com.snail.platform.framework.core.context.TenantContextHolder).get() + ':' + #orgId")
    public List<Long> subtreeIds(Long orgId) {
        SysOrg node = requireOrg(orgId);
        List<SysOrg> descendants = orgMapper.selectList(
                descendantsOf(node.getAncestors() + "," + orgId).select(SysOrg::getOrgId));
        List<Long> ids = new ArrayList<>(descendants.size() + 1);
        ids.add(orgId);
        for (SysOrg descendant : descendants) {
            ids.add(descendant.getOrgId());
        }
        return ids;
    }

    /**
     * 子树后代条件:ancestors 恰好等于路径(直接子女)或以"路径,"开头(更深后代)。
     * 不能只用 likeRight(路径)——"…,2"前缀会误命中兄弟"…,23"的节点,尾逗号又漏掉恰好等于路径的直接子女。
     */
    private LambdaQueryWrapper<SysOrg> descendantsOf(String path) {
        return Wrappers.<SysOrg>lambdaQuery()
                .and(w -> w.eq(SysOrg::getAncestors, path).or().likeRight(SysOrg::getAncestors, path + ","));
    }

    private SysOrg requireOrg(Long orgId) {
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw BizException.badRequest("组织不存在");
        }
        return org;
    }

    /** ancestors 字符串拆成 ID 集;非法段忽略——它是程序维护的路径,不是用户输入 */
    private Set<Long> ancestorIds(String ancestors) {
        if (ancestors == null || ancestors.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(ancestors.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.chars().allMatch(Character::isDigit))
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }
}
