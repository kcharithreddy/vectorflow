import { useEffect, useRef, useState, type DragEvent, type FormEvent } from "react";
import { api } from "../api";

interface Props {
  onClose: () => void;
  onAdded: () => void;
  onError: (err: unknown) => string;
}

const ACCEPT = [".pdf", ".docx", ".txt", ".md", ".markdown", ".csv", ".json", ".html", ".xml", ".log", ".rst"];
const MAX_BYTES = 20 * 1024 * 1024;

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function titleFromFilename(name: string) {
  return name.replace(/\.[^.]+$/, "").replace(/[-_]+/g, " ").trim();
}

export default function AddDocumentModal({ onClose, onAdded, onError }: Props) {
  const [mode, setMode] = useState<"file" | "text">("file");
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [category, setCategory] = useState("");
  const [tags, setTags] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && !busy && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  function pick(f: File | undefined) {
    if (!f) return;
    const ext = f.name.slice(f.name.lastIndexOf(".")).toLowerCase();
    if (!ACCEPT.includes(ext)) {
      setError(`Unsupported file type "${ext}". Use PDF, DOCX or a text file.`);
      return;
    }
    if (f.size > MAX_BYTES) {
      setError("File is larger than 20 MB.");
      return;
    }
    setFile(f);
    setError(null);
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    setDragging(false);
    pick(e.dataTransfer.files?.[0]);
  }

  const tagList = () =>
    tags
      .split(",")
      .map((t) => t.trim())
      .filter(Boolean);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "file") {
        if (!file) throw new Error("Choose a file first.");
        const res = await api.uploadFile(file, { title, category, tags: tagList() });
        if (res.status !== "COMPLETED") throw new Error(res.message);
      } else {
        const res = await api.ingest(title.trim(), content, category.trim() || undefined, tagList());
        if (res.status !== "COMPLETED") throw new Error(res.message);
      }
      onAdded();
    } catch (err) {
      setError(onError(err));
    } finally {
      setBusy(false);
    }
  }

  const canSubmit = mode === "file" ? !!file : !!title.trim() && !!content.trim();

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="add-doc-title">
        <h2 id="add-doc-title">Add a document</h2>

        <div className="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={mode === "file"} className={mode === "file" ? "active" : ""} onClick={() => setMode("file")}>
            Upload file
          </button>
          <button type="button" role="tab" aria-selected={mode === "text"} className={mode === "text" ? "active" : ""} onClick={() => setMode("text")}>
            Paste text
          </button>
        </div>

        <form onSubmit={submit} className="form">
          {mode === "file" ? (
            <>
              {file ? (
                <div className="file-chip">
                  <span className="file-icon" aria-hidden>
                    {file.name.split(".").pop()?.toUpperCase().slice(0, 4)}
                  </span>
                  <span className="file-name" title={file.name}>
                    {file.name}
                  </span>
                  <span className="muted small">{formatSize(file.size)}</span>
                  <button type="button" className="icon-btn" aria-label="Remove file" onClick={() => setFile(null)} disabled={busy}>
                    ✕
                  </button>
                </div>
              ) : (
                <div
                  className={`dropzone ${dragging ? "dragging" : ""}`}
                  role="button"
                  tabIndex={0}
                  onClick={() => inputRef.current?.click()}
                  onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && inputRef.current?.click()}
                  onDragOver={(e) => {
                    e.preventDefault();
                    setDragging(true);
                  }}
                  onDragLeave={() => setDragging(false)}
                  onDrop={onDrop}
                >
                  <strong>Drop a file here, or click to browse</strong>
                  <span className="muted small">PDF, DOCX, TXT, Markdown, CSV… up to 20 MB</span>
                </div>
              )}
              <input ref={inputRef} type="file" hidden accept={ACCEPT.join(",")} onChange={(e) => pick(e.target.files?.[0])} />
              <label>
                Title <span className="muted">(optional)</span>
                <input maxLength={500} value={title} onChange={(e) => setTitle(e.target.value)} placeholder={file ? titleFromFilename(file.name) : "Defaults to the file name"} />
              </label>
            </>
          ) : (
            <>
              <label>
                Title
                <input maxLength={500} value={title} onChange={(e) => setTitle(e.target.value)} />
              </label>
              <label>
                Content
                <textarea rows={10} value={content} onChange={(e) => setContent(e.target.value)} placeholder="Paste text here." />
              </label>
            </>
          )}

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
            <button type="submit" className="btn primary" disabled={busy || !canSubmit}>
              {busy ? (mode === "file" ? "Reading & embedding…" : "Embedding…") : "Add document"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
