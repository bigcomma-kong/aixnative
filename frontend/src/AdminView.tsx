import { useEffect, useState } from 'react'
import {
  api,
  ApiError,
  type AdminCreditEntry,
  type AdminRun,
  type AdminRunDetail,
  type AdminUser,
  type CreditReason,
  type NewsletterSendLogEntry,
  type NewsSubscriber,
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
  const [section, setSection] = useState<'users' | 'credits' | 'runs' | 'newsletter'>('users')

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">관리자</span>
          <h1>운영 콘솔</h1>
        </div>
        <div className="seg admin-seg" role="group" aria-label="관리자 섹션">
          <button type="button" aria-pressed={section === 'users'} onClick={() => setSection('users')}>사용자·크레딧</button>
          <button type="button" aria-pressed={section === 'credits'} onClick={() => setSection('credits')}>크레딧 내역</button>
          <button type="button" aria-pressed={section === 'runs'} onClick={() => setSection('runs')}>분석 데이터</button>
          <button type="button" aria-pressed={section === 'newsletter'} onClick={() => setSection('newsletter')}>뉴스레터</button>
        </div>
      </div>

      {section === 'users' && <UsersPanel currentEmail={currentEmail} />}
      {section === 'credits' && <CreditsPanel />}
      {section === 'runs' && <RunsPanel />}
      {section === 'newsletter' && <NewsletterPanel />}
    </>
  )
}

/** 관리자 — 전 사용자 크레딧 원장(충전 경로·사유·증감). 누가 어떻게 충전/소비했는지 감독용. */
function CreditsPanel() {
  const [entries, setEntries] = useState<AdminCreditEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.adminCredits()
      .then(setEntries)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  const charged = entries.filter((e) => e.delta > 0).reduce((s, e) => s + e.delta, 0)
  const spent = -entries.filter((e) => e.delta < 0).reduce((s, e) => s + e.delta, 0)

  return (
    <div className="card">
      <div className="section-title">크레딧 내역 — 전체 ({entries.length})</div>
      {error && <p className="error">{error}</p>}
      {loading ? (
        <p className="hint">불러오는 중…</p>
      ) : entries.length === 0 ? (
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
                {entries.map((e) => (
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

function UsersPanel({ currentEmail }: { currentEmail: string }) {
  const [users, setUsers] = useState<AdminUser[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [deltas, setDeltas] = useState<Record<number, string>>({})

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
      <div className="section-title">사용자 ({users.length})</div>
      {error && <p className="error">{error}</p>}
      <div className="table-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <th>이메일</th><th>권한</th><th>상태</th><th>인증</th><th>플랜</th><th>크레딧</th>
              <th>가입</th><th className="admin-actions-col">크레딧 조정 · 권한 · 계정</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => {
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

function RunsPanel() {
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

  // 도구별 그룹 — 건수 많은 순. 각 그룹 안은 최신순 유지(runs 가 이미 최신순).
  const groups = (() => {
    const map = new Map<string, AdminRun[]>()
    for (const r of runs) {
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
        <div className="section-title">분석 데이터 — 전체 ({runs.length})</div>
        <div className="seg" role="group" aria-label="보기 방식">
          <button type="button" aria-pressed={mode === 'list'} onClick={() => setMode('list')}>목록</button>
          <button type="button" aria-pressed={mode === 'group'} onClick={() => setMode('group')}>도구별 묶음</button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      {mode === 'list' ? (
        <div className="table-scroll">
          <table className="admin-table">
            <thead>{headRow}</thead>
            <tbody>{rows(runs)}</tbody>
          </table>
        </div>
      ) : (
        <div className="run-groups">
          {groups.map(([tool, items]) => {
            const ok = items.filter((i) => i.status === 'SUCCESS').length
            const fail = items.length - ok
            return (
              <details key={tool} className="run-group" open>
                <summary>
                  <span className="rg-name">{toolLabel(tool)}</span>
                  <code className="rg-code">{tool}</code>
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
          run={{ id: detail.id, dealName: detail.dealName, tool: detail.tool, status: detail.status, createdAt: detail.createdAt }}
          result={parse(detail.resultJson)}
          request={parse(detail.requestJson)}
          subtitle={detail.ownerEmail ?? `user#${detail.ownerUserId}`}
          onClose={() => setDetail(null)}
        />
      )}
    </div>
  )
}

function NewsletterPanel() {
  const [subs, setSubs] = useState<NewsSubscriber[]>([])
  const [logs, setLogs] = useState<NewsletterSendLogEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    Promise.all([api.adminNewsletterSubscribers(), api.adminNewsletterSendLog()])
      .then(([s, l]) => { setSubs(s); setLogs(l) })
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
      .finally(() => setLoading(false))
  }, [])

  const activeCount = subs.filter((s) => s.active).length

  return (
    <>
      {error && <p className="error">{error}</p>}

      <div className="card">
        <div className="section-title">구독자 — 활성 {activeCount} / 전체 {subs.length}</div>
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
        <div className="section-title">발송 로그 — 누구에게 언제 ({logs.length})</div>
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
