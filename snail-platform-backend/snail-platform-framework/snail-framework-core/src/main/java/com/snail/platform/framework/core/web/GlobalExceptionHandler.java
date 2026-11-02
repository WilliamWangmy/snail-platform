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

    /**
     * 业务文案回传前端的长度上限:框架不回显用户输入,但拦不住业务层把超长用户输入
     * 拼进 BizException 文案——统一在这里截断兜底,不信任上游自律。
     */
    private static final int MAX_BIZ_MESSAGE_LENGTH = 500;

    /**
     * 业务拒绝:业务码原样透传,HTTP 状态保持 200。
     * 文案截断到 500 字符;code ≥ 500 的业务拒绝落 warn 便于观测异常尖刺,4xx 打 debug 不刷屏。
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        if (e.getCode() >= 500) {
            log.warn("业务拒绝 code={}, message={}", e.getCode(), e.getMessage());
        } else {
            log.debug("业务拒绝 code={}, message={}", e.getCode(), e.getMessage());
        }
        return R.fail(e.getCode(), truncate(e.getMessage()));
    }

    /** 文案截断兜底:超长以省略号收尾,响应体不再携带截掉的部分 */
    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_BIZ_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_BIZ_MESSAGE_LENGTH) + "…(已截断)";
    }

    /** @Valid 请求体校验失败:只取第一条字段错误,文案带字段名 */
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

    /** 缺少必填查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(400, "缺少参数 " + e.getParameterName());
    }

    /** 路径变量或查询参数的类型转换失败 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(400, "参数 " + e.getName() + " 类型不正确");
    }

    /**
     * 上传超限: multipart 阈值先于业务的存储配额判定生效,文案要说清是大小问题而不是"系统繁忙"。
     * 业务码用 413(Payload Too Large)而非 400:HTTP 状态保持信封契约的 200 不动,
     * 但网关与监控可以按信封 code 区分"大文件攻击"与普通参数错误。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return R.fail(413, "文件超出单次上传大小限制");
    }

    /** 方法不匹配:属于传输层错误,回真实 405,前端据此判断"接口没挂上" */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail(405, "请求方法不支持"));
    }

    /** 静态资源/端点不存在:回真实 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "接口不存在"));
    }

    /** 未预期异常:记 error 日志(含堆栈)后统一 500,响应体不暴露内部细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "系统繁忙,请稍后再试"));
    }
}
