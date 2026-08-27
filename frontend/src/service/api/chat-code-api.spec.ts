import { afterEach, describe, expect, it, vi } from "vitest";
import {
  resumeChatCodeExecution,
  startChatCodeExecution,
  streamConversationEvents,
  type ChatCodeStreamEvent,
  type ExecutionEventView,
} from "./platform";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("chat code execution stream", () => {
  it("parses named SSE frames and preserves the durable cursor", async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      "id: 1\nevent: started\ndata: {\"execution_id\":\"91\",\"status\":\"running\"}\n\n",
      "id: 2\nevent: output\ndata: {\"stream\":\"stdout\",\"chunk\":\"ok\\n\"}\n\n",
      "id: 3\nevent: finished\ndata: {\"execution_id\":\"91\",\"status\":\"succeeded\",\"exit_code\":0}\n\n",
    ]));
    vi.stubGlobal("fetch", fetchMock);
    const events: ChatCodeStreamEvent[] = [];

    await startChatCodeExecution(
      { language: "python", code: "print('ok')", conversationId: "7" },
      event => events.push(event),
      new AbortController().signal,
    );

    expect(events.map(event => [event.id, event.event])).toEqual([
      ["1", "started"],
      ["2", "output"],
      ["3", "finished"],
    ]);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({
      language: "python",
      code: "print('ok')",
      conversation_id: "7",
    });
  });

  it("sends both cursor forms when resuming and surfaces HTTP provider errors", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(streamResponse([
        "id: 8\nevent: stopped\ndata: {\"execution_id\":\"91\",\"status\":\"stopped\"}\n\n",
      ]))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: "sandbox_unavailable",
        message: "当前没有可用的代码执行Runner",
      }), { status: 503, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await resumeChatCodeExecution(
      "91",
      "7",
      7,
      () => undefined,
      new AbortController().signal,
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("cursor=7");
    expect(new Headers(init.headers).get("Last-Event-ID")).toBe("7");

    await expect(startChatCodeExecution(
      { language: "python", code: "print(1)", conversationId: "7" },
      () => undefined,
      new AbortController().signal,
    )).rejects.toThrow("当前没有可用的代码执行Runner");
  });

  it("keeps conversation events usable across split, malformed, and unterminated frames", async () => {
    const first = conversationEvent("event-1", 1, "first");
    const second = conversationEvent("event-2", 2, "tail");
    const encodedFirst = JSON.stringify(first);
    const splitAt = Math.floor(encodedFirst.length / 2);
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      "data: not-json\n\n",
      `data: ${encodedFirst.slice(0, splitAt)}`,
      `${encodedFirst.slice(splitAt)}\n\n`,
      `data: ${JSON.stringify(second)}`,
    ]));
    vi.stubGlobal("fetch", fetchMock);
    const events: ExecutionEventView[] = [];

    await streamConversationEvents(
      "7",
      0,
      event => events.push(event),
      new AbortController().signal,
    );

    expect(events.map(event => [event.eventId, event.cursor, event.summary])).toEqual([
      ["event-1", 1, "first"],
      ["event-2", 2, "tail"],
    ]);
  });
});

function conversationEvent(eventId: string, cursor: number, summary: string): ExecutionEventView {
  return {
    eventId,
    traceId: "trace-1",
    conversationId: "7",
    runId: null,
    stepId: null,
    cursor,
    eventType: "text_delta",
    eventStatus: "running",
    summary,
    payload: {},
    sensitiveLevel: "internal",
    occurredAt: "2026-08-16T14:00:00",
  };
}

function streamResponse(chunks: string[]) {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream({
    start(controller) {
      chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  }), {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}
