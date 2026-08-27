package group.aitools.nhs.platform.audit.web;

import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetadataChangeView;

import java.util.List;

/**
 * 封装元数据ChangelogPage相关的不可变数据。
 */
public record MetadataChangelogPageView(
    long total,
    int page,
    int size,
    List<MetadataChangeView> items
) {
}
