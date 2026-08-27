import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { downloadSkillArchive, fetchSkillFile, fetchSkillFiles, uploadSkillArchive } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
  vi.restoreAllMocks();
});

describe('Skill bundle API', () => {
  it('uses versioned file tree and content routes', () => {
    fetchSkillFiles('10', '11');
    fetchSkillFile('10', '11', 'assets/logo.png');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/skills/10/versions/11/files',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/skills/10/versions/11/files/content',
      method: 'get',
      params: { path: 'assets/logo.png' }
    });
  });

  it('sends the complete ZIP as multipart data', () => {
    const file = new File(['zip'], 'skill.zip', { type: 'application/zip' });
    uploadSkillArchive('10', '11', file);
    const call = vi.mocked(request).mock.calls[0][0] as { url: string; data: FormData };
    expect(call.url).toBe('/platform/skills/10/versions/11/files/upload-archive');
    expect(call.data).toBeInstanceOf(FormData);
    expect(call.data.get('file')).toBe(file);
  });

  it('downloads the server archive without turning binary bytes into text', async () => {
    const blob = new Blob([new Uint8Array([0, 255, 1])], { type: 'application/zip' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(blob, {
      status: 200,
      headers: { 'content-disposition': "attachment; filename*=UTF-8''skill.zip" }
    })));

    const result = await downloadSkillArchive('10', '11');
    expect(result.fileName).toBe('skill.zip');
    expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(new Uint8Array([0, 255, 1]));
  });
});
