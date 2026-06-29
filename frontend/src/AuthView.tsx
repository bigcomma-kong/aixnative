import { useState, type FormEvent } from 'react'
import { api, ApiError, tokenStore, type AuthResult } from './api'

interface AuthViewProps {
  onAuthed: (result: AuthResult) => void
}

type Mode = 'login' | 'signup' | 'forgot'

export function AuthView({ onAuthed }: AuthViewProps) {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)

  function switchMode(next: Mode) {
    setMode(next)
    setError(null)
    setSent(false)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (mode === 'forgot') {
        await api.forgotPassword(email)
        setSent(true)
        return
      }
      const result = mode === 'login' ? await api.login(email, password) : await api.signup(email, password)
      tokenStore.set(result.token)
      onAuthed(result)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '요청 중 오류가 발생했습니다.')
    } finally {
      setBusy(false)
    }
  }

  if (mode === 'forgot') {
    return (
      <div className="card auth-card" id="auth">
        <div className="auth-head">
          <span className="auth-eyebrow">비밀번호 찾기</span>
          <h2 className="auth-title">가입 이메일로 재설정</h2>
          <p className="auth-sub">가입하신 이메일 주소를 입력하시면 재설정 링크를 보내드립니다.</p>
        </div>

        {sent ? (
          <div className="auth-sent">
            <p className="auth-sent-msg">
              해당 이메일로 가입된 계정이 있다면 <b>재설정 링크</b>를 보냈습니다. 메일함(스팸함 포함)을 확인해 주세요.
            </p>
            <button className="btn-ghost" style={{ width: '100%' }} onClick={() => switchMode('login')}>
              로그인으로 돌아가기
            </button>
          </div>
        ) : (
          <form onSubmit={submit}>
            <div className="field">
              <label htmlFor="email">이메일</label>
              <input id="email" type="email" required value={email}
                onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
            </div>
            <button className="btn-primary" type="submit" disabled={busy} style={{ width: '100%' }}>
              {busy ? '보내는 중…' : '재설정 링크 받기'}
            </button>
            {error && <p className="error">{error}</p>}
          </form>
        )}

        <p className="muted auth-switch">
          <button className="btn-link" onClick={() => switchMode('login')}>로그인으로 돌아가기</button>
        </p>
      </div>
    )
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
        <button role="tab" aria-selected={mode === 'login'} onClick={() => switchMode('login')}>로그인</button>
        <button role="tab" aria-selected={mode === 'signup'} onClick={() => switchMode('signup')}>회원가입</button>
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
          <button className="btn-link" onClick={() => switchMode('forgot')}>비밀번호를 잊으셨나요?</button>
          <span className="auth-switch-sep"> · </span>
          처음이신가요? <button className="btn-link" onClick={() => switchMode('signup')}>회원가입</button>
        </p>
      )}
    </div>
  )
}
