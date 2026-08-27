import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import RichMessageRenderer from "./rich-message-renderer.vue";
import { parseRichMessage, renderSafeMarkdown } from "./message-content";

describe("rich message content", () => {
  it("segments structured blocks without treating runnable code as chart data", () => {
    const segments = parseRichMessage([
      "结果如下 [ID:doc-7]",
      "<sql_plan>{\"goal\":\"统计订单\",\"tables\":[\"orders\"]}</sql_plan>",
      "```chart",
      "{\"xAxis\":{\"type\":\"category\"},\"series\":[{\"type\":\"bar\",\"data\":[1,2]}]}",
      "```",
      "```mermaid",
      "flowchart LR; A-->B",
      "```",
      "```python",
      "print('ok')",
      "```",
    ].join("\n"));

    expect(segments.map(segment => segment.type)).toEqual([
      "markdown",
      "sql-plan",
      "chart",
      "mermaid",
      "code",
    ]);
    const chart = segments.find(segment => segment.type === "chart");
    expect(chart?.type === "chart" ? chart.option : null).toBeTruthy();
    expect(segments.find(segment => segment.type === "code")?.runnable).toBe(true);
  });

  it("sanitizes executable HTML and unsafe links while retaining citation controls", () => {
    const html = renderSafeMarkdown(
      '<script>alert(1)</script><img src="x" onerror="alert(2)"> [ID:9] [bad](javascript:alert(3))',
    );

    expect(html).not.toContain("<script");
    expect(html).not.toContain("onerror");
    expect(html).not.toContain('href="javascript:');
    expect(html).toContain('data-cite-id="9"');
  });

  it("binds private generated-file links to the current UI host", () => {
    const artifact = "a".repeat(32);
    const token = "token_123";
    const html = renderSafeMarkdown(
      `下载: https://wrong.example/api/v1/chat/generated-files/${artifact}?token=${token}`,
    );

    expect(html).toContain(
      `/api/v1/chat/generated-files/${artifact}?token=${token}`,
    );
    expect(html).not.toContain("wrong.example");
    expect(html).toContain('class="generated-file-link"');
  });

  it("rejects unsafe structured object keys", () => {
    const [segment] = parseRichMessage('```chart\n{\"__proto__\":{\"polluted\":true}}\n```');

    expect(segment?.type).toBe("chart");
    expect(segment?.type === "chart" ? segment.option : null).toBeNull();
    expect(segment?.type === "chart" ? segment.error : null).toContain("安全的 JSON 对象");
  });

  it("emits citation and runnable-code actions", async () => {
    const wrapper = mount(RichMessageRenderer, {
      props: {
        content: "来源 [ID:abc]\n\n```python\nprint('ok')\n```",
      },
      global: {
        stubs: {
          SvgIcon: true,
          MessageChart: true,
          MessageMermaid: true,
          MessageSqlPlan: true,
          NCode: { props: ["code"], template: '<pre class="n-code-stub">{{ code }}</pre>' },
          NSpace: { template: "<div><slot /></div>" },
          NTooltip: { template: '<div><slot name="trigger" /><slot /></div>' },
          NButton: {
            emits: ["click"],
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
          },
          NAlert: { template: "<div><slot /></div>" },
        },
      },
    });

    await wrapper.get("[data-cite-id='abc']").trigger("click");
    await wrapper.get("[aria-label='运行代码']").trigger("click");

    expect(wrapper.emitted("citation")?.[0]).toEqual(["abc"]);
    expect(wrapper.emitted("runCode")?.[0]).toEqual([{ language: "python", code: "print('ok')" }]);
  });
});
