import { describe, expect, it } from "vitest";
import {
  resolveActiveConversationId,
  shouldPersistActiveConversation,
} from "./active-conversation";

describe("active conversation persistence", () => {
  const conversations = [{ id: "7" }, { id: "8" }];

  it("restores the durable pointer only when it is in the owner-scoped list", () => {
    expect(resolveActiveConversationId(conversations, "8")).toBe("8");
    expect(resolveActiveConversationId(conversations, "99")).toBe("7");
    expect(resolveActiveConversationId([], "8")).toBeNull();
  });

  it("only queues a non-empty selection that differs from the persisted pointer", () => {
    expect(shouldPersistActiveConversation("7", null)).toBe(true);
    expect(shouldPersistActiveConversation("7", "7")).toBe(false);
    expect(shouldPersistActiveConversation(null, "7")).toBe(false);
  });
});
