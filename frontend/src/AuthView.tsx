import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, tokenStore, type AuthResult } from './api'
import { SocialLogin } from './SocialLogin'
import { InfoModal, type InfoPage } from './SiteFooter'

interface AuthViewProps {
  onAuthed: (result: AuthResult) => void
  /** 소셜 로그인 콜백 실패 메시지(App 의 해시 파싱 결과). */
  initialError?: string | null
  /** 랜딩 상단 '로그인'/'무료로 시작' 이 요청한 탭. */
  requestMode?: 'login' | 'signup'
  /** 포커스 트리거(증가할 때마다 requestMode 로 전환 + 이메일 포커스). */
  focusSignal?: number
}

type Mode = 'login' | 'signup' | 'forgot'

export function AuthView({ onAuthed, initialError, requestMode, focusSignal }: AuthViewProps) {
  const [mode, setMode] = useState<Mode>('login')
  // 포커스 직후 카드에 잠깐 하이라이트를 줘 '여기가 로그인 영역' 임을 시각적으로 알린다.
  const [flash, setFlash] = useState(false)

  // 랜딩 상단 버튼 클릭(focusSignal 증가) → 해당 탭으로 전환하고 이메일에 포커스.
  // preventScroll 로 스크롤을 다시 튀지 않게 해, 한 번에 로그인 영역이 잡히도록 한다.
  useEffect(() => {
    if (!focusSignal) return
    if (requestMode) setMode(requestMode)
    setFlash(true)
    const focusTimer = setTimeout(() => {
      const el = document.getElementById('email') as HTMLInputElement | null
      el?.focus({ preventScroll: true })
    }, 300)
    const flashTimer = setTimeout(() => setFlash(false), 1100)
    return () => { clearTimeout(focusTimer); clearTimeout(flashTimer) }
  }, [focusSignal, requestMode])
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(initialError ?? null)
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  // 가입 동의 캡처(PIPA): 약관·개인정보 필수, 마케팅 선택.
  const [agreed, setAgreed] = useState(false)
  const [marketing, setMarketing] = useState(false)
  const [infoPage, setInfoPage] = useState<InfoPage | null>(null)

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
      if (mode === 'signup' && !agreed) {
        setError('약관 및 개인정보 처리방침에 동의해야 가입할 수 있습니다.')
        return
      }
      const result = mode === 'login'
        ? await api.login(email, password)
        : await api.signup(email, password, agreed, marketing)
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
    <div className={`card auth-card${flash ? ' auth-flash' : ''}`} id="auth">
      <div className="auth-head">
        <span className="auth-eyebrow">{mode === 'login' ? '로그인' : '무료 가입'}</span>
        <h2 className="auth-title">
          {mode === 'login' ? '다시 오신 걸 환영합니다' : '1분 만에 시작하세요'}
        </h2>
        <p className="auth-sub">
          {mode === 'login'
            ? '이메일로 로그인합니다.'
            : '무료 크레딧으로 시작하세요. 카드 등록 없이 · 소셜은 즉시, 이메일은 인증 후 지급.'}
        </p>
      </div>

      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={mode === 'login'} onClick={() => switchMode('login')}>로그인</button>
        <button role="tab" aria-selected={mode === 'signup'} onClick={() => switchMode('signup')}>회원가입</button>
      </div>

      {/* 가입은 소셜을 1차 CTA로(즉시 크레딧·어뷰징 방어). 제공자 미설정 시 graceful null → 폼만 노출. */}
      {mode === 'signup' && <SocialLogin variant="top" />}

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

        {mode === 'signup' && (
          <div className="consent">
            <label className="consent-row">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
              <span>
                <b>(필수)</b>{' '}
                <button type="button" className="btn-link" onClick={() => setInfoPage('terms')}>이용약관</button> 및{' '}
                <button type="button" className="btn-link" onClick={() => setInfoPage('privacy')}>개인정보 처리방침</button>에 동의합니다.
              </span>
            </label>
            <label className="consent-row">
              <input type="checkbox" checked={marketing} onChange={(e) => setMarketing(e.target.checked)} />
              <span>(선택) 마케팅·서비스 소식 이메일 수신에 동의합니다.</span>
            </label>
          </div>
        )}

        <button className="btn-primary" type="submit" disabled={busy || (mode === 'signup' && !agreed)} style={{ width: '100%' }}>
          {busy ? '처리 중…' : mode === 'login' ? '로그인' : '이메일로 회원가입'}
        </button>
        {mode === 'signup' && (
          <p className="auth-hint">이메일 가입은 인증 링크 확인 후 무료 크레딧이 지급됩니다.</p>
        )}
        {error && <p className="error">{error}</p>}
      </form>

      {mode === 'login' && <SocialLogin />}

      {mode === 'signup' && (
        <p className="consent-social">
          간편 가입 시{' '}
          <button type="button" className="btn-link" onClick={() => setInfoPage('terms')}>이용약관</button>·
          <button type="button" className="btn-link" onClick={() => setInfoPage('privacy')}>개인정보 처리방침</button>에 동의하게 됩니다.
          <br />
          소셜 로그인 시 제공자(구글·카카오·네이버)로부터 이메일·식별자를 받습니다.
        </p>
      )}

      {mode === 'login' && (
        <p className="muted auth-switch">
          <button className="btn-link" onClick={() => switchMode('forgot')}>비밀번호를 잊으셨나요?</button>
          <span className="auth-switch-sep"> · </span>
          처음이신가요? <button className="btn-link" onClick={() => switchMode('signup')}>회원가입</button>
        </p>
      )}

      {infoPage && <InfoModal page={infoPage} onClose={() => setInfoPage(null)} />}
    </div>
  )
}
