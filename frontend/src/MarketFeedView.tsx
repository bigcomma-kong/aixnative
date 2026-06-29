import { useEffect, useState } from 'react'
import {
  api, ApiError,
  type IngestReport, type MarketBriefing, type MarketFeedItem, type MarketFeedInput,
} from './api'

interface MarketFeedViewProps {
  isAdmin: boolean
  /** '이 딜 분석하기' — 카드 원문을 심화 분석(딜 진입)으로 넘긴다. */
  onAnalyzeDeal: (sourceText: string) => void
}

/**
 * 시장 인텔리전스 — 뉴스레터(마켓 브리핑) + 딜 모니터링(카드 피드)을 합친 surface.
 * 사용자는 브리핑으로 큰 그림을 보고, 카드를 훑어 관심 딜을 바로 AI 분석으로 진입.
 * 데이터는 스케줄러가 자동 수집(무료) — 관리자는 즉시 수집/카드 추가/삭제 가능.
 */
export function MarketFeedView({ isAdmin, onAnalyzeDeal }: MarketFeedViewProps) {
  const [items, setItems] = useState<MarketFeedItem[]>([])
  const [briefing, setBriefing] = useState<MarketBriefing | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [ingesting, setIngesting] = useState(false)
  const [report, setReport] = useState<IngestReport | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [feed, brief] = await Promise.all([api.marketFeed(), api.marketBriefing().catch(() => null)])
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

  return (
    <section className="feed-wrap">
      <div className="feed-head">
        <div>
          <h2 className="feed-title">시장 인텔리전스</h2>
          <p className="feed-sub">AI 마켓 브리핑으로 큰 그림을 보고, 딜 카드는 그 자리에서 AI 언더라이팅으로 진입하세요.</p>
        </div>
        <div className="feed-head-actions">
          {isAdmin && (
            <button className="btn-primary" onClick={() => void ingestNow()} disabled={ingesting}>
              {ingesting ? '수집 중…' : '지금 수집'}
            </button>
          )}
          <button className="btn-ghost" onClick={() => void load()} disabled={loading}>
            {loading ? '불러오는 중…' : '새로고침'}
          </button>
        </div>
      </div>

      {report && (
        <p className="ingest-report">
          수집 완료 — 신규 {report.inserted}건 · 중복 {report.skippedDuplicate}건 · 수집 {report.fetched}건
          {report.briefingGenerated ? ` · 브리핑 갱신(${report.briefingProvider})` : ' · 브리핑 생략(무료 AI 미설정)'}
        </p>
      )}

      {briefing && <BriefingHero briefing={briefing} />}

      {isAdmin && <AdminFeedForm onCreated={(it) => setItems((list) => [it, ...list])} onError={setError} />}

      {error && <p className="form-error">{error}</p>}

      {!loading && items.length === 0 && (
        <p className="feed-empty">
          아직 수집된 딜이 없습니다.{isAdmin ? ' ‘지금 수집’을 눌러 시장 데이터를 채우세요.' : ' 곧 자동 수집됩니다.'}
        </p>
      )}

      <div className="feed-grid">
        {items.map((it) => (
          <FeedCard key={it.id} item={it} isAdmin={isAdmin} onAnalyze={onAnalyzeDeal} onDelete={remove} />
        ))}
      </div>
    </section>
  )
}

/** 마켓 브리핑 히어로 — 헤드라인·전망 + 토픽/워치리스트/리스크 요약(뉴스레터 강점). */
function BriefingHero({ briefing }: { briefing: MarketBriefing }) {
  const date = briefing.generatedAt ? new Date(briefing.generatedAt).toLocaleString('ko-KR') : briefing.briefingDate
  return (
    <section className="briefing" aria-label="마켓 브리핑">
      <div className="briefing-top">
        <span className="briefing-tag">AI 마켓 브리핑</span>
        {date && <span className="briefing-date">{date}</span>}
      </div>
      {briefing.headline && <h3 className="briefing-headline">{briefing.headline}</h3>}
      {briefing.outlook && <p className="briefing-outlook">{briefing.outlook}</p>}

      <div className="briefing-cols">
        {briefing.sections.length > 0 && (
          <div className="briefing-block">
            <h4>주요 동향</h4>
            <ul>
              {briefing.sections.map((s, i) => (
                <li key={i}>
                  <strong>{s.topic}</strong>{s.summary ? ` — ${s.summary}` : ''}
                  {s.impact && <span className="briefing-impact"> · {s.impact}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
        {briefing.watchlist.length > 0 && (
          <div className="briefing-block">
            <h4>워치리스트</h4>
            <ul>
              {briefing.watchlist.map((w, i) => (
                <li key={i}><strong>{w.item}</strong>{w.why ? ` — ${w.why}` : ''}</li>
              ))}
            </ul>
          </div>
        )}
        {briefing.risks.length > 0 && (
          <div className="briefing-block">
            <h4>리스크</h4>
            <ul>
              {briefing.risks.map((r, i) => (
                <li key={i}>
                  {r.severity && <span className={`risk-sev ${r.severity.toLowerCase()}`}>{r.severity}</span>}
                  <strong>{r.signal}</strong>{r.mitigation ? ` — ${r.mitigation}` : ''}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
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
  const dateLabel = item.publishedAt ? new Date(item.publishedAt).toLocaleDateString('ko-KR') : null
  const sourceLabel = originLabel(item.origin)

  return (
    <article className="feed-card">
      <div className="fc-meta">
        {item.assetType && <span className="fc-chip">{item.assetType}</span>}
        {item.location && <span className="fc-chip muted">{item.location}</span>}
        {sourceLabel && <span className="fc-source">{sourceLabel}</span>}
        {dateLabel && <span className="fc-date">{dateLabel}</span>}
      </div>
      <h3 className="fc-title">{item.title}</h3>
      {item.summary && <p className="fc-summary">{item.summary}</p>}
      <div className="fc-actions">
        <button className="btn-primary fc-analyze" onClick={() => onAnalyze(seed)}>
          이 딜 분석하기 →
        </button>
        {item.sourceUrl && (
          <a className="btn-link" href={item.sourceUrl} target="_blank" rel="noreferrer">원문</a>
        )}
        {isAdmin && (
          <button className="btn-link danger" onClick={() => onDelete(item.id)}>삭제</button>
        )}
      </div>
    </article>
  )
}

/** 출처 코드 → 짧은 표시 라벨. 'RSS:한국경제'→'한국경제', 'GOOGLE_NEWS'→'구글뉴스', 'ADMIN'→'직접등록'. */
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
