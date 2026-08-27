import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;

import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描 NHS 后端生产源码，并为类与方法补充统一的中文 JavaDoc。
 *
 * <p>工具只处理 {@code group.aitools.nhs} 包，避免修改第三方源码；已有中文注释会原样保留，
 * 英文 JavaDoc 会在原内容前补充中文职责说明。对于分支较多或代码较长的方法，工具还会在
 * 方法体入口补充控制流说明。</p>
 */
public final class ChineseCommentGenerator {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+group\\.aitools\\.nhs(?:\\.|;)");
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u3400-\\u9fff]");
    private static final Pattern CHINESE_LINE_COMMENT = Pattern.compile("//[^\\r\\n]*[\\u3400-\\u9fff]");
    private static final Pattern CHINESE_BLOCK_COMMENT = Pattern.compile("/\\*(?s:.*?)?[\\u3400-\\u9fff](?s:.*?)?\\*/");
    private static final Pattern CONSTRUCTOR_DESCRIPTION = Pattern.compile(
        "^\\s*创建\\s+\\{@code\\s+([A-Za-z0-9_$]+)\\s*}\\s+实例并初始化所需依赖。\\s*$"
    );
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        ".git", "target", "build", "data", "logs", "node_modules"
    );
    private static final Map<String, String> WORDS = createWordDictionary();
    private static final List<String> TYPE_SUFFIXES = List.of(
        "Configuration", "Controller", "ApplicationService", "ServiceImpl", "Properties",
        "Repository", "Initializer", "Interceptor", "Serializer", "Deserializer", "Validator",
        "Exception", "Application", "Converter", "Decorator", "Provider", "Processor", "Resolver",
        "Listener", "Scheduler", "Dispatcher", "Executor", "Factory", "Adapter", "Handler", "Filter",
        "Manager", "Support", "Template", "Strategy", "Client", "Mapper", "Service", "Request",
        "Response", "Result", "View", "Entity", "Domain", "Configuration", "Config", "Event",
        "Command", "Query", "Context", "Metadata", "Properties", "Record", "Row", "Dto", "DTO",
        "Vo", "VO", "Bo", "BO", "Enum", "Type"
    );

    private ChineseCommentGenerator() {
    }

    /**
     * 执行注释扫描或写入任务。
     *
     * @param args 第一个参数为源码根目录，{@code --write} 表示写入，{@code --check} 表示校验
     * @throws Exception 当源码读取、解析或写入失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("用法: java ChineseCommentGenerator.java <backend-root> [--write|--check]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        boolean write = contains(args, "--write");
        boolean check = contains(args, "--check");
        List<Path> sources = collectSources(root);
        GenerationReport report = processSources(sources, write);
        System.out.printf(
            Locale.ROOT,
            "扫描 %d 个源码文件、%d 个类型、%d 个方法；缺少中文注释：类型 %d、方法 %d、复杂流程 %d；写入 %d 个文件。%n",
            report.sourceFiles(), report.types(), report.methods(), report.missingTypeDocs(),
            report.missingMethodDocs(), report.missingComplexComments(), report.changedFiles()
        );
        if (check && report.hasGaps()) {
            System.exit(1);
        }
    }

    /**
     * 判断参数列表中是否包含指定选项。
     *
     * @param args 参数列表
     * @param expected 目标选项
     * @return 包含目标选项时返回 {@code true}
     */
    private static boolean contains(String[] args, String expected) {
        for (String argument : args) {
            if (expected.equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集生产源码目录中的 NHS Java 文件，并跳过构建输出与运行数据目录。
     *
     * @param root 后端工程根目录
     * @return 按路径排序后的源码文件
     * @throws IOException 当目录遍历失败时抛出
     */
    private static List<Path> collectSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path fileName = directory.getFileName();
                if (fileName != null && SKIPPED_DIRECTORIES.contains(fileName.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/src/main/java/") && normalized.endsWith(".java")) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (PACKAGE_PATTERN.matcher(source).find()) {
                        sources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
        sources.sort(Comparator.naturalOrder());
        return sources;
    }

    /**
     * 使用 JDK 语法树解析源码，统计注释缺口并按需写入生成结果。
     *
     * @param sources 待处理源码文件
     * @param write 是否将生成内容写回源码
     * @return 本轮扫描与写入统计
     * @throws IOException 当编译器文件管理或源码写入失败时抛出
     */
    private static GenerationReport processSources(List<Path> sources, boolean write) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("必须使用完整 JDK 运行注释生成工具");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
            diagnostics, Locale.ROOT, StandardCharsets.UTF_8
        )) {
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(
                null,
                fileManager,
                diagnostics,
                List.of("-proc:none", "--release", "21", "-encoding", "UTF-8"),
                null,
                fileObjects
            );
            Iterable<? extends CompilationUnitTree> units = task.parse();
            DocTrees docTrees = DocTrees.instance(task);
            SourcePositions positions = docTrees.getSourcePositions();
            MutableReport report = new MutableReport(sources.size());
            Map<Path, List<Edit>> editsByFile = new LinkedHashMap<>();

            for (CompilationUnitTree unit : units) {
                Path file = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                String source = Files.readString(file, StandardCharsets.UTF_8);
                List<Edit> edits = new ArrayList<>();
                new CommentScanner(unit, source, docTrees, positions, edits, report).scan(unit, null);
                if (!edits.isEmpty()) {
                    editsByFile.put(file, edits);
                }
            }

            if (!diagnostics.getDiagnostics().isEmpty()) {
                long errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .count();
                if (errors > 0) {
                    throw new IllegalStateException("Java 源码解析失败，共 " + errors + " 个错误");
                }
            }
            if (write) {
                for (Map.Entry<Path, List<Edit>> entry : editsByFile.entrySet()) {
                    String source = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
                    String updated = applyEdits(source, entry.getValue());
                    if (!updated.equals(source)) {
                        Files.writeString(entry.getKey(), updated, StandardCharsets.UTF_8);
                        report.changedFiles++;
                    }
                }
            }
            return report.freeze();
        }
    }

    /**
     * 按位置倒序应用插入操作，避免前面的写入改变后续源码偏移量。
     *
     * @param source 原始源码
     * @param edits 待应用的插入操作
     * @return 写入注释后的源码
     */
    private static String applyEdits(String source, List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::offset).reversed().thenComparing(Edit::priority));
        StringBuilder builder = new StringBuilder(source);
        Set<String> applied = new LinkedHashSet<>();
        for (Edit edit : ordered) {
            String key = edit.offset() + "\0" + edit.length() + "\0" + edit.text();
            if (applied.add(key)) {
                builder.replace(edit.offset(), edit.offset() + edit.length(), edit.text());
            }
        }
        return builder.toString();
    }

    /**
     * 创建常见英文标识到中文领域词的映射表。
     *
     * @return 不可变的标识词典
     */
    private static Map<String, String> createWordDictionary() {
        Map<String, String> words = new HashMap<>();
        words.put("acceptance", "验收");
        words.put("account", "账户");
        words.put("agent", "智能体");
        words.put("api", "接口");
        words.put("application", "应用");
        words.put("approval", "审批");
        words.put("artifact", "制品");
        words.put("attachment", "附件");
        words.put("audit", "审计");
        words.put("auth", "认证");
        words.put("authorization", "授权");
        words.put("automation", "自动化");
        words.put("browser", "浏览器");
        words.put("cache", "缓存");
        words.put("canvas", "画布");
        words.put("catalog", "目录");
        words.put("chat", "对话");
        words.put("client", "客户端");
        words.put("command", "命令");
        words.put("configuration", "配置");
        words.put("connector", "连接器");
        words.put("context", "上下文");
        words.put("conversation", "会话");
        words.put("credential", "凭据");
        words.put("current", "当前");
        words.put("data", "数据");
        words.put("dataset", "数据集");
        words.put("definition", "定义");
        words.put("directory", "目录");
        words.put("document", "文档");
        words.put("embed", "嵌入式会话");
        words.put("event", "事件");
        words.put("execution", "执行");
        words.put("export", "导出");
        words.put("feedback", "反馈");
        words.put("file", "文件");
        words.put("health", "健康状态");
        words.put("history", "历史记录");
        words.put("identity", "身份");
        words.put("import", "导入");
        words.put("invocation", "调用");
        words.put("job", "作业");
        words.put("knowledge", "知识库");
        words.put("login", "登录");
        words.put("memory", "记忆");
        words.put("message", "消息");
        words.put("metadata", "元数据");
        words.put("migration", "迁移");
        words.put("model", "模型");
        words.put("notification", "通知");
        words.put("operation", "操作");
        words.put("permission", "权限");
        words.put("platform", "平台");
        words.put("policy", "策略");
        words.put("portal", "门户");
        words.put("principal", "操作主体");
        words.put("profile", "配置档案");
        words.put("project", "项目");
        words.put("prompt", "提示词");
        words.put("provider", "提供方");
        words.put("query", "查询");
        words.put("question", "追问");
        words.put("report", "报表");
        words.put("resource", "资源");
        words.put("result", "结果");
        words.put("risk", "风险");
        words.put("role", "角色");
        words.put("runtime", "运行时");
        words.put("sandbox", "沙箱");
        words.put("schedule", "调度");
        words.put("scope", "范围");
        words.put("security", "安全");
        words.put("session", "会话");
        words.put("skill", "技能");
        words.put("snapshot", "快照");
        words.put("source", "数据源");
        words.put("statistics", "统计");
        words.put("storage", "存储");
        words.put("system", "系统");
        words.put("task", "任务");
        words.put("template", "模板");
        words.put("tenant", "租户");
        words.put("timeline", "时间线");
        words.put("token", "令牌");
        words.put("tool", "工具");
        words.put("trace", "链路追踪");
        words.put("turn", "会话回合");
        words.put("user", "用户");
        words.put("version", "版本");
        words.put("webhook", "回调通知");
        words.put("worker", "工作进程");
        words.put("workflow", "工作流");
        words.put("workspace", "工作空间");
        return Map.copyOf(words);
    }

    /**
     * 将驼峰或下划线标识拆分为单词列表。
     *
     * @param identifier Java 标识
     * @return 拆分后的单词
     */
    private static List<String> splitWords(String identifier) {
        String normalized = identifier
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split("\\s+"));
    }

    /**
     * 将类名或方法名中的领域词转换为简洁的中文主题。
     *
     * @param identifier Java 标识
     * @return 中文主题；无法翻译时返回代码标识
     */
    private static String translateSubject(String identifier) {
        List<String> words = splitWords(identifier);
        if (words.isEmpty()) {
            return "相关对象";
        }
        StringBuilder translated = new StringBuilder();
        boolean hasTranslation = false;
        for (String word : words) {
            String value = WORDS.get(word.toLowerCase(Locale.ROOT));
            if (value != null) {
                translated.append(value);
                hasTranslation = true;
            } else if (word.length() > 1) {
                translated.append(word);
            }
        }
        return hasTranslation ? translated.toString() : "{@code " + identifier + "}";
    }

    /**
     * 去除类型名中的技术后缀，以便生成面向业务职责的中文主题。
     *
     * @param simpleName 类型简单名称
     * @return 去除后缀后的名称
     */
    private static String stripTypeSuffix(String simpleName) {
        for (String suffix : TYPE_SUFFIXES) {
            if (simpleName.endsWith(suffix) && simpleName.length() > suffix.length()) {
                return simpleName.substring(0, simpleName.length() - suffix.length());
            }
        }
        return simpleName;
    }

    /**
     * 根据类型名称、声明种类和修饰符生成职责说明。
     *
     * @param type 类型声明
     * @return 中文类注释首句
     */
    private static String describeType(ClassTree type) {
        String name = type.getSimpleName().toString();
        String subject = translateSubject(stripTypeSuffix(name));
        boolean contract = type.getKind() == Tree.Kind.INTERFACE;
        if (type.getKind() == Tree.Kind.ENUM) {
            return "定义" + subject + "相关的可选值。";
        }
        if (type.getKind() == Tree.Kind.ANNOTATION_TYPE) {
            return "声明" + subject + "相关的注解元数据。";
        }
        if (type.getKind() == Tree.Kind.RECORD) {
            return "封装" + subject + "相关的不可变数据。";
        }
        if (name.endsWith("Controller")) {
            return "提供" + subject + "相关的 HTTP 接口，并负责请求校验与结果返回。";
        }
        if (name.endsWith("Mapper") || name.endsWith("Repository")) {
            return contract ? "定义" + subject + "相关的数据访问契约。" : "提供" + subject + "相关的数据访问能力。";
        }
        if (name.endsWith("Service") || name.endsWith("ServiceImpl")) {
            return contract ? "定义" + subject + "相关的业务服务契约。" : "负责" + subject + "相关的业务编排与领域规则处理。";
        }
        if (name.endsWith("Configuration") || name.endsWith("Config")) {
            return "配置" + subject + "相关组件及其运行参数。";
        }
        if (name.endsWith("Properties")) {
            return "承载" + subject + "相关的外部化配置属性。";
        }
        if (name.endsWith("Request") || name.endsWith("Command")) {
            return "封装" + subject + "操作的请求参数。";
        }
        if (name.endsWith("Response") || name.endsWith("Result") || name.endsWith("View")
            || name.endsWith("VO") || name.endsWith("Vo") || name.endsWith("DTO") || name.endsWith("Dto")) {
            return "表示" + subject + "操作的返回数据。";
        }
        if (name.endsWith("Exception")) {
            return "表示" + subject + "处理过程中发生的业务异常。";
        }
        if (name.endsWith("Converter") || name.endsWith("Resolver") || name.endsWith("Processor")
            || name.endsWith("Handler") || name.endsWith("Provider") || name.endsWith("Factory")) {
            return contract ? "定义" + subject + "相关的处理能力契约。" : "负责" + subject + "相关的转换、解析或处理逻辑。";
        }
        if (name.endsWith("Application")) {
            return "启动并初始化" + subject + "应用运行环境。";
        }
        if (contract) {
            return "定义" + subject + "相关能力的服务契约。";
        }
        return "表示" + subject + "相关的领域对象。";
    }

    /**
     * 根据方法名与返回类型生成中文方法职责说明。
     *
     * @param owner 所属类型名称
     * @param method 方法声明
     * @return 中文方法注释首句
     */
    private static String describeMethod(String owner, MethodTree method) {
        String name = method.getName().toString();
        if (method.getReturnType() == null || "<init>".equals(name)) {
            return "创建 {@code " + owner + "} 实例并初始化所需依赖。";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String subject = translateSubject(removeMethodPrefix(name));
        if (lower.startsWith("get") || lower.startsWith("find") || lower.startsWith("load")
            || lower.startsWith("resolve") || lower.startsWith("select") || lower.startsWith("query")) {
            return "获取" + subject + "。";
        }
        if (lower.startsWith("list") || lower.startsWith("page") || lower.startsWith("search")) {
            return "查询" + subject + "列表。";
        }
        if (lower.startsWith("create") || lower.startsWith("add") || lower.startsWith("register")
            || lower.startsWith("insert")) {
            return "创建并保存" + subject + "。";
        }
        if (lower.startsWith("save")) {
            return "保存" + subject + "。";
        }
        if (lower.startsWith("update") || lower.startsWith("modify") || lower.startsWith("change")) {
            return "更新" + subject + "。";
        }
        if (lower.startsWith("delete") || lower.startsWith("remove")) {
            return "删除" + subject + "。";
        }
        if (lower.startsWith("clear") || lower.startsWith("reset")) {
            return "清理或重置" + subject + "。";
        }
        if (lower.startsWith("validate") || lower.startsWith("check") || lower.startsWith("require")
            || lower.startsWith("ensure") || lower.startsWith("verify")) {
            return "校验" + subject + "，并在条件不满足时终止处理。";
        }
        if (lower.startsWith("is") || lower.startsWith("has") || lower.startsWith("can")
            || lower.startsWith("supports") || lower.startsWith("matches")) {
            return "判断" + subject + "是否满足要求。";
        }
        if (lower.startsWith("to") || lower.startsWith("convert") || lower.startsWith("map")) {
            return "将输入数据转换为" + subject + "。";
        }
        if (lower.startsWith("build") || lower.startsWith("assemble")) {
            return "构建" + subject + "。";
        }
        if (lower.startsWith("handle") || lower.startsWith("process") || lower.startsWith("execute")
            || lower.startsWith("run") || lower.startsWith("invoke") || lower.startsWith("dispatch")) {
            return "执行" + subject + "相关的处理流程。";
        }
        if (lower.startsWith("set")) {
            return "设置" + subject + "。";
        }
        return method.getReturnType().getKind() == Tree.Kind.PRIMITIVE_TYPE
            && "void".equals(method.getReturnType().toString())
            ? "处理" + subject + "相关逻辑。"
            : "处理" + subject + "并返回对应结果。";
    }

    /**
     * 去除常见方法动作前缀，保留用于描述的业务对象名称。
     *
     * @param name 方法名称
     * @return 去除动作前缀后的名称
     */
    private static String removeMethodPrefix(String name) {
        List<String> prefixes = List.of(
            "supports", "validate", "register", "convert", "execute", "dispatch", "process", "require",
            "resolve", "remove", "delete", "update", "create", "search", "select", "verify", "ensure",
            "assemble", "handle", "insert", "modify", "change", "clear", "reset", "build", "query",
            "list", "page", "find", "load", "save", "check", "invoke", "matches", "get", "set", "add",
            "run", "map", "has", "can", "is", "to"
        );
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix) && name.length() > prefix.length()) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }

    /**
     * 生成方法参数的中文说明。
     *
     * @param parameter 参数声明
     * @return 参数用途说明
     */
    private static String describeParameter(VariableTree parameter) {
        String name = parameter.getName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("request") || lower.endsWith("request")) {
            return "请求参数";
        }
        if (lower.equals("principal") || lower.endsWith("principal")) {
            return "当前操作主体";
        }
        if (lower.equals("id") || lower.endsWith("id")) {
            return "资源标识";
        }
        if (lower.equals("ids") || lower.endsWith("ids")) {
            return "资源标识集合";
        }
        if (lower.contains("limit") || lower.contains("size")) {
            return "数量上限";
        }
        if (lower.contains("offset") || lower.contains("sequence")) {
            return "起始位置或序号";
        }
        if (lower.contains("status")) {
            return "目标状态";
        }
        if (lower.contains("type")) {
            return "业务类型";
        }
        if (lower.contains("name")) {
            return "名称";
        }
        if (lower.contains("content") || lower.contains("text") || lower.contains("message")) {
            return "待处理内容";
        }
        return translateSubject(name) + "参数";
    }

    /**
     * 生成方法返回值说明。
     *
     * @param method 方法声明
     * @return 返回值用途说明
     */
    private static String describeReturn(MethodTree method) {
        String returnType = method.getReturnType().toString();
        if ("boolean".equals(returnType) || "Boolean".equals(returnType)) {
            return "判断结果，{@code true} 表示条件成立";
        }
        if (returnType.startsWith("List<") || returnType.startsWith("Set<") || returnType.startsWith("Collection<")) {
            return "符合条件的数据集合";
        }
        if (returnType.startsWith("Optional<")) {
            return "可能为空的处理结果";
        }
        return "处理结果";
    }

    /**
     * 生成完整的类 JavaDoc 文本。
     *
     * @param indentation 当前声明缩进
     * @param description 中文职责说明
     * @return 可直接插入源码的 JavaDoc
     */
    private static String typeJavadoc(String indentation, String description) {
        return indentation + "/**\n"
            + indentation + " * " + description + "\n"
            + indentation + " */\n";
    }

    /**
     * 生成完整的方法 JavaDoc，包括参数、返回值和显式异常说明。
     *
     * @param indentation 当前声明缩进
     * @param owner 所属类型名称
     * @param method 方法声明
     * @return 可直接插入源码的 JavaDoc
     */
    private static String methodJavadoc(String indentation, String owner, MethodTree method) {
        StringBuilder comment = new StringBuilder();
        comment.append(indentation).append("/**\n");
        comment.append(indentation).append(" * ").append(describeMethod(owner, method)).append("\n");
        if (!method.getParameters().isEmpty() || method.getReturnType() != null
            && !"void".equals(method.getReturnType().toString()) || !method.getThrows().isEmpty()) {
            comment.append(indentation).append(" *\n");
        }
        for (VariableTree parameter : method.getParameters()) {
            comment.append(indentation).append(" * @param ").append(parameter.getName()).append(' ')
                .append(describeParameter(parameter)).append("\n");
        }
        if (method.getReturnType() != null && !"void".equals(method.getReturnType().toString())) {
            comment.append(indentation).append(" * @return ").append(describeReturn(method)).append("\n");
        }
        for (Tree thrownType : method.getThrows()) {
            comment.append(indentation).append(" * @throws ").append(thrownType)
                .append(" 当处理过程无法正常完成时抛出\n");
        }
        comment.append(indentation).append(" */\n");
        return comment.toString();
    }

    /**
     * 获取声明及其注解之前的最早源码位置。
     *
     * @param unit 编译单元
     * @param positions 源码位置服务
     * @param declaration 声明节点
     * @param annotations 声明注解
     * @return 最早有效源码位置
     */
    private static int declarationStart(
        CompilationUnitTree unit,
        SourcePositions positions,
        Tree declaration,
        List<? extends Tree> annotations
    ) {
        long start = positions.getStartPosition(unit, declaration);
        for (Tree annotation : annotations) {
            long annotationStart = positions.getStartPosition(unit, annotation);
            if (annotationStart >= 0 && (start < 0 || annotationStart < start)) {
                start = annotationStart;
            }
        }
        return Math.toIntExact(start);
    }

    /**
     * 计算声明所在行的起点与缩进，确保 JavaDoc 位于注解之前。
     *
     * @param source 源码文本
     * @param declarationStart 声明起始位置
     * @return 插入位置与缩进信息
     */
    private static InsertionPoint insertionPoint(String source, int declarationStart) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, declarationStart - 1)) + 1;
        String prefix = source.substring(lineStart, declarationStart);
        if (prefix.isBlank()) {
            return new InsertionPoint(lineStart, prefix);
        }
        return new InsertionPoint(declarationStart, "");
    }

    /**
     * 查找附着在声明上的 JavaDoc 起始位置。
     *
     * @param source 源码文本
     * @param declarationStart 声明起始位置
     * @return JavaDoc 起始位置，未找到时返回 {@code -1}
     */
    private static int findAttachedJavadocStart(String source, int declarationStart) {
        int end = source.lastIndexOf("*/", declarationStart);
        if (end < 0) {
            return -1;
        }
        int start = source.lastIndexOf("/**", end);
        if (start < 0 || !isDeclarationGlue(source.substring(end + 2, declarationStart))) {
            return -1;
        }
        return start;
    }

    /**
     * 判断 JavaDoc 结束符到声明之间是否只有注解或修饰符。
     *
     * @param glue JavaDoc 与声明之间的源码片段
     * @return 片段可视为同一声明的装饰部分时返回 {@code true}
     */
    private static boolean isDeclarationGlue(String glue) {
        String[] lines = glue.split("\\R");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith("@")) {
                continue;
            }
            if (value.matches("(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp|synchronized|native|default|transient|volatile)(?:\\s+.*)?")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 读取附着在声明上的 JavaDoc 文本。
     *
     * @param source 源码文本
     * @param declarationStart 声明起始位置
     * @return JavaDoc 内容，未找到时返回 {@code null}
     */
    private static String attachedJavadoc(String source, int declarationStart) {
        int start = findAttachedJavadocStart(source, declarationStart);
        if (start < 0) {
            return null;
        }
        int end = source.indexOf("*/", start + 3);
        return end < 0 ? null : source.substring(start, end + 2);
    }

    /**
     * 判断类 JavaDoc 的首句是否误用了构造方法说明。
     *
     * @param doc JavaDoc 文本
     * @param typeName 类名
     * @return 首句对应当前类的构造方法说明时返回 {@code true}
     */
    private static boolean hasMisplacedConstructorDescription(String doc, String typeName) {
        if (doc == null) {
            return false;
        }
        String body = doc.substring(3, Math.max(3, doc.length() - 2));
        for (String line : body.split("\\R")) {
            String content = line.trim();
            if (content.isEmpty() || "*".equals(content)) {
                continue;
            }
            if (content.startsWith("*")) {
                content = content.substring(1).trim();
            }
            Matcher matcher = CONSTRUCTOR_DESCRIPTION.matcher(content);
            return matcher.matches() && typeName.equals(matcher.group(1));
        }
        return false;
    }

    /**
     * 查找 JavaDoc 首个有效内容行的替换范围。
     *
     * @param source 源码文本
     * @param commentStart JavaDoc 起始偏移
     * @return 首个有效内容行范围，无法定位时返回 {@code null}
     */
    private static JavadocLineRange firstJavadocContentRange(String source, int commentStart) {
        int cursor = source.indexOf('\n', commentStart + 3);
        if (cursor < 0) {
            return null;
        }
        while (cursor >= 0 && cursor < source.length()) {
            int lineStart = cursor + 1;
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = source.length();
            }
            String line = source.substring(lineStart, lineEnd);
            String content = line.trim();
            if (!content.isEmpty() && !"*".equals(content) && !"*/".equals(content)) {
                int star = line.indexOf('*');
                if (star >= 0) {
                    return new JavadocLineRange(lineStart + star + 1, lineEnd);
                }
                return null;
            }
            if (line.contains("*/")) {
                return null;
            }
            cursor = lineEnd;
        }
        return null;
    }

    /**
     * 创建替换类 JavaDoc 首句的编辑操作，避免构造方法说明污染类职责说明。
     *
     * @param source 源码文本
     * @param commentStart JavaDoc 起始偏移
     * @param description 正确的类职责说明
     * @return 替换编辑操作，无法定位时返回 {@code null}
     */
    private static Edit repairTypeDescription(String source, int commentStart, String description) {
        JavadocLineRange range = firstJavadocContentRange(source, commentStart);
        if (range == null) {
            return null;
        }
        return new Edit(range.start(), " " + description, range.end() - range.start(), 5);
    }

    /**
     * 查找类职责说明被重复写入时应删除的源码范围。
     *
     * @param source 源码文本
     * @param commentStart JavaDoc 起始偏移
     * @param description 正确的类职责说明
     * @return 重复内容范围，未发现时返回 {@code null}
     */
    private static JavadocLineRange duplicatedTypeDescriptionRange(
        String source,
        int commentStart,
        String description
    ) {
        int commentEnd = source.indexOf("*/", commentStart + 3);
        if (commentEnd < 0) {
            return null;
        }
        int cursor = source.indexOf('\n', commentStart + 3);
        if (cursor < 0 || cursor >= commentEnd) {
            return null;
        }
        boolean firstDescription = false;
        int blankStart = -1;
        while (cursor >= 0 && cursor < commentEnd) {
            int lineStart = cursor + 1;
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > commentEnd) {
                lineEnd = commentEnd;
            }
            String line = source.substring(lineStart, lineEnd);
            int star = line.indexOf('*');
            String content = star >= 0 ? line.substring(star + 1).trim() : line.trim();
            if (content.isEmpty() || "/".equals(content)) {
                if (firstDescription && blankStart < 0) {
                    blankStart = lineStart;
                }
            } else if (description.equals(content)) {
                if (!firstDescription) {
                    firstDescription = true;
                    blankStart = -1;
                } else if (blankStart >= 0) {
                    int removalEnd = lineEnd;
                    if (removalEnd < source.length() && source.charAt(removalEnd) == '\n') {
                        removalEnd++;
                    }
                    return new JavadocLineRange(blankStart, removalEnd);
                }
            } else if (firstDescription) {
                // 首句之后出现其他说明，后续同名文本不属于生成器重复内容。
                return null;
            }
            cursor = lineEnd;
        }
        return null;
    }

    /**
     * 创建删除重复类职责说明的编辑操作。
     *
     * @param source 源码文本
     * @param commentStart JavaDoc 起始偏移
     * @param description 正确的类职责说明
     * @return 删除编辑操作，未发现重复内容时返回 {@code null}
     */
    private static Edit repairDuplicatedTypeDescription(String source, int commentStart, String description) {
        JavadocLineRange range = duplicatedTypeDescriptionRange(source, commentStart, description);
        return range == null ? null : new Edit(range.start(), "", range.end() - range.start(), 4);
    }

    /**
     * 判断文本中是否包含中文字符。
     *
     * @param text 待检查文本
     * @return 包含中文字符时返回 {@code true}
     */
    private static boolean containsChinese(String text) {
        return text != null && CHINESE_PATTERN.matcher(text).find();
    }

    /**
     * 判断方法体是否已经存在中文行注释或块注释。
     *
     * @param bodySource 方法体源码
     * @return 已存在中文解释性注释时返回 {@code true}
     */
    private static boolean containsChineseCodeComment(String bodySource) {
        return CHINESE_LINE_COMMENT.matcher(bodySource).find() || CHINESE_BLOCK_COMMENT.matcher(bodySource).find();
    }

    /**
     * 根据控制流统计生成复杂方法的入口说明。
     *
     * @param complexity 方法复杂度统计
     * @return 中文控制流说明
     */
    private static String describeComplexFlow(Complexity complexity) {
        if (complexity.tries > 0) {
            return "以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。";
        }
        if (complexity.switches > 0) {
            return "以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。";
        }
        if (complexity.loops > 0) {
            return "以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。";
        }
        return "以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。";
    }

    /**
     * 为复杂方法选择安全的行注释插入位置。
     *
     * @param source 源码文本
     * @param unit 编译单元
     * @param positions 源码位置服务
     * @param body 方法体
     * @param comment 中文行注释内容
     * @return 插入操作；没有安全位置时返回 {@code null}
     */
    private static Edit complexCommentEdit(
        String source,
        CompilationUnitTree unit,
        SourcePositions positions,
        BlockTree body,
        String comment
    ) {
        if (body.getStatements().isEmpty()) {
            return null;
        }
        int statementStart = Math.toIntExact(positions.getStartPosition(unit, body.getStatements().get(0)));
        if (statementStart < 0) {
            return null;
        }
        int lineStart = source.lastIndexOf('\n', Math.max(0, statementStart - 1)) + 1;
        String prefix = source.substring(lineStart, statementStart);
        if (prefix.isBlank()) {
            return new Edit(statementStart, "// " + comment + "\n" + prefix, 20);
        }
        return new Edit(statementStart, "/* " + comment + " */ ", 20);
    }

    /**
     * 扫描单个编译单元中的类型和方法声明，并记录待插入注释。
     */
    private static final class CommentScanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;
        private final String source;
        private final DocTrees docTrees;
        private final SourcePositions positions;
        private final List<Edit> edits;
        private final MutableReport report;
        private final List<String> owners = new ArrayList<>();

        /**
         * 创建编译单元注释扫描器。
         *
         * @param unit 编译单元
         * @param source 源码文本
         * @param docTrees JavaDoc 访问服务
         * @param positions 源码位置服务
         * @param edits 待写入操作集合
         * @param report 扫描统计
         */
        private CommentScanner(
            CompilationUnitTree unit,
            String source,
            DocTrees docTrees,
            SourcePositions positions,
            List<Edit> edits,
            MutableReport report
        ) {
            this.unit = unit;
            this.source = source;
            this.docTrees = docTrees;
            this.positions = positions;
            this.edits = edits;
            this.report = report;
        }

        /**
         * 检查类、接口、枚举、注解和记录声明的中文 JavaDoc。
         *
         * @param type 类型声明
         * @param unused 未使用的扫描上下文
         * @return 扫描结果
         */
        @Override
        public Void visitClass(ClassTree type, Void unused) {
            if (type.getSimpleName().length() == 0) {
                return super.visitClass(type, unused);
            }
            report.types++;
            TreePath path = getCurrentPath();
            String description = describeType(type);
            int start = declarationStart(unit, positions, type, type.getModifiers().getAnnotations());
            String doc = attachedJavadoc(source, start);
            if (doc == null) {
                report.missingTypeDocs++;
                InsertionPoint point = insertionPoint(source, start);
                edits.add(new Edit(point.offset(), typeJavadoc(point.indentation(), description), 10));
            } else if (!containsChinese(doc)) {
                report.missingTypeDocs++;
                int commentStart = findAttachedJavadocStart(source, start);
                if (commentStart >= 0) {
                    edits.add(new Edit(commentStart + 3, "\n * " + description + "\n *", 0, 10));
                }
            }
            if (doc != null && hasMisplacedConstructorDescription(doc, type.getSimpleName().toString())) {
                int commentStart = findAttachedJavadocStart(source, start);
                if (commentStart >= 0) {
                    Edit repair = repairTypeDescription(source, commentStart, description);
                    if (repair != null) {
                        edits.add(repair);
                    }
                }
            }
            if (doc != null && containsChinese(doc)) {
                int commentStart = findAttachedJavadocStart(source, start);
                if (commentStart >= 0) {
                    Edit repair = repairDuplicatedTypeDescription(source, commentStart, description);
                    if (repair != null) {
                        edits.add(repair);
                    }
                }
            }
            owners.add(type.getSimpleName().toString());
            try {
                return super.visitClass(type, unused);
            } finally {
                owners.remove(owners.size() - 1);
            }
        }

        /**
         * 检查方法与构造方法的中文 JavaDoc，并识别需要行注释的复杂流程。
         *
         * @param method 方法声明
         * @param unused 未使用的扫描上下文
         * @return 扫描结果
         */
        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            report.methods++;
            TreePath path = getCurrentPath();
            String owner = owners.isEmpty() ? "当前类型" : owners.get(owners.size() - 1);
            int start = declarationStart(unit, positions, method, method.getModifiers().getAnnotations());
            String doc = attachedJavadoc(source, start);
            if (doc == null) {
                report.missingMethodDocs++;
                InsertionPoint point = insertionPoint(source, start);
                edits.add(new Edit(point.offset(), methodJavadoc(point.indentation(), owner, method), 10));
            } else if (!containsChinese(doc)) {
                report.missingMethodDocs++;
                int commentStart = findAttachedJavadocStart(source, start);
                if (commentStart >= 0) {
                    edits.add(new Edit(
                        commentStart + 3,
                        "\n * " + describeMethod(owner, method) + "\n *",
                        0,
                        10
                    ));
                }
            }
            inspectComplexBody(method);
            return super.visitMethod(method, unused);
        }

        /**
         * 统计方法控制流复杂度，并在缺少解释时安排入口注释。
         *
         * @param method 方法声明
         */
        private void inspectComplexBody(MethodTree method) {
            BlockTree body = method.getBody();
            if (body == null) {
                return;
            }
            long start = positions.getStartPosition(unit, body);
            long end = positions.getEndPosition(unit, body);
            if (start < 0 || end <= start || end > source.length()) {
                return;
            }
            Complexity complexity = new Complexity();
            complexity.scan(body, null);
            int lines = countLines(source, Math.toIntExact(start), Math.toIntExact(end));
            boolean complex = complexity.branches >= 4 || complexity.branches >= 2 && lines >= 35 || lines >= 80;
            if (!complex) {
                return;
            }
            String bodySource = source.substring(Math.toIntExact(start), Math.toIntExact(end));
            if (containsChineseCodeComment(bodySource)) {
                return;
            }
            report.missingComplexComments++;
            Edit edit = complexCommentEdit(source, unit, positions, body, describeComplexFlow(complexity));
            if (edit != null) {
                edits.add(edit);
            }
        }
    }

    /**
     * 统计指定源码片段覆盖的行数。
     *
     * @param source 源码文本
     * @param start 起始偏移
     * @param end 结束偏移
     * @return 源码行数
     */
    private static int countLines(String source, int start, int end) {
        int lines = 1;
        for (int index = start; index < end; index++) {
            if (source.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 统计方法中的分支、循环、异常处理与状态选择节点，忽略嵌套类型和 Lambda 内部实现。
     */
    private static final class Complexity extends TreeScanner<Void, Void> {

        private int branches;
        private int loops;
        private int tries;
        private int switches;

        /**
         * 记录条件分支。
         *
         * @param node 条件节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitIf(IfTree node, Void unused) {
            branches++;
            return super.visitIf(node, unused);
        }

        /**
         * 记录普通循环。
         *
         * @param node 循环节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitForLoop(ForLoopTree node, Void unused) {
            branches++;
            loops++;
            return super.visitForLoop(node, unused);
        }

        /**
         * 记录增强循环。
         *
         * @param node 循环节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
            branches++;
            loops++;
            return super.visitEnhancedForLoop(node, unused);
        }

        /**
         * 记录前置条件循环。
         *
         * @param node 循环节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitWhileLoop(WhileLoopTree node, Void unused) {
            branches++;
            loops++;
            return super.visitWhileLoop(node, unused);
        }

        /**
         * 记录后置条件循环。
         *
         * @param node 循环节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
            branches++;
            loops++;
            return super.visitDoWhileLoop(node, unused);
        }

        /**
         * 记录状态选择分支。
         *
         * @param node 状态选择节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitSwitch(SwitchTree node, Void unused) {
            branches++;
            switches++;
            return super.visitSwitch(node, unused);
        }

        /**
         * 记录异常处理分支。
         *
         * @param node 异常处理节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitTry(TryTree node, Void unused) {
            branches += Math.max(1, node.getCatches().size());
            tries++;
            return super.visitTry(node, unused);
        }

        /**
         * 记录同步临界区。
         *
         * @param node 同步节点
         * @param unused 未使用上下文
         * @return 扫描结果
         */
        @Override
        public Void visitSynchronized(SynchronizedTree node, Void unused) {
            branches++;
            return super.visitSynchronized(node, unused);
        }

        /**
         * 跳过嵌套类型，避免把其方法复杂度计入外层方法。
         *
         * @param node 嵌套类型节点
         * @param unused 未使用上下文
         * @return 固定返回 {@code null}
         */
        @Override
        public Void visitClass(ClassTree node, Void unused) {
            return null;
        }

        /**
         * 跳过 Lambda 表达式，避免把回调实现复杂度计入当前方法。
         *
         * @param node Lambda 节点
         * @param unused 未使用上下文
         * @return 固定返回 {@code null}
         */
        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            return null;
        }
    }

    /**
     * 表示一次源码插入操作。
     *
     * @param offset 插入偏移量
     * @param text 插入文本
     * @param length 要替换的原有文本长度，插入操作为 {@code 0}
     * @param priority 相同位置时的应用优先级
     */
    private record Edit(int offset, String text, int length, int priority) {

        private Edit(int offset, String text, int priority) {
            this(offset, text, 0, priority);
        }
    }

    /**
     * 表示 JavaDoc 首句在源码中的替换范围。
     *
     * @param start 内容起点
     * @param end 内容终点
     */
    private record JavadocLineRange(int start, int end) {
    }

    /**
     * 表示 JavaDoc 插入位置及当前代码缩进。
     *
     * @param offset 插入偏移量
     * @param indentation 缩进文本
     */
    private record InsertionPoint(int offset, String indentation) {
    }

    /**
     * 保存扫描过程中的可变统计数据。
     */
    private static final class MutableReport {

        private final int sourceFiles;
        private int types;
        private int methods;
        private int missingTypeDocs;
        private int missingMethodDocs;
        private int missingComplexComments;
        private int changedFiles;

        /**
         * 创建扫描统计对象。
         *
         * @param sourceFiles 源码文件数量
         */
        private MutableReport(int sourceFiles) {
            this.sourceFiles = sourceFiles;
        }

        /**
         * 将可变统计转换为最终报告。
         *
         * @return 不可变扫描报告
         */
        private GenerationReport freeze() {
            return new GenerationReport(
                sourceFiles,
                types,
                methods,
                missingTypeDocs,
                missingMethodDocs,
                missingComplexComments,
                changedFiles
            );
        }
    }

    /**
     * 表示一次注释扫描与生成的统计报告。
     *
     * @param sourceFiles 源码文件数
     * @param types 类型声明数
     * @param methods 方法声明数
     * @param missingTypeDocs 缺少中文注释的类型数
     * @param missingMethodDocs 缺少中文注释的方法数
     * @param missingComplexComments 缺少中文流程注释的复杂方法数
     * @param changedFiles 实际写入文件数
     */
    private record GenerationReport(
        int sourceFiles,
        int types,
        int methods,
        int missingTypeDocs,
        int missingMethodDocs,
        int missingComplexComments,
        int changedFiles
    ) {

        /**
         * 判断扫描结果是否仍有注释缺口。
         *
         * @return 存在任意注释缺口时返回 {@code true}
         */
        private boolean hasGaps() {
            return missingTypeDocs > 0 || missingMethodDocs > 0 || missingComplexComments > 0;
        }
    }
}
