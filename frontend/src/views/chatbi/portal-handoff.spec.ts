import { describe, expect, it } from 'vitest';
import { parseChatBIPortalHandoff } from './portal-handoff';

describe('ChatBI data portal handoff', () => {
  it('keeps the question and exact string dataset identifier', () => {
    expect(
      parseChatBIPortalHandoff({
        source: 'data_portal',
        question: '  分析最近 30 天订单趋势  ',
        dataset_id: '9223372036854775806'
      })
    ).toEqual({
      question: '分析最近 30 天订单趋势',
      datasetId: '9223372036854775806'
    });
  });

  it('ignores unrelated or empty route query state', () => {
    expect(parseChatBIPortalHandoff({ source: 'workspace', question: '测试' })).toBeNull();
    expect(parseChatBIPortalHandoff({ source: 'data_portal', question: ' ' })).toBeNull();
  });
});
