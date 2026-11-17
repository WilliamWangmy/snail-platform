package com.snail.platform.function.system.user.dto;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:用户批量导入的 xlsx 行定义——列序即 @ExcelProperty 声明序,列名与导入模板一致。
 * 口令与组织刻意不入文件:口令全批共用、由管理员随上传请求提交(明文不落盘),组织由管理员
 * 在页面上选定,避免 Excel 里写组织 ID 既是明文泄漏面又容易错行。
 * 上游:UserController 导入入口;下游:ExcelImporter/fastexcel、UserService 校验与落库。
 * 约束:普通 JavaBean(fastexcel 按字段反射装配,record 不适用于此);字段与
 * UserCreateRequest 同口径,服务端逐行校验,本类不做任何自证。
 */
@Getter
@Setter
public class UserImportRow {

    /** 登录名,全表唯一 */
    @ExcelProperty("登录名")
    private String username;

    /** 显示名 */
    @ExcelProperty("显示名")
    private String nickname;

    /** 邮箱,可空 */
    @ExcelProperty("邮箱")
    private String email;

    /** 手机号,可空;非空须为 11 位大陆手机号或 3-20 位含区号的座机 */
    @ExcelProperty("手机号")
    private String phone;

    /** 备注,可空 */
    @ExcelProperty("备注")
    private String remark;
}
