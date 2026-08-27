package group.aitools.nhs.platform.notification.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 负责Nhs门户通知Exception相关的转换、解析或处理逻辑。
 * Preserves provider HTTP status codes on the Nhs-compatible personal notification surface. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
    NhsPortalNotificationConfigController.class,
    NhsPortalInboxController.class
})
public class NhsPortalNotificationExceptionHandler {

    /**
     * 处理{@code serviceException}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<R<Void>> serviceException(ServiceException exception) {
        int status = exception.getCode() != null
            && exception.getCode() >= 400 && exception.getCode() <= 599
            ? exception.getCode() : HttpStatus.BAD_REQUEST.value();
        return failure(status, exception.getMessage());
    }

    /**
     * 处理{@code notLoggedIn}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> notLoggedIn(NotLoginException exception) {
        return failure(HttpStatus.UNAUTHORIZED.value(), "登录状态异常，请重新登录");
    }

    /**
     * 处理{@code accessDenied}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> accessDenied(RuntimeException exception) {
        return failure(HttpStatus.FORBIDDEN.value(), "没有访问权限，请联系管理员授权");
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<R<Void>> badRequest(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST.value(), "请求参数格式错误");
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param status 目标状态
     * @param message 待处理内容
     * @return 处理结果
     */
    private ResponseEntity<R<Void>> failure(int status, String message) {
        String safeMessage = message == null || message.isBlank() ? "请求处理失败" : message;
        return ResponseEntity.status(status).body(R.fail(status, safeMessage));
    }
}
