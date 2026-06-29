import { useEffect, useMemo, useState } from 'react'
import {
  api, ApiError,
  type IngestReport, type MarketBriefing, type MarketDeepReport, type MarketFeedItem, type MarketFeedInput,
} from './api'

interface MarketFeedViewProps {
  isAdmin: boolean
  /** '이 딜 분석하기' — 카드 원문을 심화 분석(딜 진입)으로 넘긴다. */
  onAnalyzeDeal: (sourceText: string) => void
  /** 심층 리포트(크레딧 소비) 후 잔액 갱신. */
  onCreditBalance: (balance: number) => void
}

const ASSET_FILTERS = ['전체', '오피스', '물류', '호텔', '리테일'] as const
type AssetFilter = (typeof ASSET_FILTERS)[number]

/**
 * 시장 인텔리전스 — 뉴스레터(마켓 브리핑) + 딜 모니터링(카드 피드)을 합친 surface.
 * 브리핑으로 큰 그림을 보고, 자산유형으로 딜을 좁혀 그 자리에서 AI 분석으로 진입.
 * 데이터는 스케줄러가 매일 자동 수집(이력 누적) — 관리자는 즉시 수집/추가/삭제.
 */
export function MarketFeedView({ isAdmin, onAnalyzeDeal, onCreditBalance }: MarketFeedViewProps) {
  const [items, setItems] = useState<MarketFeedItem[]>([])
  const [briefing, setBriefing] = useState<MarketBriefing | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [ingesting, setIngesting] = useState(false)
  const [report, setReport] = useState<IngestReport | null>(null)
  const [filter, setFilter] = useState<AssetFilter>('전체')
  // 심층 리포트(크레딧)
  const [deep, setDeep] = useState<MarketDeepReport | null>(null)
  const [deepBusy, setDeepBusy] = useState(false)

  async function runDeepReport() {
    setDeepBusy(true)
    setError(null)
    try {
      const r = await api.marketDeepReport()
      setDeep(r)
      onCreditBalance(r.creditBalance)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '심층 리포트 생성에 실패했습니다.')
    } finally {
      setDeepBusy(false)
    }
  }

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [feed, brief] = await Promise.all([api.marketFeed(60), api.marketBriefing().catch(() => null)])
      setItems(feed)
      setBriefing(brief)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '피드를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  async function remove(id: number) {
    try {
      await api.marketFeedDelete(id)
      setItems((list) => list.filter((it) => it.id !== id))
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '삭제에 실패했습니다.')
    }
  }

  async function ingestNow() {
    setIngesting(true)
    setError(null)
    setReport(null)
    try {
      const r = await api.marketFeedIngest()
      setReport(r)
      await load()
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '수집에 실패했습니다.')
    } finally {
      setIngesting(false)
    }
  }

  const counts = useMemo(() => {
    const c: Record<string, number> = { 전체: items.length }
    for (const it of items) if (it.assetType) c[it.assetType] = (c[it.assetType] ?? 0) + 1
    return c
  }, [items])

  const visible = filter === '전체' ? items : items.filter((it) => it.assetType === filter)

  return (
    <section className="mi">
      <header className="mi-head">
        <div className="mi-head-text">
          <span className="mi-eyebrow">AI MARKET INTELLIGENCE</span>
          <h2 className="mi-title">시장 인텔리전스</h2>
          <p className="mi-sub">매일 자동 수집되는 시장 브리핑과 딜 — 관심 딜은 그 자리에서 AI 언더라이팅으로.</p>
        </div>
        <div className="mi-actions">
          {isAdmin && (
            <button className="btn-primary" onClick={() => void ingestNow()} disabled={ingesting}>
              {ingesting ? '수집 중…' : '지금 수집'}
            </button>
          )}
          <button className="btn-ghost" onClick={() => void load()} disabled={loading}>
            {loading ? '불러오는 중…' : '새로고침'}
          </button>
        </div>
      </header>

      <NewsletterBar onError={setError} />

      {report && (
        <p className="mi-report">
          수집 완료 — 신규 <b>{report.inserted}</b> · 중복 {report.skippedDuplicate} · 분석 {report.fetched}건
          {report.briefingGenerated ? ` · 브리핑 갱신(${report.briefingProvider})` : ' · 브리핑 생략'}
        </p>
      )}
      {error && <p className="form-error">{error}</p>}

      {briefing && <BriefingHero briefing={briefing} />}

      {items.length > 0 && (
        <div className="deep-bar">
          <div className="deep-bar-text">
            <strong>AI 심층 시장 분석</strong>
            <span>무료 브리핑보다 깊은 섹터·모멘텀·액션 리포트 — Claude 기반</span>
          </div>
          <button className="btn-primary" onClick={() => void runDeepReport()} disabled={deepBusy}>
            {deepBusy ? 'AI 분석 중…' : 'AI 심층 분석 · 1크레딧'}
          </button>
        </div>
      )}
      {deep && <DeepReportPanel report={deep} onClose={() => setDeep(null)} />}

      {isAdmin && <AdminFeedForm onCreated={(it) => setItems((list) => [it, ...list])} onError={setError} />}

      <div className="mi-deals-head">
        <h3 className="mi-section-title">오늘의 딜 <span className="mi-count">{items.length}</span></h3>
        <div className="mi-filters" role="tablist" aria-label="자산유형 필터">
          {ASSET_FILTERS.map((f) => (
            <button
              key={f}
              role="tab"
              aria-selected={filter === f}
              className="mi-chip"
              onClick={() => setFilter(f)}
              disabled={f !== '전체' && !counts[f]}
            >
              {f}{counts[f] ? <span className="mi-chip-n">{counts[f]}</span> : null}
            </button>
          ))}
        </div>
      </div>

      {!loading && items.length === 0 && (
        <p className="feed-empty">
          아직 수집된 딜이 없습니다.{isAdmin ? ' ‘지금 수집’을 눌러 시장 데이터를 채우세요.' : ' 곧 자동 수집됩니다.'}
        </p>
      )}
      {!loading && items.length > 0 && visible.length === 0 && (
        <p className="feed-empty">‘{filter}’ 유형의 딜이 없습니다.</p>
      )}

      <div className="feed-grid">
        {visible.map((it) => (
          <FeedCard key={it.id} item={it} isAdmin={isAdmin} onAnalyze={onAnalyzeDeal} onDelete={remove} />
        ))}
      </div>
    </section>
  )
}

/** 마켓 브리핑 — 프리미엄 다크 히어로(헤드라인·전망 + 동향/워치리스트/리스크). */
function BriefingHero({ briefing }: { briefing: MarketBriefing }) {
  const date = briefing.generatedAt
    ? new Date(briefing.generatedAt).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
    : briefing.briefingDate
  return (
    <section className="brief" aria-label="마켓 브리핑">
      <div className="brief-top">
        <span className="brief-badge"><span className="brief-dot" />AI 마켓 브리핑</span>
        <span className="brief-meta">
          {briefing.articleCount ? `${briefing.articleCount}건 분석` : null}
          {date ? ` · ${date}` : null}
        </span>
      </div>
      {briefing.headline && <h3 className="brief-headline">{briefing.headline}</h3>}
      {briefing.outlook && <p className="brief-outlook">{briefing.outlook}</p>}

      <div className="brief-cols">
        {briefing.sections.length > 0 && (
          <div className="brief-block">
            <h4>주요 동향</h4>
            <ul>
              {briefing.sections.map((s, i) => (
                <li key={i}>
                  <strong>{s.topic}</strong>{s.summary ? <span className="brief-li-sum"> {s.summary}</span> : null}
                  {s.impact && <span className="brief-impact">{s.impact}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
        {briefing.watchlist.length > 0 && (
          <div className="brief-block">
            <h4>워치리스트</h4>
            <ul>
              {briefing.watchlist.map((w, i) => (
                <li key={i}><strong>{w.item}</strong>{w.why ? <span className="brief-li-sum"> {w.why}</span> : null}</li>
              ))}
            </ul>
          </div>
        )}
        {briefing.risks.length > 0 && (
          <div className="brief-block">
            <h4>리스크</h4>
            <ul>
              {briefing.risks.map((r, i) => (
                <li key={i}>
                  {r.severity && <span className={`risk-sev ${r.severity.toLowerCase()}`}>{r.severity}</span>}
                  <strong>{r.signal}</strong>{r.mitigation ? <span className="brief-li-sum"> {r.mitigation}</span> : null}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  )
}

/** 무료 메일 구독 바 — 매일 아침 브리핑을 메일로(재방문 유도). */
function NewsletterBar({ onError }: { onError: (m: string | null) => void }) {
  const [subscribed, setSubscribed] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.newsletterStatus().then((s) => setSubscribed(s.subscribed)).catch(() => setSubscribed(false))
  }, [])

  async function toggle() {
    setBusy(true)
    onError(null)
    try {
      const r = subscribed ? await api.newsletterUnsubscribe() : await api.newsletterSubscribe()
      setSubscribed(r.subscribed)
    } catch (err: unknown) {
      onError(err instanceof ApiError ? err.message : '구독 처리에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  if (subscribed === null) return null
  return (
    <div className={`nl-bar${subscribed ? ' on' : ''}`}>
      <div className="nl-text">
        <span className="nl-ico" aria-hidden="true">✉</span>
        <span>{subscribed ? '매일 아침 시장 브리핑을 메일로 받고 있습니다.' : '매일 아침 시장 브리핑을 메일로 받아보세요. (무료)'}</span>
      </div>
      <button className={subscribed ? 'btn-ghost' : 'btn-primary'} onClick={() => void toggle()} disabled={busy}>
        {busy ? '처리 중…' : subscribed ? '구독 중 · 해지' : '메일 구독'}
      </button>
    </div>
  )
}

/** AI 심층 리포트 결과 패널(크레딧 소비 결과). */
function DeepReportPanel({ report, onClose }: { report: MarketDeepReport; onClose: () => void }) {
  return (
    <section className="deep-panel" aria-label="AI 심층 시장 리포트">
      <div className="deep-panel-head">
        <span className="deep-tag">AI 심층 리포트 · {report.provider}</span>
        <button className="deep-close" onClick={onClose} aria-label="닫기">×</button>
      </div>
      {report.headline && <h3 className="deep-headline">{report.headline}</h3>}
      {report.summary && <p className="deep-summary">{report.summary}</p>}
      {report.sections.length > 0 && (
        <div className="deep-sections">
          {report.sections.map((s, i) => (
            <div key={i} className="deep-section">
              <h4>{s.title}</h4>
              <p>{s.body}</p>
            </div>
          ))}
        </div>
      )}
      {report.picks.length > 0 && (
        <div className="deep-picks">
          <h4>주목 포인트</h4>
          <ul>
            {report.picks.map((p, i) => (
              <li key={i}><strong>{p.title}</strong>{p.why ? ` — ${p.why}` : ''}</li>
            ))}
          </ul>
        </div>
      )}
      <p className="deep-disc">{report.disclaimer}</p>
    </section>
  )
}

function FeedCard({
  item, isAdmin, onAnalyze, onDelete,
}: {
  item: MarketFeedItem
  isAdmin: boolean
  onAnalyze: (sourceText: string) => void
  onDelete: (id: number) => void
}) {
  const seed = item.sourceText?.trim() || item.summary?.trim() || item.title
  const dateLabel = item.publishedAt ? new Date(item.publishedAt).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' }) : null
  const sourceLabel = originLabel(item.origin)

  return (
    <article className="feed-card" data-asset={item.assetType ?? ''}>
      <div className="fc-top">
        {item.assetType && <span className="fc-asset">{item.assetType}</span>}
        {item.location && <span className="fc-loc">{item.location}</span>}
      </div>
      <h3 className="fc-title">{item.title}</h3>
      {item.summary && <p className="fc-summary">{item.summary}</p>}
      <div className="fc-foot">
        {sourceLabel && <span className="fc-source">{sourceLabel}</span>}
        {dateLabel && <span className="fc-date">{dateLabel}</span>}
      </div>
      <div className="fc-actions">
        <button className="fc-analyze" onClick={() => onAnalyze(seed)}>이 딜 분석하기 →</button>
        {item.sourceUrl && (
          <a className="fc-link" href={item.sourceUrl} target="_blank" rel="noreferrer" title="원문 보기">원문</a>
        )}
        {isAdmin && (
          <button className="fc-del" onClick={() => onDelete(item.id)} title="삭제">×</button>
        )}
      </div>
    </article>
  )
}

/** 출처 코드 → 짧은 표시 라벨. */
function originLabel(origin: string | null): string | null {
  if (!origin) return null
  if (origin.startsWith('RSS:')) return origin.slice(4)
  if (origin === 'GOOGLE_NEWS') return '구글뉴스'
  if (origin === 'ADMIN') return '직접등록'
  return origin
}

const ASSET_TYPES = ['', '오피스', '물류', '호텔', '리테일']

function AdminFeedForm({
  onCreated, onError,
}: {
  onCreated: (item: MarketFeedItem) => void
  onError: (msg: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState<MarketFeedInput>({ title: '' })
  const [saving, setSaving] = useState(false)

  function set<K extends keyof MarketFeedInput>(key: K, value: MarketFeedInput[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  async function submit() {
    if (!form.title.trim()) { onError('제목은 필수입니다.'); return }
    onError(null)
    setSaving(true)
    try {
      const created = await api.marketFeedCreate({
        title: form.title.trim(),
        summary: form.summary?.trim() || undefined,
        assetType: form.assetType || undefined,
        location: form.location?.trim() || undefined,
        sourceText: form.sourceText?.trim() || undefined,
        sourceUrl: form.sourceUrl?.trim() || undefined,
      })
      onCreated(created)
      setForm({ title: '' })
      setOpen(false)
    } catch (err: unknown) {
      onError(err instanceof ApiError ? err.message : '추가에 실패했습니다.')
    } finally {
      setSaving(false)
    }
  }

  if (!open) {
    return (
      <div className="feed-admin-bar">
        <span className="fa-tag">ADMIN</span>
        <button className="btn-ghost" onClick={() => setOpen(true)}>+ 피드 카드 추가</button>
      </div>
    )
  }

  return (
    <form className="feed-admin-form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
      <div className="fa-row">
        <input placeholder="제목 *" value={form.title} onChange={(e) => set('title', e.target.value)} />
        <select value={form.assetType ?? ''} onChange={(e) => set('assetType', e.target.value)}>
          {ASSET_TYPES.map((t) => <option key={t} value={t}>{t || '자산유형'}</option>)}
        </select>
        <input placeholder="위치(예: 서울 중구)" value={form.location ?? ''} onChange={(e) => set('location', e.target.value)} />
      </div>
      <textarea rows={2} placeholder="요약(카드에 표시)" value={form.summary ?? ''} onChange={(e) => set('summary', e.target.value)} />
      <textarea rows={3} placeholder="딜 원문 — '이 딜 분석하기' 진입 시 AI 추출에 사용" value={form.sourceText ?? ''} onChange={(e) => set('sourceText', e.target.value)} />
      <input placeholder="원문 URL(선택)" value={form.sourceUrl ?? ''} onChange={(e) => set('sourceUrl', e.target.value)} />
      <div className="fa-actions">
        <button type="button" className="btn-link" onClick={() => setOpen(false)}>취소</button>
        <button type="submit" className="btn-primary" disabled={saving || !form.title.trim()}>
          {saving ? '저장 중…' : '카드 추가'}
        </button>
      </div>
    </form>
  )
}
