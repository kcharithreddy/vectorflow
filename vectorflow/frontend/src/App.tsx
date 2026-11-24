import { useCallback, useEffect, useState } from "react";
import { ApiError, api, getToken, setToken, type User } from "./api";
import AuthScreen from "./components/AuthScreen";
import ChatPanel from "./components/ChatPanel";
import DocumentsPanel from "./components/DocumentsPanel";

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(() => !!getToken());
  const [docsVersion, setDocsVersion] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  // Restore the session from a saved token.
  useEffect(() => {
    if (!getToken()) return;
    api
      .me()
      .then((me) => {
        if (me) setUser(me);
        else setToken(null);
      })
      .catch(() => setToken(null))
      .finally(() => setChecking(false));
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  // Any call that fails with UNAUTHORIZED means the token expired: sign out.
  const handleError = useCallback(
    (err: unknown): string => {
      if (err instanceof ApiError && err.isAuthError) {
        logout();
        return "Your session expired. Please sign in again.";
      }
      return err instanceof Error ? err.message : "Something went wrong";
    },
    [logout],
  );

  if (checking) {
    return <div className="center-screen muted">Loading…</div>;
  }

  if (!user) {
    return (
      <AuthScreen
        onAuthenticated={(token, u) => {
          setToken(token);
          setUser(u);
        }}
      />
    );
  }

  const canEdit = user.role === "EDITOR" || user.role === "ADMIN";

  return (
    <div className="app">
      <header className="topbar">
        <button className="icon-btn only-mobile" aria-label="Toggle documents" onClick={() => setSidebarOpen((o) => !o)}>
          ☰
        </button>
        <div className="brand">
          <span className="logo" aria-hidden>
            V
          </span>
          VectorFlow
        </div>
        <div className="topbar-right">
          <span className="user-email">{user.email}</span>
          <span className={`badge role-${user.role.toLowerCase()}`}>{user.role}</span>
          <button className="btn ghost small" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>

      <div className="layout">
        <aside className={`sidebar ${sidebarOpen ? "open" : ""}`}>
          <DocumentsPanel
            user={user}
            canEdit={canEdit}
            version={docsVersion}
            onChanged={() => setDocsVersion((v) => v + 1)}
            onError={handleError}
          />
        </aside>
        <main className="main">
          <ChatPanel canEdit={canEdit} onError={handleError} />
        </main>
      </div>
    </div>
  );
}
