import { useEffect, useState } from 'react'
import { api, tokenStore, type AuthResult } from './api'
import { AuthView } from './AuthView'
import { UnderwriteView } from './UnderwriteView'
import { CreditHistoryView } from './CreditHistoryView'
import { Paywall } from './Paywall'

type Plan = 'FREE' | 'PAID'
type Tab = 'underwrite' | 'credits'

interface Session {
  email: string
  creditBalance: number
  plan: Plan
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [booting, setBooting] = useState(true)
  const [tab, setTab] = useState<Tab>('underwrite')

  // 앱 시작 시 저장된 토큰이 있으면 세션 복원. (plan 은 결제 도입 전까지 FREE; 사용 내역에서 서버값으로 보정)
  useEffect(() => {
    if (!tokenStore.get()) {
      setBooting(false)
      return
    }
    api.me()
      .then((me) => setSession({ email: me.email, creditBalance: me.creditBalance, plan: 'FREE' }))
      .catch(() => tokenStore.clear())
      .finally(() => setBooting(false))
  }, [])

  function onAuthed(result: AuthResult) {
    setSession({ email: result.email, creditBalance: result.creditBalance, plan: (result.plan as Plan) ?? 'FREE' })
  }

  function logout() {
    tokenStore.clear()
    setSession(null)
    setTab('underwrite')
  }

  function patchSession(patch: Partial<Session>) {
    setSession((s) => (s ? { ...s, ...patch } : s))
  }

  if (booting) return <div className="spinner">불러오는 중…</div>
  if (!session) return <AuthView onAuthed={onAuthed} />

  const initial = session.email.trim().charAt(0).toUpperCase() || '?'

  return (
    <>
      <header className="topbar">
        <div className="brand">aix<span>native</span></div>
        <nav className="topnav" aria-label="주요 메뉴">
          <button aria-current={tab === 'underwrite'} onClick={() => setTab('underwrite')}>언더라이팅</button>
          <button aria-current={tab === 'credits'} onClick={() => setTab('credits')}>사용 내역</button>
        </nav>
        <div className="topbar-right">
          <span className="credits">
            {session.plan === 'FREE' && <span className="plan-pill free">무료</span>}
            크레딧 <b className="num">{session.creditBalance}</b>
          </span>
          <div className="who">
            <span className="avatar" aria-hidden="true">{initial}</span>
            <span className="muted">{session.email}</span>
          </div>
          <button className="btn-link" onClick={logout}>로그아웃</button>
        </div>
      </header>

      {session.creditBalance <= 0 && (
        <div className="paywall-bar">
          <Paywall creditBalance={0} variant="banner" />
        </div>
      )}

      <main>
        {tab === 'underwrite' ? (
          <UnderwriteView onCreditBalance={(balance) => patchSession({ creditBalance: balance })} />
        ) : (
          <CreditHistoryView onSync={(plan, creditBalance) => patchSession({ plan, creditBalance })} />
        )}
      </main>
    </>
  )
}

export default App
