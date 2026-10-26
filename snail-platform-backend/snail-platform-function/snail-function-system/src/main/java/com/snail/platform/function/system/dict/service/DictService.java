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

    /** 删类型同事务逻辑删其全部数据项;历史业务行保留原始 dictValue,不做回写 */
    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void deleteType(Long dictId) {
        requireType(dictId);
        dataMapper.delete(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictId, dictId));
        typeMapper.deleteById(dictId);
    }

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

    @Transactional
    @CacheEvict(cacheNames = "dictItem", allEntries = true)
    public void deleteData(Long dictDataId) {
        requireData(dictDataId);
        dataMapper.deleteById(dictDataId);
    }

    /**
     * 下拉项解析:类型必须存在且启用,只返回启用数据项,按 sort 升序。
     * 缓存键为 (租户, 类型标识) 双段——dictType 只在租户内唯一,单段键会让租户间串数据。
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
