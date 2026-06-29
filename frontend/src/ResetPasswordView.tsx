import { useState, type FormEvent } from 'react'
import { api, ApiError } from './api'

interface ResetPasswordViewProps {
  token: string
  /** 완료/취소 시 쿼리스트링을 비우고 일반 랜딩으로 복귀. */
  onDone: () => void
}

/**
 * 비밀번호 재설정 화면. 메일의 `/?reset=<token>` 링크로 진입한다(App 부팅 시 분기).
 * 토큰 + 새 비밀번호를 백엔드에 전달하고, 성공하면 로그인으로 안내한다.
 */
export function ResetPasswordView({ token, onDone }: ResetPasswordViewProps) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 8) {
      setError('비밀번호는 8자 이상이어야 합니다.')
      return
    }
    if (password !== confirm) {
      setError('비밀번호가 일치하지 않습니다.')
      return
    }
    setBusy(true)
    try {
      await api.resetPassword(token, password)
      setDone(true)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '재설정 중 오류가 발생했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="reset-wrap">
      <div className="card auth-card reset-card">
        <div className="auth-head">
          <span className="auth-eyebrow">비밀번호 재설정</span>
          <h2 className="auth-title">{done ? '재설정 완료' : '새 비밀번호 설정'}</h2>
          <p className="auth-sub">
            {done ? '비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.' : '새로 사용할 비밀번호를 입력해 주세요.'}
          </p>
        </div>

        {done ? (
          <button className="btn-primary" style={{ width: '100%' }} onClick={onDone}>
            로그인하러 가기
          </button>
        ) : (
          <form onSubmit={submit}>
            <div className="field">
              <label htmlFor="new-password">새 비밀번호</label>
              <input id="new-password" type="password" required minLength={8} value={password}
                onChange={(e) => setPassword(e.target.value)} placeholder="8자 이상" autoComplete="new-password" />
            </div>
            <div className="field">
              <label htmlFor="confirm-password">새 비밀번호 확인</label>
              <input id="confirm-password" type="password" required minLength={8} value={confirm}
                onChange={(e) => setConfirm(e.target.value)} placeholder="다시 입력" autoComplete="new-password" />
            </div>
            <button className="btn-primary" type="submit" disabled={busy} style={{ width: '100%' }}>
              {busy ? '변경 중…' : '비밀번호 변경'}
            </button>
            {error && <p className="error">{error}</p>}
            <p className="muted auth-switch">
              <button type="button" className="btn-link" onClick={onDone}>취소하고 돌아가기</button>
            </p>
          </form>
        )}
      </div>
    </div>
  )
}
