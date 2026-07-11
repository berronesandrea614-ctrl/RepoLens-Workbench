import { SlashItem } from "../../api/slashApi";

export type { SlashItem };

/**
 * Detect the `/` slash trigger. Slash commands live at the very start of the
 * message (Claude Code semantics), so:
 * - The `/` must be the first character of the whole input.
 * - The menu stays open only while typing the command name — once a space is
 *   typed, the rest is arguments and the menu closes.
 */
export function findSlashTrigger(
  text: string,
  cursor: number,
): { slashIdx: number; query: string } | null {
  if (!text.startsWith("/")) return null;
  const before = text.slice(0, cursor);
  const query = before.slice(1);
  // A space (anywhere before the cursor) means we've moved on to arguments.
  if (/\s/.test(query)) return null;
  return { slashIdx: 0, query };
}

/** Filter slash items by name or description (case-insensitive). Up to 20. */
export function filterSlashItems(query: string, items: SlashItem[]): SlashItem[] {
  if (!query) return items.slice(0, 20);
  const q = query.toLowerCase();
  return items
    .filter(
      (i) =>
        i.name.toLowerCase().includes(q) ||
        i.description.toLowerCase().includes(q),
    )
    .slice(0, 20);
}
