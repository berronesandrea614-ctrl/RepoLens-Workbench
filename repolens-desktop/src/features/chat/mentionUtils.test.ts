import { describe, it, expect } from 'vitest';
import { filterMentionItems, navigateMentionMenu, buildSymbolMentionValue, findAtTrigger, MentionItem } from './mentionUtils';

function makeItems(count: number): MentionItem[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `item-${i}`,
    type: 'file' as const,
    label: `file-${i}.ts`,
    value: `src/file-${i}.ts`,
  }));
}

describe('filterMentionItems', () => {
  it('empty query returns all items (up to 15)', () => {
    const items = makeItems(10);
    expect(filterMentionItems('', items)).toHaveLength(10);
  });

  it('empty query truncates beyond 15', () => {
    const items = makeItems(20);
    expect(filterMentionItems('', items)).toHaveLength(15);
  });

  it('case-insensitive match', () => {
    const items: MentionItem[] = [
      { id: '1', type: 'file', label: 'UserService.ts', value: 'src/UserService.ts' },
      { id: '2', type: 'file', label: 'orderService.ts', value: 'src/orderService.ts' },
    ];
    const result = filterMentionItems('user', items);
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe('1');
  });

  it('substring match', () => {
    const items: MentionItem[] = [
      { id: '1', type: 'file', label: 'UserRepository.ts', value: 'src/UserRepository.ts' },
      { id: '2', type: 'file', label: 'OrderController.ts', value: 'src/OrderController.ts' },
    ];
    const result = filterMentionItems('Repo', items);
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe('1');
  });

  it('items beyond 15 are truncated', () => {
    const items = makeItems(20);
    // All items label is "file-N.ts", query "file" matches all
    const result = filterMentionItems('file', items);
    expect(result).toHaveLength(15);
  });

  it('no match returns empty array', () => {
    const items = makeItems(5);
    const result = filterMentionItems('zzznomatch', items);
    expect(result).toHaveLength(0);
  });
});

describe('navigateMentionMenu', () => {
  it('returns -1 when itemCount is 0', () => {
    expect(navigateMentionMenu(0, 'up', 0)).toBe(-1);
    expect(navigateMentionMenu(0, 'down', 0)).toBe(-1);
  });

  it('up from 0 wraps to last', () => {
    expect(navigateMentionMenu(0, 'up', 5)).toBe(4);
  });

  it('down from last wraps to 0', () => {
    expect(navigateMentionMenu(4, 'down', 5)).toBe(0);
  });

  it('normal down increments', () => {
    expect(navigateMentionMenu(2, 'down', 5)).toBe(3);
  });

  it('normal up decrements', () => {
    expect(navigateMentionMenu(3, 'up', 5)).toBe(2);
  });
});

describe('buildSymbolMentionValue', () => {
  it('returns ClassName#methodName when both are present', () => {
    expect(buildSymbolMentionValue('UserService', 'createUser')).toBe('UserService#createUser');
  });

  it('returns className only when methodName is null', () => {
    expect(buildSymbolMentionValue('UserService', null)).toBe('UserService');
  });

  it('returns className only when methodName is undefined', () => {
    expect(buildSymbolMentionValue('UserService', undefined)).toBe('UserService');
  });

  it('returns empty string when className is null', () => {
    expect(buildSymbolMentionValue(null, null)).toBe('');
  });

  it('returns empty string when className is undefined', () => {
    expect(buildSymbolMentionValue(undefined, undefined)).toBe('');
  });

  it('returns className only when methodName is empty string (falsy)', () => {
    expect(buildSymbolMentionValue('UserService', '')).toBe('UserService');
  });
});

describe('findAtTrigger', () => {
  it('returns null when no @ present', () => {
    expect(findAtTrigger('hello world', 11)).toBeNull();
  });

  it('returns trigger at start of input', () => {
    expect(findAtTrigger('@user', 5)).toEqual({ atIdx: 0, query: 'user' });
  });

  it('returns trigger when @ preceded by space', () => {
    expect(findAtTrigger('hello @user', 11)).toEqual({ atIdx: 6, query: 'user' });
  });

  it('returns trigger when @ preceded by tab', () => {
    expect(findAtTrigger('hello\t@user', 11)).toEqual({ atIdx: 6, query: 'user' });
  });

  it('returns null when @ preceded by non-whitespace (email address)', () => {
    expect(findAtTrigger('a@b', 3)).toBeNull();
  });

  it('returns null when @ is embedded in a word', () => {
    expect(findAtTrigger('abc@def', 7)).toBeNull();
  });

  it('returns null when space exists between @ and cursor', () => {
    expect(findAtTrigger('@user query', 11)).toBeNull();
  });

  it('returns empty query when cursor is right after @', () => {
    expect(findAtTrigger('hello @', 7)).toEqual({ atIdx: 6, query: '' });
  });

  it('respects cursor position — only text before cursor is scanned', () => {
    // cursor at 5 means only "@user" is before cursor, rest is ignored
    expect(findAtTrigger('@user more text', 5)).toEqual({ atIdx: 0, query: 'user' });
  });

  it('returns null when @ preceded by non-whitespace at end of input', () => {
    expect(findAtTrigger('abc@', 4)).toBeNull();
  });
});
