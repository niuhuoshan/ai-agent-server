package group.aitools.nhs.platform.portal.quota.persistence.row;

import lombok.Data;

/**
 * 表示Quota用户相关的领域对象。
 * Minimal user projection for quota administration. */
@Data
public class QuotaUserRow {

    private Long userId;
    private String userName;
}
