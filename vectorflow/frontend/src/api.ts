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

  async search(query: string, limit = 8, filters?: Filters) {
    const d = await gql<{ search: { results: SearchHit[] } }>(
      `query($q:String!,$l:Int,$f:FilterInput){ search(query:$q,limit:$l,filters:$f){
         results{ id documentId title snippet score metadata{ category tags } } } }`,
      { q: query, l: limit, f: cleanFilters(filters) },
    );
    return d.search.results;
  },
};
