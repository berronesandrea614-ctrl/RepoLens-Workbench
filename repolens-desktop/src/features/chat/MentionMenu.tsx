import { useEffect, useRef } from 'react';
import { MentionItem } from './mentionUtils';

interface Props {
  items: MentionItem[];
  activeIndex: number;
  onSelect: (item: MentionItem) => void;
  onClose: () => void;
  loading?: boolean;
}

export function MentionMenu({ items, activeIndex, onSelect, onClose, loading }: Props) {
  const menuRef = useRef<HTMLDivElement>(null);

  // Auto-scroll active item into view
  useEffect(() => {
    if (!menuRef.current) return;
    const active = menuRef.current.querySelector('.mention-menu-item.active');
    if (active) {
      active.scrollIntoView({ block: 'nearest' });
    }
  }, [activeIndex]);

  // Close on click outside
  useEffect(() => {
    function onMouseDown(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, [onClose]);

  return (
    <div className="mention-menu" ref={menuRef}>
      {loading && items.length === 0 ? (
        <div className="mention-menu-empty">加载中…</div>
      ) : !loading && items.length === 0 ? (
        <div className="mention-menu-empty">没有匹配项</div>
      ) : (
        items.map((item, index) => (
          <div
            key={item.id}
            className={`mention-menu-item${index === activeIndex ? ' active' : ''}${item.disabled ? ' disabled' : ''}`}
            onMouseDown={(e) => {
              // Use mousedown to fire before blur on textarea
              e.preventDefault();
              if (!item.disabled) onSelect(item);
            }}
          >
            <span className="mention-menu-label">{item.label}</span>
            <span className="mention-menu-type">
              {item.type === 'current-file' ? '当前文件' : item.type === 'symbol' ? '符号' : '文件'}
            </span>
          </div>
        ))
      )}
    </div>
  );
}
