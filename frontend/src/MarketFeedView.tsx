import { useEffect, useState } from 'react'
import { api, ApiError, type MarketFeedItem, type MarketFeedInput } from './api'

interface MarketFeedViewProps {
  isAdmin: boolean
  /** '이 딜 분석하기' — 카드 원문을 심화 분석(딜 진입)으로 넘긴다. */
  onAnalyzeDeal: (sourceText: string) => void
}

/**
 * 시장 인텔리전스 피드 — 큐레이션된 거래/시장 다이제스트.
 * 사용자는 카드를 훑고 관심 딜을 'AI로 분석'으로 바로 진입. 관리자는 카드를 추가/삭제.
 */
export function MarketFeedView({ isAdmin, onAnalyzeDeal }: MarketFeedViewProps) {
  const [items, setItems] = useState<MarketFeedItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      setItems(await api.marketFeed())
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

  return (
    <section className="feed-wrap">
      <div className="feed-head">
        <div>
          <h2 className="feed-title">시장 인텔리전스</h2>
          <p className="feed-sub">최신 거래·시장 동향을 훑고, 관심 딜은 그 자리에서 AI 언더라이팅으로 진입하세요.</p>
        </div>
        <button className="btn-ghost" onClick={() => void load()} disabled={loading}>
          {loading ? '불러오는 중…' : '새로고침'}
        </button>
      </div>

      {isAdmin && <AdminFeedForm onCreated={(it) => setItems((list) => [it, ...list])} onError={setError} />}

      {error && <p className="form-error">{error}</p>}

      {!loading && items.length === 0 && (
        <p className="feed-empty">아직 등록된 피드가 없습니다.{isAdmin ? ' 위에서 첫 카드를 추가하세요.' : ''}</p>
      )}

      <div className="feed-grid">
        {items.map((it) => (
          <FeedCard key={it.id} item={it} isAdmin={isAdmin} onAnalyze={onAnalyzeDeal} onDelete={remove} />
        ))}
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

  return (
    <article className="feed-card">
      <div className="fc-meta">
        {item.assetType && <span className="fc-chip">{item.assetType}</span>}
        {item.location && <span className="fc-chip muted">{item.location}</span>}
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
