import { useState, type FormEvent } from "react";
import { api, type User } from "../api";

interface Props {
  onAuthenticated: (token: string, user: User) => void;
}

export default function AuthScreen({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = mode === "login" ? await api.login(email, password) : await api.register(email, password);
      onAuthenticated(res.token, res.user);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed");
    } finally {
      setBusy(false);
    }
  }

  function fillDemo(which: "editor" | "admin") {
    setMode("login");
    setEmail(`${which}@vectorflow.dev`);
    setPassword(`${which}123`);
  }

  return (
    <div className="center-screen">
      <div className="auth-card">
        <div className="brand big">
          <span className="logo" aria-hidden>
            V
          </span>
          VectorFlow
        </div>
        <p className="muted tagline">Ask questions about your documents. Answers cite their sources.</p>

        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={mode === "login"} className={mode === "login" ? "active" : ""} onClick={() => setMode("login")}>
            Sign in
          </button>
          <button role="tab" aria-selected={mode === "register"} className={mode === "register" ? "active" : ""} onClick={() => setMode("register")}>
            Create account
          </button>
        </div>

        <form onSubmit={submit} className="form">
          <label>
            Email
            <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            Password
            <input
              type="password"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
              minLength={mode === "register" ? 8 : undefined}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          {mode === "register" && <p className="hint">At least 8 characters, with a letter and a number. New accounts can search and ask; an admin can grant upload rights.</p>}
          {error && <div className="alert error">{error}</div>}
          <button className="btn primary" type="submit" disabled={busy}>
            {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}
          </button>
        </form>

        <div className="demo">
          <span className="muted">Demo accounts:</span>
          <button className="link" onClick={() => fillDemo("editor")}>
            editor
          </button>
          <button className="link" onClick={() => fillDemo("admin")}>
            admin
          </button>
        </div>
      </div>
    </div>
  );
}
