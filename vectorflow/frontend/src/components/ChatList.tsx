import { useEffect, useState } from "react";
import { api, type Conversation } from "../api";

interface Props {
  activeId: string | null;
  version: number;
  onSelect: (id: string | null) => void;
  onError: (err: unknown) => string;
}

function group(updatedAt: string): string {
  const d = new Date(updatedAt);
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = 24 * 60 * 60 * 1000;
  if (d.getTime() >= startOfToday) return "Today";
  if (d.getTime() >= startOfToday - day) return "Yesterday";
  if (d.getTime() >= startOfToday - 7 * day) return "Previous 7 days";
  return "Older";
}

export default function ChatList({ activeId, version, onSelect, onError }: Props) {
  const [items, setItems] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .conversations(100)
      .then((c) => {
        if (!cancelled) {
          setItems(c);
          setError(null);
        }
      })
      .catch((err) => !cancelled && setError(onError(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [version, onError]);

  async function rename(c: Conversation) {
    const title = window.prompt("Rename conversation", c.title)?.trim();
    if (!title || title === c.title) return;
    try {
      await api.renameConversation(c.id, title);
      setItems((list) => list.map((x) => (x.id === c.id ? { ...x, title } : x)));
    } catch (err) {
      setError(onError(err));
    }
  }

  async function remove(c: Conversation) {
    if (!window.confirm(`Delete "${c.title}"?`)) return;
    try {
      await api.deleteConversation(c.id);
      setItems((list) => list.filter((x) => x.id !== c.id));
      if (c.id === activeId) onSelect(null);
    } catch (err) {
      setError(onError(err));
    }
  }

  const groups: [string, Conversation[]][] = [];
  for (const c of items) {
    const g = group(c.updatedAt);
    const last = groups[groups.length - 1];
    if (last && last[0] === g) last[1].push(c);
    else groups.push([g, [c]]);
  }

  return (
    <div className="chats">
      <button className="btn new-chat" onClick={() => onSelect(null)}>
        <span aria-hidden>＋</span> New chat
      </button>
      {error && <div className="alert error">{error}</div>}
      {loading ? (
        <p className="muted pad">Loading…</p>
      ) : items.length === 0 ? (
        <p className="muted pad small">No conversations yet. Ask a question to start one.</p>
      ) : (
        groups.map(([label, list]) => (
          <div key={label} className="chat-group">
            <div className="chat-group-label">{label}</div>
            <ul className="chat-list">
              {list.map((c) => (
                <li key={c.id} className={`chat-item ${c.id === activeId ? "active" : ""}`}>
                  <button className="chat-title" title={c.title} onClick={() => onSelect(c.id)}>
                    {c.title}
                  </button>
                  <span className="chat-actions">
                    <button className="icon-btn small" aria-label={`Rename ${c.title}`} onClick={() => rename(c)}>
                      ✎
                    </button>
                    <button className="icon-btn small danger" aria-label={`Delete ${c.title}`} onClick={() => remove(c)}>
                      ✕
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
    </div>
  );
}
