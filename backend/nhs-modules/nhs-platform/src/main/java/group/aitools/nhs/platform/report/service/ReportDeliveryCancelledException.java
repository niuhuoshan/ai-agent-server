package group.aitools.nhs.platform.report.service;

/**
 * 表示报表DeliveryCancelled处理过程中发生的业务异常。
 * Signals that a queued delivery is obsolete and must not be retried. */
public class ReportDeliveryCancelledException extends RuntimeException {

    public ReportDeliveryCancelledException(String message) {
        super(message);
    }
}
