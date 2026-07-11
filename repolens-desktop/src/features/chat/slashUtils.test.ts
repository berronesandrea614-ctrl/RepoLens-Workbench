import { describe, it, expect } from 'vitest';
import { findSlashTrigger, filterSlashItems, SlashItem } from './slashUtils';

function items(...names: string[]): SlashItem[] {
  return names.map((n) => ({ name: n, description: `desc of ${n}`, type: 'skill' as const, source: 'builtin' }));
}

describe('findSlashTrigger', () => {
  it('opens when input starts with / and no space yet', () => {
    expect(findSlashTrigger('/deep', 5)).toEqual({ slashIdx: 0, query: 'deep' });
    expect(findSlashTrigger('/', 1)).toEqual({ slashIdx: 0, query: '' });
  });

  it('does not open when / is not the first char', () => {
    expect(findSlashTrigger('hi /deep', 8)).toBeNull();
    expect(findSlashTrigger('a/b', 3)).toBeNull();
  });

  it('closes once a space is typed (rest is arguments)', () => {
    expect(findSlashTrigger('/deep research this', 19)).toBeNull();
    expect(findSlashTrigger('/deep ', 6)).toBeNull();
  });

  it('uses the cursor position, not the full text', () => {
    // cursor right after "/de" while more text exists after
    expect(findSlashTrigger('/deep-research', 3)).toEqual({ slashIdx: 0, query: 'de' });
  });
});

describe('filterSlashItems', () => {
  const all = items('deep-research', 'research', 'brainstorm', 'test-driven-development');

  it('empty query returns all (up to 20)', () => {
    expect(filterSlashItems('', all).length).toBe(4);
  });

  it('matches by name substring, case-insensitive', () => {
    const r = filterSlashItems('RESEARCH', all).map((i) => i.name);
    expect(r).toContain('deep-research');
    expect(r).toContain('research');
    expect(r).not.toContain('brainstorm');
  });

  it('matches by description too', () => {
    const custom: SlashItem[] = [
      { name: 'x', description: 'debug the failing test', type: 'skill', source: 'builtin' },
    ];
    expect(filterSlashItems('debug', custom).length).toBe(1);
  });
});
