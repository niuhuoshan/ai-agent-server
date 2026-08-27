package group.aitools.nhs.platform.connector.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表示Builtin工具目录相关的领域对象。
 * Frozen Nhs builtin-tool ledger used by runtime validation and diagnostics. */
public final class BuiltinToolCatalog {

    private BuiltinToolCatalog() {
    }

    private static final List<String> NAMES = List.of(
        "get_dataset_schema", "execute_sql_query", "update_dashboard_context",
        "system_http_request", "get_current_model", "search_knowledge_base",
        "search_qa_examples", "create_recurring_task", "get_my_tasks", "cancel_task",
        "start_task", "pause_task", "run_task_manually", "send_dingtalk_message",
        "send_email", "send_wechat_work_message", "send_portal_notification", "jira_search",
        "jira_create_issue", "jira_get_projects", "read_file", "write_file", "search_text",
        "exec_command", "manage_process", "list_process", "create_skills",
        "list_available_skills", "read_skill_instruction", "sqlite_scratchpad",
        "directory_tree_navigator", "web_renderer_and_snapshot", "code_syntax_linter",
        "fetch_static_web_url", "web_search_baidu", "web_search_baidu_http",
        "web_search_bing_http", "update_user_preference", "fetch_user_long_term_memory",
        "delete_user_preference", "memory_search", "list_accessible_datasets", "list_available_agents",
        "list_accessible_knowledge_bases", "get_myinfo", "session_status", "read_image",
        "request_user_confirmation", "ask_user_question", "todo_write",
        "sub_agent_call", "sub_agent_batch_call", "excel_document_read", "excel_document_write", "word_document_read",
        "word_document_write", "get_current_time", "resolve_relative_dates",
        "browser_open", "browser_navigate", "browser_snapshot", "browser_click", "browser_fill", "browser_close",
        "browser_press", "browser_scroll", "browser_hover", "browser_upload", "browser_tabs", "browser_tab_open",
        "browser_tab_activate", "browser_tab_close", "browser_human_handoff", "browser_back", "browser_forward",
        "browser_reload", "browser_wait_for", "browser_select_option", "browser_read_visible", "browser_drag",
        "browser_download", "browser_switch_tab", "browser_close_tab"
    );

    private static final Set<String> IMPLEMENTED = Set.of(
        "get_dataset_schema", "execute_sql_query",
        "get_current_time", "resolve_relative_dates", "get_myinfo", "session_status", "read_image",
        "get_current_model", "search_knowledge_base", "search_qa_examples", "memory_search",
        "fetch_user_long_term_memory", "update_user_preference",
        "delete_user_preference", "send_portal_notification", "read_file",
        "write_file", "search_text", "directory_tree_navigator", "list_accessible_datasets", "list_available_agents",
        "list_accessible_knowledge_bases", "web_search_baidu",
        "web_search_baidu_http", "web_search_bing_http", "get_my_tasks",
        "create_recurring_task", "cancel_task", "start_task", "pause_task",
        "run_task_manually", "list_available_skills", "read_skill_instruction",
        "send_dingtalk_message", "send_email", "send_wechat_work_message",
        "system_http_request", "web_renderer_and_snapshot", "code_syntax_linter",
        "fetch_static_web_url", "create_skills", "exec_command", "list_process",
        "manage_process", "update_dashboard_context", "jira_search", "jira_get_projects",
        "jira_create_issue", "sqlite_scratchpad", "request_user_confirmation",
        "sub_agent_call", "sub_agent_batch_call", "ask_user_question", "todo_write", "excel_document_read", "excel_document_write",
        "word_document_read", "word_document_write", "browser_open", "browser_navigate", "browser_snapshot",
        "browser_click", "browser_fill", "browser_close", "browser_press", "browser_scroll", "browser_hover",
        "browser_upload", "browser_tabs", "browser_tab_open", "browser_tab_activate", "browser_tab_close"
        , "browser_human_handoff", "browser_back", "browser_forward", "browser_reload", "browser_wait_for",
        "browser_select_option", "browser_read_visible", "browser_drag", "browser_download", "browser_switch_tab",
        "browser_close_tab"
    );

    /**
     * 处理{@code names}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * 处理{@code contains}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public static boolean contains(String value) {
        return NAMES.contains(normalize(value));
    }

    /**
     * 处理{@code descriptor}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static Map<String, Object> descriptor(String value) {
        String normalized = normalize(value);
        if (!contains(normalized)) {
            return Map.of();
        }
        return Map.of(
            "name", normalized,
            "description", description(normalized),
            "source", "nhs-v1-tool-ledger",
            "registered", true,
            "execution", implemented(normalized) ? "local" : "unavailable",
            "readOnly", readOnly(normalized),
            "riskLevel", readOnly(normalized) ? "R0" : "R2",
            "parameterSchema", parameterSchema(normalized)
        );
    }

    /**
     * 处理{@code descriptors}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public static List<Map<String, Object>> descriptors() {
        return NAMES.stream().map(BuiltinToolCatalog::descriptor).toList();
    }

    /**
     * 处理{@code implemented}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public static boolean implemented(String value) {
        return IMPLEMENTED.contains(normalize(value));
    }

    /**
     * 处理{@code parameterSchema}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static Map<String, Object> parameterSchema(String value) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String normalized = normalize(value);
        Map<String, Object> properties = switch (normalized) {
            case "update_dashboard_context" -> Map.of(
                "room_name", property("string", "房间或业务空间名称"),
                "metric_name", property("string", "当前关注指标"),
                "time_range", property("string", "时间范围，例如 24h、7d、today")
            );
            case "jira_search" -> Map.of(
                "jql", Map.of("type", "string", "title", "Jira JQL", "minLength", 1, "maxLength", 4000)
            );
            case "jira_create_issue" -> Map.of(
                "project_key", Map.of("type", "string", "title", "Jira 项目 Key", "pattern", "^[A-Za-z][A-Za-z0-9_-]{1,19}$"),
                "summary", Map.of("type", "string", "title", "工单标题", "minLength", 1, "maxLength", 255),
                "description", Map.of("type", "string", "title", "工单描述", "minLength", 1, "maxLength", 32000),
                "issue_type", Map.of("type", "string", "title", "工单类型", "default", "Task", "maxLength", 64)
            );
            case "sqlite_scratchpad" -> Map.of(
                "sql", Map.of("type", "string", "title", "SQLite SQL", "minLength", 1, "maxLength", 65536),
                "session_id", Map.of("type", "string", "title", "隔离会话标识", "minLength", 1, "maxLength", 128),
                "import_data", Map.of("type", "object", "title", "可选导入数据")
            );
            case "request_user_confirmation" -> Map.of(
                "title", Map.of("type", "string", "title", "确认卡标题", "minLength", 1, "maxLength", 255),
                "fields", Map.of("type", "array", "title", "待确认字段", "minItems", 1, "maxItems", 32),
                "summary", Map.of("type", "string", "title", "确认说明", "maxLength", 2000),
                "confirm_label", Map.of("type", "string", "title", "确认按钮文案", "maxLength", 64),
                "cancel_label", Map.of("type", "string", "title", "取消按钮文案", "maxLength", 64),
                "risk_note", Map.of("type", "string", "title", "风险提示", "maxLength", 2000)
            );
            case "ask_user_question" -> Map.of(
                "question", Map.of(
                    "type", "string", "title", "向用户提出的问题", "minLength", 1, "maxLength", 2000
                ),
                "options", Map.of(
                    "type", "array", "title", "可选回答项", "minItems", 2, "maxItems", 12,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "id", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                            "label", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                            "description", Map.of("type", "string", "maxLength", 1000)
                        ),
                        "required", List.of("id", "label"),
                        "additionalProperties", false
                    )
                ),
                "multi_select", Map.of("type", "boolean", "title", "是否允许多选", "default", false),
                "allow_custom_input", Map.of("type", "boolean", "title", "是否允许补充输入", "default", false),
                "context", Map.of("type", "string", "title", "问题上下文", "maxLength", 2000),
                "purpose", Map.of("type", "string", "title", "提问目的", "maxLength", 255),
                "expires_in_seconds", Map.of(
                    "type", "integer", "title", "有效期秒数", "minimum", 60, "maximum", 3600
                ),
                "idempotency_key", Map.of("type", "string", "title", "幂等键", "maxLength", 128),
                "question_id", Map.of("type", "string", "title", "问题标识", "maxLength", 128),
                "tool_call_id", Map.of("type", "string", "title", "工具调用标识", "maxLength", 128)
            );
            case "todo_write" -> Map.of(
                "todos", Map.of(
                    "type", "array", "title", "完整任务清单", "maxItems", 20,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "content", Map.of(
                                "type", "string", "title", "任务描述", "minLength", 1,
                                "maxLength", 200
                            ),
                            "status", Map.of(
                                "type", "string", "title", "任务状态",
                                "enum", List.of("pending", "in_progress", "completed")
                            )
                        ),
                        "required", List.of("content", "status"),
                        "additionalProperties", false
                    )
                )
            );
            case "sub_agent_call" -> Map.of(
                "agent_name", Map.of("type", "string", "title", "目标 Agent", "minLength", 1, "maxLength", 128),
                "query", Map.of("type", "string", "title", "委派问题", "minLength", 1, "maxLength", 12000)
            );
            case "sub_agent_batch_call" -> Map.of(
                "calls", Map.of(
                    "type", "array", "title", "并行委派项", "minItems", 1, "maxItems", 4,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "agent_name", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                            "query", Map.of("type", "string", "minLength", 1, "maxLength", 12000)
                        ),
                        "required", List.of("agent_name", "query"),
                        "additionalProperties", false
                    )
                )
            );
            case "read_image" -> Map.of(
                "path", Map.of("type", "string", "title", "工作区图片路径", "minLength", 1, "maxLength", 512),
                "question", Map.of("type", "string", "title", "图片问题", "maxLength", 2000)
            );
            case "browser_open" -> Map.of(
                "profile_key", Map.of("type", "string", "title", "浏览器配置档", "maxLength", 128),
                "start_url", Map.of("type", "string", "title", "初始页面 URL", "format", "uri", "maxLength", 2048)
            );
            case "browser_navigate" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "url", Map.of("type", "string", "title", "页面 URL", "format", "uri", "minLength", 1, "maxLength", 2048)
            );
            case "browser_snapshot", "browser_close" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1)
            );
            case "browser_click" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "title", "CSS 选择器", "minLength", 1, "maxLength", 1000)
            );
            case "browser_fill" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "title", "CSS 选择器", "minLength", 1, "maxLength", 1000),
                "value", Map.of("type", "string", "title", "输入内容", "maxLength", 20000)
            );
            case "browser_press" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "key", Map.of("type", "string", "title", "键盘按键或组合键", "minLength", 1, "maxLength", 64)
            );
            case "browser_scroll" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "x", Map.of("type", "integer", "minimum", -100000, "maximum", 100000),
                "y", Map.of("type", "integer", "minimum", -100000, "maximum", 100000),
                "selector", Map.of("type", "string", "maxLength", 1000)
            );
            case "browser_hover" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "title", "CSS 选择器", "minLength", 1, "maxLength", 1000)
            );
            case "browser_upload" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "title", "文件输入选择器", "minLength", 1, "maxLength", 1000),
                "files", Map.of("type", "array", "minItems", 1, "maxItems", 10,
                    "items", Map.of("type", "string", "minLength", 1, "maxLength", 512))
            );
            case "browser_tabs" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1)
            );
            case "browser_tab_open" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "url", Map.of("type", "string", "format", "uri", "maxLength", 2048)
            );
            case "browser_tab_activate", "browser_tab_close" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "tab_id", Map.of("type", "string", "minLength", 1, "maxLength", 255)
            );
            case "browser_human_handoff" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "reason", Map.of("type", "string", "title", "人工接管原因", "maxLength", 255)
            );
            case "browser_back", "browser_forward", "browser_reload", "browser_read_visible" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1)
            );
            case "browser_wait_for" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "condition", Map.of("type", "string", "enum", List.of("text", "url", "target", "page_state")),
                "value", Map.of("type", "string", "maxLength", 2048),
                "timeout_ms", Map.of("type", "integer", "minimum", 100, "maximum", 30000)
            );
            case "browser_select_option" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "title", "CSS 选择器", "minLength", 1, "maxLength", 1000),
                "target_ref", Map.of("type", "string", "title", "快照目标引用", "maxLength", 1000),
                "value", Map.of("type", "string", "maxLength", 255),
                "label", Map.of("type", "string", "maxLength", 255)
            );
            case "browser_drag" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "source_selector", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "source_ref", Map.of("type", "string", "maxLength", 1000),
                "target_selector", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "target_ref", Map.of("type", "string", "maxLength", 1000)
            );
            case "browser_download" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "selector", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "target_ref", Map.of("type", "string", "maxLength", 1000)
            );
            case "browser_switch_tab", "browser_close_tab" -> Map.of(
                "session_id", Map.of("type", "integer", "title", "浏览器会话 ID", "minimum", 1),
                "tab_id", Map.of("type", "string", "minLength", 1, "maxLength", 255)
            );
            case "excel_document_read" -> Map.of(
                "action", Map.of("type", "string", "enum", List.of("inspect", "read_range")),
                "path", Map.of("type", "string", "title", "工作区 Excel 路径", "minLength", 1, "maxLength", 512),
                "sheet_name", Map.of("type", "string", "maxLength", 255),
                "cell_range", Map.of("type", "string", "maxLength", 64)
            );
            case "excel_document_write" -> Map.of(
                "action", Map.of("type", "string", "enum", List.of("create", "write_cells", "append_rows", "create_sheet")),
                "output_filename", Map.of("type", "string", "title", "输出文件名", "maxLength", 255),
                "path", Map.of("type", "string", "maxLength", 512),
                "sheet_name", Map.of("type", "string", "maxLength", 255),
                "cells", Map.of("type", "array", "maxItems", 1000),
                "rows", Map.of("type", "array", "maxItems", 1000)
            );
            case "word_document_read" -> Map.of(
                "action", Map.of("type", "string", "enum", List.of("inspect", "read_content")),
                "path", Map.of("type", "string", "title", "工作区 Word 路径", "minLength", 1, "maxLength", 512),
                "start", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50)
            );
            case "word_document_write" -> Map.of(
                "action", Map.of("type", "string", "enum", List.of("create", "replace_text", "append_paragraphs", "append_table")),
                "output_filename", Map.of("type", "string", "title", "输出文件名", "maxLength", 255),
                "path", Map.of("type", "string", "maxLength", 512),
                "replacements", Map.of("type", "array", "maxItems", 100),
                "paragraphs", Map.of("type", "array", "maxItems", 1000),
                "headers", Map.of("type", "array", "maxItems", 50),
                "rows", Map.of("type", "array", "maxItems", 1000),
                "title", Map.of("type", "string", "maxLength", 255)
            );
            case "get_current_time" -> Map.of(
                "timezone", property("string", "IANA 时区，例如 Asia/Shanghai")
            );
            case "resolve_relative_dates" -> Map.of(
                "expression", property("string", "相对日期表达式，例如 today、this_week、今天、本周")
            );
            case "get_my_tasks" -> Map.of(
                "status", property("string", "可选任务状态过滤"),
                "limit", Map.of(
                    "type", "integer", "title", "返回数量", "minimum", 1, "maximum", 100
                )
            );
            case "create_recurring_task" -> Map.of(
                "name", Map.of(
                    "type", "string", "title", "周期任务名称", "minLength", 1, "maxLength", 128
                ),
                "cron", Map.of(
                    "type", "string", "title", "Cron表达式", "minLength", 1, "maxLength", 128
                ),
                "prompt", Map.of(
                    "type", "string", "title", "周期执行指令", "minLength", 1, "maxLength", 12000
                ),
                "notification_channels", Map.of(
                    "type", "array", "title", "通知渠道", "maxItems", 4,
                    "uniqueItems", true,
                    "items", Map.of(
                        "type", "string",
                        "enum", List.of("portal", "dingtalk", "wechat_work", "email")
                    )
                ),
                "timezone", Map.of(
                    "type", "string", "title", "IANA时区", "maxLength", 64
                ),
                "service_account_id", Map.of(
                    "type", "integer", "title", "可选执行服务账号ID", "minimum", 1
                )
            );
            case "cancel_task", "start_task", "pause_task", "run_task_manually" -> Map.of(
                "task_id", Map.of(
                    "type", "integer", "title", "周期任务ID", "minimum", 1
                )
            );
            case "read_skill_instruction" -> Map.of(
                "skill_id", Map.of(
                    "type", "string", "title", "Skill 标识",
                    "minLength", 1, "maxLength", 128,
                    "pattern", "^[a-z][a-z0-9._-]{0,127}$"
                )
            );
            case "send_dingtalk_message" -> Map.of(
                "title", Map.of(
                    "type", "string", "title", "消息标题", "minLength", 1, "maxLength", 255
                ),
                "content", Map.of(
                    "type", "string", "title", "Markdown 消息正文", "minLength", 1,
                    "maxLength", 16_384
                )
            );
            case "send_wechat_work_message" -> Map.of(
                "content", Map.of(
                    "type", "string", "title", "Markdown 消息正文", "minLength", 1,
                    "maxLength", 16_384
                )
            );
            case "send_email" -> Map.of(
                "to_email", Map.of(
                    "type", "string", "title", "收件人邮箱", "format", "email",
                    "minLength", 3, "maxLength", 320
                ),
                "subject", Map.of(
                    "type", "string", "title", "邮件主题", "minLength", 1, "maxLength", 255
                ),
                "content", Map.of(
                    "type", "string", "title", "邮件正文", "minLength", 1, "maxLength", 16_384
                )
            );
            case "system_http_request" -> Map.of(
                "method", Map.of(
                    "type", "string", "title", "HTTP 方法", "enum", List.of(
                        "GET", "POST", "PUT", "PATCH", "DELETE"
                    )
                ),
                "url", Map.of(
                    "type", "string", "title", "完整 HTTP URL", "format", "uri",
                    "minLength", 1, "maxLength", 8192
                ),
                "headers", Map.of("type", "object", "title", "请求头"),
                "body", Map.of("type", "object", "title", "JSON 请求体"),
                "params", Map.of("type", "object", "title", "查询参数")
            );
            case "web_renderer_and_snapshot", "fetch_static_web_url" -> Map.of(
                "url", Map.of(
                    "type", "string", "title", "网页 URL", "format", "uri",
                    "minLength", 1, "maxLength", 8192
                )
            );
            case "code_syntax_linter" -> Map.of(
                "code", Map.of(
                    "type", "string", "title", "待检测源码", "minLength", 1,
                    "maxLength", 262144
                ),
                "language", Map.of(
                    "type", "string", "title", "编程语言", "default", "python",
                    "maxLength", 32
                )
            );
            case "create_skills" -> Map.of(
                "skill_id", Map.of(
                    "type", "string", "title", "Skill 标识", "minLength", 1, "maxLength", 128,
                    "pattern", "^[a-z][a-z0-9._-]{0,127}$"
                ),
                "name", Map.of(
                    "type", "string", "title", "Skill 名称", "minLength", 1, "maxLength", 128
                ),
                "description", Map.of("type", "string", "title", "Skill 描述", "maxLength", 12000),
                "skill_md_content", Map.of(
                    "type", "string", "title", "SKILL.md 内容", "minLength", 1, "maxLength", 32768
                ),
                "scope", Map.of(
                    "type", "string", "title", "作用域", "enum", List.of("personal", "global", "project")
                ),
                "project_id", Map.of("type", "integer", "title", "项目 ID", "minimum", 1),
                "tags", Map.of("type", "array", "title", "标签", "maxItems", 32)
            );
            case "exec_command" -> Map.of(
                "command", Map.of("type", "string", "title", "Shell 命令", "minLength", 1, "maxLength", 32768),
                "workspace_path", Map.of("type", "string", "title", "工作区相对路径", "maxLength", 512),
                "workspace_access", Map.of("type", "string", "enum", List.of("read_only", "read_write")),
                "network_policy", Map.of("type", "string", "enum", List.of("none", "allowlist")),
                "allowed_hosts", Map.of("type", "array", "maxItems", 32),
                "timeout_seconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600),
                "memory_mb", Map.of("type", "integer", "minimum", 64, "maximum", 32768),
                "cpu_millis", Map.of("type", "integer", "minimum", 100, "maximum", 16000),
                "pids_limit", Map.of("type", "integer", "minimum", 16, "maximum", 2048),
                "max_output_bytes", Map.of("type", "integer", "minimum", 1024, "maximum", 10485760)
            );
            case "list_process" -> Map.of(
                "workspace_path", Map.of("type", "string", "title", "工作区相对路径", "maxLength", 512),
                "timeout_seconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600),
                "max_output_bytes", Map.of("type", "integer", "minimum", 1024, "maximum", 10485760)
            );
            case "manage_process" -> Map.of(
                "action", Map.of(
                    "type", "string", "title", "进程动作", "enum", List.of("list", "terminate", "kill")
                ),
                "pid", Map.of("type", "integer", "title", "进程 ID", "minimum", 1),
                "timeout_seconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600),
                "max_output_bytes", Map.of("type", "integer", "minimum", 1024, "maximum", 10485760)
            );
            default -> Map.of();
        };
        List<String> required = switch (normalized) {
            case "jira_search" -> List.of("jql");
            case "jira_create_issue" -> List.of("project_key", "summary", "description");
            case "sqlite_scratchpad" -> List.of("sql", "session_id");
            case "request_user_confirmation" -> List.of("title", "fields");
            case "ask_user_question" -> List.of("question", "options");
            case "todo_write" -> List.of("todos");
            case "sub_agent_call" -> List.of("agent_name", "query");
            case "sub_agent_batch_call" -> List.of("calls");
            case "read_image" -> List.of("path");
            case "browser_open" -> List.of();
            case "browser_navigate", "browser_snapshot", "browser_click", "browser_fill", "browser_close", "browser_press",
                 "browser_scroll", "browser_hover", "browser_upload", "browser_tabs", "browser_tab_open",
                 "browser_tab_activate", "browser_tab_close", "browser_back", "browser_forward", "browser_reload",
                 "browser_wait_for", "browser_select_option", "browser_read_visible", "browser_drag", "browser_download",
                 "browser_switch_tab", "browser_close_tab" ->
                List.of("session_id");
            case "browser_human_handoff" -> List.of("session_id");
            case "excel_document_read", "word_document_read" -> List.of("path");
            case "excel_document_write", "word_document_write" -> List.of("output_filename");
            case "resolve_relative_dates" -> List.of("expression");
            case "create_recurring_task" -> List.of("name", "cron", "prompt");
            case "cancel_task", "start_task", "pause_task", "run_task_manually" ->
                List.of("task_id");
            case "read_skill_instruction" -> List.of("skill_id");
            case "send_dingtalk_message" -> List.of("title", "content");
            case "send_wechat_work_message" -> List.of("content");
            case "send_email" -> List.of("to_email", "subject", "content");
            case "system_http_request", "web_renderer_and_snapshot", "fetch_static_web_url" ->
                List.of("url");
            case "code_syntax_linter" -> List.of("code");
            case "create_skills" -> List.of("skill_id", "name", "skill_md_content");
            case "exec_command" -> List.of("command");
            case "manage_process" -> List.of("action", "pid");
            default -> List.of();
        };
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", !Set.of(
                "get_current_model", "get_myinfo", "session_status", "get_my_tasks",
                "list_accessible_datasets", "list_available_agents", "list_accessible_knowledge_bases",
                "get_current_time", "resolve_relative_dates", "create_recurring_task",
                "cancel_task", "start_task", "pause_task", "run_task_manually",
                "list_available_skills", "read_skill_instruction", "send_dingtalk_message",
                "send_wechat_work_message", "send_email", "system_http_request",
                "web_renderer_and_snapshot", "fetch_static_web_url", "code_syntax_linter",
                "create_skills", "exec_command", "list_process", "manage_process",
                "update_dashboard_context", "jira_search", "jira_create_issue",
                "jira_get_projects", "sqlite_scratchpad", "request_user_confirmation",
                "sub_agent_call", "sub_agent_batch_call", "ask_user_question", "todo_write", "read_image",
                "excel_document_read", "excel_document_write",
                "word_document_read", "word_document_write", "browser_open", "browser_navigate", "browser_snapshot",
                "browser_click", "browser_fill", "browser_close", "browser_press", "browser_scroll", "browser_hover",
                "browser_upload", "browser_tabs", "browser_tab_open", "browser_tab_activate", "browser_tab_close"
                , "browser_human_handoff", "browser_back", "browser_forward", "browser_reload", "browser_wait_for",
                "browser_select_option", "browser_read_visible", "browser_drag", "browser_download", "browser_switch_tab",
                "browser_close_tab"
            ).contains(normalized)
        );
    }

    /**
     * 处理{@code property}并返回对应结果。
     *
     * @param type 业务类型
     * @param title {@code title}参数
     * @return 处理结果
     */
    private static Map<String, Object> property(String type, String title) {
        return Map.of("type", type, "title", title);
    }

    /**
     * 处理{@code readOnly}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean readOnly(String value) {
        return !Set.of(
            "update_dashboard_context", "system_http_request", "create_recurring_task", "cancel_task", "start_task",
            "pause_task", "run_task_manually", "send_dingtalk_message", "send_email",
            "send_wechat_work_message", "send_portal_notification", "write_file", "exec_command",
            "manage_process", "create_skills", "sqlite_scratchpad", "update_user_preference",
            "delete_user_preference", "request_user_confirmation", "ask_user_question", "sub_agent_call",
            "sub_agent_batch_call",
            "browser_open", "browser_navigate", "browser_click", "browser_fill", "browser_close", "browser_press",
            "browser_scroll", "browser_hover", "browser_upload", "browser_tabs", "browser_tab_open",
            "browser_tab_activate", "browser_tab_close",
            "browser_human_handoff", "browser_back", "browser_forward", "browser_reload", "browser_wait_for",
            "browser_select_option", "browser_drag", "browser_download", "browser_switch_tab", "browser_close_tab",
            "excel_document_write", "word_document_write"
        ).contains(value);
    }

    /**
     * 处理{@code description}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String description(String value) {
        return switch (value) {
            case "get_current_model" -> "读取本轮实际模型与当前 Agent 的非敏感运行信息";
            case "session_status" -> "读取当前会话、运行、模型和授权快照的非敏感事实";
            case "read_image" -> "读取工作区中的图片元数据并为视觉模型准备安全的图片引用";
            case "get_my_tasks" -> "按冻结主体权限查询当前可见任务";
            case "create_recurring_task" -> "基于当前Agent和冻结资源创建可持久调度的周期任务";
            case "cancel_task" -> "取消并归档当前主体有权管理的周期任务";
            case "start_task" -> "启用当前主体有权管理的周期任务调度";
            case "pause_task" -> "暂停当前主体有权管理的周期任务调度";
            case "run_task_manually" -> "立即将周期任务加入持久执行队列";
            case "list_available_skills" -> "列出当前 Agent 运行快照中已授权的 Skill 摘要";
            case "read_skill_instruction" -> "读取当前 Agent 运行快照中已授权 Skill 的 SKILL.md 指令和依赖";
            case "send_dingtalk_message" -> "读取当前用户的钉钉配置并发送 Markdown 群机器人消息";
            case "send_wechat_work_message" -> "读取当前用户的企业微信配置并发送 Markdown 群机器人消息";
            case "send_email" -> "读取当前用户的 SMTP 配置并发送邮件";
            case "system_http_request" -> "在受限公网边界内执行一次 HTTP API 请求";
            case "fetch_static_web_url" -> "拉取静态网页并提取可读正文";
            case "web_renderer_and_snapshot" -> "通过受管 Chromium 渲染网页并生成截图工件";
            case "code_syntax_linter" -> "使用 Python 语法解析器检查源码语法";
            case "browser_open" -> "在隔离 Playwright Worker 中创建浏览器会话";
            case "browser_navigate" -> "控制隔离浏览器打开指定页面";
            case "browser_snapshot" -> "读取隔离浏览器当前页面快照";
            case "browser_click" -> "在隔离浏览器页面点击受控元素";
            case "browser_fill" -> "在隔离浏览器页面输入内容";
            case "browser_close" -> "关闭隔离浏览器会话";
            case "browser_press" -> "在隔离浏览器页面发送键盘按键或组合键";
            case "browser_scroll" -> "在隔离浏览器页面滚动或定位到受控元素";
            case "browser_hover" -> "在隔离浏览器页面悬停于受控元素";
            case "browser_upload" -> "向隔离浏览器页面的文件输入控件上传 Worker 目录内文件";
            case "browser_tabs" -> "读取隔离浏览器会话的标签页列表";
            case "browser_tab_open" -> "在隔离浏览器会话中打开新的标签页";
            case "browser_tab_activate" -> "切换隔离浏览器会话的当前标签页";
            case "browser_tab_close" -> "关闭隔离浏览器会话中的标签页";
            case "browser_human_handoff" -> "暂停浏览器 AI 操作并请求用户人工接管，交还前不会继续调用浏览器";
            case "browser_back" -> "返回隔离浏览器历史记录上一页";
            case "browser_forward" -> "前进隔离浏览器历史记录下一页";
            case "browser_reload" -> "刷新隔离浏览器当前页面";
            case "browser_wait_for" -> "等待隔离浏览器页面满足文本、URL、目标或加载状态条件";
            case "browser_select_option" -> "选择隔离浏览器页面原生下拉框选项";
            case "browser_read_visible" -> "读取隔离浏览器当前视口内的页面文字";
            case "browser_drag" -> "在隔离浏览器页面拖拽受控元素";
            case "browser_download" -> "点击隔离浏览器下载目标并生成短期私有下载链接";
            case "browser_switch_tab" -> "切换隔离浏览器会话的当前标签页";
            case "browser_close_tab" -> "关闭隔离浏览器会话中的指定标签页";
            case "get_myinfo" -> "读取冻结主体与本轮运行上下文";
            case "ask_user_question" -> "在需要用户选择或补充信息时展示结构化问题并等待回答";
            case "todo_write" -> "记录和更新多步骤任务的结构化执行清单";
            case "list_accessible_datasets" -> "列出本轮实际可查询的数据集轻量目录";
            case "list_available_agents" -> "列出当前用户有权限运行的智能体目录，并标记当前会话智能体";
            case "list_accessible_knowledge_bases" -> "列出本轮实际可检索的知识库轻量目录";
            default -> value;
        };
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.startsWith("builtin.")
            ? normalized.substring("builtin.".length()) : normalized;
    }
}
