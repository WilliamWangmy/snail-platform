package com.snail.platform.function.system.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.system.dict.dto.DictDataCreateRequest;
import com.snail.platform.function.system.dict.dto.DictDataQuery;
import com.snail.platform.function.system.dict.dto.DictDataUpdateRequest;
import com.snail.platform.function.system.dict.dto.DictDataVO;
import com.snail.platform.function.system.dict.dto.DictItemVO;
import com.snail.platform.function.system.dict.dto.DictTypeCreateRequest;
import com.snail.platform.function.system.dict.dto.DictTypeQuery;
import com.snail.platform.function.system.dict.dto.DictTypeUpdateRequest;
import com.snail.platform.function.system.dict.dto.DictTypeVO;
import com.snail.platform.function.system.dict.entity.SysDictData;
import com.snail.platform.function.system.dict.entity.SysDictType;
import com.snail.platform.function.system.dict.mapper.SysDictDataMapper;
import com.snail.platform.function.system.dict.mapper.SysDictTypeMapper;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:租户字典管理的全部写入与读取——类型分页/新增/修改/删除(删类型级联逻辑删数据)、
 * 数据分页/新增/修改/删除,以及给任意登录用户的下拉项解析。
 * 守护口径:dict_type 建后不可改、租户内唯一含已删记录;dict_value 建后不可改、同类型内唯一含已删记录;
 * 停用类型或停用数据项都不进入下拉解析。dictItem 缓存键含租户,任何字典写操作全量失效。
 * 上游:DictController;下游:sys_dict_type/sys_dict_data 读写(租户拦截器圈定本租户)。
 */
@Service
public class DictService {

    /** 状态:停用 */
    private static final int STATUS_DISABLED = 1;

    /** 状态:正常 */
    private static final int STATUS_ENABLED = 0;

    /** 字典类型读写 */
    private final SysDictTypeMapper typeMapper;

    /** 字典数据读写 */
    private final SysDictDataMapper dataMapper;

    public DictService(SysDictTypeMapper typeMapper, SysDictDataMapper dataMapper) {
        this.typeMapper = typeMapper;
        this.dataMapper = dataMapper;
    }

    /**
     * 类型分页:名称与标识都按模糊匹配,先 trimToNull——留空要落到"根本不拼这段 like"上,而不是留一条恒真的 LIKE '%%'。
     * 按 dictId 倒序,新建的排最前;租户条件由拦截器注入,这里不写也只看本租户。
     */
    public PageResult<DictTypeVO> pageTypes(DictTypeQuery query) {
        Page<SysDictType> page = PageAssembler.from(query);
        String dictName = trimToNull(query.dictName());
        String dictType = trimToNull(query.dictType());
        IPage<SysDictType> result = typeMapper.selectPage(page, Wrappers.<SysDictType>lambdaQuery()
                .like(dictName != null, SysDictType::getDictName, dictName)
                .like(dictType != null, SysDictType::getDictType, dictType)
                .eq(query.status() != null, SysDictType::getStatus, query.status())
                .orderByDesc(SysDictType::getDictId));
        return PageResult.of(result.getRecords().stream().map(DictTypeVO::from).toList(),
                result.getTotal());
    }

    /**
     * 新增类型:status 一律建为正常,契约里没有这一列,先建后停是修改的事;dictType 只在新增出现,建后不可改。
     * 不做应用层查重:uk_dict_type(tenant_id, dict_type) 不含 deleted,并发重名由数据库拦下再转业务拒绝。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public Long createType(DictTypeCreateRequest request) {
        SysDictType type = new SysDictType();
        type.setDictName(request.dictName().trim());
        type.setDictType(request.dictType().trim());
        type.setStatus(STATUS_ENABLED);
        type.setRemark(trimToNull(request.remark()));
        try {
            typeMapper.insert(type);
        } catch (DuplicateKeyException e) {
            // deleted 行仍占唯一键,标识终身占用;数据库拦下时统一转业务拒绝
            throw BizException.badRequest("字典类型已存在");
        }
        return type.getDictId();
    }

    /**
     * 修改类型:契约不含 dict_type,改不动。remark 无条件 set 即"不传就清空",status 不传沿用现值——
     * 管理页整表单回填后提交,所以清空只在用户真删了备注时发生。
     * 停用类型不级联停用数据项:解析只看类型状态,数据行各自的停用位要跨这次开关活下来。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void updateType(Long dictId, DictTypeUpdateRequest request) {
        SysDictType type = requireType(dictId);
        int status = request.status() == null ? type.getStatus() : request.status();
        typeMapper.update(new SysDictType(), Wrappers.<SysDictType>lambdaUpdate()
                .set(SysDictType::getDictName, request.dictName().trim())
                .set(SysDictType::getStatus, status)
                .set(SysDictType::getRemark, trimToNull(request.remark()))
                .eq(SysDictType::getDictId, dictId));
    }

    /**
     * 删类型同事务逻辑删其全部数据项;历史业务行保留原始 dictValue,不做回写。
     * 不拦"仍有数据项":字典是配置而不是被外键引用的实体,删类型就是整组作废。
     * 类型标识终身占用唯一键(uk_dict_type 不含 deleted),同标识重建必被数据库拦下,只能另起一个码。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void deleteType(Long dictId) {
        requireType(dictId);
        dataMapper.delete(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictId, dictId));
        typeMapper.deleteById(dictId);
    }

    /**
     * 数据分页:先 requireType,别的租户递来的 dictId 被拦截器滤成查不到,同样报"字典类型不存在",不做特判。
     * sort 允许并列,第二排序键恒为 dictDataId,否则同值行的翻页次序会漂。
     */
    public PageResult<DictDataVO> pageData(DictDataQuery query) {
        requireType(query.dictId());
        Page<SysDictData> page = PageAssembler.from(query);
        String label = trimToNull(query.dictLabel());
        IPage<SysDictData> result = dataMapper.selectPage(page, Wrappers.<SysDictData>lambdaQuery()
                .eq(SysDictData::getDictId, query.dictId())
                .like(label != null, SysDictData::getDictLabel, label)
                .eq(query.status() != null, SysDictData::getStatus, query.status())
                .orderByAsc(SysDictData::getSort).orderByAsc(SysDictData::getDictDataId));
        return PageResult.of(result.getRecords().stream().map(DictDataVO::from).toList(),
                result.getTotal());
    }

    /**
     * 新增数据:requireType 先跑,孤儿 dictId 进不了库;status 一律建为正常。
     * 唯一键是 (tenant_id, dict_id, dict_value)——同类型内值不许重,跨类型同值合法,查重交给数据库。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public Long createData(DictDataCreateRequest request) {
        requireType(request.dictId());
        SysDictData data = new SysDictData();
        data.setDictId(request.dictId());
        data.setDictLabel(request.dictLabel().trim());
        data.setDictValue(request.dictValue().trim());
        data.setSort(request.sort() == null ? 0 : request.sort());
        data.setStatus(STATUS_ENABLED);
        data.setRemark(trimToNull(request.remark()));
        try {
            dataMapper.insert(data);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("该字典下值已存在");
        }
        return data.getDictDataId();
    }

    /**
     * 修改数据:契约不含 dictValue 与 dictId——值建后不可改,归属类型也不给迁移的入口。
     * label 必传,status 与 sort 不传即沿用现值,remark 不传即清空。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void updateData(Long dictDataId, DictDataUpdateRequest request) {
        SysDictData data = requireData(dictDataId);
        int status = request.status() == null ? data.getStatus() : request.status();
        dataMapper.update(new SysDictData(), Wrappers.<SysDictData>lambdaUpdate()
                .set(SysDictData::getDictLabel, request.dictLabel().trim())
                .set(SysDictData::getSort, request.sort() == null ? data.getSort() : request.sort())
                .set(SysDictData::getStatus, status)
                .set(SysDictData::getRemark, trimToNull(request.remark()))
                .eq(SysDictData::getDictDataId, dictDataId));
    }

    /**
     * 删数据项:不查业务引用,已落库的历史值不回写。逻辑删行仍占 (tenant_id, dict_id, dict_value) 唯一键,
     * 删掉的值在同类型下不能原样重建——与"值建后不可改"是同一条终身口径。
     */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void deleteData(Long dictDataId) {
        requireData(dictDataId);
        dataMapper.deleteById(dictDataId);
    }

    /**
     * 下拉项解析:类型必须存在且启用,只返回启用数据项,按 sort 升序。
     * 缓存键为 (租户, 类型标识) 双段——dictType 只在租户内唯一,单段键会让租户间串数据。
     * 取不到类型走业务拒绝而不是回空列表:空列表意味着"这组字典配置项确实为空",静默会让业务页把配置缺失
     * 当成没数据,两类问题混成一次空白下拉。
     */
    @Cacheable(cacheNames = "dictItem",
            key = "T(com.snail.platform.framework.core.context.TenantContextHolder).get() + ':' + #dictType")
    public List<DictItemVO> items(String dictType) {
        String code = trimToNull(dictType);
        if (code == null) {
            throw BizException.badRequest("字典类型不存在或已停用");
        }
        SysDictType type = typeMapper.selectOne(Wrappers.<SysDictType>lambdaQuery()
                .eq(SysDictType::getDictType, code));
        if (type == null || Integer.valueOf(STATUS_DISABLED).equals(type.getStatus())) {
            throw BizException.badRequest("字典类型不存在或已停用");
        }
        List<SysDictData> rows = dataMapper.selectList(Wrappers.<SysDictData>lambdaQuery()
                .eq(SysDictData::getDictId, type.getDictId())
                .eq(SysDictData::getStatus, STATUS_ENABLED)
                .orderByAsc(SysDictData::getSort).orderByAsc(SysDictData::getDictDataId));
        return rows.stream().map(DictItemVO::from).toList();
    }

    private SysDictType requireType(Long dictId) {
        SysDictType type = typeMapper.selectById(dictId);
        if (type == null) {
            throw BizException.badRequest("字典类型不存在");
        }
        return type;
    }

    private SysDictData requireData(Long dictDataId) {
        SysDictData data = dataMapper.selectById(dictDataId);
        if (data == null) {
            throw BizException.badRequest("字典数据不存在");
        }
        return data;
    }

    /** 查询条件与可空列共用这把尺子:空串一律折成 null——前者据此不拼 like,后者据此把清空落库 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
