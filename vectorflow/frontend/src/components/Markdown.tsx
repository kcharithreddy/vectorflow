import { Fragment, useState, type ReactNode } from "react";

/**
 * Small, safe Markdown renderer for assistant answers.
 *
 * Supports paragraphs, headings, bullet/numbered lists, fenced code blocks,
 * blockquotes, **bold**, *italic*, `inline code`, links and [n] citations.
 * It builds React elements directly (never innerHTML), so model output or
 * text quoted from documents can't inject markup or scripts.
 */

interface Props {
  text: string;
  sourceCount: number;
  onCite: (n: number) => void;
}

type Block =
  | { kind: "p"; text: string }
  | { kind: "h"; level: number; text: string }
  | { kind: "ul" | "ol"; items: string[]; start: number }
  | { kind: "code"; lang: string; code: string }
  | { kind: "quote"; text: string };

export default function Markdown({ text, sourceCount, onCite }: Props) {
  const blocks = parseBlocks(text);
  const inline = (s: string) => renderInline(s, sourceCount, onCite);
  return (
    <div className="md">
      {blocks.map((b, i) => {
        switch (b.kind) {
          case "h": {
            const Tag = (`h${Math.min(b.level + 2, 6)}`) as "h3" | "h4" | "h5" | "h6";
            return <Tag key={i}>{inline(b.text)}</Tag>;
          }
          case "ul":
            return (
              <ul key={i}>
                {b.items.map((it, j) => (
                  <li key={j}>{inline(it)}</li>
                ))}
              </ul>
            );
          case "ol":
            return (
              <ol key={i} start={b.start}>
                {b.items.map((it, j) => (
                  <li key={j}>{inline(it)}</li>
                ))}
              </ol>
            );
          case "code":
            return <CodeBlock key={i} lang={b.lang} code={b.code} />;
          case "quote":
            return <blockquote key={i}>{inline(b.text)}</blockquote>;
          default:
            return <p key={i}>{inline(b.text)}</p>;
        }
      })}
    </div>
  );
}

function CodeBlock({ lang, code }: { lang: string; code: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
  }
  return (
    <div className="code-block">
      <div className="code-head">
        <span>{lang || "code"}</span>
        <button type="button" className="link small" onClick={copy}>
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre>
        <code>{code}</code>
      </pre>
    </div>
  );
}

export function parseBlocks(text: string): Block[] {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const blocks: Block[] = [];
  let para: string[] = [];
  const flush = () => {
    if (para.length) blocks.push({ kind: "p", text: para.join("\n") });
    para = [];
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fence = /^\s*```\s*([\w+-]*)\s*$/.exec(line);
    if (fence) {
      flush();
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) code.push(lines[i++]);
      blocks.push({ kind: "code", lang: fence[1], code: code.join("\n") });
      continue;
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(line);
    if (heading) {
      flush();
      blocks.push({ kind: "h", level: heading[1].length, text: heading[2] });
      continue;
    }
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
    const numbered = /^\s*(\d+)[.)]\s+(.*)$/.exec(line);
    if (bullet || numbered) {
      flush();
      const kind = bullet ? "ul" : "ol";
      const items: string[] = [];
      const start = numbered ? Number(numbered[1]) : 1;
      while (i < lines.length) {
        const m = kind === "ul" ? /^\s*[-*•]\s+(.*)$/.exec(lines[i]) : /^\s*\d+[.)]\s+(.*)$/.exec(lines[i]);
        if (m) items.push(m[1]);
        else if (/^\s{2,}\S/.test(lines[i]) && items.length) items[items.length - 1] += " " + lines[i].trim();
        else break;
        i++;
      }
      i--;
      blocks.push({ kind, items, start });
      continue;
    }
    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote) {
      flush();
      blocks.push({ kind: "quote", text: quote[1] });
      continue;
    }
    if (!line.trim()) flush();
    else para.push(line);
  }
  flush();
  return blocks;
}

const INLINE = /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__|\*[^*\s][^*]*\*|\[\d+\]|\[[^\]]+\]\((https?:\/\/[^\s)]+)\))/g;

function renderInline(text: string, sourceCount: number, onCite: (n: number) => void): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(INLINE)) {
    const token = m[0];
    const start = m.index ?? 0;
    if (start > last) out.push(withBreaks(text.slice(last, start), key++));
    if (token.startsWith("`")) {
      out.push(<code key={key++}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith("**") || token.startsWith("__")) {
      out.push(<strong key={key++}>{renderInline(token.slice(2, -2), sourceCount, onCite)}</strong>);
    } else if (token.startsWith("*")) {
      out.push(<em key={key++}>{token.slice(1, -1)}</em>);
    } else if (/^\[\d+\]$/.test(token)) {
      const n = Number(token.slice(1, -1));
      out.push(
        n >= 1 && n <= sourceCount ? (
          <button key={key++} type="button" className="cite" onClick={() => onCite(n)} aria-label={`Show source ${n}`}>
            {n}
          </button>
        ) : (
          <Fragment key={key++}>{token}</Fragment>
        ),
      );
    } else {
      const link = /^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/.exec(token);
      out.push(
        link ? (
          <a key={key++} href={link[2]} target="_blank" rel="noopener noreferrer nofollow">
            {link[1]}
          </a>
        ) : (
          <Fragment key={key++}>{token}</Fragment>
        ),
      );
    }
    last = start + token.length;
  }
  if (last < text.length) out.push(withBreaks(text.slice(last), key++));
  return out;
}

function withBreaks(s: string, key: number): ReactNode {
  const parts = s.split("\n");
  return (
    <Fragment key={key}>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && <br />}
          {p}
        </Fragment>
      ))}
    </Fragment>
  );
}
