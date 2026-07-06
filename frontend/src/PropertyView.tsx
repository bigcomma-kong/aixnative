import { useEffect, useState } from 'react'
import {
  api, ApiError, type DocAnalysis, type PmAmReport, type PmBuilding, type PmCalendarEvent,
  type PmLease, type PmRentRoll,
} from './api'
import { LeaseForm } from './LeaseForm'
import { LeaseCalendar } from './LeaseCalendar'
import { RentBarChart } from './RentBarChart'
import { fmtDate, dDayLabel, dDayTone } from './pmDate'
import { printLeaseReport, downloadLeaseReportDoc, downloadRentRollXls } from './reportExport'

interface PropertyViewProps {
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  toolCosts?: Record<string, number>
}

const ASSET_TYPES = ['오피스', '물류', '호텔', '리테일'] as const
const money = (v: number | null | undefined): string => (v == null ? '-' : v.toLocaleString('ko-KR'))

/** 오늘 기준 days 만큼 이동한 날짜 ISO(yyyy-MM-dd). 샘플 데이터 날짜 생성용. */
function offsetIso(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const STATUS_LABEL: Record<string, string> = { ACTIVE: '진행', UPCOMING: '예정', EXPIRED: '만료', UNKNOWN: '미상' }
const statusTone = (s: string): 'go' | 'cond' | 'no' => (s === 'ACTIVE' ? 'go' : s === 'EXPIRED' ? 'no' : 'cond')

/** 판정값 → 톤. 안정=go, 위험=no, 그 외(주의)=cond. */
function verdictTone(v?: string): 'go' | 'no' | 'cond' {
  if (!v) return 'cond'
  if (v.includes('안정') || v.includes('양호')) return 'go'
  if (v.includes('위험')) return 'no'
  return 'cond'
}
function sevTone(s?: string): 'go' | 'no' | 'cond' {
  if (!s) return 'cond'
  const t = s.trim().toUpperCase()
  if (t.startsWith('H') || t.includes('높')) return 'no'
  if (t.startsWith('L') || t.includes('낮')) return 'go'
  return 'cond'
}

/**
 * 자산관리(PM) - 임대차 관리. 건물을 선택하면 렌트롤 표·임대료 차트·다가오는 일정·리스크를 보고,
 * 계약서를 AI 로 추출해 임대차를 쌓고, AM 제출용 보고서를 뽑는다(저장형 관리 허브).
 */
export function PropertyView({ onCreditBalance, onNeedCredits, toolCosts }: PropertyViewProps) {
  const [buildings, setBuildings] = useState<PmBuilding[] | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [rentRoll, setRentRoll] = useState<PmRentRoll | null>(null)
  const [events, setEvents] = useState<PmCalendarEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  const [showBuildingForm, setShowBuildingForm] = useState(false)
  const [leaseEditor, setLeaseEditor] = useState<PmLease | 'new' | null>(null)
  const [amReport, setAmReport] = useState<PmAmReport | null>(null)
  const [amBusy, setAmBusy] = useState(false)
  const [busyLeaseId, setBusyLeaseId] = useState<number | null>(null)
  const [seeding, setSeeding] = useState(false)

  const selected = buildings?.find((b) => b.id === selectedId) ?? null

  useEffect(() => { void loadBuildings() }, [])

  async function loadBuildings(selectAfter?: number) {
    setError(null)
    try {
      const list = await api.pmBuildings()
      setBuildings(list)
      const next = selectAfter ?? selectedId ?? list[0]?.id ?? null
      setSelectedId(next)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '건물 목록을 불러오지 못했습니다.')
      setBuildings([])
    }
  }

  // 선택 건물이 바뀌면 렌트롤·일정을 로드. 보고서/편집기는 초기화.
  useEffect(() => {
    if (selectedId == null) { setRentRoll(null); setEvents([]); return }
    setAmReport(null); setLeaseEditor(null)
    void loadDetail(selectedId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  async function loadDetail(buildingId: number) {
    setError(null)
    try {
      const [rr, cal] = await Promise.all([api.pmRentRoll(buildingId), api.pmCalendar(buildingId)])
      setRentRoll(rr)
      setEvents(cal.events)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '건물 상세를 불러오지 못했습니다.')
    }
  }

  async function deleteBuilding(id: number) {
    if (!window.confirm('이 건물과 소속 임대차를 모두 삭제할까요?')) return
    try {
      await api.pmDeleteBuilding(id)
      setSelectedId(null)
      await loadBuildings()
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '삭제에 실패했습니다.')
    }
  }

  async function deleteLease(id: number) {
    if (!window.confirm('이 임대차를 삭제할까요?')) return
    setBusyLeaseId(id)
    try {
      await api.pmDeleteLease(id)
      if (selectedId != null) await loadDetail(selectedId)
      await loadBuildings()
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '삭제에 실패했습니다.')
    } finally {
      setBusyLeaseId(null)
    }
  }

  async function onLeaseSaved() {
    setLeaseEditor(null)
    if (selectedId != null) await loadDetail(selectedId)
    await loadBuildings()
  }

  /** 샘플 데이터 넣기 - 데모 건물 1채 + 임대차 1건을 넣어 등록/추가 흐름을 바로 체험. */
  async function seedSample() {
    setSeeding(true); setError(null)
    try {
      const b = await api.pmCreateBuilding({
        name: '샘플 · 강남 파인타워', address: '서울 강남구 테헤란로 152', assetType: '오피스', gfaPyeong: 3200,
      })
      await api.pmCreateLease({
        buildingId: b.id, tenantName: '가온테크', unitLabel: '8F', areaPyeong: 420,
        monthlyRentManwon: 3800, depositManwon: 45000, leaseStartDate: offsetIso(-700),
        leaseEndDate: offsetIso(35), escalationPct: 3, nextEscalationDate: offsetIso(20),
      })
      await loadBuildings(b.id)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '샘플 데이터를 넣지 못했습니다.')
    } finally {
      setSeeding(false)
    }
  }

  async function generateAmReport() {
    if (selectedId == null) return
    setAmBusy(true); setError(null)
    try {
      const r = await api.pmAmReport(selectedId)
      setAmReport(r)
      onCreditBalance(r.creditBalance)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      else if (err instanceof ApiError && err.status === 503) setError(err.message || 'AI 보고서 서비스를 사용할 수 없습니다.')
      else setError(err instanceof ApiError ? err.message : '보고서 생성에 실패했습니다.')
    } finally {
      setAmBusy(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">ASSET MANAGEMENT</span>
          <h1>임대차를 한 곳에서 관리하고, AM 보고서로.</h1>
          <p className="page-sub">임대차 계약을 등록하면 만기·임대료를 자동 추적하고, AM 제출용 보고서로 정리합니다.</p>
        </div>
      </div>

      <ol className="use-steps" aria-label="사용 방법">
        <li><span className="us-n">1</span><span className="us-t"><b>건물 등록</b> 관리할 건물을 추가합니다</span></li>
        <li><span className="us-n">2</span><span className="us-t"><b>계약서 추출</b> 계약서를 붙여넣으면 AI 가 임대차 데이터를 뽑습니다</span></li>
        <li><span className="us-n">3</span><span className="us-t"><b>관리·보고</b> 렌트롤·일정·리스크를 보고 AM 보고서를 뽑습니다</span></li>
      </ol>

      {error && <p className="error">{error}</p>}

      <div className="pm-layout">
        {/* 좌: 건물 목록 */}
        <aside className="pm-buildings card">
          <div className="pm-side-head">
            <span className="section-title" style={{ margin: 0 }}>건물</span>
            <button type="button" className="btn-ghost btn-xs" onClick={() => setShowBuildingForm((v) => !v)}>
              {showBuildingForm ? '닫기' : '+ 추가'}
            </button>
          </div>
          {showBuildingForm && (
            <BuildingForm
              onSaved={async (b) => { setShowBuildingForm(false); await loadBuildings(b.id) }}
              onError={setError}
            />
          )}
          {buildings === null ? (
            <p className="hint">불러오는 중…</p>
          ) : buildings.length === 0 ? (
            <p className="hint">등록된 건물이 없습니다. ‘+ 추가’로 시작하세요.</p>
          ) : (
            <ul className="pm-blist">
              {buildings.map((b) => (
                <li key={b.id}>
                  <button type="button" className={`pm-bitem${b.id === selectedId ? ' on' : ''}`} onClick={() => setSelectedId(b.id)}>
                    <span className="pm-bname">{b.name}</span>
                    <span className="pm-bmeta">{[b.assetType, `임대차 ${b.leaseCount}`].filter(Boolean).join(' · ')}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>

        {/* 우: 선택 건물 상세 */}
        <section className="pm-detail">
          {!selected ? (
            <div className="card pm-empty">
              <p className="hint">왼쪽에서 건물을 선택하거나 새로 추가하세요.</p>
              <p className="hint">처음이라면 샘플 데이터로 렌트롤·일정·리스크·보고서를 바로 체험할 수 있습니다.</p>
              <button type="button" className="btn-primary btn-xs" onClick={() => void seedSample()} disabled={seeding}>
                {seeding ? '샘플 넣는 중…' : '샘플 데이터 넣기'}
              </button>
            </div>
          ) : (
            <>
              <div className="card">
                <div className="pm-detail-head">
                  <div>
                    <h2 className="pm-title">{selected.name}</h2>
                    <div className="muted">{[selected.assetType, selected.address, selected.gfaPyeong ? `${selected.gfaPyeong}평` : null].filter(Boolean).join(' · ') || '-'}</div>
                  </div>
                  <div className="pm-detail-actions">
                    <button type="button" className="btn-ghost btn-xs" onClick={() => setLeaseEditor('new')}>+ 임대차 추가</button>
                    <button type="button" className="btn-link btn-xs" onClick={() => void deleteBuilding(selected.id)}>건물 삭제</button>
                  </div>
                </div>

                {rentRoll && <RentRollKpis rr={rentRoll} />}
              </div>

              {leaseEditor && selectedId != null && (
                <div className="card">
                  <div className="section-title">{leaseEditor === 'new' ? '임대차 추가' : '임대차 수정'}</div>
                  <LeaseForm
                    buildingId={selectedId}
                    initial={leaseEditor === 'new' ? undefined : leaseEditor}
                    onSaved={onLeaseSaved}
                    onCancel={() => setLeaseEditor(null)}
                    onCreditBalance={onCreditBalance}
                    onNeedCredits={onNeedCredits}
                    extractCost={toolCosts?.['LEASE_EXTRACT']}
                  />
                </div>
              )}

              {rentRoll && rentRoll.leases.length > 0 && (
                <>
                  <div className="card">
                    <div className="section-title">임대료 (임차인별 · 만원/월)</div>
                    <RentBarChart data={rentRoll.leases.map((l) => ({
                      label: [l.tenantName, l.unitLabel].filter(Boolean).join(' '),
                      value: l.monthlyRentManwon ?? 0,
                    }))} />
                  </div>

                  <div className="card">
                    <div className="section-title">렌트롤</div>
                    <RentRollTable leases={rentRoll.leases} onEdit={setLeaseEditor} onDelete={deleteLease} busyId={busyLeaseId} />
                  </div>
                </>
              )}

              <div className="pm-two">
                <div className="card">
                  <div className="section-title">다가오는 일정</div>
                  <LeaseCalendar events={events} />
                </div>
                <div className="card">
                  <div className="section-title">리스크</div>
                  {rentRoll && rentRoll.flags.length > 0 ? (
                    <div className="pm-flags">
                      {rentRoll.flags.map((f, i) => (
                        <div key={i} className="risk">
                          <span className="r-name">{f.label}</span>
                          <span className="r-impact"><span className={`sev-badge ${sevTone(f.severity)}`}>{f.severity}</span></span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="hint">식별된 리스크가 없습니다.</p>
                  )}
                </div>
              </div>

              {/* AM 제출 보고서 */}
              <div className="card">
                <div className="pm-detail-head">
                  <div className="section-title" style={{ margin: 0 }}>AM 제출 보고서</div>
                  <div className="pm-detail-actions">
                    {rentRoll && (
                      <>
                        <button type="button" className="btn-ghost btn-xs" onClick={() => rentRoll && downloadRentRollXls(rentRoll)}>렌트롤 XLS</button>
                        <button type="button" className="btn-ghost btn-xs" onClick={() => rentRoll && printLeaseReport(rentRoll, events, amReport)}>PDF</button>
                        <button type="button" className="btn-ghost btn-xs" onClick={() => rentRoll && downloadLeaseReportDoc(rentRoll, events, amReport)}>Word</button>
                      </>
                    )}
                    <button type="button" className="btn-primary btn-xs" onClick={() => void generateAmReport()}
                      disabled={amBusy || !rentRoll || rentRoll.leases.length === 0}>
                      {amBusy ? 'AI 작성 중…' : `AI 서술 생성${toolCosts?.['PM_AM_REPORT'] != null ? ` · ${toolCosts['PM_AM_REPORT']}크레딧` : ''}`}
                    </button>
                  </div>
                </div>
                {amReport ? (
                  <AmReportView report={amReport} />
                ) : (
                  <p className="hint">렌트롤·일정·리스크는 위 버튼으로 바로 export 할 수 있습니다. ‘AI 서술 생성’을 누르면 AM 제출용 해설·권고가 추가됩니다.</p>
                )}
              </div>
            </>
          )}
        </section>
      </div>
    </>
  )
}

/** 렌트롤 핵심 지표 카드. */
function RentRollKpis({ rr }: { rr: PmRentRoll }) {
  const kpis: [string, string][] = [
    ['임차 건수', `${rr.leaseCount}건`],
    ['총 월임대료', `${money(rr.totalMonthlyRentManwon)}만원`],
    ['연 환산', `${money(rr.annualRentManwon)}만원`],
    ...(rr.waltYears != null ? [['WALT', `${rr.waltYears}년`] as [string, string]] : []),
    ...(rr.topTenantPct != null ? [['최대 임차인 비중', `${rr.topTenantPct}%`] as [string, string]] : []),
    ...(rr.avgRentPerPyeongManwon != null ? [['평당 월임대료', `${rr.avgRentPerPyeongManwon}만원`] as [string, string]] : []),
  ]
  return (
    <div className="metrics">
      {kpis.map(([k, v]) => (
        <div key={k} className="metric"><span className="k">{k}</span><span className="v">{v}</span></div>
      ))}
    </div>
  )
}

/** 렌트롤 표 - 수정·삭제 액션 포함. */
function RentRollTable({ leases, onEdit, onDelete, busyId }: {
  leases: PmLease[]
  onEdit: (l: PmLease) => void
  onDelete: (id: number) => void
  busyId: number | null
}) {
  return (
    <table className="pm-rentroll">
      <thead>
        <tr><th>임차인</th><th>층/호</th><th className="num">면적</th><th className="num">월임대료</th><th className="num">보증금</th><th>만기</th><th>상태</th><th></th></tr>
      </thead>
      <tbody>
        {leases.map((l) => (
          <tr key={l.id}>
            <td>{l.tenantName}</td>
            <td>{l.unitLabel ?? '-'}</td>
            <td className="num">{money(l.areaPyeong)}</td>
            <td className="num">{money(l.monthlyRentManwon)}</td>
            <td className="num">{money(l.depositManwon)}</td>
            <td>
              {fmtDate(l.leaseEndDate)}
              {l.daysToExpiry != null && <span className={`pm-dday ${dDayTone(l.daysToExpiry)}`}>{dDayLabel(l.daysToExpiry)}</span>}
            </td>
            <td><span className={`sev-badge ${statusTone(l.status)}`}>{STATUS_LABEL[l.status] ?? l.status}</span></td>
            <td className="pm-row-actions">
              <button type="button" className="btn-link btn-xs" onClick={() => onEdit(l)}>수정</button>
              <button type="button" className="btn-link btn-xs" disabled={busyId === l.id} onClick={() => onDelete(l.id)}>삭제</button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** AM 보고서 AI 서술(sections) 렌더. */
function AmReportView({ report }: { report: PmAmReport }) {
  const a: DocAnalysis | null = report.analysis
  if (!a) {
    return report.analysisRaw ? <p className="narrative">{report.analysisRaw}</p> : <p className="hint">생성된 서술이 없습니다.</p>
  }
  return (
    <div className="ai-block">
      {a.headline && <p className="narrative"><b>{a.headline}</b></p>}
      {a.verdict && (
        <div className={`verdict ${verdictTone(a.verdict)}`}>
          <div className="v-mark">{verdictTone(a.verdict) === 'go' ? '✓' : verdictTone(a.verdict) === 'no' ? '×' : '!'}</div>
          <div><div className="v-label">{a.verdict}{a.confidence ? ` · 신뢰도 ${a.confidence}` : ''}</div></div>
        </div>
      )}
      {a.flags && a.flags.length > 0 && (
        <section className="scr-section">
          <div className="section-title">주요 플래그</div>
          {a.flags.map((f, i) => (
            <div key={i} className="risk">
              <span className="r-name">{f.label}</span>
              <span className="r-impact"><span className={`sev-badge ${sevTone(f.severity)}`}>{f.severity}</span></span>
            </div>
          ))}
        </section>
      )}
      {a.sections && a.sections.map((s, i) => (
        <section key={i}>
          {s.title && <div className="section-title">{s.title}</div>}
          {s.text && <p className="narrative">{s.text}</p>}
          {s.bullets && s.bullets.length > 0 && <ul>{s.bullets.map((b, j) => <li key={j}>{b}</li>)}</ul>}
          {s.table && s.table.headers && (
            <table>
              <thead><tr>{s.table.headers.map((h, j) => <th key={j}>{h}</th>)}</tr></thead>
              <tbody>{s.table.rows.map((row, j) => <tr key={j}>{row.map((c, k) => <td key={k}>{c}</td>)}</tr>)}</tbody>
            </table>
          )}
        </section>
      ))}
      <p className="disclaimer">{report.disclaimer}</p>
    </div>
  )
}

/** 건물 추가/수정 소형 폼. */
function BuildingForm({ onSaved, onError }: { onSaved: (b: PmBuilding) => void; onError: (m: string) => void }) {
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [assetType, setAssetType] = useState<string>('오피스')
  const [gfa, setGfa] = useState('')
  const [saving, setSaving] = useState(false)

  async function save() {
    if (!name.trim()) { onError('건물명을 입력하세요.'); return }
    setSaving(true)
    try {
      const b = await api.pmCreateBuilding({
        name: name.trim(),
        address: address.trim() || undefined,
        assetType,
        gfaPyeong: gfa.trim() ? Number(gfa) : undefined,
      })
      onSaved(b)
    } catch (e: unknown) {
      onError(e instanceof ApiError ? e.message : '저장에 실패했습니다.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="pm-bform">
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder="건물명 *" />
      <input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="주소 (선택)" />
      <select value={assetType} onChange={(e) => setAssetType(e.target.value)} aria-label="자산유형">
        {ASSET_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      <input value={gfa} onChange={(e) => setGfa(e.target.value)} type="number" inputMode="decimal" placeholder="연면적(평, 선택)" />
      <button type="button" className="btn-primary btn-xs" onClick={() => void save()} disabled={saving}>
        {saving ? '저장 중…' : '건물 저장'}
      </button>
    </div>
  )
}
