import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { api, type AnswerMode, type AskResult, type ChatOptions, type ConversationEvent, type SearchHit } from "../api";
import Markdown from "./Markdown";

type Mode = "ask" | "search";

type Message =
  | { id: number; role: "user"; text: string; mode: Mode }
  | { id: number; role: "answer"; result: AskResult; streaming?: boolean; stopped?: boolean }
  | { id: number; role: "results"; hits: SearchHit[]; query: string }
  | { id: number; role: "error"; text: string };

interface Props {
  canEdit: boolean;
  /** Conversation to show; null = a new, empty chat. */
  conversationId: string | null;
  /** Called when the server creates/continues a conversation for a message we sent. */
  onConversation: (c: ConversationEvent) => void;
  onError: (err: unknown) => string;
}

const EXAMPLES: Record<AnswerMode, string[]> = {
  auto: ["Summarize the main ideas in my documents", "Explain how HTTPS works in simple terms", "Write a Python function that reverses a string"],
  documents: ["Summarize the main ideas in my documents", "What are the key points I should know?", "Explain this topic simply"],
  general: ["Explain how HTTPS works in simple terms", "Compare SQL and NoSQL databases in a table", "Write a Python function that reverses a string"],
};

const ANSWER_MODES: { value: AnswerMode; label: string; hint: string }[] = [
  { value: "auto", label: "Auto", hint: "Uses your documents when they answer the question, otherwise general knowledge" },
  { value: "documents", label: "My documents", hint: "Answers come only from your documents" },
  { value: "general", label: "General knowledge", hint: "A general AI assistant; your documents are not used" },
];

const MODE_KEY = "vectorflow.answerMode";
const MODEL_KEY = "vectorflow.model";

function load(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function save(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* storage unavailable */
  }
}

let nextId = 1;

export default function ChatPanel({ canEdit, conversationId, onConversation, onError }: Props) {
  const [mode, setMode] = useState<Mode>("ask");
  const [input, setInput] = useState("");
  const [category, setCategory] = useState("");
  const [answerMode, setAnswerMode] = useState<AnswerMode>(() => {
    const saved = load(MODE_KEY);
    return saved === "documents" || saved === "general" ? saved : "auto";
  });
  const [model, setModel] = useState<string>(() => load(MODEL_KEY) ?? "");
  const [options, setOptions] = useState<ChatOptions | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  // The conversation this panel is already showing (so selecting it again doesn't reload mid-stream).
  const shownConversation = useRef<string | null>(null);

  // Which models the server allows (the picker is hidden until this arrives).
  useEffect(() => {
    let cancelled = false;
    api
      .chatOptions()
      .then((o) => {
        if (cancelled) return;
        setOptions(o);
        setModel((m) => (o.models.includes(m) ? m : o.default_model));
      })
      .catch(() => {
        /* the server default model is used */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function chooseMode(m: AnswerMode) {
    setAnswerMode(m);
    save(MODE_KEY, m);
  }

  function chooseModel(m: string) {
    setModel(m);
    save(MODEL_KEY, m);
  }

  // Load a conversation's history when the selection changes.
  useEffect(() => {
    if (conversationId === shownConversation.current) return;
    abortRef.current?.abort();
    shownConversation.current = conversationId;
    setMessages([]);
    if (!conversationId) return;
    let cancelled = false;
    setLoading(true);
    api
      .conversationMessages(conversationId)
      .then((c) => {
        if (cancelled || !c) return;
        setMessages(
          c.messages.map((m) =>
            m.role === "user"
              ? { id: nextId++, role: "user", text: m.content, mode: "ask" as Mode }
              : {
                  id: nextId++,
                  role: "answer",
                  result: { answer: m.content, confidence: m.confidence ?? 0, sources: m.sources, answerType: m.answerType, model: m.model },
                },
          ),
        );
      })
      .catch((err) => !cancelled && setMessages([{ id: nextId++, role: "error", text: onError(err) }]))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [conversationId, onError]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages, busy]);

  // Grow the textarea with its content (up to the CSS max-height).
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [input]);

  async function streamAnswer(question: string, regenerate = false) {
    const filters = category.trim() ? { category: category.trim() } : undefined;
    const id = nextId++;
    const controller = new AbortController();
    abortRef.current = controller;
    setMessages((m) => [...m, { id, role: "answer", streaming: true, result: { answer: "", confidence: 0, sources: [] } }]);
    const update = (fn: (msg: Extract<Message, { role: "answer" }>) => Partial<Extract<Message, { role: "answer" }>>) =>
      setMessages((m) => m.map((msg) => (msg.id === id && msg.role === "answer" ? { ...msg, ...fn(msg) } : msg)));
    try {
      const final = await api.askStream(question, (text) => update((msg) => ({ result: { ...msg.result, answer: msg.result.answer + text } })), {
        filters,
        signal: controller.signal,
        conversationId: shownConversation.current,
        regenerate,
        mode: answerMode,
        model: options ? model : null,
        onConversation: (c) => {
          shownConversation.current = c.id;
          onConversation(c);
        },
      });
      update(() => ({ result: final, streaming: false }));
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        update(() => ({ streaming: false, stopped: true }));
        return;
      }
      setMessages((m) => m.filter((msg) => msg.id !== id || (msg.role === "answer" && msg.result.answer)));
      update(() => ({ streaming: false }));
      throw err;
    } finally {
      abortRef.current = null;
    }
  }

  async function send(text: string) {
    const q = text.trim();
    if (!q || busy) return;
    setMessages((m) => [...m, { id: nextId++, role: "user", text: q, mode }]);
    setInput("");
    setBusy(true);
    try {
      if (mode === "ask") {
        await streamAnswer(q);
      } else {
        const filters = category.trim() ? { category: category.trim() } : undefined;
        const hits = await api.search(q, 8, filters);
        setMessages((m) => [...m, { id: nextId++, role: "results", hits, query: q }]);
      }
    } catch (err) {
      setMessages((m) => [...m, { id: nextId++, role: "error", text: onError(err) }]);
    } finally {
      setBusy(false);
    }
  }

  async function regenerate() {
    if (busy || !shownConversation.current) return;
    // Drop the last answer locally; the server drops it from the history too.
    setMessages((m) => {
      const last = m.length - 1;
      return last >= 0 && m[last].role === "answer" ? m.slice(0, last) : m;
    });
    setBusy(true);
    try {
      await streamAnswer("", true);
    } catch (err) {
      setMessages((m) => [...m, { id: nextId++, role: "error", text: onError(err) }]);
    } finally {
      setBusy(false);
    }
  }

  function stop() {
    abortRef.current?.abort();
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

  const lastAnswerId = [...messages].reverse().find((m) => m.role === "answer")?.id;
  const streaming = busy && messages.length > 0 && messages[messages.length - 1].role === "answer";

  return (
    <div className="chat">
      <div className="chat-scroll">
        {loading ? (
          <p className="muted center">Loading conversation…</p>
        ) : messages.length === 0 ? (
          <div className="welcome">
            <h1>What would you like to know?</h1>
            <p className="muted">
              {answerMode === "general" ? (
                "Ask me anything. Answers stream in, and I remember the conversation, so you can ask follow-up questions."
              ) : (
                <>
                  Ask anything. Answers from your documents cite their sources like <span className="cite static">1</span>
                  {answerMode === "auto" ? ", and anything else is answered from general knowledge" : ""}. I remember the conversation, so you
                  can ask follow-up questions.
                  {canEdit ? " Add documents from the Documents tab." : ""}
                </>
              )}
            </p>
            <div className="examples">
              {EXAMPLES[answerMode].map((ex) => (
                <button key={ex} className="example" onClick={() => send(ex)}>
                  {ex}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((m) => (
            <MessageView
              key={m.id}
              message={m}
              canRegenerate={m.id === lastAnswerId && !busy && !!shownConversation.current}
              onRegenerate={regenerate}
            />
          ))
        )}
        {busy && !streaming && (
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
          {mode === "ask" && (
            <select className="picker" value={answerMode} onChange={(e) => chooseMode(e.target.value as AnswerMode)} aria-label="Answer from" title={ANSWER_MODES.find((m) => m.value === answerMode)?.hint}>
              {ANSWER_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.value === "auto" ? "✨ " : m.value === "documents" ? "📄 " : "🌐 "}
                  {m.label}
                </option>
              ))}
            </select>
          )}
          {mode === "ask" && options && options.models.length > 1 && (
            <select className="picker" value={model} onChange={(e) => chooseModel(e.target.value)} aria-label="Model">
              {options.models.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          )}
          <input className="filter" value={category} onChange={(e) => setCategory(e.target.value)} placeholder="Filter by category (optional)" aria-label="Category filter" />
        </div>
        <div className="composer-row">
          <textarea
            ref={textareaRef}
            rows={1}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={mode === "ask" ? "Message VectorFlow…" : "Search your documents…"}
            aria-label="Message"
          />
          {streaming ? (
            <button className="btn stop" type="button" onClick={stop} aria-label="Stop generating">
              ■ Stop
            </button>
          ) : (
            <button className="btn primary" type="submit" disabled={busy || !input.trim()}>
              {mode === "ask" ? "Send" : "Search"}
            </button>
          )}
        </div>
        <p className="hint center">
          Enter to send · Shift+Enter for a new line · {mode === "ask" ? ANSWER_MODES.find((m) => m.value === answerMode)?.hint : "Hybrid keyword + meaning search"}
        </p>
      </form>
    </div>
  );
}

function MessageView({ message, canRegenerate, onRegenerate }: { message: Message; canRegenerate: boolean; onRegenerate: () => void }) {
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
      return (
        <AnswerView
          id={message.id}
          result={message.result}
          streaming={!!message.streaming}
          stopped={!!message.stopped}
          canRegenerate={canRegenerate}
          onRegenerate={onRegenerate}
        />
      );
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

function AnswerView({
  id,
  result,
  streaming,
  stopped,
  canRegenerate,
  onRegenerate,
}: {
  id: number;
  result: AskResult;
  streaming: boolean;
  stopped: boolean;
  canRegenerate: boolean;
  onRegenerate: () => void;
}) {
  const [highlight, setHighlight] = useState<number | null>(null);
  const [copied, setCopied] = useState(false);
  const sourceId = (n: number) => `src-${id}-${n}`;

  function focusSource(n: number) {
    setHighlight(n);
    document.getElementById(sourceId(n))?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    window.setTimeout(() => setHighlight(null), 1600);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(result.answer.replace(/\s*\[\d+\]/g, ""));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
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
        {!streaming && result.answerType && <AnswerBadge type={result.answerType} model={result.model} />}
        <div className="answer">
          <Markdown text={result.answer} sourceCount={result.sources.length} onCite={focusSource} />
          {streaming && <span className="cursor" aria-hidden />}
          {stopped && <p className="muted small stopped">Stopped</p>}
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
        {!streaming && result.answer && (
          <div className="answer-actions">
            <button type="button" className="action" onClick={copy}>
              {copied ? "✓ Copied" : "Copy"}
            </button>
            {canRegenerate && (
              <button type="button" className="action" onClick={onRegenerate}>
                ↻ Regenerate
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function AnswerBadge({ type, model }: { type: "documents" | "general"; model?: string | null }) {
  return (
    <div className="answer-badges">
      <span className={`badge ${type}`} title={type === "documents" ? "Grounded in your documents, with citations" : "Answered from the model's general knowledge, not your documents"}>
        {type === "documents" ? "📄 From your documents" : "🌐 General knowledge"}
      </span>
      {model && <span className="badge model">{model}</span>}
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
