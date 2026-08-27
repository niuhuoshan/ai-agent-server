package group.aitools.nhs.platform.nhs.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import group.aitools.nhs.platform.nhs.service.NhsV1OperationAuditService;
import group.aitools.nhs.platform.nhs.service.DatasetNavigationService;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.ClickRequest;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.NavigationResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshRequest;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.RefreshResponse;
import group.aitools.nhs.platform.nhs.web.DatasetNavigationContracts.TableRecommendRequest;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.core.constant.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供数据集Navigation相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs V1-compatible data portal routes backed by authorized local metadata. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/chat/dataset-menu")
public class DatasetNavigationController {

    private final DatasetNavigationService service;
    private final NhsV1OperationAuditService auditService;

    @Autowired
    public DatasetNavigationController(
        DatasetNavigationService service,
        NhsV1OperationAuditService auditService
    ) {
        this.service = service;
        this.auditService = auditService;
    }

    /**
     * 处理{@code navigation}并返回对应结果。
     *
     * @param refresh {@code refresh}参数
     * @return 处理结果
     */
    @GetMapping
    public R<NavigationResponse> navigation(
        @RequestParam(required = false, defaultValue = "false") boolean refresh
    ) {
        NavigationResponse response;
        try {
            response = service.navigation(refresh);
        } catch (RuntimeException exception) {
            auditFailure(
                "dataset_menu.view", decision(exception), reason(exception), "refresh=" + refresh, exception
            );
            throw exception;
        }
        audit(
            "dataset_menu.view", "success", "authorized_catalog",
            "refresh=" + refresh + "; datasets=" + response.datasetCount()
                + "; groups=" + response.groups().size()
        );
        return R.ok(response);
    }

    /**
     * 处理{@code click}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/click")
    public R<Map<String, Boolean>> click(@Valid @RequestBody ClickRequest request) {
        try {
            service.recordClick(request.query(), request.label(), request.groupId());
        } catch (RuntimeException exception) {
            auditFailure(
                "dataset_menu.click", decision(exception), reason(exception),
                "queryLength=" + request.query().length(), exception
            );
            throw exception;
        }
        audit(
            "dataset_menu.click", "success", "preference_recorded",
            "queryLength=" + request.query().length()
                + "; hasLabel=" + (request.label() != null && !request.label().isBlank())
                + "; hasGroup=" + (request.groupId() != null && !request.groupId().isBlank())
        );
        return R.ok(Map.of("success", true));
    }

    /**
     * 清理或重置{@code Click}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/click/clear")
    public R<Map<String, Boolean>> clearClick(@Valid @RequestBody ClickRequest request) {
        boolean cleared;
        try {
            cleared = service.clearClick(request.query());
        } catch (RuntimeException exception) {
            auditFailure(
                "dataset_menu.click_clear", decision(exception), reason(exception),
                "queryLength=" + request.query().length(), exception
            );
            throw exception;
        }
        audit(
            "dataset_menu.click_clear", "success", cleared ? "preference_removed" : "preference_absent",
            "queryLength=" + request.query().length() + "; removed=" + cleared
        );
        return R.ok(Map.of("success", cleared));
    }

    /**
     * 处理{@code refresh}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/refresh-group-questions")
    public R<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response;
        try {
            response = service.refresh(request);
        } catch (RuntimeException exception) {
            auditFailure(
                "dataset_menu.refresh", decision(exception), reason(exception),
                "purpose=" + request.purpose() + "; tables=" + request.tables().size(), exception
            );
            throw exception;
        }
        audit(
            "dataset_menu.refresh", "success", "questions_refreshed",
            "purpose=" + request.purpose() + "; tables=" + request.tables().size()
                + "; exclusions=" + request.excludeQuestions().size()
                + "; questions=" + response.questions().size()
        );
        return R.ok(response);
    }

    /**
     * 处理{@code recommend}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/recommend-table-questions")
    public R<RefreshResponse> recommend(@Valid @RequestBody TableRecommendRequest request) {
        RefreshResponse response;
        try {
            response = service.recommend(request);
        } catch (RuntimeException exception) {
            auditFailure(
                "dataset_menu.recommend", decision(exception), reason(exception),
                "hasDataset=" + (request.datasetName() != null && !request.datasetName().isBlank())
                    + "; suppliedColumns=" + request.columns().size(), exception
            );
            throw exception;
        }
        audit(
            "dataset_menu.recommend", "success", "table_questions_generated",
            "hasDataset=" + (request.datasetName() != null && !request.datasetName().isBlank())
                + "; suppliedColumns=" + request.columns().size()
                + "; questions=" + response.questions().size()
        );
        return R.ok(response);
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param action {@code action}参数
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    private void audit(String action, String decision, String reason, String summary) {
        auditService.recordCurrent(action, "dataset_menu", null, decision, reason, summary);
    }

    /**
     * 处理审计Failure相关逻辑。
     *
     * @param action {@code action}参数
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     * @param operationFailure 操作Failure参数
     */
    private void auditFailure(
        String action,
        String decision,
        String reason,
        String summary,
        RuntimeException operationFailure
    ) {
        try {
            audit(action, decision, reason, summary);
        } catch (RuntimeException auditFailure) {
            if (auditFailure != operationFailure) {
                auditFailure.addSuppressed(operationFailure);
            }
            throw auditFailure;
        }
    }

    /**
     * 处理{@code decision}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String decision(RuntimeException exception) {
        return exception instanceof ServiceException serviceException
            && Integer.valueOf(HttpStatus.FORBIDDEN).equals(serviceException.getCode())
            ? "deny" : "failure";
    }

    /**
     * 处理{@code reason}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String reason(RuntimeException exception) {
        if (exception instanceof ServiceException serviceException && serviceException.getCode() != null) {
            return "service_error(code=" + serviceException.getCode() + ")";
        }
        return "runtime_error(" + exception.getClass().getSimpleName() + ")";
    }
}
