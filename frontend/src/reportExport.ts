import type { MarketDeepReport } from './api'

/** HTML 특수문자 이스케이프(인젝션·깨짐 방지). */
function esc(s: string | null | undefined): string {
  if (!s) return ''
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 심층 리포트를 단독 실행 가능한 HTML 문서로 직렬화(Word/PDF 공통 소스). */
export function deepReportHtml(r: MarketDeepReport): string {
  const sectors = r.sectors.map((s) =>
    `<tr><td>${esc(s.name)}</td><td>${esc(s.stance)}</td><td style="text-align:right">${s.score ?? ''}</td><td>${esc(s.note)}</td></tr>`,
  ).join('')
  const scenarios = r.scenarios.map((s) =>
    `<div class="blk"><b>${esc(s.name)}</b><p>${esc(s.narrative)}</p></div>`,
  ).join('')
  const sections = r.sections.map((s) =>
    `<div class="blk"><h3>${esc(s.title)}</h3><p>${esc(s.body)}</p></div>`,
  ).join('')
  const picks = r.picks.map((p) =>
    `<li><b>${esc(p.title)}</b>${p.conviction ? ` <span class="tag">확신 ${esc(p.conviction)}</span>` : ''}` +
    `${p.why ? `<br/>${esc(p.why)}` : ''}${p.risk ? `<br/><i>리스크 · ${esc(p.risk)}</i>` : ''}</li>`,
  ).join('')

  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>${esc(r.headline) || 'AI 심층 시장 리포트'}</title>
<style>
  body { font-family: 'Malgun Gothic','맑은 고딕',sans-serif; color:#1a1a1a; line-height:1.55; max-width:840px; margin:32px auto; padding:0 24px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .meta { color:#777; font-size:12px; margin-bottom:18px; }
  h2 { font-size:15px; border-bottom:2px solid #222; padding-bottom:4px; margin:26px 0 12px; }
  h3 { font-size:13px; margin:0 0 4px; color:#0b5; }
  .summary { background:#f6f7f9; padding:14px 16px; border-radius:8px; }
  table { width:100%; border-collapse:collapse; font-size:13px; }
  th,td { border:1px solid #ddd; padding:6px 8px; text-align:left; }
  th { background:#f2f2f2; }
  .blk { margin:0 0 12px; }
  .tag { font-size:11px; background:#eef; color:#225; padding:1px 6px; border-radius:8px; }
  ul { padding-left:18px; }
  .gauge { font-size:13px; margin:8px 0 0; }
  .disc { color:#999; font-size:11px; margin-top:28px; border-top:1px solid #eee; padding-top:10px; }
  @media print { body { margin:0; } }
</style></head><body>
  <h1>${esc(r.headline) || 'AI 심층 시장 리포트'}</h1>
  <div class="meta">AI 심층 시장 리포트 · ${esc(r.provider)} · aixnative</div>
  ${(r.marketTempScore != null || r.marketTempLabel)
    ? `<p class="gauge"><b>시장 온도</b> · ${esc(r.marketTempLabel)}${r.marketTempScore != null ? ` (${r.marketTempScore}/100)` : ''}</p>` : ''}
  ${r.summary ? `<div class="summary">${esc(r.summary)}</div>` : ''}
  ${sectors ? `<h2>섹터 스코어보드</h2><table><thead><tr><th>섹터</th><th>스탠스</th><th>점수</th><th>근거</th></tr></thead><tbody>${sectors}</tbody></table>` : ''}
  ${scenarios ? `<h2>시나리오</h2>${scenarios}` : ''}
  ${sections ? `<h2>분석</h2>${sections}` : ''}
  ${picks ? `<h2>실행 픽</h2><ul>${picks}</ul>` : ''}
  ${r.contrarian ? `<h2>컨트래리안 뷰</h2><p>${esc(r.contrarian)}</p>` : ''}
  <p class="disc">${esc(r.disclaimer)}</p>
</body></html>`
}

function safeName(headline: string | null): string {
  const base = (headline ?? '시장심층리포트').replace(/[\\/:*?"<>|]/g, '').slice(0, 40).trim()
  return base || 'market-deep-report'
}

/** Word(.doc) 다운로드 — HTML 을 application/msword 로 저장(Word 가 HTML 을 연다). */
export function downloadDeepReportDoc(r: MarketDeepReport): void {
  const blob = new Blob(['﻿', deepReportHtml(r)], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${safeName(r.headline)}.doc`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** PDF 저장 — 새 창에 리포트를 띄우고 인쇄 대화상자(사용자가 'PDF로 저장' 선택). */
export function printDeepReport(r: MarketDeepReport): void {
  const w = window.open('', '_blank', 'width=900,height=1000')
  if (!w) return
  w.document.write(deepReportHtml(r))
  w.document.close()
  w.focus()
  // 렌더 후 인쇄.
  setTimeout(() => w.print(), 300)
}
