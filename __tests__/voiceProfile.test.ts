import { isCompatibleVoiceProfile, VOICEPRINT_MODEL } from '../src/types';

const profile = {
  model: VOICEPRINT_MODEL,
  embedding: [1, 0],
  templates: [
    [1, 0],
    [0.9, 0.1],
  ],
  createdAt: 1,
  durationMs: 10000,
};

describe('voiceprint compatibility', () => {
  it('accepts the current model and legacy profiles without templates', () => {
    expect(isCompatibleVoiceProfile(profile)).toBe(true);
    expect(isCompatibleVoiceProfile({ ...profile, templates: undefined })).toBe(
      true,
    );
  });
  it.each([
    null,
    {},
    { ...profile, model: 'old-model' },
    { ...profile, embedding: [] },
    { ...profile, embedding: [0, 0] },
    { ...profile, embedding: [NaN, 0] },
    { ...profile, embedding: [Infinity, 0] },
    { ...profile, embedding: [1e100, 0] },
    { ...profile, embedding: ['1', 0] },
    { ...profile, templates: [[1]] },
    { ...profile, templates: [[Infinity, 0]] },
    { ...profile, templates: Array(9).fill([1, 0]) },
    { ...profile, durationMs: NaN },
  ])('rejects incompatible or malformed data %#', value => {
    expect(isCompatibleVoiceProfile(value)).toBe(false);
  });
});
