package com.snail.platform.framework.core.web;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 职责:HTTP 边界的异常→R 唯一翻译处。BizException 按码回文案;入参形态错误(校验不过、请求体读不出、
 * 类型不符、上传超限)统一回业务码 400;路由与方法不匹配、鉴权拒绝回真实 HTTP 状态码;
 * 其余异常一律 500 兜底并记 error(响应体不暴露堆栈)。
 * 上游:任意 Controller 抛出的异常;下游:前端消费的 JSON 信封。
 * 口径:业务拒绝用 HTTP 200 + code,是本平台对前端拦截器的契约(与开源管理端生态主流一致),
 * 不改成真实状态码——那会让每一条既有断言与拦截器都要重写,却不带来任何安全收益;
 * 只有传输层与鉴权层的拒绝(401/403/404/405)才落到状态码上,前端据此区分"接口没挂上"和"业务被拒"。
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

    /** 校验注解直接标在方法参数上(查询串、路径变量)时的拒绝形态 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public R<Void> handleMethodValidation(HandlerMethodValidationException e) {
        String message = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数不合法");
        return R.fail(400, message);
    }

    /** @Validated 类上的单值校验 */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数不合法");
        return R.fail(400, message);
    }

    /** 请求体读不出:JSON 畸形与类型不符都归客户端错误,不进 500 兜底 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        return R.fail(400, "请求体格式不正确");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(400, "缺少参数 " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(400, "参数 " + e.getName() + " 类型不正确");
    }

    /** 上传超限: multipart 阈值先于业务的存储配额判定生效,文案要说清是大小问题而不是"系统繁忙" */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return R.fail(400, "文件超出单次上传大小限制");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail(405, "请求方法不支持"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "系统繁忙,请稍后再试"));
    }
}
