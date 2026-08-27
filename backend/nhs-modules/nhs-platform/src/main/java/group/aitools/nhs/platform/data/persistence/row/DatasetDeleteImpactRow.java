package group.aitools.nhs.platform.data.persistence.row;

import lombok.Data;

/** Anonymous blocking-reference counts used to guard dataset deletion. */
@Data
public class DatasetDeleteImpactRow {

    private Long activeTaskBindings;
    private Long activeReports;
    private Long runningDataQueries;
    private Long runningProfileJobs;
    private Long draftSmartImports;
    private Long draftCatalogImports;
    private Long runningMetadataSyncs;
    private Long activeAgentDatasetBindings;
    private Long activePermissionProfileReferences;
    private Long activePermissionOverrideReferences;
    private Long activeTemporaryGrantReferences;
    private Long activePermissionSnapshotReferences;
}
