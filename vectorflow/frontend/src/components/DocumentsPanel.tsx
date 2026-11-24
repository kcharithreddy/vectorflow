import { useEffect, useState } from "react";
import { api, type DocumentItem, type User } from "../api";
import AddDocumentModal from "./AddDocumentModal";

interface Props {
  user: User;
  canEdit: boolean;
  version: number;
  onChanged: () => void;
  onError: (err: unknown) => string;
}

export default function DocumentsPanel({ user, canEdit, version, onChanged, onError }: Props) {
  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [mineOnly, setMineOnly] = useState(false);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .documents(1, 100, mineOnly)
      .then((page) => {
        if (cancelled) return;
        setDocs(page.items);
        setTotal(page.totalCount);
        setError(null);
      })
      .catch((err) => !cancelled && setError(onError(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [version, mineOnly, onError]);

  async function remove(doc: DocumentItem) {
    if (!confirm(`Delete "${doc.title}"? This removes it from search.`)) return;
    setDeleting(doc.id);
    try {
      await api.deleteDocument(doc.id);
      onChanged();
    } catch (err) {
      setError(onError(err));
    } finally {
      setDeleting(null);
    }
  }

  const canDelete = (d: DocumentItem) => user.role === "ADMIN" || (user.role === "EDITOR" && d.ownerEmail === user.email);

  return (
    <div className="docs">
      <div className="docs-header">
        <h2>
          Documents <span className="count">{total}</span>
        </h2>
        {canEdit && (
          <button className="btn primary small" onClick={() => setAdding(true)}>
            + Add
          </button>
        )}
      </div>

      <label className="toggle">
        <input type="checkbox" checked={mineOnly} onChange={(e) => setMineOnly(e.target.checked)} />
        Only mine
      </label>

      {error && <div className="alert error">{error}</div>}

      {loading && docs.length === 0 ? (
        <p className="muted pad">Loading…</p>
      ) : docs.length === 0 ? (
        <div className="empty pad">
          <p>No documents yet.</p>
          {canEdit ? <p className="muted">Click “+ Add” to upload your first one.</p> : <p className="muted">Ask an editor to add some.</p>}
        </div>
      ) : (
        <ul className="doc-list">
          {docs.map((d) => (
            <li key={d.id} className="doc-item">
              <div className="doc-main">
                <span className="doc-title" title={d.title}>
                  {d.title}
                </span>
                <span className="doc-meta">
                  <span className={`status status-${d.status.toLowerCase()}`}>{d.status.toLowerCase()}</span>
                  {d.chunkCount} chunk{d.chunkCount === 1 ? "" : "s"}
                  {d.metadata?.category && <span className="chip">{d.metadata.category}</span>}
                </span>
              </div>
              {canDelete(d) && (
                <button className="icon-btn danger" aria-label={`Delete ${d.title}`} disabled={deleting === d.id} onClick={() => remove(d)}>
                  {deleting === d.id ? "…" : "✕"}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {adding && (
        <AddDocumentModal
          onClose={() => setAdding(false)}
          onAdded={() => {
            setAdding(false);
            onChanged();
          }}
          onError={onError}
        />
      )}
    </div>
  );
}
