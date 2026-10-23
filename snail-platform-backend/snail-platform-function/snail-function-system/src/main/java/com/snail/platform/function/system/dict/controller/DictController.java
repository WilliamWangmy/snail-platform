package com.snail.platform.function.system.dict.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.dict.dto.DictDataCreateRequest;
import com.snail.platform.function.system.dict.dto.DictDataQuery;
import com.snail.platform.function.system.dict.dto.DictDataUpdateRequest;
import com.snail.platform.function.system.dict.dto.DictDataVO;
import com.snail.platform.function.system.dict.dto.DictItemVO;
import com.snail.platform.function.system.dict.dto.DictTypeCreateRequest;
import com.snail.platform.function.system.dict.dto.DictTypeQuery;
import com.snail.platform.function.system.dict.dto.DictTypeUpdateRequest;
import com.snail.platform.function.system.dict.dto.DictTypeVO;
import com.snail.platform.function.system.dict.service.DictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:租户字典管理入口——类型与数据两套 CRUD,外加任意登录用户可取的字典项解析。
 * 管理端点全挂 system:dict:* 权限码;items 只要求登录,因为下拉项是业务页面的通用渲染依赖。
 * 上游:字典管理页面与各业务页面;下游:DictService。
 */
@Tag(name = "字典管理")
@RestController
@RequestMapping("/system/dict")
public class DictController {

    /** 字典服务 */
    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @PreAuthorize("hasAuthority('system:dict:list')")
    @Operation(summary = "字典类型分页")
    @GetMapping("/type/page")
    public R<PageResult<DictTypeVO>> pageTypes(@Valid DictTypeQuery query) {
        return R.ok(dictService.pageTypes(query));
    }

    @PreAuthorize("hasAuthority('system:dict:create')")
    @Operation(summary = "新增字典类型")
    @OperLog(module = "字典管理", businessType = BusinessType.INSERT)
    @PostMapping("/type")
    public R<Long> createType(@Valid @RequestBody DictTypeCreateRequest request) {
        return R.ok(dictService.createType(request));
    }

    @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary = "修改字典类型(类型标识不可改)")
    @OperLog(module = "字典管理", businessType = BusinessType.UPDATE)
    @PutMapping("/type/{dictId}")
    public R<Void> updateType(@PathVariable Long dictId,
                              @Valid @RequestBody DictTypeUpdateRequest request) {
        dictService.updateType(dictId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:dict:delete')")
    @Operation(summary = "删除字典类型(级联逻辑删其数据项)")
    @OperLog(module = "字典管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/type/{dictId}")
    public R<Void> deleteType(@PathVariable Long dictId) {
        dictService.deleteType(dictId);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:dict:list')")
    @Operation(summary = "字典数据分页(须指定类型)")
    @GetMapping("/data/page")
    public R<PageResult<DictDataVO>> pageData(@Valid DictDataQuery query) {
        return R.ok(dictService.pageData(query));
    }

    @PreAuthorize("hasAuthority('system:dict:create')")
    @Operation(summary = "新增字典数据")
    @OperLog(module = "字典管理", businessType = BusinessType.INSERT)
    @PostMapping("/data")
    public R<Long> createData(@Valid @RequestBody DictDataCreateRequest request) {
        return R.ok(dictService.createData(request));
    }

    @PreAuthorize("hasAuthority('system:dict:update')")
    @Operation(summary = "修改字典数据(字典值不可改)")
    @OperLog(module = "字典管理", businessType = BusinessType.UPDATE)
    @PutMapping("/data/{dictDataId}")
    public R<Void> updateData(@PathVariable Long dictDataId,
                              @Valid @RequestBody DictDataUpdateRequest request) {
        dictService.updateData(dictDataId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:dict:delete')")
    @Operation(summary = "删除字典数据")
    @OperLog(module = "字典管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/data/{dictDataId}")
    public R<Void> deleteData(@PathVariable Long dictDataId) {
        dictService.deleteData(dictDataId);
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "按类型标识取启用字典项(任何登录用户)")
    @GetMapping("/items/{dictType}")
    public R<List<DictItemVO>> items(@PathVariable String dictType) {
        return R.ok(dictService.items(dictType));
    }
}
