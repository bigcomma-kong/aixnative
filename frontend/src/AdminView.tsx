import { useEffect, useState } from 'react'
import {
  api,
  ApiError,
  type AdminCreditEntry,
  type AdminEvent,
  type AdminRun,
  type AdminRunDetail,
  type AdminStats,
  type AdminUser,
  type CreditReason,
  type IngestReport,
  type NewsletterSendLogEntry,
  type NewsSubscriber,
  type SocialPost,
  type UserRole,
  type UserStatus,
} from './api'
import { ResultModal, toolLabel } from './ResultModal'

interface AdminViewProps {
  currentEmail: string
}

const fmtDate = (s: string | null): string => (s ? new Date(s).toLocaleString('ko-KR') : '-')

const CREDIT_REASON_LABEL: Record<CreditReason, string> = {
  SIGNUP_GRANT: '가입 무료 지급',
  AI_ANALYSIS: 'AI 분석',
  PURCHASE: '크레딧 충전',
  ADMIN_ADJUST: '관리자 조정',
}

export function AdminView({ currentEmail }: AdminViewProps) {
  const [section, setSection] = useState<'dashboard' | 'access' | 'users' | 'credits' | 'runs' | 'market' | 'social'>('dashboard')
  // 전역 '관리자 계정 표시' - 끄면 모든 패널에서 관리자 계정 데이터 제외(관리자 자신의 테스트 활동이 지표를 오염시키지 않게).
  const [showAdmin, setShowAdmin] = useState(false)
  const [adminIds, setAdminIds] = useState<Set<number>>(new Set())
  // 새로고침 키 - 증가시키면 활성 패널이 리마운트되어 최신 데이터를 다시 불러온다(패널 내부 수정 불필요).
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    api.adminUsers()
      .then((us) => setAdminIds(new Set(us.filter((u) => u.role === 'ADMIN').map((u) => u.id))))
      .catch(() => setAdminIds(new Set()))
  }, [reloadKey])

  return (
    <>
      <div className="page-head admin-page-head">
        <div>
          <span className="eyebrow">ADMIN CONSOLE</span>
          <h1>운영 콘솔</h1>
          <p className="page-sub">가입·크레딧·매출 지표와 운영 상태를 한 화면에서 관리합니다.</p>
        </div>
        <div className="admin-head-right">
          <div className="admin-head-tools">
            <button
              type="button"
              className="admin-refresh"
              onClick={() => setReloadKey((k) => k + 1)}
              title="현재 화면을 최신 데이터로 다시 불러옵니다"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-2.64-6.36" />
                <path d="M21 3v6h-6" />
              </svg>
              새로고침
            </button>
            <label className="admin-toggle" title="끄면 모든 지표·목록에서 관리자 계정 데이터를 제외합니다">
              <input type="checkbox" checked={showAdmin} onChange={(e) => setShowAdmin(e.target.checked)} />
              <span className="admin-toggle-track" aria-hidden="true"><span className="admin-toggle-knob" /></span>
              <span className="admin-toggle-text">관리자 계정 표시</span>
            </label>
          </div>
          <div className="seg admin-seg" role="group" aria-label="관리자 섹션">
            <button type="button" aria-pressed={section === 'dashboard'} onClick={() => setSection('dashboard')}>대시보드</button>
            <button type="button" aria-pressed={section === 'access'} onClick={() => setSection('access')}>접속</button>
            <button type="button" aria-pressed={section === 'users'} onClick={() => setSection('users')}>사용자·크레딧</button>
            <button type="button" aria-pressed={section === 'credits'} onClick={() => setSection('credits')}>크레딧 내역</button>
            <button type="button" aria-pressed={section === 'runs'} onClick={() => setSection('runs')}>분석 데이터</button>
            <button type="button" aria-pressed={section === 'market'} onClick={() => setSection('market')}>시장</button>
            <button type="button" aria-pressed={section === 'social'} onClick={() => setSection('social')}>공감랭킹</button>
          </div>
        </div>
      </div>

      {section === 'dashboard' && <DashboardPanel key={reloadKey} showAdmin={showAdmin} />}
      {section === 'access' && <AccessPanel key={reloadKey} showAdmin={showAdmin} />}
      {section === 'users' && <UsersPanel key={reloadKey} currentEmail={currentEmail} showAdmin={showAdmin} />}
      {section === 'credits' && <CreditsPanel key={reloadKey} showAdmin={showAdmin} adminIds={adminIds} />}
      {section === 'runs' && <RunsPanel key={reloadKey} showAdmin={showAdmin} adminIds={adminIds} />}
      {section === 'market' && <MarketPanel key={reloadKey} />}
      {section === 'social' && <SocialPanel key={reloadKey} />}
    </>
  )
}

const SOCIAL_STATUS_LABEL: Record<SocialPost['status'], string> = {
  DRAFT: '초안',
  PENDING: '승인 대기',
  APPROVED: '승인됨',
  PUBLISHED: '게시됨',
  REJECTED: '반려',
}

const SOCIAL_STATUS_PILL: Record<SocialPost['status'], string> = {
  DRAFT: 'free',
  PENDING: 'admin',
  APPROVED: 'paid',
  PUBLISHED: 'verified',
  REJECTED: 'unverified',
}

const SOCIAL_SOURCE_LABEL: Record<SocialPost['sourceType'], string> = {
  YOUTUBE: '유튜브 인기',
  TREND: '급상승 검색어',
  NEWS: '언론사 뉴스',
  COMMUNITY: '커뮤니티',
}

const SOCIAL_RISK: Record<SocialPost['riskLevel'], { label: string; cls: string } | null> = {
  LOW: null,
  MEDIUM: { label: '확인 권장', cls: 'risk-medium' },
  HIGH: { label: '리스크 감수 필요', cls: 'risk-high' },
}

/** 공감랭킹 - 자동 생성된 랭킹 카드 검토·승인·게시. */
function SocialPanel() {
  const [posts, setPosts] = useState<SocialPost[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [ingesting, setIngesting] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    api.adminSocialPosts()
      .then(setPosts)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  const replace = (p: SocialPost) => setPosts((list) => list.map((x) => (x.id === p.id ? p : x)))

  async function ingest() {
    setIngesting(true); setError(null); setMsg(null)
    try {
      const r = await api.adminSocialIngest()
      setMsg(`생성 ${r.postsCreated}건 · 중복 ${r.skippedDuplicate}건 · 렌더 ${r.rendered}건 (소재 ${r.sourcesFetched}건)${r.errors.length ? ` · 오류 ${r.errors.length}건` : ''}`)
      setPosts(await api.adminSocialPosts())
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '수집 실패')
    } finally {
      setIngesting(false)
    }
  }

  async function act(id: number, fn: () => Promise<SocialPost>, label: string) {
    setBusyId(id); setError(null); setMsg(null)
    try { replace(await fn()) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : `${label} 실패`) }
    finally { setBusyId(null) }
  }

  async function remove(id: number) {
    if (!window.confirm('이 게시물을 삭제할까요?')) return
    setBusyId(id); setError(null)
    try { await api.adminSocialDelete(id); setPosts((list) => list.filter((x) => x.id !== id)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '삭제 실패') }
    finally { setBusyId(null) }
  }

  return (
    <>
      {error && <p className="error">{error}</p>}

      <div className="card">
        <div className="section-title-row">
          <div className="section-title">공감랭킹 소셜 게시물 ({posts.length})</div>
          <button type="button" className="btn-primary btn-xs" onClick={ingest} disabled={ingesting}>
            {ingesting ? '수집 중…' : '지금 수집·생성'}
          </button>
        </div>
        <p className="hint">
          유튜브 인기영상·급상승 검색어·언론사 RSS·커뮤니티 소재를 Claude가 랭킹 카드로 큐레이션합니다.
          카드마다 출처 배지와 리스크 등급을 표기하니, 리스크가 있는 소스는 원문 확인 후 승인하세요(게시는 인스타 계정 연동 시).
          {msg && <><br /><b>{msg}</b></>}
        </p>
      </div>

      {loading ? (
        <div className="card"><p className="hint">불러오는 중…</p></div>
      ) : posts.length === 0 ? (
        <div className="card"><p className="hint">아직 생성된 게시물이 없습니다. ‘지금 수집·생성’을 눌러보세요.</p></div>
      ) : (
        <div className="social-grid">
          {posts.map((p) => {
            const busy = busyId === p.id
            return (
              <article key={p.id} className="social-card">
                <div className="social-card-head">
                  <span className={`plan-pill ${SOCIAL_STATUS_PILL[p.status]}`}>{SOCIAL_STATUS_LABEL[p.status]}</span>
                  <span className={`social-source src-${p.sourceType.toLowerCase()}`}>{SOCIAL_SOURCE_LABEL[p.sourceType]}</span>
                  <span className="social-platform">{p.platform}</span>
                </div>
                <h3 className="social-title">{p.title}</h3>
                {SOCIAL_RISK[p.riskLevel] && (
                  <p className={`social-risk ${SOCIAL_RISK[p.riskLevel]!.cls}`}>
                    ⚠ {SOCIAL_RISK[p.riskLevel]!.label} · 출처: {SOCIAL_SOURCE_LABEL[p.sourceType]} — 승인 전 원문 확인
                  </p>
                )}

                {p.hasImage && p.imageUrl ? (
                  <img className="social-img" src={p.imageUrl} alt={p.title} loading="lazy" />
                ) : (
                  <ol className="social-slides">
                    {p.slides.map((s) => (
                      <li key={s.rank}>
                        <b>{s.title}</b>
                        <span className="social-slide-sum">{s.summary}</span>
                        {s.sourceUrl && <a className="social-src" href={s.sourceUrl} target="_blank" rel="noreferrer">{s.sourceName || '출처'} →</a>}
                      </li>
                    ))}
                  </ol>
                )}

                {p.caption && <p className="social-caption">{p.caption}</p>}
                {p.hashtags && <p className="social-tags">{p.hashtags}</p>}
                {p.error && <p className="error">{p.error}</p>}

                <div className="social-actions">
                  {(p.status === 'PENDING' || p.status === 'DRAFT') && (
                    <button className="btn-primary btn-xs" disabled={busy} onClick={() => act(p.id, () => api.adminSocialApprove(p.id), '승인')}>승인</button>
                  )}
                  {p.status === 'APPROVED' && (
                    <button
                      className="btn-primary btn-xs"
                      disabled={busy || !p.canPublish}
                      title={p.canPublish ? '' : '플랫폼 계정 미연동'}
                      onClick={() => act(p.id, () => api.adminSocialPublish(p.id), '게시')}
                    >
                      {p.canPublish ? '게시' : '게시(계정 미연동)'}
                    </button>
                  )}
                  {p.status !== 'REJECTED' && p.status !== 'PUBLISHED' && (
                    <button className="btn-ghost btn-xs" disabled={busy} onClick={() => act(p.id, () => api.adminSocialReject(p.id), '반려')}>반려</button>
                  )}
                  <button className="btn-ghost btn-xs btn-danger" disabled={busy} onClick={() => remove(p.id)}>삭제</button>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </>
  )
}

const KRW = (n: number): string => n.toLocaleString('ko-KR')

/** 운영 대시보드 - 사용자·분석·크레딧·결제 핵심 지표 카드. */
function DashboardPanel({ showAdmin }: { showAdmin: boolean }) {
  const [stats, setStats] = useState<AdminStats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    api.adminStats(!showAdmin)
      .then(setStats)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [showAdmin])

  if (loading) return <div className="card"><p className="hint">불러오는 중…</p></div>
  if (error) return <div className="card"><p className="error">{error}</p></div>
  if (!stats) return null

  const runToolLabel = (t: string): string => toolLabel(t)

  return (
    <>
      <div className="stat-cards">
        <StatCard k="총 사용자" v={String(stats.users.total)} sub={`오늘 +${stats.users.newToday} · 7일 +${stats.users.new7d}`} />
        <StatCard k="이메일 인증" v={String(stats.users.verified)} sub={`유료 전환 ${stats.users.paid}명`} />
        <StatCard k="총 분석 실행" v={String(stats.runs.total)} sub={`오늘 ${stats.runs.today} · 7일 ${stats.runs.last7d} · 성공 ${stats.runs.success}`} accent />
        <StatCard k="결제 매출" v={`${KRW(stats.payments.totalKrw)}원`} sub={`승인 ${stats.payments.confirmedCount}건`} accent />
        <StatCard k="7일 활성 사용자" v={String(stats.events.activeUsers7d)} sub="최근 7일 화면 진입(로그인) 기준" />
      </div>

      <div className="dash-grid">
        <div className="card">
          <div className="section-title">크레딧 흐름</div>
          <table className="admin-table">
            <tbody>
              <tr><td>가입 무료 지급</td><td className="num pos">+{stats.credits.granted}</td></tr>
              <tr><td>결제 충전</td><td className="num pos">+{stats.credits.purchased}</td></tr>
              <tr><td>관리자 조정</td><td className={`num ${stats.credits.adminAdjust >= 0 ? 'pos' : 'neg'}`}>{stats.credits.adminAdjust >= 0 ? '+' : ''}{stats.credits.adminAdjust}</td></tr>
              <tr><td>AI 분석 사용</td><td className="num neg">−{stats.credits.spent}</td></tr>
            </tbody>
          </table>
        </div>

        <div className="card">
          <div className="section-title">분석 유형별 실행</div>
          {Object.keys(stats.runs.byTool).length === 0 ? (
            <p className="hint">아직 분석 실행이 없습니다.</p>
          ) : (
            <HBars
              accent
              data={Object.entries(stats.runs.byTool)
                .sort((a, b) => b[1] - a[1])
                .map(([tool, n]) => ({ label: runToolLabel(tool), value: n }))}
            />
          )}
        </div>

        <div className="card">
          <div className="section-title">행동 퍼널 (7일 · 오늘)</div>
          {FUNNEL_STEPS.every(([k]) => !(stats.events.last7d[k])) ? (
            <p className="hint">아직 행동 이벤트가 없습니다.</p>
          ) : (
            <HBars
              data={FUNNEL_STEPS.map(([k, label]) => ({
                label,
                value: stats.events.last7d[k] ?? 0,
                sub: `오늘 ${stats.events.today[k] ?? 0}`,
              }))}
            />
          )}
        </div>
      </div>
    </>
  )
}

/** 행동 퍼널 표시 순서·라벨(가입 유입 → 무료 체험 → 과금 진입 → 완료). */
const FUNNEL_STEPS: ReadonlyArray<readonly [string, string]> = [
  ['page_view', '화면 진입'],
  ['free_calc', '무료 계산'],
  ['analysis_start', '분석 시작(과금 진입)'],
  ['analysis_done', '분석 완료'],
  ['checkout_view', '크레딧 화면'],
  ['credit_request', '크레딧 요청'],
]

/** 대시보드 수평 막대 차트 - 값 비례 폭. 외부 라이브러리 없이 디자인 토큰으로 렌더. */
function HBars({ data, accent }: { data: ReadonlyArray<{ label: string; value: number; sub?: string }>; accent?: boolean }) {
  const max = Math.max(1, ...data.map((d) => d.value))
  return (
    <ul className="hbars">
      {data.map((d, i) => (
        <li key={i} className="hbar-row">
          <span className="hbar-label" title={d.label}>{d.label}</span>
          <span className="hbar-track">
            <span className={`hbar-fill${accent ? ' accent' : ''}`} style={{ width: `${Math.round((d.value / max) * 100)}%` }} />
          </span>
          <span className="hbar-val num">{d.value}{d.sub && <em>{d.sub}</em>}</span>
        </li>
      ))}
    </ul>
  )
}

function StatCard({ k, v, sub, accent }: { k: string; v: string; sub?: string; accent?: boolean }) {
  return (
    <div className={`stat-card${accent ? ' accent' : ''}`}>
      <span className="sc-k">{k}</span>
      <span className="sc-v num">{v}</span>
      {sub && <span className="sc-sub">{sub}</span>}
    </div>
  )
}

/** 이벤트 코드 → 짧은 한글 라벨(활동 로그 표시용). */
const EVENT_LABEL: Record<string, string> = {
  page_view: '화면 진입',
  free_calc: '무료 계산',
  analysis_start: '분석 시작',
  analysis_done: '분석 완료',
  checkout_view: '크레딧 화면',
  credit_request: '크레딧 요청',
  login: '로그인',
}

/** KST 기준 날짜 문자열(오늘 여부 비교용). */
const kstDay = (s: string | null): string | null =>
  s ? new Date(s).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' }) : null

/** 이벤트 → 배지 톤(색). 행동 유형을 색으로 구분해 스캔성을 높인다. */
const EVENT_TONE: Record<string, string> = {
  page_view: 'neutral',
  free_calc: 'info',
  analysis_start: 'accent',
  analysis_done: 'good',
  checkout_view: 'warn',
  credit_request: 'warn',
  login: 'muted',
}

/** KST 월.일 시:분:초 - 활동 로그 페이징 행에 날짜+시각을 함께 표시. */
const fmtLogTime = (s: string | null): string =>
  s ? new Date(s).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }) : '-'

/** 활동 로그 페이지 크기(페이징 네비게이션). */
const LOG_PAGE_SIZE = 25

/** 접속 현황 - 오늘 누가 언제 접속했는지 + 마지막 접속 최신순 + 최근 활동 로그. */
function AccessPanel({ showAdmin }: { showAdmin: boolean }) {
  const [users, setUsers] = useState<AdminUser[]>([])
  const [events, setEvents] = useState<AdminEvent[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [logPage, setLogPage] = useState(0)

  useEffect(() => {
    setLoading(true)
    Promise.all([api.adminUsers(), api.adminEvents()])
      .then(([u, e]) => { setUsers(u); setEvents(e) })
      .catch((err: unknown) => setError(err instanceof ApiError ? err.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  // 관리자 표시 토글로 목록이 바뀌면 첫 페이지로.
  useEffect(() => { setLogPage(0) }, [showAdmin])

  const todayKst = new Date().toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })
  const adminIds = new Set(users.filter((u) => u.role === 'ADMIN').map((u) => u.id))
  // 전역 '관리자 계정 표시' 반영: 끄면 접속 목록·카운트도 일반 사용자만. 방문자(userId 없음)는 항상 표시.
  const baseUsers = showAdmin ? users : users.filter((u) => u.role !== 'ADMIN')
  // 전체 사용자(미접속 포함). 마지막 접속(로그인) 최신순, 미접속은 최근 가입순으로 뒤에. ISO 문자열 사전식 비교 = 시간순.
  const roster = baseUsers
    .slice()
    .sort((a, b) => {
      const byLogin = (b.lastLoginAt ?? '').localeCompare(a.lastLoginAt ?? '')
      return byLogin !== 0 ? byLogin : (b.createdAt ?? '').localeCompare(a.createdAt ?? '')
    })
  // 카드 요약용 파생 카운트.
  const accessed = baseUsers.filter((u) => u.lastLoginAt)
  const todayCount = accessed.filter((u) => kstDay(u.lastLoginAt) === todayKst).length
  const neverCount = baseUsers.length - accessed.length
  const shownEvents = showAdmin ? events : events.filter((e) => e.userId == null || !adminIds.has(e.userId))
  const visitorCount = events.filter((e) => e.userId == null).length
  const pageCount = Math.max(1, Math.ceil(shownEvents.length / LOG_PAGE_SIZE))
  const page = Math.min(logPage, pageCount - 1)
  const pageEvents = shownEvents.slice(page * LOG_PAGE_SIZE, page * LOG_PAGE_SIZE + LOG_PAGE_SIZE)

  return (
    <>
      {error && <p className="error">{error}</p>}

      <div className="stat-cards">
        <StatCard k="오늘 접속" v={String(todayCount)} sub={`전체 ${baseUsers.length}명 중`} accent />
        <StatCard k="접속 이력" v={String(accessed.length)} sub={`미접속 ${neverCount}명`} />
        <StatCard k="방문자 활동" v={String(visitorCount)} sub={`로그인 전 · 전체 이벤트 ${events.length}건 중`} />
      </div>

      <div className="card">
        <div className="section-title">접속 현황 - 전체 사용자 · 마지막 접속 최신순 ({roster.length})</div>
        {loading ? <p className="hint">불러오는 중…</p> : roster.length === 0 ? (
          <p className="hint">아직 가입한 사용자가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="admin-table">
              <thead>
                <tr><th>이메일</th><th>권한</th><th>인증</th><th>마지막 접속</th><th className="num">누적 접속</th></tr>
              </thead>
              <tbody>
                {roster.map((u) => {
                  const isToday = kstDay(u.lastLoginAt) === todayKst
                  return (
                    <tr key={u.id}>
                      <td className="admin-email">{u.email}</td>
                      <td><span className={`plan-pill ${u.role === 'ADMIN' ? 'admin' : 'free'}`}>{u.role}</span></td>
                      <td><span className={`plan-pill ${u.emailVerified ? 'verified' : 'unverified'}`}>{u.emailVerified ? '인증' : '미인증'}</span></td>
                      <td className="num admin-date">{u.lastLoginAt ? <>{fmtDate(u.lastLoginAt)}{isToday && <span className="self-tag">오늘</span>}</> : <span className="muted-x">미접속</span>}</td>
                      <td className="num"><b>{u.loginCount}</b></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">‘인증’ = 이메일 인증 완료 여부, ‘마지막 접속’ = 최근 로그인 시각, ‘누적 접속’ = 로그인 횟수. 시각은 KST 기준.</p>
      </div>

      <div className="card">
        <div className="section-title">최근 활동 로그 - 무엇을 언제 ({shownEvents.length})</div>
        {loading ? <p className="hint">불러오는 중…</p> : shownEvents.length === 0 ? (
          <p className="hint">표시할 활동 이벤트가 없습니다.</p>
        ) : (
          <>
            <div className="table-scroll">
              <table className="admin-table log-table">
                <thead>
                  <tr><th className="log-time-col">일시</th><th>사용자</th><th>행동</th><th>경로</th></tr>
                </thead>
                <tbody>
                  {pageEvents.map((e) => (
                    <tr key={e.id}>
                      <td className="num log-time">{fmtLogTime(e.createdAt)}</td>
                      <td className="admin-email">{e.ownerEmail ?? <span className="log-visitor">방문자</span>}</td>
                      <td><span className={`ev-badge ${EVENT_TONE[e.event] ?? 'neutral'}`}>{EVENT_LABEL[e.event] ?? e.event}</span></td>
                      <td>{e.path ? <code className="log-path">{e.path}</code> : <span className="muted-x">-</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="log-pager">
              <button type="button" className="btn-ghost btn-xs" disabled={page <= 0} onClick={() => setLogPage(page - 1)}>← 이전</button>
              <span className="log-pager-info">
                {page * LOG_PAGE_SIZE + 1}–{Math.min(shownEvents.length, (page + 1) * LOG_PAGE_SIZE)} / {shownEvents.length}건 · {page + 1}/{pageCount}쪽
              </span>
              <button type="button" className="btn-ghost btn-xs" disabled={page >= pageCount - 1} onClick={() => setLogPage(page + 1)}>다음 →</button>
            </div>
          </>
        )}
        <p className="hint">방문·계산·분석 등 얕은 행동 신호. ‘방문자’ = 로그인 전 익명(그중 {visitorCount}건). 관리자 활동은 상단 ‘관리자 계정 표시’ 토글로 숨김. 최근 200건 · KST.</p>
      </div>
    </>
  )
}

/** 관리자 - 전 사용자 크레딧 원장(충전 경로·사유·증감). 누가 어떻게 충전/소비했는지 감독용. */
function CreditsPanel({ showAdmin, adminIds }: { showAdmin: boolean; adminIds: Set<number> }) {
  const [entries, setEntries] = useState<AdminCreditEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.adminCredits()
      .then(setEntries)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  const shown = showAdmin ? entries : entries.filter((e) => !adminIds.has(e.userId))
  const charged = shown.filter((e) => e.delta > 0).reduce((s, e) => s + e.delta, 0)
  const spent = -shown.filter((e) => e.delta < 0).reduce((s, e) => s + e.delta, 0)

  return (
    <div className="card">
      <div className="section-title">크레딧 내역 - 전체 ({shown.length})</div>
      {error && <p className="error">{error}</p>}
      {loading ? (
        <p className="hint">불러오는 중…</p>
      ) : shown.length === 0 ? (
        <p className="hint">크레딧 변동 내역이 없습니다.</p>
      ) : (
        <>
          <div className="gl-summary">
            <span className="gl-tag go">지급·충전 +{charged}</span>
            <span className="gl-tag no">사용 −{spent}</span>
          </div>
          <div className="table-scroll">
            <table className="admin-table">
              <thead>
                <tr><th>#</th><th>사용자</th><th>증감</th><th>사유</th><th>출처 / 경로</th><th>일시</th></tr>
              </thead>
              <tbody>
                {shown.map((e) => (
                  <tr key={e.id}>
                    <td className="num">{e.id}</td>
                    <td className="admin-email">{e.ownerEmail ?? `user#${e.userId}`}</td>
                    <td className={`num ${e.delta >= 0 ? 'pos' : 'neg'}`}>{e.delta >= 0 ? `+${e.delta}` : e.delta}</td>
                    <td>{CREDIT_REASON_LABEL[e.reason] ?? e.reason}</td>
                    <td>{e.ref ?? '-'}</td>
                    <td className="num admin-date">{fmtDate(e.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      <p className="hint">충전(+)·관리자 조정(±)·AI 분석(−)을 모두 표시합니다. 최근 300건. 충전은 결제 수단·금액이 출처에 기록됩니다.</p>
    </div>
  )
}

function UsersPanel({ currentEmail, showAdmin }: { currentEmail: string; showAdmin: boolean }) {
  const [users, setUsers] = useState<AdminUser[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [deltas, setDeltas] = useState<Record<number, string>>({})
  const shown = showAdmin ? users : users.filter((u) => u.role !== 'ADMIN')

  function load() {
    api.adminUsers().then(setUsers).catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
  }
  useEffect(load, [])

  function replace(u: AdminUser) {
    setUsers((list) => list.map((x) => (x.id === u.id ? u : x)))
  }

  async function setRole(u: AdminUser, role: UserRole) {
    setBusyId(u.id); setError(null)
    try { replace(await api.adminSetRole(u.id, role)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '권한 변경 실패') }
    finally { setBusyId(null) }
  }

  async function adjust(u: AdminUser, sign: 1 | -1) {
    const raw = Number(deltas[u.id] ?? '5')
    const delta = sign * Math.abs(Number.isFinite(raw) ? raw : 0)
    if (delta === 0) { setError('조정 수량을 입력하세요.'); return }
    setBusyId(u.id); setError(null)
    try { replace(await api.adminAdjustCredits(u.id, delta)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '크레딧 조정 실패') }
    finally { setBusyId(null) }
  }

  async function setStatus(u: AdminUser, status: UserStatus) {
    setBusyId(u.id); setError(null)
    try { replace(await api.adminSetStatus(u.id, status)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '상태 변경 실패') }
    finally { setBusyId(null) }
  }

  async function remove(u: AdminUser) {
    if (!window.confirm(`'${u.email}' 계정을 영구 삭제합니다.\n이 사용자의 분석 이력·크레딧·관심 딜이 모두 삭제되며 되돌릴 수 없습니다. 계속할까요?`)) return
    setBusyId(u.id); setError(null)
    try {
      await api.adminDeleteUser(u.id)
      setUsers((list) => list.filter((x) => x.id !== u.id))
    } catch (e: unknown) { setError(e instanceof ApiError ? e.message : '삭제 실패') }
    finally { setBusyId(null) }
  }

  return (
    <div className="card">
      <div className="section-title">사용자 ({shown.length})</div>
      {error && <p className="error">{error}</p>}
      <div className="table-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <th>이메일</th><th>권한</th><th>상태</th><th>인증</th><th>플랜</th><th>크레딧</th>
              <th>가입</th><th>마지막 접속</th><th className="admin-actions-col">크레딧 조정 · 권한 · 계정</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((u) => {
              const isSelf = u.email === currentEmail
              const busy = busyId === u.id
              return (
                <tr key={u.id}>
                  <td className="admin-email">{u.email}{isSelf && <span className="self-tag">나</span>}</td>
                  <td>
                    <span className={`plan-pill ${u.role === 'ADMIN' ? 'admin' : 'free'}`}>{u.role}</span>
                  </td>
                  <td>
                    {u.status === 'DISABLED'
                      ? <span className="st-fail">차단됨</span>
                      : <span className="st-ok">정상</span>}
                  </td>
                  <td>{u.emailVerified ? '✓' : <span className="muted-x">미인증</span>}</td>
                  <td>{u.plan}</td>
                  <td className="num"><b>{u.creditBalance}</b></td>
                  <td className="num admin-date">{fmtDate(u.createdAt)}</td>
                  <td className="num admin-date">
                    {u.lastLoginAt
                      ? <span title={`누적 ${u.loginCount}회`}>{fmtDate(u.lastLoginAt)}</span>
                      : <span className="muted-x">미접속</span>}
                  </td>
                  <td>
                    <div className="admin-row-actions">
                      <input
                        className="admin-delta" type="number" min={1} placeholder="5"
                        value={deltas[u.id] ?? ''} disabled={busy}
                        onChange={(e) => setDeltas((d) => ({ ...d, [u.id]: e.target.value }))}
                      />
                      <button className="btn-ghost btn-xs" disabled={busy} onClick={() => adjust(u, 1)}>+ 지급</button>
                      <button className="btn-ghost btn-xs" disabled={busy} onClick={() => adjust(u, -1)}>− 차감</button>
                      {u.role === 'ADMIN' ? (
                        <button className="btn-ghost btn-xs" disabled={busy || isSelf} onClick={() => setRole(u, 'USER')}>관리자 해제</button>
                      ) : (
                        <button className="btn-primary btn-xs" disabled={busy} onClick={() => setRole(u, 'ADMIN')}>관리자 지정</button>
                      )}
                      {u.status === 'DISABLED' ? (
                        <button className="btn-ghost btn-xs" disabled={busy} onClick={() => setStatus(u, 'ACTIVE')}>차단 해제</button>
                      ) : (
                        <button className="btn-ghost btn-xs" disabled={busy || isSelf} onClick={() => setStatus(u, 'DISABLED')}>차단</button>
                      )}
                      <button className="btn-ghost btn-xs btn-danger" disabled={busy || isSelf} onClick={() => remove(u)}>삭제</button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="hint">권한 변경은 해당 사용자가 <b>재로그인</b>해야 토큰에 반영됩니다. 크레딧은 즉시 반영.</p>
    </div>
  )
}

function RunsPanel({ showAdmin, adminIds }: { showAdmin: boolean; adminIds: Set<number> }) {
  const [runs, setRuns] = useState<AdminRun[]>([])
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<AdminRunDetail | null>(null)
  // 보기 모드: 목록(시간순 전체) | 도구별 묶음.
  const [mode, setMode] = useState<'list' | 'group'>('list')

  useEffect(() => {
    api.adminRuns().then(setRuns).catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
  }, [])

  async function open(id: number) {
    setError(null)
    try { setDetail(await api.adminRunDetail(id)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '상세 조회 실패') }
  }

  const shown = showAdmin ? runs : runs.filter((r) => !adminIds.has(r.ownerUserId))

  // 도구별 그룹 - 건수 많은 순. 각 그룹 안은 최신순 유지(runs 가 이미 최신순).
  const groups = (() => {
    const map = new Map<string, AdminRun[]>()
    for (const r of shown) {
      const list = map.get(r.tool) ?? []
      list.push(r)
      map.set(r.tool, list)
    }
    return [...map.entries()].sort((a, b) => b[1].length - a[1].length)
  })()

  const rows = (items: AdminRun[]) =>
    items.map((r) => (
      <tr key={r.id}>
        <td className="num">{r.id}</td>
        <td className="admin-email">{r.ownerEmail ?? `user#${r.ownerUserId}`}</td>
        <td className="num">{r.tenantId}</td>
        <td><span className="tool-badge" title={r.tool}>{toolLabel(r.tool)}</span></td>
        <td>{r.dealName ?? '-'}</td>
        <td><span className={r.status === 'SUCCESS' ? 'st-ok' : 'st-fail'}>{r.status === 'SUCCESS' ? '성공' : r.status}</span></td>
        <td className="num admin-date">{fmtDate(r.createdAt)}</td>
        <td><button className="btn-link" onClick={() => open(r.id)}>보기</button></td>
      </tr>
    ))

  const headRow = (
    <tr><th>#</th><th>소유자</th><th>테넌트</th><th>도구</th><th>딜</th><th>상태</th><th>일시</th><th></th></tr>
  )

  return (
    <div className="card">
      <div className="section-title-row">
        <div className="section-title">분석 데이터 - 전체 ({shown.length})</div>
        <div className="seg seg-sm" role="group" aria-label="보기 방식">
          <button type="button" aria-pressed={mode === 'list'} onClick={() => setMode('list')}>목록</button>
          <button type="button" aria-pressed={mode === 'group'} onClick={() => setMode('group')}>도구별 묶음</button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      {mode === 'list' ? (
        <div className="table-scroll">
          <table className="admin-table">
            <thead>{headRow}</thead>
            <tbody>{rows(shown)}</tbody>
          </table>
        </div>
      ) : (
        <div className="run-groups">
          {groups.map(([tool, items]) => {
            const ok = items.filter((i) => i.status === 'SUCCESS').length
            const fail = items.length - ok
            return (
              <details key={tool} className="run-group" open>
                <summary title={tool}>
                  <span className="rg-name">{toolLabel(tool)}</span>
                  <span className="rg-count">{items.length}건</span>
                  <span className="rg-stat st-ok">성공 {ok}</span>
                  {fail > 0 && <span className="rg-stat st-fail">실패 {fail}</span>}
                </summary>
                <div className="table-scroll">
                  <table className="admin-table">
                    <thead>{headRow}</thead>
                    <tbody>{rows(items)}</tbody>
                  </table>
                </div>
              </details>
            )
          })}
        </div>
      )}

      {detail && (
        <ResultModal
          run={{ id: detail.id, dealId: null, dealName: detail.dealName, tool: detail.tool, status: detail.status, createdAt: detail.createdAt }}
          result={parse(detail.resultJson)}
          request={parse(detail.requestJson)}
          subtitle={detail.ownerEmail ?? `user#${detail.ownerUserId}`}
          onClose={() => setDetail(null)}
        />
      )}
    </div>
  )
}

/** 관리자 - 즉시 수집(딜/뉴스 카드 + 무료 브리핑, 스케줄러와 동일 경로). */
function MarketIngestCard() {
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<IngestReport | null>(null)
  const [err, setErr] = useState<string | null>(null)

  async function run() {
    setBusy(true); setErr(null); setReport(null)
    try { setReport(await api.marketFeedIngest()) }
    catch (e: unknown) { setErr(e instanceof ApiError ? e.message : '수집에 실패했습니다.') }
    finally { setBusy(false) }
  }

  return (
    <div className="card">
      <div className="section-title">시장 데이터 수집</div>
      <div className="nl-tools">
        <button type="button" className="btn-primary" onClick={() => void run()} disabled={busy}>
          {busy ? '수집 중…' : '지금 수집'}
        </button>
      </div>
      {report && (
        <p className="hint">
          수집 완료 - 신규 <b>{report.inserted}</b> · 중복 {report.skippedDuplicate} · 분석 {report.fetched}건
          {report.briefingGenerated ? ` · 브리핑 갱신(${report.briefingProvider})` : ' · 브리핑 생략'}
        </p>
      )}
      {err && <p className="error">{err}</p>}
      <p className="hint">딜·뉴스 카드와 무료 브리핑을 한 번에 수집합니다(평일 06:30 자동 수집과 동일 경로). 발송은 새 브리핑 생성 시 활성 구독자에게 자동.</p>
    </div>
  )
}

/** 관리자 - 시장 운영: 즉시 수집 + 뉴스레터(브리핑 메일) 미리보기·테스트·구독자·발송 로그. */
function MarketPanel() {
  const [subs, setSubs] = useState<NewsSubscriber[]>([])
  const [logs, setLogs] = useState<NewsletterSendLogEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [testEmail, setTestEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([api.adminNewsletterSubscribers(), api.adminNewsletterSendLog()])
      .then(([s, l]) => { setSubs(s); setLogs(l) })
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  async function preview() {
    setMsg(null); setBusy(true)
    try {
      const html = await api.adminNewsletterPreview()
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (e: unknown) { setMsg(e instanceof ApiError ? e.message : '미리보기 실패') }
    finally { setBusy(false) }
  }

  async function testSend() {
    setMsg(null); setBusy(true)
    try {
      await api.adminNewsletterTestSend(testEmail.trim())
      setMsg(`${testEmail.trim()} 로 테스트 메일을 보냈습니다.`)
    } catch (e: unknown) { setMsg(e instanceof ApiError ? e.message : '발송 실패') }
    finally { setBusy(false) }
  }

  const activeCount = subs.filter((s) => s.active).length

  return (
    <>
      {error && <p className="error">{error}</p>}

      <MarketIngestCard />

      <div className="card">
        <div className="section-title">메일 미리보기 · 테스트 발송</div>
        <div className="nl-tools">
          <button type="button" className="btn-ghost" onClick={preview} disabled={busy}>메일 미리보기</button>
          <input className="nl-test-email" type="email" placeholder="테스트 받을 이메일"
            value={testEmail} onChange={(e) => setTestEmail(e.target.value)} />
          <button type="button" className="btn-primary" onClick={testSend} disabled={busy || !testEmail.trim()}>
            {busy ? '처리 중…' : '테스트 발송'}
          </button>
        </div>
        {msg && <p className="hint">{msg}</p>}
        <p className="hint">최신 브리핑으로 만든 실제 메일을 미리보거나, 지정 주소로 1건만 보냅니다(구독자 영향 없음).</p>
      </div>

      <div className="card">
        <div className="section-title">구독자 - 활성 {activeCount} / 전체 {subs.length}</div>
        {loading ? <p className="hint">불러오는 중…</p> : (
          <div className="table-scroll">
            <table className="admin-table">
              <thead>
                <tr><th>이메일</th><th>상태</th><th>구독일</th></tr>
              </thead>
              <tbody>
                {subs.length === 0 ? (
                  <tr><td colSpan={3} className="muted">아직 구독자가 없습니다.</td></tr>
                ) : subs.map((s) => (
                  <tr key={s.email}>
                    <td className="admin-email">{s.email}</td>
                    <td>
                      <span className={`plan-pill ${s.active ? 'admin' : 'free'}`}>{s.active ? '구독중' : '해지'}</span>
                    </td>
                    <td className="num admin-date">{fmtDate(s.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="section-title">발송 로그 - 누구에게 언제 ({logs.length})</div>
        {loading ? <p className="hint">불러오는 중…</p> : (
          <div className="table-scroll">
            <table className="admin-table">
              <thead>
                <tr><th>수신자</th><th>제목</th><th>상태</th><th>발송 일시</th></tr>
              </thead>
              <tbody>
                {logs.length === 0 ? (
                  <tr><td colSpan={4} className="muted">아직 발송 이력이 없습니다.</td></tr>
                ) : logs.map((l, i) => (
                  <tr key={`${l.email}-${l.sentAt ?? i}`}>
                    <td className="admin-email">{l.email}</td>
                    <td>{l.subject ?? '-'}</td>
                    <td><span className={l.status === 'SENT' ? 'st-ok' : 'st-fail'}>{l.status}</span></td>
                    <td className="num admin-date">{fmtDate(l.sentAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">발송은 수집 스케줄러가 새 브리핑을 만들 때 활성 구독자 전원에게 자동 전송됩니다. 발송 자체는 무료(과금 없음).</p>
      </div>
    </>
  )
}

/** 저장된 JSON 문자열 → 객체(파싱 실패 시 null). */
function parse(json: string | null): unknown {
  if (!json) return null
  try { return JSON.parse(json) } catch { return null }
}
