package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews;

import java.util.List;
import java.util.Map;

/**
 * 表示Scenario模板目录相关的领域对象。
 * Versioned, provider-neutral catalog of the eight Nhs scenario templates. */
final class ScenarioTemplateCatalog {
    private ScenarioTemplateCatalog() {
    }

    /**
     * 处理{@code all}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    static List<Definition> all() {
        return List.of(
            definition("chatbi-business-analysis", "经营分析 ChatBI 助手", "ChatBI", "面向经营指标、销售趋势、区域排名和月报解读的可交付数据分析助手。", true, "data", "你是经营分析 ChatBI 助手，必须优先使用数据查询能力获取真实数据，再给出趋势、排名、异常、原因假设和下一步建议。", List.of("get_dataset_schema", "execute_sql_query", "search_knowledge_base"), List.of(req("metadata_dataset", "经营数据集", true, "建议绑定销售、订单、客户或财务类元数据集。"), req("knowledge_base", "指标口径知识库", false, "建议绑定指标解释、经营制度、报表口径文档。")), List.of("本月销售额同比和环比怎么样？", "哪些区域销售下滑最明显？", "帮我生成本周经营分析简报。")),
            definition("knowledge-qa-assistant", "企业知识问答助手", "知识库", "面向制度、产品、交付文档和常见问题的企业知识检索问答助手。", true, "knowledge", "你是企业知识问答助手，必须优先检索已授权知识库并引用依据；资料不足时明确说明缺口，不要编造制度或产品承诺。", List.of("search_knowledge_base"), List.of(req("knowledge_base", "业务知识库", true, "建议绑定制度、产品手册、交付规范、FAQ 文档。"), req("feedback", "反馈纠错入口", false, "建议开启点赞点踩和问题反馈。")), List.of("请说明售后服务响应时效要求。", "这个产品支持哪些部署方式？", "新员工报销流程是什么？")),
            definition("ops-inspection-assistant", "运维巡检助手", "运维", "面向告警排查、巡检汇总、变更审计和通知推送的运维助手。", false, "general", "你是运维巡检助手，涉及真实系统状态时必须优先调用工具获取证据；结论要区分事实、风险推断和建议动作，高风险操作只给建议。", List.of("execute_sql_query", "send_dingtalk_message", "send_wechat_work_message"), List.of(req("mcp_tool", "监控或资产工具", true, "建议绑定监控、资产、工单或变更系统的 MCP/API 工具。"), req("notification", "通知渠道", false, "建议配置钉钉或企业微信。")), List.of("查看昨天高压告警并按机房汇总。", "统计本周巡检异常并给出风险等级。", "查询最近 7 天变更审批记录并生成摘要。")),
            definition("finance-expense-analysis", "财务费用分析助手", "数据分析", "面向费用趋势、预算执行、部门成本和异常费用解释的财务分析助手。", true, "data", "你是财务费用分析助手，必须优先使用数据查询工具获取真实数据，说明统计口径、时间范围和异常判断依据。", List.of("get_dataset_schema", "execute_sql_query", "search_knowledge_base"), List.of(req("metadata_dataset", "财务费用数据集", true, "建议绑定费用明细、预算、部门和科目类元数据集。"), req("knowledge_base", "财务口径知识库", false, "建议绑定预算口径和财务制度文档。")), List.of("本月费用相比预算超了多少？", "按部门统计近 3 个月费用趋势。", "哪些费用科目波动最明显？")),
            definition("sales-customer-insight", "销售客户洞察助手", "数据分析", "面向客户分层、商机跟进、区域销售和复购/流失分析的销售经营助手。", true, "data", "你是销售客户洞察助手，必须优先使用数据查询工具获取真实数据；结论要区分客户事实、趋势和建议，并说明客户分层口径。", List.of("get_dataset_schema", "execute_sql_query", "search_knowledge_base"), List.of(req("metadata_dataset", "销售客户数据集", true, "建议绑定客户、商机、订单和销售区域类元数据集。"), req("knowledge_base", "销售方法知识库", false, "建议绑定客户分层规则和销售流程文档。")), List.of("本季度重点客户销售额排名如何？", "哪些客户最近 90 天没有复购？", "按区域分析本月商机转化情况。")),
            definition("support-ticket-analysis", "客服工单分析助手", "数据分析", "面向工单分类、热点问题、响应时效和满意度分析的客服运营助手。", false, "data", "你是客服工单分析助手，统计必须基于真实数据，解释类回答优先结合 FAQ 或工单知识库，不要编造客户反馈。", List.of("get_dataset_schema", "execute_sql_query", "search_knowledge_base"), List.of(req("metadata_dataset", "客服工单数据集", true, "建议绑定工单、客户、产品、响应时效和满意度类元数据集。"), req("knowledge_base", "客服 FAQ 知识库", false, "建议绑定 FAQ 和处理 SOP。")), List.of("本周工单量最高的问题类型有哪些？", "统计各产品线平均首次响应时长。", "满意度下降主要集中在哪些问题？")),
            definition("hr-policy-qa", "人力制度问答助手", "知识问答", "面向入职、考勤、报销、假期和绩效制度的人力行政问答助手。", false, "knowledge", "你是人力制度问答助手，必须优先检索知识库并引用依据；资料不足时明确说明缺口，不要编造制度、审批权限或福利承诺。", List.of("search_knowledge_base"), List.of(req("knowledge_base", "人力行政制度库", true, "建议绑定员工手册、考勤假期、报销和绩效制度。"), req("feedback", "反馈纠错入口", false, "建议开启反馈入口。")), List.of("年假如何计算和申请？", "差旅报销需要哪些材料？", "新员工试用期绩效怎么评估？")),
            definition("legal-contract-review", "合同法务审阅助手", "知识问答", "面向合同条款解释、风险提示和制度匹配的法务知识助手。", false, "knowledge", "你是合同法务审阅助手，必须基于已授权知识库解释条款和提示风险，不得编造法律条文；重大事项必须建议法务复核。", List.of("search_knowledge_base"), List.of(req("knowledge_base", "合同法务知识库", true, "建议绑定合同模板、审阅清单、授权制度和历史风险案例。"), req("feedback", "反馈纠错入口", false, "建议开启反馈入口。")), List.of("这段违约责任条款有哪些风险？", "付款条件是否符合公司标准模板？", "这个合同审批需要哪些授权材料？"))
        );
    }

    /**
     * 处理定义并返回对应结果。
     *
     * @param key {@code key}参数
     * @param name 名称
     * @param category {@code category}参数
     * @param description {@code description}参数
     * @param recommended {@code recommended}参数
     * @param agentType 业务类型
     * @param systemPrompt 系统提示词参数
     * @param tools {@code tools}参数
     * @param requirements {@code requirements}参数
     * @param questions {@code questions}参数
     * @return 处理结果
     */
    private static Definition definition(
        String key, String name, String category, String description, boolean recommended,
        String agentType, String systemPrompt, List<String> tools,
        List<ScenarioTemplateViews.ResourceRequirement> requirements, List<String> questions
    ) {
        return new Definition(key, name, category, description, recommended, agentType, systemPrompt, tools, requirements, questions);
    }

    /**
     * 处理{@code req}并返回对应结果。
     *
     * @param type 业务类型
     * @param name 名称
     * @param required {@code required}参数
     * @param description {@code description}参数
     * @return 处理结果
     */
    private static ScenarioTemplateViews.ResourceRequirement req(String type, String name, boolean required, String description) {
        return new ScenarioTemplateViews.ResourceRequirement(type, name, required, description);
    }

    /**
     * 封装定义相关的不可变数据。
     */
    record Definition(
        String key, String name, String category, String description, boolean recommended,
        String agentType, String systemPrompt, List<String> tools,
        List<ScenarioTemplateViews.ResourceRequirement> requiredResources, List<String> sampleQuestions
    ) {
        /**
         * 处理{@code summary}并返回对应结果。
         *
         * @return 处理结果
         */
        ScenarioTemplateViews.Summary summary() {
            return new ScenarioTemplateViews.Summary(
                key, name, category, description,
                List.of(category, "Nhs场景模板"), recommended,
                List.of(), "0.5-1 天", "标准版",
                List.of("Agent", "真实运行时", "验收样例"),
                List.of("Agent", "系统提示词", "工具配置", "推荐问题", "验收标准"),
                List.of(description), List.of("基础信息", "绑定资源", "预检", "安装发布"),
                List.of("资源权限和运行时均可用", "至少完成 3 条样例问题试跑", "回答不得编造数据或制度"),
                requiredResources, sampleQuestions
            );
        }

        /**
         * 处理{@code manifest}并返回对应结果。
         *
         * @return 处理结果
         */
        Map<String, Object> manifest() {
            return Map.of(
                "id", key, "name", name, "category", category, "description", description,
                "agent", Map.of("name", key, "display_name", name, "agent_type", agentType),
                "version", Map.of("system_prompt", systemPrompt, "tools", tools),
                "required_resources", requiredResources, "sample_questions", sampleQuestions
            );
        }
    }
}
