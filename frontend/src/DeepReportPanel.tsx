import type { MarketDeepReport } from './api'

/** 섹터 스탠스 → 색상 클래스(비중확대=up/비중축소=down/그외=neutral). */
const STANCE_CLASS: Record<string, string> = { '비중확대': 'up', '비중축소': 'down', '중립': 'neutral' }

/**
 * AI 심층 시장 리포트 본문 - 온도 게이지·섹터 스코어보드·시나리오·섹션·실행 픽·컨트래리안.
 * 시장 탭(지난 리포트)과 내 딜(데이터 보기 모달)이 **같은 렌더러**를 공유해 UI 일관성을 보장한다.
 */
export function DeepReportContent({ report }: { report: MarketDeepReport }) {
  const temp = report.marketTempScore
  return (
    <div className="deep-report">
      {report.headline && <h3 className="deep-headline">{report.headline}</h3>}

      {(temp != null || report.marketTempLabel) && (
        <div className="deep-temp">
          <div className="deep-temp-row">
            <span className="deep-temp-label">시장 온도</span>
            {report.marketTempLabel && <span className="deep-temp-tag">{report.marketTempLabel}</span>}
            {temp != null && <span className="deep-temp-score">{temp}<small>/100</small></span>}
          </div>
          {temp != null && (
            <div className="deep-temp-bar"><span style={{ width: `${Math.max(0, Math.min(100, temp))}%` }} /></div>
          )}
        </div>
      )}

      {report.summary && (
        <div className="deep-thesis">
          <span className="deep-thesis-k">핵심 논지</span>
          <p className="deep-summary">{report.summary}</p>
        </div>
      )}

      {report.sectors.length > 0 && (
        <div className="deep-board">
          <h4 className="deep-h">섹터 스코어보드</h4>
          <div className="deep-board-grid">
            {report.sectors.map((s, i) => (
              <div key={i} className={`deep-sector ${STANCE_CLASS[s.stance ?? ''] ?? 'neutral'}`}>
                <div className="ds-top">
                  <strong>{s.name}</strong>
                  {s.stance && <span className="ds-stance">{s.stance}</span>}
                </div>
                {s.score != null && (
                  <div className="ds-bar-row">
                    <div className="ds-bar"><span style={{ width: `${Math.max(0, Math.min(100, s.score))}%` }} /></div>
                    <span className="ds-score">{s.score}</span>
                  </div>
                )}
                {s.note && <p className="ds-note">{s.note}</p>}
              </div>
            ))}
          </div>
        </div>
      )}

      {report.scenarios.length > 0 && (
        <div className="deep-scenarios">
          <h4 className="deep-h">시나리오</h4>
          <div className="deep-scn-grid">
            {report.scenarios.map((s, i) => (
              <div key={i} className="deep-scn">
                <span className="scn-name">{s.name}</span>
                <p>{s.narrative}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {report.sections.length > 0 && (
        <div className="deep-sections">
          {report.sections.map((s, i) => (
            <div key={i} className="deep-section">
              <h4>{s.title}</h4>
              {s.body && <p>{s.body}</p>}
              {s.bullets && s.bullets.length > 0 && (
                <ul className="deep-bullets">{s.bullets.map((b, j) => <li key={j}>{b}</li>)}</ul>
              )}
            </div>
          ))}
        </div>
      )}

      {report.picks.length > 0 && (
        <div className="deep-picks">
          <h4 className="deep-h">실행 픽</h4>
          <div className="deep-pick-list">
            {report.picks.map((p, i) => (
              <div key={i} className="deep-pick">
                <div className="dp-top">
                  <strong>{p.title}</strong>
                  {p.conviction && <span className={`dp-conv c-${p.conviction}`}>확신 {p.conviction}</span>}
                </div>
                {p.why && <p className="dp-why">{p.why}</p>}
                {p.risk && <p className="dp-risk">리스크 · {p.risk}</p>}
              </div>
            ))}
          </div>
        </div>
      )}

      {report.contrarian && (
        <div className="deep-contra">
          <h4 className="deep-h">컨트래리안 뷰</h4>
          <p>{report.contrarian}</p>
        </div>
      )}

      <p className="deep-disc">{report.disclaimer}</p>
    </div>
  )
}
