import { useState, type FormEvent } from 'react'
import { api, ApiError, tokenStore, type AuthResult } from './api'

interface AuthViewProps {
  onAuthed: (result: AuthResult) => void
}

type Mode = 'login' | 'signup'

export function AuthView({ onAuthed }: AuthViewProps) {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const result = mode === 'login' ? await api.login(email, password) : await api.signup(email, password)
      tokenStore.set(result.token)
      onAuthed(result)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '요청 중 오류가 발생했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card auth-card" id="auth">
      <div className="auth-head">
        <span className="auth-eyebrow">{mode === 'login' ? '로그인' : '무료 가입'}</span>
        <h2 className="auth-title">
          {mode === 'login' ? '다시 오신 걸 환영합니다' : '1분 만에 시작하세요'}
        </h2>
        <p className="auth-sub">
          {mode === 'login' ? '이메일로 로그인합니다.' : '가입 즉시 무료 크레딧을 드립니다. 카드 등록 없이.'}
        </p>
      </div>

      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={mode === 'login'} onClick={() => setMode('login')}>로그인</button>
        <button role="tab" aria-selected={mode === 'signup'} onClick={() => setMode('signup')}>회원가입</button>
      </div>

      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input id="email" type="email" required value={email}
            onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <input id="password" type="password" required minLength={8} value={password}
            onChange={(e) => setPassword(e.target.value)} placeholder="8자 이상" />
        </div>
        <button className="btn-primary" type="submit" disabled={busy} style={{ width: '100%' }}>
          {busy ? '처리 중…' : mode === 'login' ? '로그인' : '회원가입 (무료 크레딧 지급)'}
        </button>
        {error && <p className="error">{error}</p>}
      </form>

      {mode === 'login' && (
        <p className="muted auth-switch">
          처음이신가요? <button className="btn-link" onClick={() => setMode('signup')}>회원가입</button> 시 무료 크레딧을 드립니다.
        </p>
      )}
    </div>
  )
}
