import { useEffect, useState } from 'react'
import { api, ApiError, type AdminRun, type AdminRunDetail, type AdminUser, type UserRole } from './api'

interface AdminViewProps {
  currentEmail: string
}

const fmtDate = (s: string | null): string => (s ? new Date(s).toLocaleString('ko-KR') : '-')

export function AdminView({ currentEmail }: AdminViewProps) {
  const [section, setSection] = useState<'users' | 'runs'>('users')

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">관리자</span>
          <h1>운영 콘솔</h1>
        </div>
        <div className="seg admin-seg" role="group" aria-label="관리자 섹션">
          <button type="button" aria-pressed={section === 'users'} onClick={() => setSection('users')}>사용자·크레딧</button>
          <button type="button" aria-pressed={section === 'runs'} onClick={() => setSection('runs')}>분석 데이터</button>
        </div>
      </div>

      {section === 'users' ? <UsersPanel currentEmail={currentEmail} /> : <RunsPanel />}
    </>
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

  return (
    <div className="card">
      <div className="section-title">사용자 ({users.length})</div>
      {error && <p className="error">{error}</p>}
      <div className="table-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <th>이메일</th><th>권한</th><th>인증</th><th>플랜</th><th>크레딧</th>
              <th>가입</th><th className="admin-actions-col">크레딧 조정 · 권한</th>
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

  useEffect(() => {
    api.adminRuns().then(setRuns).catch((e: unknown) => setError(e instanceof ApiError ? e.message : '조회 실패'))
  }, [])

  async function open(id: number) {
    setError(null)
    try { setDetail(await api.adminRunDetail(id)) }
    catch (e: unknown) { setError(e instanceof ApiError ? e.message : '상세 조회 실패') }
  }

  return (
    <div className="card">
      <div className="section-title">분석 데이터 — 전체 ({runs.length})</div>
      {error && <p className="error">{error}</p>}
      <div className="table-scroll">
        <table className="admin-table">
          <thead>
            <tr><th>#</th><th>소유자</th><th>테넌트</th><th>도구</th><th>딜</th><th>상태</th><th>일시</th><th></th></tr>
          </thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td className="num">{r.id}</td>
                <td className="admin-email">{r.ownerEmail ?? `user#${r.ownerUserId}`}</td>
                <td className="num">{r.tenantId}</td>
                <td>{r.tool}</td>
                <td>{r.dealName ?? '-'}</td>
                <td><span className={r.status === 'SUCCESS' ? 'st-ok' : 'st-fail'}>{r.status}</span></td>
                <td className="num admin-date">{fmtDate(r.createdAt)}</td>
                <td><button className="btn-link" onClick={() => open(r.id)}>보기</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {detail && (
        <div className="analyze-overlay" role="dialog" aria-label="분석 데이터 상세" onClick={() => setDetail(null)}>
          <div className="admin-detail" onClick={(e) => e.stopPropagation()}>
            <div className="admin-detail-head">
              <strong>#{detail.id} · {detail.tool} · {detail.ownerEmail ?? `user#${detail.ownerUserId}`}</strong>
              <button className="btn-ghost btn-xs" onClick={() => setDetail(null)}>닫기</button>
            </div>
            <div className="section-title">입력(request)</div>
            <pre className="admin-json">{pretty(detail.requestJson)}</pre>
            <div className="section-title">결과(result)</div>
            <pre className="admin-json">{pretty(detail.resultJson)}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

function pretty(json: string | null): string {
  if (!json) return '(없음)'
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}
