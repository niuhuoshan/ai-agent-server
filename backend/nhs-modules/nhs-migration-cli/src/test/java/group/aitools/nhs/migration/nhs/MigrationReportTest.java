package group.aitools.nhs.migration.nhs;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class MigrationReportTest {

    @Test
    void warningsDoNotFailButErrorsDo() {
        MigrationReport warning = new MigrationReport(new JsonCodec(), "inventory", "warning-run");
        warning.issue("warning", "REVIEW", "models", "1", "review needed");
        warning.add(MigrationReport.EntityResult.inventory("models", "passed", 1, "hash", Map.of()));
        assertTrue(warning.canPass());
        warning.finish(true);
        assertTrue(warning.passed());

        MigrationReport error = new MigrationReport(new JsonCodec(), "inventory", "error-run");
        error.issue("error", "BROKEN", "models", "1", "broken");
        assertFalse(error.canPass());
        error.finish(true);
        assertFalse(error.passed());
    }
}
