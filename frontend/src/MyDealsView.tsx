import { useEffect, useState } from 'react'
import { api, ApiError, type AnalysisType, type DealStage, type DealSummary, type RunResult, type RunSummary, type UnderwriteInput } from './api'
import { ResultModal } from './ResultModal'

const fmtDate = (s: string | null): string =>
  s ? new Date(s).toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' }) : '-'

/** 완료단계 칩 라벨(= AnalysisType.label) → 단계 타입. 파이프라인 칩만 결과 모달로 열 수 있다. */
const LABEL_TO_TYPE: Record<string, AnalysisType> = {
  '1차 스크리닝': 'SCREENING',
  '시장조사': 'MARKET_STUDY',
  '언더라이팅': 'UNDERWRITING',
  '투심 메모': 'IC_MEMO',
}

interface StageModalState {
  run: RunSummary
  result: RunResult
  request: UnderwriteInput | null
}

/**
 * 내 딜 대시보드 — 내가 분석한 딜을 한 곳에 모아 상태·최근 활동을 보여주고, 단계 결과를 모달로
 * 다시 보거나 보고서로 재진입(리텐션 허브). 서버가 딜명으로 집계한 요약(`/api/underwriting/deals`).
 */
interface MyDealsViewProps {
  /** 딜 카드의 '이어서 분석' — 언더라이팅 탭으로 이동해 그 딜을 자동 로드. */
  onContinue?: (dealName: string) => void
}

export function MyDealsView({ onContinue }: MyDealsViewProps) {
  const [deals, setDeals] = useState<DealSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  // 딜명 → 단계 목록 캐시(칩 클릭 시 1회 로드). 모달 상태 + 로딩 중인 칩 키.
  const [stagesCache, setStagesCache] = useState<Record<string, DealStage[]>>({})
  const [modal, setModal] = useState<StageModalState | null>(null)
  const [loadingChip, setLoadingChip] = useState<string | null>(null)

  useEffect(() => {
    api.myDeals()
      .then(setDeals)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : '딜 목록 조회 실패'))
  }, [])

  async function openReport(anchorRunId: number) {
    setBusyId(anchorRunId); setError(null)
    try {
      const html = await api.reportHtml(anchorRunId)
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '보고서를 불러오지 못했습니다.')
    } finally {
      setBusyId(null)
    }
  }

  /** 런 id 로 결과를 바로 모달에 — 시장 심층 분석·심화 등 파이프라인 아닌 결과 보기용. */
  async function openRunResult(runId: number) {
    setError(null); setBusyId(runId)
    try {
      const detail = await api.run(runId)
      if (!detail.result) { setError('결과를 찾지 못했습니다.'); return }
      setModal({
        run: { id: detail.id, dealName: detail.dealName, tool: detail.tool, status: detail.status, createdAt: detail.createdAt },
        result: detail.result,
        request: detail.request,
      })
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '데이터를 불러오지 못했습니다.')
    } finally {
      setBusyId(null)
    }
  }

  /** 완료단계 칩 클릭 → 해당 단계 결과를 모달로. 단계 목록은 딜별 1회 로드해 캐시. */
  async function openStage(dealName: string, label: string) {
    const type = LABEL_TO_TYPE[label]
    if (!type) return
    setError(null)
    const chipKey = `${dealName}:${label}`
    try {
      let stages = stagesCache[dealName]
      if (!stages) {
        setLoadingChip(chipKey)
        stages = (await api.dealStages(dealName)).stages
        setStagesCache((c) => ({ ...c, [dealName]: stages! }))
      }
      const stage = stages.find((s) => s.analysisType === type)
      if (!stage || !stage.result) {
        setError('해당 단계 결과를 찾지 못했습니다.')
        return
      }
      setModal({
        run: { id: stage.runId, dealName, tool: stage.analysisType, status: 'SUCCESS', createdAt: null },
        result: stage.result,
        request: stage.request,
      })
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '단계 결과를 불러오지 못했습니다.')
    } finally {
      setLoadingChip(null)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">내 딜</span>
          <h1>내가 분석한 딜</h1>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      {deals === null ? (
        <p className="hint">불러오는 중…</p>
      ) : deals.length === 0 ? (
        <div className="card">
          <p className="hint">아직 분석한 딜이 없습니다. 언더라이팅·심화 분석에서 딜을 분석하면 여기에 모입니다.</p>
        </div>
      ) : (
        <div className="deals-grid">
          {deals.map((d) => (
            <div className="deal-card" key={d.dealName}>
              <div className="deal-top">
                {d.assetType && <span className="deal-type">{d.assetType}</span>}
                <span className="deal-date">최근 {fmtDate(d.lastActivityAt)}</span>
              </div>
              <h3 className="deal-name">{d.dealName}</h3>
              {d.location && <div className="deal-loc">📍 {d.location}</div>}
              <div className="deal-stages">
                {d.isMarketReport ? (
                  <span className="deal-stage-chip market">📊 AI 시장 분석</span>
                ) : (
                  <>
                    {d.completedStages.map((s) =>
                      LABEL_TO_TYPE[s] ? (
                        <button
                          key={s}
                          type="button"
                          className="deal-stage-chip clickable"
                          disabled={loadingChip === `${d.dealName}:${s}`}
                          onClick={() => void openStage(d.dealName, s)}
                          title="분석 결과 보기"
                        >
                          {loadingChip === `${d.dealName}:${s}` ? '여는 중…' : s}
                        </button>
                      ) : (
                        <span key={s} className="deal-stage-chip">{s}</span>
                      ),
                    )}
                    {d.advancedCount > 0 && <span className="deal-stage-chip adv">심화 {d.advancedCount}종</span>}
                    {d.completedStages.length === 0 && d.advancedCount === 0 && (
                      <span className="deal-stage-none">완료 단계 없음</span>
                    )}
                  </>
                )}
              </div>
              {!d.isMarketReport && d.completedStages.some((s) => LABEL_TO_TYPE[s]) && (
                <p className="deal-stage-hint">단계를 눌러 분석 결과를 다시 볼 수 있어요.</p>
              )}
              <div className="deal-foot">
                <span className="deal-runs">분석 {d.runCount}건</span>
                <div className="deal-actions">
                  {!d.isMarketReport && onContinue && d.canContinue && (
                    <button type="button" className="btn-ghost btn-xs" onClick={() => onContinue(d.dealName)}>
                      이어서 분석 →
                    </button>
                  )}
                  {d.hasReport ? (
                    <button
                      type="button"
                      className="btn-primary btn-xs"
                      disabled={busyId === d.anchorRunId}
                      onClick={() => void openReport(d.anchorRunId)}
                    >
                      {busyId === d.anchorRunId ? '여는 중…' : '보고서 보기'}
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="btn-primary btn-xs"
                      disabled={busyId === d.anchorRunId}
                      onClick={() => void openRunResult(d.anchorRunId)}
                    >
                      {busyId === d.anchorRunId ? '여는 중…' : '데이터 보기'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {modal && (
        <ResultModal
          run={modal.run}
          result={modal.result}
          request={modal.request}
          onClose={() => setModal(null)}
        />
      )}
    </>
  )
}
