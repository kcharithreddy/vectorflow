import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { api } from "../api";

interface Props {
  onClose: () => void;
  onAdded: () => void;
  onError: (err: unknown) => string;
}

const TEXT_TYPES = [".txt", ".md", ".markdown", ".csv", ".json", ".html", ".xml", ".log"];
const MAX_FILE_BYTES = 2_000_000;

export default function AddDocumentModal({ onClose, onAdded, onError }: Props) {
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [category, setCategory] = useState("");
  const [tags, setTags] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && !busy && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function loadFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_FILE_BYTES) {
      setError("File is larger than 2 MB.");
      return;
    }
    const text = await file.text();
    setContent(text);
    if (!title) setTitle(file.name.replace(/\.[^.]+$/, "").replace(/[-_]+/g, " "));
    setError(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const tagList = tags
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean);
      const res = await api.ingest(title.trim(), content, category.trim() || undefined, tagList);
      if (res.status !== "COMPLETED") throw new Error(res.message);
      onAdded();
    } catch (err) {
      setError(onError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="add-doc-title">
        <h2 id="add-doc-title">Add a document</h2>
        <form onSubmit={submit} className="form">
          <label>
            Upload a text file <span className="muted">(optional)</span>
            <input type="file" accept={TEXT_TYPES.join(",")} onChange={loadFile} />
          </label>
          <label>
            Title
            <input required maxLength={500} value={title} onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label>
            Content
            <textarea required rows={10} value={content} onChange={(e) => setContent(e.target.value)} placeholder="Paste text here, or upload a file above." />
          </label>
          <div className="row">
            <label>
              Category <span className="muted">(optional)</span>
              <input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="e.g. devops" />
            </label>
            <label>
              Tags <span className="muted">(comma-separated)</span>
              <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="e.g. kubernetes, notes" />
            </label>
          </div>
          {error && <div className="alert error">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button type="submit" className="btn primary" disabled={busy || !title.trim() || !content.trim()}>
              {busy ? "Embedding…" : "Add document"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
