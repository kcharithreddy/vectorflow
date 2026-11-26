import { Fragment, useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { api, type AskResult, type SearchHit } from "../api";

type Mode = "ask" | "search";

type Message =
  | { id: number; role: "user"; text: string; mode: Mode }
  | { id: number; role: "answer"; result: AskResult; streaming?: boolean }
  | { id: number; role: "results"; hits: SearchHit[]; query: string }
  | { id: number; role: "error"; text: string };

interface Props {
  canEdit: boolean;
  onError: (err: unknown) => string;
}

const EXAMPLES = ["Summarize the main ideas in my documents", "What is a vector database?", "How do JWT tokens work?"];

let nextId = 1;

export default function ChatPanel({ canEdit, onError }: Props) {
  const [mode, setMode] = useState<Mode>("ask");
  const [input, setInput] = useState("");
  const [category, setCategory] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);
  const [busy, setBusy] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages, busy]);

  async function send(text: string) {
    const q = text.trim();
    if (!q || busy) return;
    const filters = category.trim() ? { category: category.trim() } : undefined;
    setMessages((m) => [...m, { id: nextId++, role: "user", text: q, mode }]);
    setInput("");
    setBusy(true);
    try {
      if (mode === "ask") {
        // Stream the answer: show text as it arrives, sources/confidence at the end.
        const id = nextId++;
        setMessages((m) => [...m, { id, role: "answer", streaming: true, result: { answer: "", confidence: 0, sources: [] } }]);
        const update = (fn: (r: AskResult) => AskResult, streaming = true) =>
          setMessages((m) => m.map((msg) => (msg.id === id && msg.role === "answer" ? { ...msg, streaming, result: fn(msg.result) } : msg)));
        try {
          const final = await api.askStream(q, (text) => update((r) => ({ ...r, answer: r.answer + text })), { filters });
          update(() => final, false);
        } catch (err) {
          setMessages((m) => m.filter((msg) => msg.id !== id || (msg.role === "answer" && msg.result.answer)));
          update((r) => r, false);
          throw err;
        }
      } else {
        const hits = await api.search(q, 8, filters);
        setMessages((m) => [...m, { id: nextId++, role: "results", hits, query: q }]);
      }
    } catch (err) {
      setMessages((m) => [...m, { id: nextId++, role: "error", text: onError(err) }]);
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    send(input);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send(input);
    }
  }

  return (
    <div className="chat">
      <div className="chat-scroll">
        {messages.length === 0 ? (
          <div className="welcome">
            <h1>Ask your documents anything</h1>
            <p className="muted">
              Answers are generated only from your ingested documents and cite their sources like <span className="cite static">1</span>.
              {canEdit ? " Add documents from the panel on the left." : ""}
            </p>
            <div className="examples">
              {EXAMPLES.map((ex) => (
                <button key={ex} className="example" onClick={() => send(ex)}>
                  {ex}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((m) => <MessageView key={m.id} message={m} />)
        )}
        {busy && !(messages.length && messages[messages.length - 1].role === "answer") && (
          <div className="msg assistant">
            <div className="bubble typing" aria-label="Thinking">
              <span />
              <span />
              <span />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <form className="composer" onSubmit={onSubmit}>
        <div className="composer-tools">
          <div className="segmented" role="radiogroup" aria-label="Mode">
            <button type="button" role="radio" aria-checked={mode === "ask"} className={mode === "ask" ? "active" : ""} onClick={() => setMode("ask")}>
              Ask
            </button>
            <button type="button" role="radio" aria-checked={mode === "search"} className={mode === "search" ? "active" : ""} onClick={() => setMode("search")}>
              Search
            </button>
          </div>
          <input className="filter" value={category} onChange={(e) => setCategory(e.target.value)} placeholder="Filter by category (optional)" aria-label="Category filter" />
        </div>
        <div className="composer-row">
          <textarea
            rows={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={mode === "ask" ? "Ask a question about your documents…" : "Search your documents…"}
            aria-label="Message"
          />
          <button className="btn primary" type="submit" disabled={busy || !input.trim()}>
            {mode === "ask" ? "Ask" : "Search"}
          </button>
        </div>
      </form>
    </div>
  );
}

function MessageView({ message }: { message: Message }) {
  switch (message.role) {
    case "user":
      return (
        <div className="msg user">
          <div className="bubble">
            {message.mode === "search" && <span className="mode-tag">search</span>}
            {message.text}
          </div>
        </div>
      );
    case "error":
      return (
        <div className="msg assistant">
          <div className="bubble error-bubble">{message.text}</div>
        </div>
      );
    case "answer":
      return <AnswerView id={message.id} result={message.result} streaming={!!message.streaming} />;
    case "results":
      return (
        <div className="msg assistant">
          <div className="bubble wide">
            {message.hits.length === 0 ? (
              <p className="muted">No matching passages found.</p>
            ) : (
              <>
                <p className="muted small">
                  {message.hits.length} passage{message.hits.length === 1 ? "" : "s"} for “{message.query}”
                </p>
                <ol className="sources">
                  {message.hits.map((h) => (
                    <li key={h.id} className="source">
                      <div className="source-head">
                        <strong>{h.title}</strong>
                        <Relevance value={h.score} />
                      </div>
                      <p>{h.snippet}</p>
                      {h.metadata?.category && <span className="chip">{h.metadata.category}</span>}
                    </li>
                  ))}
                </ol>
              </>
            )}
          </div>
        </div>
      );
  }
}

function AnswerView({ id, result, streaming }: { id: number; result: AskResult; streaming: boolean }) {
  const [highlight, setHighlight] = useState<number | null>(null);
  const sourceId = (n: number) => `src-${id}-${n}`;

  function focusSource(n: number) {
    setHighlight(n);
    document.getElementById(sourceId(n))?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    window.setTimeout(() => setHighlight(null), 1600);
  }

  // Sources the answer actually cites come first; the rest are tucked away.
  const cited = citedNumbers(result.answer, result.sources.length);
  const numbered = result.sources.map((s, i) => [s, i + 1] as const);
  const primary = cited.size ? numbered.filter(([, n]) => cited.has(n)) : numbered;
  const secondary = cited.size ? numbered.filter(([, n]) => !cited.has(n)) : [];

  const renderSource = ([s, n]: readonly [AskResult["sources"][number], number]) => (
    <li key={n} id={sourceId(n)} className={`source ${highlight === n ? "flash" : ""}`}>
      <div className="source-head">
        <span className="cite static">{n}</span>
        <strong>{s.title}</strong>
        <Relevance value={s.relevance} />
      </div>
      <p>{s.snippet}</p>
    </li>
  );

  return (
    <div className="msg assistant">
      <div className="bubble wide">
        <div className="answer">
          {renderWithCitations(result.answer, result.sources.length, focusSource)}
          {streaming && <span className="cursor" aria-hidden />}
        </div>
        {!streaming && result.sources.length > 0 && (
          <>
            <div className="answer-meta">
              <span className="muted small">Confidence</span>
              <Meter value={result.confidence} />
              <span className="small">{Math.round(result.confidence * 100)}%</span>
            </div>
            <ol className="sources">{primary.map(renderSource)}</ol>
            {secondary.length > 0 && (
              <details className="more-sources" open={highlight !== null && secondary.some(([, n]) => n === highlight)}>
                <summary>
                  {secondary.length} more retrieved passage{secondary.length === 1 ? "" : "s"}
                </summary>
                <ol className="sources">{secondary.map(renderSource)}</ol>
              </details>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function citedNumbers(text: string, sourceCount: number): Set<number> {
  const found = new Set<number>();
  for (const m of text.matchAll(/\[(\d+)\]/g)) {
    const n = Number(m[1]);
    if (n >= 1 && n <= sourceCount) found.add(n);
  }
  return found;
}

/** Turn "[1]" / "[2][3]" markers in the answer into clickable citation chips. */
function renderWithCitations(text: string, sourceCount: number, onCite: (n: number) => void): ReactNode {
  const parts = text.split(/(\[\d+\])/g);
  return parts.map((part, i) => {
    const m = /^\[(\d+)\]$/.exec(part);
    if (m) {
      const n = Number(m[1]);
      if (n >= 1 && n <= sourceCount) {
        return (
          <button key={i} className="cite" onClick={() => onCite(n)} aria-label={`Show source ${n}`}>
            {n}
          </button>
        );
      }
    }
    return <Fragment key={i}>{part}</Fragment>;
  });
}

function Meter({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  const level = pct >= 75 ? "high" : pct >= 45 ? "mid" : "low";
  return (
    <span className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <span className={`meter-fill ${level}`} style={{ width: `${pct}%` }} />
    </span>
  );
}

function Relevance({ value }: { value: number }) {
  return <span className="relevance">{(value * 100).toFixed(0)}% match</span>;
}
