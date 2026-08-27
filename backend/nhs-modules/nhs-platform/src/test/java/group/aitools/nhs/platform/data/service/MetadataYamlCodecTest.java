package group.aitools.nhs.platform.data.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class MetadataYamlCodecTest {

    private final MetadataYamlCodec codec = new MetadataYamlCodec();

    @Test
    void canonicalRoundTripSortsResourcesAndOmitsDocumentMarker() {
        var column = new MetadataYamlCodec.ColumnDocument(
            "id", "bigint", "订单ID", "订单唯一标识", true, false,
            "active", List.of(), List.of("订单编号")
        );
        var document = new MetadataYamlCodec.CatalogDocument(
            1,
            new MetadataYamlCodec.DatasetDocument("sales", "销售数据", "销售语义目录"),
            List.of(new MetadataYamlCodec.TableDocument(
                "public", "orders", "订单", "订单事实表", "table", "active",
                List.of("销售订单"), List.of(column)
            )),
            List.of(),
            List.of()
        );

        String first = codec.write(document);
        String second = codec.write(codec.parse(first));

        assertEquals(first, second);
        assertFalse(first.startsWith("---"));
    }

    @Test
    void rejectsUnknownFieldsAndCaseInsensitiveDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> codec.parse("""
            version: 1
            unsupported: true
            tables: []
            """));
        assertThrows(IllegalArgumentException.class, () -> codec.parse("""
            version: 1
            tables:
              - schema: public
                name: Orders
                columns: []
              - schema: PUBLIC
                name: orders
                columns: []
        """));
    }

    @Test
    void enforcesYamlResourceLimitsAndDisallowsAliases() {
        assertThrows(IllegalArgumentException.class, () -> codec.parse("""
            version: 1
            tables: &table_set
              - schema: public
                name: orders
                columns: []
            metrics: []
            relationships: *table_set
            """));
        String oversized = "version: 1\ntables:\n  - schema: public\n    name: orders\n    description: '"
            + "x".repeat(100_001) + "'\n    columns: []\n";
        assertThrows(IllegalArgumentException.class, () -> codec.parse(oversized));
        StringBuilder deep = new StringBuilder("version: 1\nunknown_0:\n");
        IntStream.range(1, 70).forEach(level -> deep
            .append("  ".repeat(level)).append("unknown_").append(level).append(":\n"));
        deep.append("  ".repeat(70)).append("value: true\n");
        assertThrows(IllegalArgumentException.class, () -> codec.parse(deep.toString()));
    }
}
