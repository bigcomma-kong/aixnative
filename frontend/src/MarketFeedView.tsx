import { useEffect, useMemo, useState } from 'react'
import {
  api, ApiError,
  type BriefingHistoryItem,
  type DeepReportHistoryItem,
  type IngestReport, type MarketBriefing, type MarketDeepReport, type MarketFeedItem, type MarketFeedInput,
} from './api'
import { ResultModal } from './ResultModal'

interface MarketFeedViewProps {
  isAdmin: boolean
  /** '이 딜 분석하기' - 카드 원문을 심화 분석(딜 진입)으로 넘긴다. */
  onAnalyzeDeal: (sourceText: string) => void
  /** 심층 리포트(크레딧 소비) 후 잔액 갱신. */
  onCreditBalance: (balance: number) => void
  /** 크레딧 소진(402) 시 중앙 페이월 안내 노출. */
  onNeedCredits: () => void
  /** 분석유형 id → 크레딧 단가(서버 단일 소스). 미로딩 시 숫자 생략. */
  toolCosts?: Record<string, number>
}

const PAGE_SIZE = 60
const ASSET_FILTERS = ['전체', '관심', '오피스', '물류', '호텔', '리테일'] as const
type AssetFilter = (typeof ASSET_FILTERS)[number]

/**
 * 시장 인텔리전스 - 뉴스레터(마켓 브리핑) + 딜 모니터링(카드 피드)을 합친 surface.
 * 브리핑으로 큰 그림을 보고, 자산유형으로 딜을 좁혀 그 자리에서 AI 분석으로 진입.
 * 데이터는 스케줄러가 매일 자동 수집(이력 누적) - 관리자는 즉시 수집/추가/삭제.
 */
export function MarketFeedView({ isAdmin, onAnalyzeDeal, onCreditBalance, onNeedCredits, toolCosts }: MarketFeedViewProps) {
  const deepCost = toolCosts?.['MARKET_DEEP_REPORT']
  const [items, setItems] = useState<MarketFeedItem[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [moreBusy, setMoreBusy] = useState(false)
  const [briefing, setBriefing] = useState<MarketBriefing | null>(null)
  const [briefHistory, setBriefHistory] = useState<BriefingHistoryItem[]>([])
  const [briefOpenId, setBriefOpenId] = useState<number | null>(null)
  const [watched, setWatched] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [ingesting, setIngesting] = useState(false)
  const [report, setReport] = useState<IngestReport | null>(null)
  const [filter, setFilter] = useState<AssetFilter>('전체')
  // 심층 리포트(크레딧)
  const [deep, setDeep] = useState<MarketDeepReport | null>(null)
  const [deepBusy, setDeepBusy] = useState(false)
  const [history, setHistory] = useState<DeepReportHistoryItem[]>([])
  const [openId, setOpenId] = useState<number | null>(null)

  function loadHistory() {
    api.marketDeepReportHistory().then(setHistory).catch(() => setHistory([]))
  }

  async function runDeepReport() {
    setDeepBusy(true)
    setError(null)
    try {
      const r = await api.marketDeepReport()
      setDeep(r)
      setOpenId(null)
      onCreditBalance(r.creditBalance)
      loadHistory()
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) {
        onNeedCredits()
      } else {
        setError(err instanceof ApiError ? err.message : '심층 리포트 생성에 실패했습니다.')
      }
    } finally {
      setDeepBusy(false)
    }
  }

  async function openPastReport(id: number) {
    setError(null)
    try {
      const r = await api.marketDeepReportById(id)
      setDeep(r)
      setOpenId(id)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '리포트를 불러오지 못했습니다.')
    }
  }

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [feed, brief] = await Promise.all([api.marketFeed(PAGE_SIZE, 0), api.marketBriefing().catch(() => null)])
      setItems(feed.items)
      setPage(0)
      setHasMore(feed.hasMore)
      setBriefing(brief)
      setBriefOpenId(null)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '피드를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  async function loadMore() {
    if (moreBusy) return
    setMoreBusy(true)
    setError(null)
    try {
      const next = page + 1
      const feed = await api.marketFeed(PAGE_SIZE, next)
      setItems((list) => [...list, ...feed.items])
      setPage(next)
      setHasMore(feed.hasMore)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '과거 딜을 불러오지 못했습니다.')
    } finally {
      setMoreBusy(false)
    }
  }

  function loadBriefHistory() {
    api.marketBriefingHistory().then(setBriefHistory).catch(() => setBriefHistory([]))
  }

  function loadWatched() {
    api.watchIds().then((ids) => setWatched(new Set(ids))).catch(() => setWatched(new Set()))
  }

  async function toggleWatch(item: MarketFeedItem) {
    const on = watched.has(item.id)
    // 낙관적 업데이트 후 실패 시 롤백.
    setWatched((s) => {
      const next = new Set(s)
      if (on) next.delete(item.id); else next.add(item.id)
      return next
    })
    try {
      if (on) await api.watchRemove(item.id)
      else await api.watchAdd(item.id)
    } catch (err: unknown) {
      setWatched((s) => {
        const next = new Set(s)
        if (on) next.add(item.id); else next.delete(item.id)
        return next
      })
      setError(err instanceof ApiError ? err.message : '관심 딜 처리에 실패했습니다.')
    }
  }

  async function openPastBriefing(id: number) {
    setError(null)
    try {
      const b = await api.marketBriefingById(id)
      setBriefing(b)
      setBriefOpenId(id)
      // 칩이 화면 아래에 있어 매번 top 으로 끌어올리면 "눌렀다 튕김"이 반복됨 →
      // 갱신되는 브리핑(상단)만 필요 시 부드럽게 노출하고, 사용자 위치는 유지.
      requestAnimationFrame(() => {
        document.getElementById('brief-hero')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      })
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '브리핑을 불러오지 못했습니다.')
    }
  }

  useEffect(() => { void load(); loadHistory(); loadBriefHistory(); loadWatched() }, [])

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
    const c: Record<string, number> = { 전체: items.length, 관심: items.filter((it) => watched.has(it.id)).length }
    for (const it of items) if (it.assetType) c[it.assetType] = (c[it.assetType] ?? 0) + 1
    return c
  }, [items, watched])

  const visible =
    filter === '전체' ? items
    : filter === '관심' ? items.filter((it) => watched.has(it.id))
    : items.filter((it) => it.assetType === filter)

  return (
    <section className="mi">
      <header className="mi-head">
        <div className="mi-head-text">
          <span className="mi-eyebrow">AI MARKET INTELLIGENCE</span>
          <h2 className="mi-title">시장 인텔리전스</h2>
          <p className="mi-sub">매일 자동 수집되는 시장 브리핑과 딜 - 관심 딜은 그 자리에서 AI 언더라이팅으로.</p>
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
          수집 완료 - 신규 <b>{report.inserted}</b> · 중복 {report.skippedDuplicate} · 분석 {report.fetched}건
          {report.briefingGenerated ? ` · 브리핑 갱신(${report.briefingProvider})` : ' · 브리핑 생략'}
        </p>
      )}
      {error && <p className="form-error">{error}</p>}

      {briefing && <BriefingHero briefing={briefing} isPast={briefOpenId != null} onLatest={() => void load()} />}

      {briefHistory.length > 1 && (
        <div className="brief-archive">
          <span className="brief-archive-label">지난 브리핑 <span className="arc-count">{briefHistory.length}</span></span>
          <div className="brief-archive-list">
            {briefHistory.map((b) => {
              const active = briefOpenId === b.id || (briefOpenId == null && briefing?.id === b.id)
              return (
                <button
                  key={b.id}
                  className={`brief-archive-chip${active ? ' active' : ''}`}
                  onClick={() => void openPastBriefing(b.id)}
                  title={b.headline ?? '브리핑'}
                >
                  <span className="ba-date">{fmtArchiveDate(b.generatedAt ?? b.briefingDate)}</span>
                  <span className="ba-title">{b.headline ?? '시장 브리핑'}</span>
                </button>
              )
            })}
          </div>
        </div>
      )}

      {(items.length > 0 || history.length > 0) && (
        <div className="deep-zone">
          <div className="deep-bar">
            <div className="deep-bar-text">
              <strong>AI 심층 시장 분석</strong>
              <span>무료 브리핑보다 깊은 섹터·모멘텀·액션 리포트 - Claude 기반</span>
            </div>
            <button className="btn-primary" onClick={() => void runDeepReport()} disabled={deepBusy || items.length === 0}>
              {deepBusy ? 'AI 분석 중…' : `AI 심층 분석 · ${deepCost != null ? `${deepCost}크레딧` : '크레딧'}`}
            </button>
          </div>

          {history.length > 0 && (
            <div className="deep-history">
              <span className="deep-history-label">지난 리포트 <span className="arc-count">{history.length}</span></span>
              <div className="deep-history-list">
                {history.map((h) => (
                  <button
                    key={h.id}
                    className={`deep-history-chip${openId === h.id ? ' active' : ''}`}
                    onClick={() => void openPastReport(h.id)}
                    title={h.headline ?? '심층 시장 분석'}
                  >
                    <span className="dh-date">{fmtHistoryDate(h.generatedAt)}</span>
                    <span className="dh-title">{h.headline ?? '심층 시장 분석'}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {deep && (
        <ResultModal
          run={{
            id: openId ?? 0,
            dealName: deep.headline ?? '심층 시장 분석',
            tool: 'MARKET_DEEP_REPORT',
            status: 'SUCCESS',
            createdAt: openId != null ? (history.find((h) => h.id === openId)?.generatedAt ?? null) : null,
          }}
          result={deep}
          subtitle={`${deep.provider}${openId != null ? ' · 저장된 이력' : ' · 유료 하우스 뷰'}`}
          onClose={() => { setDeep(null); setOpenId(null) }}
        />
      )}

      {deepBusy && (
        <div className="analyze-overlay" role="alertdialog" aria-busy="true" aria-live="assertive" aria-label="심층 리포트 생성 중">
          <div className="analyze-modal">
            <div className="analyze-spinner" aria-hidden="true" />
            <strong className="analyze-modal-title">AI가 시장을 심층 분석 중…</strong>
            <p className="analyze-modal-sub">
              보통 30~60초 걸립니다. 이 창을 닫거나 이동하지 마세요.<br />
              (완료된 리포트는 ‘지난 리포트’에도 저장되니 나중에 다시 볼 수 있습니다.)
            </p>
          </div>
        </div>
      )}

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
              {f === '관심' ? '★ 관심' : f}{counts[f] ? <span className="mi-chip-n">{counts[f]}</span> : null}
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
        <p className="feed-empty">
          {filter === '관심' ? '아직 관심 딜이 없습니다. 카드의 ★ 를 눌러 저장하세요.' : `‘${filter}’ 유형의 딜이 없습니다.`}
        </p>
      )}

      <div className="feed-grid">
        {visible.map((it) => (
          <FeedCard
            key={it.id}
            item={it}
            isAdmin={isAdmin}
            watched={watched.has(it.id)}
            onAnalyze={onAnalyzeDeal}
            onDelete={remove}
            onToggleWatch={toggleWatch}
          />
        ))}
      </div>

      {hasMore && filter === '전체' && (
        <div className="feed-more">
          <button className="btn-ghost" onClick={() => void loadMore()} disabled={moreBusy}>
            {moreBusy ? '불러오는 중…' : '과거 딜 더 보기'}
          </button>
        </div>
      )}
      {hasMore && filter !== '전체' && visible.length > 0 && (
        <p className="feed-more-hint">‘{filter}’ 필터 중 - 과거 딜을 더 보려면 ‘전체’로 전환하세요.</p>
      )}
    </section>
  )
}

/** 마켓 브리핑 - 프리미엄 다크 히어로(헤드라인·전망 + 동향/워치리스트/리스크). */
function BriefingHero({ briefing, isPast = false, onLatest }: { briefing: MarketBriefing; isPast?: boolean; onLatest?: () => void }) {
  const date = briefing.generatedAt
    ? new Date(briefing.generatedAt).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
    : briefing.briefingDate
  return (
    <section className="brief" id="brief-hero" aria-label="마켓 브리핑">
      <div className="brief-top">
        <span className="brief-badge">
          <span className="brief-dot" />{isPast ? '지난 브리핑' : 'AI 마켓 브리핑'}
        </span>
        <span className="brief-meta">
          {briefing.articleCount ? `${briefing.articleCount}건 분석` : null}
          {date ? ` · ${date}` : null}
          {isPast && onLatest && (
            <button type="button" className="brief-latest" onClick={onLatest}>최신으로 →</button>
          )}
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
                <li key={i} className="brief-item">
                  <span className="bi-lead">{s.topic}</span>
                  {s.summary && <span className="bi-sum">{s.summary}</span>}
                  {s.impact && <span className="bi-impact">시사점 · {s.impact}</span>}
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
                <li key={i} className="brief-item">
                  <span className="bi-lead">{w.item}</span>
                  {w.why && <span className="bi-sum">{w.why}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
        {briefing.risks.length > 0 && (
          <div className="brief-block">
            <h4>리스크</h4>
            <ul>
              {briefing.risks.map((r, i) => (
                <li key={i} className="brief-item">
                  <span className="bi-lead">
                    {r.severity && <span className={`risk-sev ${r.severity.toLowerCase()}`}>{r.severity}</span>}
                    {r.signal}
                  </span>
                  {r.mitigation && <span className="bi-sum">대응 · {r.mitigation}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  )
}

/** 무료 메일 구독 바 - 매일 아침 브리핑을 메일로(재방문 유도). */
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

function fmtHistoryDate(s: string | null): string {
  if (!s) return '-'
  return new Date(s).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' })
}

function fmtArchiveDate(s: string | null): string {
  if (!s) return '-'
  return new Date(s).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric', weekday: 'short' })
}

function FeedCard({
  item, isAdmin, watched, onAnalyze, onDelete, onToggleWatch,
}: {
  item: MarketFeedItem
  isAdmin: boolean
  watched: boolean
  onAnalyze: (sourceText: string) => void
  onDelete: (id: number) => void
  onToggleWatch: (item: MarketFeedItem) => void
}) {
  const seed = item.sourceText?.trim() || item.summary?.trim() || item.title
  const dateLabel = item.publishedAt ? new Date(item.publishedAt).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' }) : null
  const sourceLabel = originLabel(item.origin)
  // 자산유형이 분류된 카드만 "딜"로 간주(뉴스·인사이트엔 분석 CTA 대신 원문 보기).
  const isDeal = !!item.assetType

  return (
    <article className="feed-card" data-asset={item.assetType ?? ''}>
      <button
        className={`fc-star${watched ? ' on' : ''}`}
        onClick={() => onToggleWatch(item)}
        aria-pressed={watched}
        aria-label={watched ? '관심 딜 해제' : '관심 딜 저장'}
        title={watched ? '관심 딜 해제' : '관심 딜 저장'}
      >
        {watched ? '★' : '☆'}
      </button>
      <div className="fc-top">
        {isDeal
          ? <span className="fc-asset">{item.assetType}</span>
          : <span className="fc-asset fc-news">뉴스·인사이트</span>}
        {item.location && <span className="fc-loc">{item.location}</span>}
      </div>
      <h3 className="fc-title">{item.title}</h3>
      {item.summary && <p className="fc-summary">{item.summary}</p>}
      <div className="fc-foot">
        {sourceLabel && <span className="fc-source">{sourceLabel}</span>}
        {dateLabel && <span className="fc-date">{dateLabel}</span>}
      </div>
      <div className="fc-actions">
        {isDeal ? (
          <button className="fc-analyze" onClick={() => onAnalyze(seed)}>이 딜 분석하기 →</button>
        ) : item.sourceUrl ? (
          <a className="fc-readmore" href={item.sourceUrl} target="_blank" rel="noreferrer">원문 보기 →</a>
        ) : (
          <span className="fc-news-note">시장 참고 정보</span>
        )}
        {isDeal && item.sourceUrl && (
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
      <textarea rows={3} placeholder="딜 원문 - '이 딜 분석하기' 진입 시 AI 추출에 사용" value={form.sourceText ?? ''} onChange={(e) => set('sourceText', e.target.value)} />
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
