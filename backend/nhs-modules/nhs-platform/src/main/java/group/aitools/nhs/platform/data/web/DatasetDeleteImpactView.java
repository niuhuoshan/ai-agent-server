package group.aitools.nhs.platform.data.web;

import java.util.List;

/** Aggregated deletion blockers without exposing referenced resource details. */
public record DatasetDeleteImpactView(
    Long datasetId,
    List<CategoryView> categories,
    long blockingTotal,
    boolean deletable
) {

    public DatasetDeleteImpactView {
        categories = List.copyOf(categories);
    }

    public record CategoryView(String category, long count) {
    }
}
