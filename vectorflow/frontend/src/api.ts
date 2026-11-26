// Minimal GraphQL client for the VectorFlow gateway.

export type Role = "VIEWER" | "EDITOR" | "ADMIN";

export interface User {
  id: string;
  email: string;
  role: Role;
  createdAt: string;
}

export interface Metadata {
  category: string | null;
  tags: string[] | null;
}

export interface DocumentItem {
  id: string;
  title: string;
  chunkCount: number;
  status: "PROCESSING" | "COMPLETED" | "FAILED";
  ownerEmail: string | null;
  createdAt: string;
  metadata: Metadata | null;
}

export interface DocumentPage {
  items: DocumentItem[];
  totalCount: number;
  hasNext: boolean;
}

export interface Source {
  documentId: string;
  title: string;
  snippet: string;
  relevance: number;
}

export interface AskResult {
  answer: string;
  confidence: number;
  sources: Source[];
}

export interface SearchHit {
  id: string;
  documentId: string;
  title: string;
  snippet: string;
  score: number;
  metadata: Metadata | null;
}

export interface UploadResult {
  documentId: string;
  title: string;
  chunkCount: number;
  status: string;
  message: string;
  fileType: string;
  pages: number | null;
  characters: number;
}

export interface Filters {
  category?: string;
  tags?: string[];
}

export class ApiError extends Error {
  constructor(message: string, readonly classification?: string) {
    super(message);
  }
  get isAuthError() {
    return this.classification === "UNAUTHORIZED";
  }
}

const TOKEN_KEY = "vectorflow.token";

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable - token lives for this page only */
  }
}

async function gql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
  const token = getToken();
  let res: Response;
  try {
    res = await fetch("/graphql", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ query, variables }),
    });
  } catch {
    throw new ApiError("Cannot reach the VectorFlow gateway. Is it running?");
  }
  let body: { data?: T; errors?: { message: string; extensions?: { classification?: string } }[] };
  try {
    body = await res.json();
  } catch {
    throw new ApiError(`Gateway returned HTTP ${res.status}. It may still be starting.`);
  }
  if (body.errors?.length) {
    const e = body.errors[0];
    throw new ApiError(e.message, e.extensions?.classification);
  }
  return body.data as T;
}

function cleanFilters(f?: Filters): Filters | null {
  if (!f) return null;
  const out: Filters = {};
  if (f.category?.trim()) out.category = f.category.trim();
  if (f.tags?.length) out.tags = f.tags;
  return Object.keys(out).length ? out : null;
}

const USER_FIELDS = "id email role createdAt";

export const api = {
  async login(email: string, password: string) {
    const d = await gql<{ login: { token: string; user: User } }>(
      `mutation($e:String!,$p:String!){ login(input:{email:$e,password:$p}){ token user{ ${USER_FIELDS} } } }`,
      { e: email, p: password },
    );
    return d.login;
  },

  async register(email: string, password: string) {
    const d = await gql<{ register: { token: string; user: User } }>(
      `mutation($e:String!,$p:String!){ register(input:{email:$e,password:$p}){ token user{ ${USER_FIELDS} } } }`,
      { e: email, p: password },
    );
    return d.register;
  },

  async me() {
    const d = await gql<{ me: User | null }>(`{ me { ${USER_FIELDS} } }`);
    return d.me;
  },

  async documents(page = 1, pageSize = 50, mine = false) {
    const d = await gql<{ documents: DocumentPage }>(
      `query($p:Int,$s:Int,$m:Boolean){ documents(page:$p,pageSize:$s,mine:$m){
         totalCount hasNext items{ id title chunkCount status ownerEmail createdAt metadata{ category tags } } } }`,
      { p: page, s: pageSize, m: mine },
    );
    return d.documents;
  },

  async ingest(title: string, content: string, category?: string, tags?: string[]) {
    const metadata = category || tags?.length ? { category: category || null, tags: tags ?? [] } : null;
    const d = await gql<{ ingest: { documentId: string; chunkCount: number; status: string; message: string } }>(
      `mutation($t:String!,$c:String!,$m:MetadataInput){ ingest(input:{title:$t,content:$c,metadata:$m}){
         documentId chunkCount status message } }`,
      { t: title, c: content, m: metadata },
    );
    return d.ingest;
  },

  /** Upload a PDF/DOCX/text file; the server extracts its text and ingests it. */
  async uploadFile(file: File, opts: { title?: string; category?: string; tags?: string[] } = {}) {
    const form = new FormData();
    form.append("file", file);
    if (opts.title?.trim()) form.append("title", opts.title.trim());
    if (opts.category?.trim()) form.append("category", opts.category.trim());
    if (opts.tags?.length) form.append("tags", opts.tags.join(","));

    const token = getToken();
    let res: Response;
    try {
      res = await fetch("/api/documents/upload", {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: form,
      });
    } catch {
      throw new ApiError("Cannot reach the VectorFlow gateway. Is it running?");
    }
    let body: UploadResult & { error?: string };
    try {
      body = await res.json();
    } catch {
      throw new ApiError(res.status === 413 ? "File is too large (max 20 MB)" : `Upload failed (HTTP ${res.status})`);
    }
    if (!res.ok) {
      throw new ApiError(body.error ?? `Upload failed (HTTP ${res.status})`, res.status === 401 ? "UNAUTHORIZED" : undefined);
    }
    return body as UploadResult;
  },

  async deleteDocument(id: string) {
    const d = await gql<{ deleteDocument: { success: boolean; message: string } }>(
      `mutation($id:ID!){ deleteDocument(documentId:$id){ success message } }`,
      { id },
    );
    return d.deleteDocument;
  },

  async ask(question: string, maxSources = 5, filters?: Filters) {
    const d = await gql<{ ask: AskResult }>(
      `query($q:String!,$n:Int,$f:FilterInput){ ask(question:$q,maxSources:$n,filters:$f){
         answer confidence sources{ documentId title snippet relevance } } }`,
      { q: question, n: maxSources, f: cleanFilters(filters) },
    );
    return d.ask;
  },

  /**
   * Streaming ask: calls onDelta with answer text as it is generated, then
   * resolves with the final result (confidence + sources to show).
   */
  async askStream(
    question: string,
    onDelta: (text: string) => void,
    opts: { maxSources?: number; filters?: Filters; signal?: AbortSignal } = {},
  ): Promise<AskResult> {
    const token = getToken();
    const f = cleanFilters(opts.filters);
    let res: Response;
    try {
      res = await fetch("/api/ask/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream, application/json", // JSON for errors (401/400)
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ question, maxSources: opts.maxSources ?? 5, category: f?.category, tags: f?.tags }),
        signal: opts.signal,
      });
    } catch {
      throw new ApiError("Cannot reach the VectorFlow gateway. Is it running?");
    }
    if (!res.ok || !res.body) {
      let message = `Request failed (HTTP ${res.status})`;
      try {
        message = (await res.json()).error ?? message;
      } catch {
        /* not JSON */
      }
      throw new ApiError(message, res.status === 401 ? "UNAUTHORIZED" : undefined);
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let answer = "";
    let result: AskResult | null = null;

    const handle = (block: string) => {
      let event = "message";
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (!data.length) return;
      const payload = JSON.parse(data.join("\n"));
      if (event === "delta") {
        answer += payload.text;
        onDelta(payload.text);
      } else if (event === "done") {
        result = { answer: answer.trim(), confidence: payload.confidence, sources: payload.sources };
      } else if (event === "error") {
        throw new ApiError(payload.message);
      }
    };

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) !== -1) {
        handle(buffer.slice(0, sep));
        buffer = buffer.slice(sep + 2);
      }
    }
    if (buffer.trim()) handle(buffer);
    if (!result) throw new ApiError("The answer stream ended unexpectedly.");
    return result;
  },

  async search(query: string, limit = 8, filters?: Filters) {
    const d = await gql<{ search: { results: SearchHit[] } }>(
      `query($q:String!,$l:Int,$f:FilterInput){ search(query:$q,limit:$l,filters:$f){
         results{ id documentId title snippet score metadata{ category tags } } } }`,
      { q: query, l: limit, f: cleanFilters(filters) },
    );
    return d.search.results;
  },
};
