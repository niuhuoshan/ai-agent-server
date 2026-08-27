package group.aitools.nhs.platform.knowledge.service;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表示知识库文档Parser相关的领域对象。
 * Bounded Apache Tika extraction with a small metadata allow-list. */
@Component
public class KnowledgeDocumentParser {

    private static final int MAX_EXTRACTED_CHARACTERS = 2_000_000;
    private static final byte[] RAR4_MAGIC = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00};
    private static final byte[] RAR5_MAGIC = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00};
    private static final Set<String> RAR_MIME_TYPES = Set.of(
        "application/vnd.rar", "application/x-rar-compressed"
    );
    private static final Set<String> METADATA_KEYS = Set.of(
        "title", "Author", "creator", "Content-Type", "xmpTPg:NPages",
        "meta:page-count", "dcterms:created", "dcterms:modified"
    );

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param fileName 名称
     * @param declaredMimeType 业务类型
     * @return 处理结果
     */
    public ParsedDocument parse(InputStream input, String fileName, String declaredMimeType) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        PushbackInputStream inspectedInput = inspectSupportedFormat(input, fileName, declaredMimeType);
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        if (declaredMimeType != null && !declaredMimeType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, declaredMimeType);
        }
        WriteOutContentHandler limited = new WriteOutContentHandler(MAX_EXTRACTED_CHARACTERS);
        BodyContentHandler handler = new BodyContentHandler(limited);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            /**
             * 处理{@code shouldParseEmbedded}并返回对应结果。
             *
             * @param embeddedMetadata embedded元数据参数
             * @return 判断结果，{@code true} 表示条件成立
             */
            @Override
            public boolean shouldParseEmbedded(Metadata embeddedMetadata) {
                return false;
            }

            /**
             * 处理{@code parseEmbedded}相关逻辑。
             *
             * @param embeddedInput {@code embeddedInput}参数
             * @param embeddedHandler {@code embeddedHandler}参数
             * @param embeddedMetadata embedded元数据参数
             * @param outputHtml {@code outputHtml}参数
             */
            @Override
            public void parseEmbedded(
                InputStream embeddedInput,
                ContentHandler embeddedHandler,
                Metadata embeddedMetadata,
                boolean outputHtml
            ) {
                // Embedded objects are excluded from the phase-one searchable document boundary.
            }
        });
        try {
            parser.parse(inspectedInput, handler, metadata, context);
        } catch (WriteLimitReachedException exception) {
            throw new IllegalArgumentException("文档解析正文超过 200 万字符限制");
        } catch (IOException | SAXException | TikaException exception) {
            throw new IllegalArgumentException("文档格式无法解析", exception);
        }
        String content = normalize(handler.toString());
        if (content.isBlank()) {
            throw new IllegalArgumentException("文档没有可检索正文");
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String key : METADATA_KEYS) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                selected.put(key, value.length() <= 2000 ? value : value.substring(0, 2000));
            }
        }
        return new ParsedDocument(
            content,
            metadata.get(Metadata.CONTENT_TYPE),
            Map.copyOf(selected),
            parser.getClass().getSimpleName()
        );
    }

    /**
     * 处理{@code inspectSupportedFormat}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param fileName 名称
     * @param declaredMimeType 业务类型
     * @return 处理结果
     */
    private PushbackInputStream inspectSupportedFormat(
        InputStream input,
        String fileName,
        String declaredMimeType
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (input == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        String normalizedName = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        String normalizedMimeType = declaredMimeType == null
            ? ""
            : declaredMimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".rar") || RAR_MIME_TYPES.contains(normalizedMimeType)) {
            throw unsupportedRar();
        }

        PushbackInputStream inspected = new PushbackInputStream(input, RAR5_MAGIC.length);
        byte[] header = new byte[RAR5_MAGIC.length];
        try {
            int read = inspected.read(header);
            if (read > 0) {
                inspected.unread(header, 0, read);
            }
            if (startsWith(header, read, RAR4_MAGIC) || startsWith(header, read, RAR5_MAGIC)) {
                throw unsupportedRar();
            }
            return inspected;
        } catch (IOException exception) {
            throw new IllegalArgumentException("文档格式无法读取", exception);
        }
    }

    /**
     * 处理{@code startsWith}并返回对应结果。
     *
     * @param content 待处理内容
     * @param contentLength 待处理内容
     * @param prefix {@code prefix}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean startsWith(byte[] content, int contentLength, byte[] prefix) {
        if (contentLength < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 处理{@code unsupportedRar}并返回对应结果。
     *
     * @return 处理结果
     */
    private IllegalArgumentException unsupportedRar() {
        return new IllegalArgumentException("一期不支持 RAR 文档");
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        String normalized = value.replace('\0', ' ').replace('\uFFFD', ' ')
            .replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("[\\t\\x0B\\f ]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.strip();
    }

    /**
     * 封装Parsed文档相关的不可变数据。
     */
    public record ParsedDocument(
        String content,
        String detectedMimeType,
        Map<String, Object> metadata,
        String parserType
    ) {
    }
}
