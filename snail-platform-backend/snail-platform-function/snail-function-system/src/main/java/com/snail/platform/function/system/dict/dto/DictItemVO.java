package com.snail.platform.function.system.dict.dto;

import com.snail.platform.function.system.dict.entity.SysDictData;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:下拉项出参——只给业务渲染需要的标签与值,不透出管理面字段。
 * 上游:DictService.items;下游:任何已登录用户的前端下拉控件。
 */
@Schema(description = "字典项")
public record DictItemVO(

        @Schema(description = "标签")
        String label,

        @Schema(description = "值")
        String value) {

    public static DictItemVO from(SysDictData data) {
        return new DictItemVO(data.getDictLabel(), data.getDictValue());
    }
}
