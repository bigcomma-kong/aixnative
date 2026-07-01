import type { Analysis } from './api'

/** 스크리닝 핵심지표 표 — 키 → (라벨, 단위). 존재하는 값만 렌더. */
const KPI_FIELDS: { key: string; label: string; unit: string }[] = [
  { key: 'asking_price_eok', label: '매입가', unit: '억' },
  { key: 'price_per_pyeong_manwon', label: '평당가', unit: '만원' },
  { key: 'noi_eok', label: 'NOI', unit: '억' },
  { key: 'cap_rate_pct', label: 'Cap Rate', unit: '%' },
  { key: 'occupancy_pct', label: '임대율', unit: '%' },
  { key: 'walt_yr', label: 'WALT', unit: '년' },
  { key: 'top1_tenant_pct', label: '최대임차인 비중', unit: '%' },
  { key: 'loss_to_lease_pct', label: 'Loss-to-Lease', unit: '%' },
  { key: 'opex_ratio_pct', label: 'OPEX 비율', unit: '%' },
]

/** G/Y/R · GREEN/YELLOW/RED 등 신호등 값을 dot 클래스로. */
function signalClass(v: string): string {
  const t = v.trim().toUpperCase()
  if (t.startsWith('G')) return 'go'
  if (t.startsWith('R')) return 'no'
  return 'cond'
}

/** 신뢰도 배지 — HIGH=초록 / MEDIUM=주황 / LOW=빨강. 단계 제목 옆 노출. */
function ConfBadge({ c }: { c: string | null }) {
  if (!c) return null
  const sig = c.toUpperCase() === 'HIGH' ? 'G' : c.toUpperCase() === 'LOW' ? 'R' : 'Y'
  return <span className={`conf-badge ${signalClass(sig)}`}>신뢰도 {c}</span>
}

/** 심각도/발생가능성 → 색상. HIGH·높음=빨강, LOW·낮음=초록, 그 외(MEDIUM·중간)=주황. */
function severityClass(v: string): string {
  const t = v.trim().toUpperCase()
  if (t.startsWith('H') || t.includes('높')) return 'no'
  if (t.startsWith('L') || t.includes('낮')) return 'go'
  return 'cond'
}

/** 심각도 뱃지 — HIGH/MEDIUM/LOW·높음/중간/낮음 을 색상 알약으로. */
function ImpactBadge({ v }: { v?: string | null }) {
  if (!v || v === '-') return null
  return <span className={`sev-badge ${severityClass(v)}`}>{v}</span>
}

/** House View → 색상. Bullish=초록, Bearish=빨강, Neutral=주황. */
function houseViewClass(v: string): string {
  const t = v.trim().toLowerCase()
  if (t.includes('bull') || t.includes('강세') || t.includes('긍정')) return 'go'
  if (t.includes('bear') || t.includes('약세') || t.includes('부정')) return 'no'
  return 'cond'
}

/**
 * 단계별 AI 결과 인라인 렌더. 라이브 분석(UnderwriteView)·이력/관리자 모달(ResultModal) 공용.
 * 프롬프트가 생성하는 표·플래그·매크로를 그대로 화면에 그린다(생성됐는데 버려지던 데이터 복구).
 */
export function StageAnalysis({ type, analysis, provider }: { type?: string; analysis: Analysis; provider?: string }) {
  const a = analysis as unknown as Record<string, unknown>
  const str = (k: string): string | null => (typeof a[k] === 'string' && a[k] ? (a[k] as string) : null)
  const list = (k: string): unknown[] => (Array.isArray(a[k]) ? (a[k] as unknown[]) : [])
  // 문자열/숫자 모두 허용(예: confidence 가 숫자일 수 있음).
  const val = (k: string): string | null => {
    const x = a[k]
    if (x == null || x === '') return null
    return typeof x === 'string' || typeof x === 'number' ? String(x) : null
  }
  const obj = (k: string): Record<string, unknown> | null =>
    a[k] && typeof a[k] === 'object' && !Array.isArray(a[k]) ? (a[k] as Record<string, unknown>) : null
  const cell = (x: unknown): string => (x != null && x !== '' ? String(x) : '-')

  if (type === 'MARKET_STUDY') {
    const assumptions = list('assumption_check')
    const comps = list('comps')
    const fundamentals = list('fundamentals') // 신규: 불릿 배열 / 구버전: 문자열(아래 str 폴백)
    return (
      <section className="ai-block">
        <div className="section-title">시장조사 <ConfBadge c={val('confidence')} /></div>
        {(str('region') || str('house_view')) && (
          <div className="ms-head">
            {str('region') && <span className="ms-region">{str('region')}</span>}
            {str('house_view') && (
              <span className={`hv-badge ${houseViewClass(String(a.house_view))}`}>House View · {str('house_view')}</span>
            )}
          </div>
        )}
        {str('house_view_reason') && (
          <div className="scr-section">
            <div className="section-title">하우스뷰 근거</div>
            <p className="narrative">{str('house_view_reason')}</p>
          </div>
        )}
        {(fundamentals.length > 0 || str('fundamentals')) && (
          <div className="scr-section">
            <div className="section-title">권역 현황</div>
            {fundamentals.length > 0 ? (
              <ul className="bullet-list">{fundamentals.map((f, i) => <li key={i}>{String(f)}</li>)}</ul>
            ) : (
              <p className="narrative">{str('fundamentals')}</p>
            )}
          </div>
        )}

        {assumptions.length > 0 && (
          <div className="scr-section">
            <div className="section-title">가정 검증</div>
            <div className="bench-table" role="table" aria-label="가정 검증">
              {assumptions.map((it, i) => {
                const o = it as Record<string, unknown>
                return (
                  <div key={i} className="bench-row r3" role="row">
                    <span className={`bench-dot ${signalClass(String(o.verdict ?? ''))}`} aria-hidden="true" />
                    <span className="bench-metric">{cell(o.assumption)}</span>
                    <span className="bench-guide">{cell(o.market)}</span>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {comps.length > 0 && (
          <div className="scr-section">
            <div className="section-title">거래 사례</div>
            <table>
              <thead><tr><th>사례</th><th>권역</th><th>평당가(만원)</th><th>Cap(%)</th></tr></thead>
              <tbody>
                {comps.map((it, i) => {
                  const o = it as Record<string, unknown>
                  return (
                    <tr key={i}>
                      <td>{cell(o.name)}</td>
                      <td>{cell(o.region)}</td>
                      <td className="num">{cell(o.price_per_pyeong_manwon)}</td>
                      <td className="num">{cell(o.cap_rate_pct)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        {val('macro') && (
          <div className="scr-section">
            <div className="section-title">매크로</div>
            <p className="narrative">{val('macro')}</p>
          </div>
        )}
        {str('conclusion') && <p className="guideline">{str('conclusion')}</p>}
      </section>
    )
  }

  if (type === 'SCREENING') {
    const points = list('key_points')
    const green = list('green_flags')
    const red = list('red_flags')
    const conditions = list('conditions')
    const benchmarks = list('benchmark_eval')
    const nextSteps = list('next_steps')
    const metrics = obj('metrics')
    const kpis = metrics ? KPI_FIELDS.filter((f) => metrics[f.key] != null && metrics[f.key] !== '') : []
    const ratingClass = (r: string): string => (r === 'GREEN' ? 'go' : r === 'RED' ? 'no' : 'cond')
    return (
      <section className="ai-block">
        <div className="section-title">1차 스크리닝 <ConfBadge c={val('confidence')} /></div>
        {str('verdict') && (
          <Verdict analysis={{ recommendation: str('verdict') ?? undefined, recommendation_reason: str('verdict_reason') ?? undefined }} />
        )}

        {kpis.length > 0 && metrics && (
          <div className="scr-section">
            <div className="section-title">핵심 지표</div>
            <div className="kpi-table">
              {kpis.map((f) => (
                <div key={f.key} className="kpi-cell">
                  <span className="k">{f.label}</span>
                  <span className="v num">{String(metrics[f.key])}<i>{f.unit}</i></span>
                </div>
              ))}
            </div>
          </div>
        )}

        {str('investment_thesis') && (
          <div className="scr-section">
            <div className="section-title">투자 논리</div>
            <p className="narrative">{str('investment_thesis')}</p>
          </div>
        )}

        {/* 추가 핵심 근거 — key_points 불릿 / 구버전 저장분: thesis 문단 폴백 */}
        {points.length > 0 ? (
          <ul className="bullet-list">{points.map((p, i) => <li key={i}>{String(p)}</li>)}</ul>
        ) : !str('investment_thesis') && str('thesis') ? (
          <p className="narrative">{str('thesis')}</p>
        ) : null}

        {benchmarks.length > 0 && (
          <div className="bench-table" role="table" aria-label="지표 점검">
            {benchmarks.map((b, i) => {
              const o = b as Record<string, unknown>
              const rt = String(o.rating ?? '').toUpperCase()
              return (
                <div key={i} className="bench-row" role="row">
                  <span className={`bench-dot ${ratingClass(rt)}`} aria-hidden="true" />
                  <span className="bench-metric">{String(o.metric ?? '')}</span>
                  <span className="bench-val">{o.value != null && o.value !== '' ? String(o.value) : '-'}</span>
                  <span className="bench-guide">{String(o.guideline ?? '')}</span>
                </div>
              )
            })}
          </div>
        )}

        {conditions.length > 0 && (
          <div className="scr-section">
            <div className="section-title">진행 조건</div>
            <ul className="check-list">{conditions.map((c, i) => <li key={i}>{String(c)}</li>)}</ul>
          </div>
        )}

        {green.length > 0 && (
          <div className="scr-section">
            <div className="section-title">Green Flags</div>
            <div className="chips">{green.map((g, i) => <span key={i} className="chip chip-go">{String(g)}</span>)}</div>
          </div>
        )}
        {red.length > 0 && (
          <div className="scr-section">
            <div className="section-title">Red Flags</div>
            {red.map((r, i) => {
              const o = r as Record<string, unknown>
              const verify = cell(o.verify)
              return (
                <div key={i} className="risk">
                  <span className="r-name">{cell(o.flag)}</span>
                  <span className="r-impact"><ImpactBadge v={cell(o.impact)} /></span>
                  {verify !== '-' && <span className="r-verify">검증: {verify}</span>}
                </div>
              )
            })}
          </div>
        )}
        {nextSteps.length > 0 && (
          <div className="scr-section">
            <div className="section-title">다음 단계</div>
            <ul className="check-list">{nextSteps.map((s, i) => <li key={i}>{String(s)}</li>)}</ul>
          </div>
        )}
      </section>
    )
  }

  if (type === 'IC_MEMO') {
    const highlights = list('highlights')
    const conditions = list('conditions')
    const matrix = list('risk_matrix')
    const exec = obj('exec_summary')
    const execRows: { label: string; key: string }[] = [
      { label: '자산', key: 'asset' },
      { label: '매입가', key: 'price' },
      { label: '전략', key: 'strategy' },
      { label: '기대수익', key: 'expected_return' },
      { label: '추천', key: 'recommendation' },
    ]
    return (
      <section className="ai-block">
        <div className="section-title">투심 메모 <ConfBadge c={val('confidence')} /></div>
        {str('thesis') && <p className="narrative">{str('thesis')}</p>}

        {exec && (
          <div className="scr-section">
            <div className="section-title">요약</div>
            <table className="kv-table">
              <tbody>
                {execRows
                  .filter((r) => exec[r.key] != null && exec[r.key] !== '')
                  .map((r) => (
                    <tr key={r.key}>
                      <td className="kv-k">{r.label}</td>
                      <td>{String(exec[r.key])}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}

        {highlights.length > 0 && (
          <div className="scr-section">
            <div className="section-title">투자 하이라이트</div>
            <ul className="bullet-list">{highlights.map((h, i) => <li key={i}>{String(h)}</li>)}</ul>
          </div>
        )}

        {matrix.length > 0 && (
          <div className="scr-section">
            <div className="section-title">리스크 매트릭스</div>
            <table>
              <thead><tr><th>리스크</th><th>발생</th><th>영향</th><th>완화</th></tr></thead>
              <tbody>
                {matrix.map((it, i) => {
                  const o = it as Record<string, unknown>
                  return (
                    <tr key={i}>
                      <td>{cell(o.risk)}</td>
                      <td><ImpactBadge v={cell(o.likelihood)} /></td>
                      <td><ImpactBadge v={cell(o.impact)} /></td>
                      <td>{cell(o.mitigation)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        {conditions.length > 0 && (
          <div className="scr-section">
            <div className="section-title">진행 조건</div>
            <ul className="check-list">{conditions.map((c, i) => <li key={i}>{String(c)}</li>)}</ul>
          </div>
        )}

        {str('lp_alignment') && <p className="guideline">{str('lp_alignment')}</p>}
        {str('recommendation_reason') && <p className="narrative">{str('recommendation_reason')}</p>}
      </section>
    )
  }

  if (type === 'UNDERWRITING') {
    const strengths = list('strengths')
    const drivers = list('key_drivers')
    const risks = list('key_risks')
    // 신규 스캔형 스키마(thesis·strengths·downside) 유무. 없으면 구버전(summary 블록) 폴백.
    const hasStructured = str('thesis') != null || strengths.length > 0 || str('downside') != null
    if (!hasStructured) return <AiNarrative analysis={analysis} provider={provider} />
    return (
      <section className="ai-block">
        <div className="section-title">언더라이팅 결론 <ConfBadge c={val('confidence')} /></div>
        {str('thesis') && <p className="narrative">{str('thesis')}</p>}

        {strengths.length > 0 && (
          <div className="scr-section">
            <div className="section-title">강점</div>
            <ul className="check-list">{strengths.map((s, i) => <li key={i}>{String(s)}</li>)}</ul>
          </div>
        )}

        {drivers.length > 0 && (
          <div className="scr-section">
            <div className="section-title">수익 민감 변수</div>
            <ul className="bullet-list">{drivers.map((d, i) => <li key={i}>{String(d)}</li>)}</ul>
          </div>
        )}

        {risks.length > 0 && (
          <div className="scr-section">
            <div className="section-title">리스크</div>
            {risks.map((r, i) => {
              const o = r as Record<string, unknown>
              return (
                <div key={i} className="risk">
                  <span className="r-name">{cell(o.risk)}</span>
                  <span className="r-impact"><ImpactBadge v={cell(o.impact)} /></span>
                </div>
              )
            })}
          </div>
        )}

        {str('downside') && (
          <div className="uw-downside">
            <span className="uw-downside-tag">⚠ 하방 시나리오</span>
            <p>{str('downside')}</p>
          </div>
        )}
      </section>
    )
  }

  // 기타/레거시
  return <AiNarrative analysis={analysis} provider={provider} />
}

interface VerdictStyle { cls: 'go' | 'cond' | 'no'; mark: string; label: string }
function verdictStyle(rec?: string): VerdictStyle {
  if (rec === 'GO') return { cls: 'go', mark: 'GO', label: '투자 적격' }
  if (rec === 'NO_GO') return { cls: 'no', mark: 'NO', label: '투자 부적격' }
  return { cls: 'cond', mark: '!', label: '조건부 검토' }
}

export function Verdict({ analysis }: { analysis: Analysis }) {
  if (!analysis.recommendation) return null
  const s = verdictStyle(analysis.recommendation)
  return (
    <div className={`verdict ${s.cls}`}>
      <div className="v-mark">{s.mark}</div>
      <div>
        <div className="v-label">{s.label}</div>
        {analysis.recommendation_reason && <div className="v-reason">{analysis.recommendation_reason}</div>}
      </div>
    </div>
  )
}

function AiNarrative({ analysis, provider }: { analysis: Analysis; provider?: string }) {
  return (
    <section className="ai-block">
      <div className="section-title">AI 언더라이팅 {provider ? `· ${provider}` : ''}</div>
      {analysis.summary && <p className="narrative">{analysis.summary}</p>}
      {analysis.guideline_check && <p className="guideline">{analysis.guideline_check}</p>}
      {analysis.key_drivers && analysis.key_drivers.length > 0 && (
        <div>
          <div className="section-title">주요 동인</div>
          <div className="chips">{analysis.key_drivers.map((d, i) => <span key={i} className="chip">{d}</span>)}</div>
        </div>
      )}
      {analysis.key_risks && analysis.key_risks.length > 0 && (
        <div>
          <div className="section-title">리스크</div>
          {analysis.key_risks.map((r, i) => (
            <div key={i} className="risk"><span className="r-name">{r.risk}</span><span className="r-impact"><ImpactBadge v={r.impact} /></span></div>
          ))}
        </div>
      )}
    </section>
  )
}
