package com.snail.platform.function.system.org.service;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.db.datascope.DataScopeContextHolder;
import com.snail.platform.framework.db.datascope.DataScopeFilter;
import com.snail.platform.function.system.org.dto.OrgCreateRequest;
import com.snail.platform.function.system.org.dto.OrgNodeVO;
import com.snail.platform.function.system.org.dto.OrgUpdateRequest;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.role.service.DataScopeService;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:组织树写侧与页面读侧——整棵返回、新增子节点、改属性、移动(子树 ancestors 前缀整体替换+环校验)、
 * 删除前置检查,以及对外的子树清单入口。行的读取与子树缓存住在 OrgQueryService,本类只管"能不能动"和"怎么动"。
 * 数据权限口径(整改线 Phase 5,补 Phase 2 残留 2):读侧 tree() 与子树清单经可见集开窗过滤,
 * 写侧四个入口一律先过 requireOrgInScope 定点闸——与 UserService.requireUserInScope 同一把尺子;
 * 新增按父节点判可见,因为操作者看得见父节点才有资格往它下面挂。
 * 由此带来的收缩是设计而非漏洞:档位 2(本组织)只看得见自己那一个节点,它在下面新建的子女要等档位放宽才可见。
 * ancestors 是硬约束口径:新增/移动都在本服务内维护,任何写入路径不许绕过;它同时是数据权限档位 3
 * 与业务库单据子树查询(档位 A)的地基。
 * 上游:OrgController;下游:OrgQueryService(读)、DataScopeService(可见性判定)、sys_org 写、sys_user 删除前检查。
 * 不做:不产权限谓词(只消费 DataScopeService 的判定结果)、不直连子树缓存的读(经只读端口)。
 */
@Service
public class OrgService {

    /** 组织表写侧读写与删除前置检查计数 */
    private final SysOrgMapper orgMapper;

    /** 用户表读,删除前置检查用 */
    private final SysUserMapper userMapper;

    /** 组织树只读端口:节点取回、子树清单、后代行 */
    private final OrgQueryService orgQueryService;

    /** 数据权限判定:可见组织集来自当前登录者的多角色并集 */
    private final DataScopeService dataScopeService;

    public OrgService(SysOrgMapper orgMapper, SysUserMapper userMapper,
                      OrgQueryService orgQueryService, DataScopeService dataScopeService) {
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
        this.orgQueryService = orgQueryService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 整棵返回:树体量有限,分页在这里没有意义;开窗后按可见集过滤,parentId 指向被过滤掉的父节点时
     * 该节点自动升为根层展示(沿用孤儿按根处理的口径),不让可见的数据凭空消失也无需二次补查。
     */
    public List<OrgNodeVO> tree() {
        DataScopeFilter scope = dataScopeService.currentFilterOrNull();
        List<SysOrg> all = DataScopeContextHolder.filter(scope, () -> orgMapper.selectList(
                Wrappers.<SysOrg>lambdaQuery().orderByAsc(SysOrg::getSort).orderByAsc(SysOrg::getOrgId)));
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

    /** 子树清单的页面入口:先过可见性闸,再取未过滤的缓存清单(档位三内部调用不走这里,它没有"目标节点"概念) */
    public List<Long> subtreeWithinScope(Long orgId) {
        requireOrgInScope(orgId);
        return orgQueryService.subtreeIds(orgId);
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public Long create(OrgCreateRequest request) {
        if (request.parentId() == 0L) {
            throw BizException.badRequest("根组织由租户开通模板生成,不支持新增");
        }
        SysOrg parent = orgQueryService.requireOrg(request.parentId());
        requireOrgInScope(parent.getOrgId());
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
    @CacheEvict(cacheNames = "userSession", allEntries = true)
    public void update(Long orgId, OrgUpdateRequest request) {
        SysOrg org = orgQueryService.requireOrg(orgId);
        requireOrgInScope(org.getOrgId());
        orgMapper.update(new SysOrg(), Wrappers.<SysOrg>lambdaUpdate()
                .set(SysOrg::getName, request.name().trim())
                .set(request.sort() != null, SysOrg::getSort, request.sort())
                .set(request.status() != null, SysOrg::getStatus, request.status())
                .set(SysOrg::getRemark, request.remark())
                .eq(SysOrg::getOrgId, org.getOrgId()));
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public void move(Long orgId, Long newParentId) {
        SysOrg node = orgQueryService.requireOrg(orgId);
        requireOrgInScope(node.getOrgId());
        if (node.getParentId() == 0L) {
            throw BizException.badRequest("根组织不可移动");
        }
        if (Objects.equals(node.getParentId(), newParentId)) {
            return;
        }
        SysOrg newParent = orgQueryService.requireOrg(newParentId);
        requireOrgInScope(newParent.getOrgId());
        if (Objects.equals(newParent.getOrgId(), orgId) || ancestorIds(newParent.getAncestors()).contains(orgId)) {
            throw BizException.badRequest("不能把组织移入自己的子树");
        }

        String oldPrefix = node.getAncestors() + "," + orgId;
        String newAncestors = newParent.getAncestors() + "," + newParent.getOrgId();
        String newPrefix = newAncestors + "," + orgId;

        orgMapper.update(new SysOrg(), Wrappers.<SysOrg>lambdaUpdate()
                .set(SysOrg::getParentId, newParent.getOrgId())
                .set(SysOrg::getAncestors, newAncestors)
                .eq(SysOrg::getOrgId, orgId));

        for (SysOrg descendant : orgQueryService.descendants(oldPrefix)) {
            String rewritten = newPrefix + descendant.getAncestors().substring(oldPrefix.length());
            orgMapper.update(new SysOrg(), Wrappers.<SysOrg>lambdaUpdate()
                    .set(SysOrg::getAncestors, rewritten)
                    .eq(SysOrg::getOrgId, descendant.getOrgId()));
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "orgSubtree", allEntries = true)
    public void delete(Long orgId) {
        orgQueryService.requireOrg(orgId);
        requireOrgInScope(orgId);
        if (orgMapper.selectCount(Wrappers.<SysOrg>lambdaQuery().eq(SysOrg::getParentId, orgId)) > 0) {
            throw BizException.badRequest("存在下级组织,不能删除");
        }
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getOrgId, orgId)) > 0) {
            throw BizException.badRequest("组织下存在用户,不能删除");
        }
        orgMapper.deleteById(orgId);
    }

    /**
     * 定点写的数据权限闸:目标组织必须在当前登录者 data_scope 的可见集内,口径与用户域一致
     * (全部档返回 null 不设限;其余复用列表检索的同一谓词开窗计数)。
     * 组织没有"本人豁免"一说——可见集里点不到的节点,无论读写都拒。
     */
    private void requireOrgInScope(Long orgId) {
        DataScopeFilter scope = dataScopeService.currentFilterOrNull();
        if (scope == null) {
            return;
        }
        Long visible = DataScopeContextHolder.filter(scope, () -> orgMapper.selectCount(
                Wrappers.<SysOrg>lambdaQuery().eq(SysOrg::getOrgId, orgId)));
        if (visible == null || visible == 0) {
            throw BizException.forbidden("目标组织不在当前数据权限范围内");
        }
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
