import { request } from '../request';
import type { RuntimeUserQuestionStatus } from '@/utils/runtime-user-question';

export interface RuntimeUserQuestionView {
  questionId: string;
  conversationId?: string | null;
  conversationTurnId?: string | null;
  turnId?: string | null;
  runId?: string | null;
  executionId?: string | null;
  toolCallId?: string | null;
  status: RuntimeUserQuestionStatus | 'superseded';
  question?: string;
  options?: Array<{ id: string; label: string; description?: string }>;
  multiSelect?: boolean;
  allowCustomInput?: boolean;
  context?: string | null;
  purpose?: string | null;
  selectedOptionIds?: string[];
  customInput?: string | null;
  expiresAt?: string | null;
  answeredAt?: string | null;
}

export interface RuntimeUserQuestionDecisionPayload {
  idempotencyKey: string;
  selectedOptionIds: string[];
  customInput: string;
  cancelled?: boolean;
}

export interface RuntimeUserQuestionDecisionResult {
  question: RuntimeUserQuestionView;
  replayed: boolean;
  resumed?: boolean;
}

function questionPath(questionId: string, action: 'answer' | 'cancel') {
  return `/platform/runtime-user-questions/${encodeURIComponent(questionId)}/${action}`;
}

/** Submit a selected answer to an Agent-originated question. */
export function answerRuntimeUserQuestion(
  questionId: string,
  payload: RuntimeUserQuestionDecisionPayload,
) {
  return request<RuntimeUserQuestionDecisionResult>({
    url: questionPath(questionId, 'answer'),
    method: 'post',
    data: payload,
  });
}

/** Cancel an Agent-originated question without treating it as a business approval. */
export function cancelRuntimeUserQuestion(
  questionId: string,
  payload: Pick<RuntimeUserQuestionDecisionPayload, 'idempotencyKey'>,
) {
  return request<RuntimeUserQuestionDecisionResult>({
    url: questionPath(questionId, 'cancel'),
    method: 'post',
    data: { idempotencyKey: payload.idempotencyKey },
  });
}

export function fetchRuntimeUserQuestion(questionId: string) {
  return request<RuntimeUserQuestionView>({
    url: `/platform/runtime-user-questions/${encodeURIComponent(questionId)}`,
    method: 'get',
  });
}

export function fetchPendingRuntimeUserQuestions(conversationId: string, limit = 20) {
  return request<RuntimeUserQuestionView[]>({
    url: '/platform/runtime-user-questions/pending',
    method: 'get',
    params: { conversationId, limit },
  });
}
