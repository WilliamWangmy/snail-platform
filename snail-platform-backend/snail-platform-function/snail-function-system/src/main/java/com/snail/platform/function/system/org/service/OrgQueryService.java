package com.snail.platform.function.system.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 职责:组织树的只读端口——按 ID 取节点、子树 ID 清单(orgSubtree 缓存的唯一生产处)、后代行查询。
 * 刻意不开数据权限窗口:subtreeIds 同时是档位三的组织臂数据源,若被权限谓词二次过滤,
 * 判定就会拿自己的结果当输入(自我循环),可见集还会随每次判定收缩。
 * 因此本类只服务"组织树本身长什么样",谁能看到哪几棵由 OrgService 的闸门负责。
 * 上游:OrgService(写侧取行与前置检查)、DataScopeService(档位三取子树)、OrgController 的只读端点;
 * 下游:sys_org 单表读,租户圈定由拦截器完成。失效由 OrgService 的写方法统一 @CacheEvict。
 * 不做:不写库、不开数据权限窗口、不判权限。
 */
@Service
public class OrgQueryService {

    /** 组织表只读 */
    private final SysOrgMapper orgMapper;

    public OrgQueryService(SysOrgMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    /** 按 ID 取组织;不存在即业务拒绝——它同时是写侧每个入口的前置检查 */
    public SysOrg requireOrg(Long orgId) {
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw BizException.badRequest("组织不存在");
        }
        return org;
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

    /** 后代行(不含自身):移动时由写侧取全量子树逐行重写 ancestors */
    public List<SysOrg> descendants(String path) {
        return orgMapper.selectList(descendantsOf(path));
    }

    /**
     * 子树后代条件:ancestors 恰好等于路径(直接子女)或以"路径,"开头(更深后代)。
     * 不能只用 likeRight(路径)——"…,2"前缀会误命中兄弟"…,23"的节点,尾逗号又漏掉恰好等于路径的直接子女。
     */
    private LambdaQueryWrapper<SysOrg> descendantsOf(String path) {
        return Wrappers.<SysOrg>lambdaQuery()
                .and(w -> w.eq(SysOrg::getAncestors, path).or().likeRight(SysOrg::getAncestors, path + ","));
    }
}
