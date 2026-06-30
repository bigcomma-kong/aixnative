/* eslint-disable @typescript-eslint/no-explicit-any */
import type { ReactNode } from 'react'
import type { Analysis, RunSummary } from './api'
import { StageAnalysis } from './StageAnalysis'

/** 파이프라인 4단계 — 결과를 StageAnalysis 공용 렌더러로 표시(표·플래그·매트릭스 포함). */
const PIPELINE_TOOLS = new Set(['SCREENING', 'MARKET_STUDY', 'UNDERWRITING', 'IC_MEMO'])

/** 백엔드 tool 코드 → 한국어 라벨(사용 내역 표시용). */
export const TOOL_LABEL: Record<string, string> = {
  SCREENING: '딜 스크리닝', MARKET_STUDY: '시장 조사', UNDERWRITING: '언더라이팅', IC_MEMO: 'IC 메모',
  UNDERWRITING_GUIDE: '언더라이팅 입력가이드', BUILDING_RESEARCH: '건물 검색(예비 IM)',
  TAX_PRICE_DIAGNOSIS: '세무·가격 진단', BOV_NARRATIVE: '매각 BOV', AM_QUARTERLY: '분기 자산보고',
  HOLD_SELL_REFI: '보유·매각·리파이', DEV_FEASIBILITY: '개발 타당성', MARKET_RESEARCH_DEEP: '심화 시장리서치',
  COUNTERPARTY_DD: '거래상대방 실사', PRICE_FORECAST: '가격 예측', MARKET_DEEP_REPORT: 'AI 심층 시장 리포트',
}
export const toolLabel = (t: string): string => TOOL_LABEL[t] ?? t

/** 입력(request) 필드 한국어 라벨 — 모르는 키는 그대로 노출. */
const INPUT_LABEL: Record<string, string> = {
  dealName: '딜 이름', assetType: '자산유형', location: '위치', notes: '메모',
  askingPriceEok: '매입가(억)', noiEok: 'NOI(억)', ltvPct: 'LTV(%)', loanRatePct: '대출금리(%)',
  exitCapPct: 'Exit Cap(%)', holdYears: '보유(년)', rentGrowthPct: '임대성장(%)',
  focus: '분석 초점', bizNo: '사업자번호', counterpartyName: '상대방', parcelAddress: '지번주소',
  documentText: '문서/딜 텍스트',
}

/** 사용 내역·관리자 → 분석 결과 모달. 도구 무관하게 결과 JSON 을 읽기 좋게 렌더(개발 지식 불필요). */
export function ResultModal({ run, result, request, subtitle, onClose }: {
  run: RunSummary; result: any; request?: any; subtitle?: string; onClose: () => void
}) {
  // 언더라이팅·문서 결과는 핵심 내용이 result.analysis 에, 시장 심층 리포트는 최상위에 있다.
  const a = (result?.analysis ?? result) ?? {}
  const pf = result?.proForma
  const metrics = pf ? pfMetrics(pf) : []
  const facts: { source: string; detail: string }[] = result?.marketFacts ?? []
  const disclaimer: string | undefined = a?.disclaimer ?? result?.disclaimer
  // 파이프라인 단계는 공용 StageAnalysis 로 렌더(인라인 화면과 동일한 표·플래그). 그 외는 기존 제네릭 렌더러.
  const isPipeline = PIPELINE_TOOLS.has(run.tool)

  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="분석 결과" onClick={onClose}>
      <div className="result-modal" onClick={(e) => e.stopPropagation()}>
        <div className="rm-head">
          <div>
            <span className="rm-tool">{toolLabel(run.tool)}{subtitle ? ` · ${subtitle}` : ''}</span>
            <strong className="rm-title">{run.dealName ?? '(이름 없음)'}</strong>
            <span className="rm-date">{run.createdAt ? new Date(run.createdAt).toLocaleString('ko-KR') : ''}</span>
          </div>
          <button className="deep-close" onClick={onClose} aria-label="닫기">×</button>
        </div>

        <div className="rm-body">
          {request && inputRows(request).length > 0 && (
            <div className="rm-input">
              {inputRows(request).map(([k, v]) => (
                <div key={k} className="rm-input-row"><span>{INPUT_LABEL[k] ?? k}</span><b>{v}</b></div>
              ))}
            </div>
          )}

          {metrics.length > 0 && (
            <div className="rm-metrics">
              {metrics.map((m) => (
                <div key={m.label} className="rm-metric"><span>{m.label}</span><b>{m.value}</b></div>
              ))}
            </div>
          )}

          {isPipeline && <StageAnalysis type={run.tool} analysis={a as Analysis} />}

          {!isPipeline && (<>
          {str(a.headline) && <h3 className="rm-h3">{a.headline}</h3>}
          {(str(a.verdict) || str(a.priceVerdict) || str(a.recommendation)) && (
            <div className="rm-verdict">{a.verdict ?? a.priceVerdict ?? a.recommendation}</div>
          )}
          {str(a.recommendation_reason) && <p className="rm-p">{a.recommendation_reason}</p>}
          {str(a.summary) && <p className="rm-p">{a.summary}</p>}
          {str(a.outlook) && <p className="rm-p">{a.outlook}</p>}
          {str(a.priceComment) && <p className="rm-p">{a.priceComment}</p>}
          {str(a.rationale) && <p className="rm-p">{a.rationale}</p>}

          {(a.marketTempLabel || a.marketTempScore != null) && (
            <p className="rm-gauge"><b>시장 온도</b> · {a.marketTempLabel ?? ''}{a.marketTempScore != null ? ` (${a.marketTempScore}/100)` : ''}</p>
          )}

          {arr(a.key_drivers).length > 0 && (
            <Block title="핵심 동인"><ul className="rm-ul">{a.key_drivers.map((d: string, i: number) => <li key={i}>{d}</li>)}</ul></Block>
          )}
          {arr(a.key_risks).length > 0 && (
            <Block title="핵심 리스크"><ul className="rm-ul">{a.key_risks.map((r: any, i: number) => <li key={i}><b>{r.risk}</b>{r.impact ? ` — ${r.impact}` : ''}</li>)}</ul></Block>
          )}

          {arr(a.sectors).length > 0 && (
            <Block title="섹터 스코어보드">
              <table className="rm-table"><tbody>
                {a.sectors.map((s: any, i: number) => (
                  <tr key={i}><td>{s.name}</td><td>{s.stance}</td><td className="num">{s.score ?? ''}</td><td>{s.note}</td></tr>
                ))}
              </tbody></table>
            </Block>
          )}

          {arr(a.scenarios).length > 0 && (
            <Block title="시나리오">
              {a.scenarios.map((s: any, i: number) => (
                <div key={i} className="rm-block"><b>{s.name}</b><p>{s.narrative ?? scenarioLine(s)}</p></div>
              ))}
            </Block>
          )}

          {arr(a.sections).length > 0 && (
            <Block title="분석">
              {a.sections.map((s: any, i: number) => (
                <div key={i} className="rm-block">
                  {str(s.title ?? s.topic) && <h4 className="rm-h4">{s.title ?? s.topic}</h4>}
                  {str(s.body ?? s.text ?? s.summary) && <p>{s.body ?? s.text ?? s.summary}</p>}
                  {arr(s.bullets).length > 0 && <ul className="rm-ul">{s.bullets.map((b: string, j: number) => <li key={j}>{b}</li>)}</ul>}
                  {str(s.impact) && <p className="rm-impact">시사점 · {s.impact}</p>}
                </div>
              ))}
            </Block>
          )}

          {arr(a.picks).length > 0 && (
            <Block title="실행 픽">
              {a.picks.map((p: any, i: number) => (
                <div key={i} className="rm-block">
                  <b>{p.title}</b>{p.conviction ? <span className="rm-tag"> 확신 {p.conviction}</span> : null}
                  {str(p.why) && <p>{p.why}</p>}
                  {str(p.risk) && <p className="rm-risk">리스크 · {p.risk}</p>}
                </div>
              ))}
            </Block>
          )}

          {str(a.contrarian) && <Block title="컨트래리안 뷰"><p className="rm-p">{a.contrarian}</p></Block>}

          {arr(a.guides).length > 0 && (
            <Block title="세무 가이드">
              {a.guides.map((g: any, i: number) => (
                <div key={i} className="rm-block"><b>{g.title}</b>{str(g.detail) && <p>{g.detail}</p>}{str(g.basis) && <p className="rm-impact">근거 · {g.basis}</p>}</div>
              ))}
            </Block>
          )}
          </>)}

          {facts.length > 0 && (
            <Block title="실측·확정 데이터">
              <ul className="rm-ul">{facts.map((f, i) => <li key={i}><b>{f.source}</b> — {f.detail}</li>)}</ul>
            </Block>
          )}

          {disclaimer && <p className="rm-disc">{disclaimer}</p>}
        </div>
      </div>
    </div>
  )
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  return <div className="rm-sec"><h4 className="rm-sec-title">{title}</h4>{children}</div>
}

function str(v: any): v is string { return typeof v === 'string' && v.trim().length > 0 }
function arr(v: any): any[] { return Array.isArray(v) ? v : [] }

/** 입력 객체 → 표시용 [키, 값] 목록. 객체/배열/빈값은 건너뛰고, 긴 텍스트는 자른다. */
function inputRows(req: any): [string, string][] {
  if (!req || typeof req !== 'object') return []
  const out: [string, string][] = []
  for (const [k, v] of Object.entries(req)) {
    if (v == null || v === '') continue
    if (typeof v === 'object') continue
    const s = String(v)
    out.push([k, s.length > 120 ? `${s.slice(0, 120)}…` : s])
  }
  return out
}

function num(v: any, digits: number, unit: string): string {
  return typeof v === 'number' && Number.isFinite(v) ? `${v.toFixed(digits)}${unit}` : '–'
}
function pfMetrics(pf: any): { label: string; value: string }[] {
  const out: { label: string; value: string }[] = []
  if (pf.leveredIrrPct != null) out.push({ label: 'Levered IRR', value: num(pf.leveredIrrPct, 1, '%') })
  if (pf.equityMultiple != null) out.push({ label: 'Equity Multiple', value: num(pf.equityMultiple, 2, '×') })
  if (pf.goingInCapPct != null) out.push({ label: 'Going-in Cap', value: num(pf.goingInCapPct, 1, '%') })
  if (pf.yieldOnCostPct != null) out.push({ label: 'Yield on Cost', value: num(pf.yieldOnCostPct, 1, '%') })
  if (pf.totalInvestEok != null) out.push({ label: '총투자', value: num(pf.totalInvestEok, 0, '억') })
  return out
}
function scenarioLine(s: any): string {
  const parts: string[] = []
  if (s.leveredIrrPct != null) parts.push(`IRR ${s.leveredIrrPct}%`)
  if (s.equityMultiple != null) parts.push(`EM ${s.equityMultiple}×`)
  if (s.minDscr != null) parts.push(`DSCR ${s.minDscr}`)
  return parts.join(' · ')
}
