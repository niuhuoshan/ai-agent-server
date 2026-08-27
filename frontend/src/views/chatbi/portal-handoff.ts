export interface ChatBIPortalHandoff {
  question: string;
  datasetId?: string;
}

function queryText(value: unknown) {
  const candidate = Array.isArray(value) ? value[0] : value;
  return typeof candidate === 'string' ? candidate.trim() : '';
}

export function parseChatBIPortalHandoff(query: Record<string, unknown>): ChatBIPortalHandoff | null {
  if (queryText(query.source) !== 'data_portal') return null;
  const question = queryText(query.question);
  if (!question) return null;
  const datasetId = queryText(query.dataset_id);
  return { question, ...(datasetId ? { datasetId } : {}) };
}
