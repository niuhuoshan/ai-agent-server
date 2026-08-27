import { describe, expect, it } from 'vitest';
import {
  defaultPromptVariables,
  mergePromptVariables,
  missingPromptVariables,
  parsePromptVariables,
  promptRequestError
} from './prompt-studio-state';

describe('prompt studio request state', () => {
  it('accepts only a JSON object for prompt variables', () => {
    expect(parsePromptVariables('{"department":"财务","limit":10}')).toEqual({
      department: '财务',
      limit: 10
    });
    expect(() => parsePromptVariables('["财务"]')).toThrow('JSON 对象');
    expect(() => parsePromptVariables('{invalid')).toThrow('有效的 JSON 对象');
  });

  it('builds an editable variable document from declared placeholders', () => {
    expect(defaultPromptVariables(['department', 'period'])).toBe(
      '{\n  "department": "",\n  "period": ""\n}'
    );
  });

  it('detects missing declared variables and preserves existing test values', () => {
    expect(missingPromptVariables(['department', 'period'], '{"department":"财务"}')).toEqual(['period']);
    expect(mergePromptVariables(['department', 'period'], '{"department":"财务"}')).toBe(
      '{\n  "department": "财务",\n  "period": ""\n}'
    );
  });

  it('keeps the backend model error available for the inline failure state', () => {
    expect(
      promptRequestError(
        { response: { data: { message: '未配置可用的对话模型' } } },
        '测试失败'
      )
    ).toBe('未配置可用的对话模型');
    expect(promptRequestError(null, '测试失败')).toBe('测试失败');
  });

  it('turns a 501 model capability response into an actionable inline error', () => {
    expect(
      promptRequestError(
        { response: { status: 501, data: { code: 501, message: '模型端点未配置' } } },
        '测试失败'
      )
    ).toBe('提示词测试/优化当前不可用：请先在模型中心配置并启用对话模型');
  });
});
