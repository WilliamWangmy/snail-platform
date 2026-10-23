package com.snail.platform.framework.core.web;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 职责:HTTP 边界的异常→R 唯一翻译处;BizException 按码回文案,参数校验取首条字段错误,
 * 其余异常一律 500 兜底并记 error(响应体不暴露堆栈)。
 * 上游:任意 Controller 抛出的异常;下游:前端消费的 JSON 信封。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 兜底日志,含堆栈 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = first == null ? "参数不合法" : first.getField() + ": " + first.getDefaultMessage();
        return R.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "系统繁忙,请稍后再试"));
    }
}
