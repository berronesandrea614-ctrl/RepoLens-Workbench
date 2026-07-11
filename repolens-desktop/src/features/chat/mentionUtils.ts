export interface MentionItem {
  id: string;       // unique key for React
  type: 'file' | 'symbol' | 'current-file';
  label: string;    // display
  value: string;    // what gets inserted and sent to backend
  disabled?: boolean;
}

/** Filter items by query (case-insensitive substring). Returns up to 15 results. */
export function filterMentionItems(query: string, items: MentionItem[]): MentionItem[] {
  if (!query) return items.slice(0, 15);
  const q = query.toLowerCase();
  return items.filter(i => i.label.toLowerCase().includes(q)).slice(0, 15);
}

/** Navigate menu up/down, wrapping. Returns new index. */
export function navigateMentionMenu(
  currentIndex: number,
  direction: 'up' | 'down',
  itemCount: number,
): number {
  if (itemCount === 0) return -1;
  if (direction === 'up') {
    return currentIndex <= 0 ? itemCount - 1 : currentIndex - 1;
  } else {
    return currentIndex >= itemCount - 1 ? 0 : currentIndex + 1;
  }
}

/**
 * Build the backend-canonical value for a symbol mention.
 * Uses `#` as class/method separator so the backend can reliably parse it.
 * The display label keeps the user-friendly `.` separator.
 */
export function buildSymbolMentionValue(
  className: string | null | undefined,
  methodName: string | null | undefined,
): string {
  if (methodName && className) return `${className}#${methodName}`;
  return className ?? '';
}

/**
 * Given the textarea value and cursor position, returns the @ trigger state
 * if an active mention trigger is found, or null otherwise.
 *
 * Rules:
 * - The `@` must be at the start of input or preceded by whitespace (emails like
 *   `a@b` must NOT open the menu).
 * - There must be no space between `@` and the cursor (a space closes the trigger).
 */
export function findAtTrigger(
  text: string,
  cursor: number,
): { atIdx: number; query: string } | null {
  const textBeforeCursor = text.slice(0, cursor);
  const atIdx = textBeforeCursor.lastIndexOf('@');
  if (atIdx === -1) return null;
  // Word boundary: @ must be at start-of-input or preceded by whitespace
  if (atIdx > 0 && !/\s/.test(textBeforeCursor[atIdx - 1])) return null;
  const query = textBeforeCursor.slice(atIdx + 1);
  // Space after @ closes the trigger
  if (query.includes(' ')) return null;
  return { atIdx, query };
}
