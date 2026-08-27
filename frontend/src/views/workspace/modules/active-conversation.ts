export interface ActiveConversationCandidate {
  id: string;
}

/** Prefer the durable server pointer, but never select a conversation absent from the current owner-scoped list. */
export function resolveActiveConversationId(
  conversations: ActiveConversationCandidate[],
  serverConversationId: string | null | undefined
) {
  if (serverConversationId && conversations.some(item => item.id === serverConversationId)) {
    return serverConversationId;
  }
  return conversations[0]?.id || null;
}

export function shouldPersistActiveConversation(
  selectedConversationId: string | null,
  persistedConversationId: string | null
) {
  return Boolean(selectedConversationId && selectedConversationId !== persistedConversationId);
}
