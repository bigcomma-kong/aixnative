import type { DocAnalyzeResponse, MarketDeepReport, ProForma, Scenario, UnderwriteInput } from './api'

/** HTML 특수문자 이스케이프(인젝션·깨짐 방지). */
function esc(s: string | null | undefined): string {
  if (!s) return ''
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function fileSafe(name: string): string {
  const base = name.replace(/[\\/:*?"<>|]/g, '').slice(0, 40).trim()
  return base || 'aixnative-report'
}

function downloadBlob(content: string, mime: string, filename: string): void {
  const blob = new Blob(['﻿', content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * 언더라이팅 ProForma 모델을 Excel(.xls) 로 export - 실무 재가공용.
 * HTML 테이블을 application/vnd.ms-excel 로 저장(Excel 이 HTML 표를 연다, 외부 의존성 없음).
 * 수치는 원시값으로 담아 셀에서 바로 재계산 가능.
 */
export function downloadUnderwritingXls(
  p: ProForma, scenarios: Scenario[], inputs?: UnderwriteInput | null, dealName?: string | null,
): void {
  const name = dealName || inputs?.dealName || '언더라이팅'
  const md = p.proForma.map((r) => r.dscr).filter((d) => Number.isFinite(d) && d > 0)
  const minDscr = md.length ? Math.min(...md) : null

  const inputRows = [
    ['자산유형', inputs?.assetType ?? ''],
    ['위치', inputs?.location ?? ''],
    ['매입가(억)', inputs?.askingPriceEok ?? ''],
    ['NOI(억)', inputs?.noiEok ?? ''],
    ['LTV(%)', inputs?.ltvPct ?? ''],
    ['대출금리(%)', inputs?.loanRatePct ?? ''],
    ['Exit Cap(%)', inputs?.exitCapPct ?? ''],
    ['보유기간(년)', inputs?.holdYears ?? ''],
    ['임대성장률(%)', inputs?.rentGrowthPct ?? ''],
  ].map(([k, v]) => `<tr><th>${esc(String(k))}</th><td>${esc(String(v))}</td></tr>`).join('')

  const structRows = [
    ['총투자비(억)', p.totalInvestEok], ['대출(억)', p.debtEok], ['자기자본(억)', p.equityEok],
    ['연이자(억)', p.annualInterestEok], ['Levered IRR(%)', p.leveredIrrPct], ['Equity Multiple(x)', p.equityMultiple],
    ['Going-in Cap(%)', p.goingInCapPct], ['Yield-on-Cost(%)', p.yieldOnCostPct],
    ['최소 DSCR', minDscr ?? '-'], ['Exit Value(억)', p.exitValueEok],
  ].map(([k, v]) => `<tr><th>${esc(String(k))}</th><td>${esc(String(v))}</td></tr>`).join('')

  const yearRows = p.proForma.map((r) =>
    `<tr><td>Y${r.year}</td><td>${r.noi}</td><td>${r.interest}</td><td>${r.leveredCf}</td><td>${r.dscr}</td><td>${r.cocPct}</td></tr>`,
  ).join('')

  const scnRows = scenarios.map((s) =>
    `<tr><td>${esc(s.name)}</td><td>${s.rentGrowthPct}</td><td>${s.exitCapPct}</td><td>${s.leveredIrrPct}</td><td>${s.equityMultiple}</td><td>${s.minDscr}</td></tr>`,
  ).join('')

  const html = `<!doctype html><html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">
<head><meta charset="utf-8">
<style>table{border-collapse:collapse;font-family:'Malgun Gothic',sans-serif;font-size:12px;margin-bottom:16px}
th,td{border:1px solid #bbb;padding:4px 8px;text-align:right}th{background:#eef;text-align:left}
h2{font-size:13px;margin:14px 0 6px}</style></head><body>
  <h1>${esc(name)} · 언더라이팅 모델</h1>
  <p>aixnative · 수치는 결정론적 ProForma 계산값</p>
  <h2>입력 가정</h2><table>${inputRows}</table>
  <h2>매입구조·핵심지표</h2><table>${structRows}</table>
  <h2>연차별 운영</h2><table><tr><th>연차</th><th>NOI</th><th>이자</th><th>Levered CF</th><th>DSCR</th><th>CoC%</th></tr>${yearRows}</table>
  <h2>시나리오</h2><table><tr><th>케이스</th><th>임대성장%</th><th>Exit Cap%</th><th>IRR%</th><th>EM(x)</th><th>최소 DSCR</th></tr>${scnRows}</table>
</body></html>`

  downloadBlob(html, 'application/vnd.ms-excel', `${fileSafe(name)}-model.xls`)
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

/* ── 심화분석(BOV·개발·세무 등) export - 단독 실행 HTML(PDF/Word 공통 소스) ── */

function humanizeKey(k: string): string {
  return k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ')
    .replace(/\bEok\b/i, '(억)').replace(/\bPct\b/i, '(%)').replace(/\bKrw\b/i, '(원)')
    .trim().replace(/^\w/, (c) => c.toUpperCase())
}

/** calc(코드 확정 수치) 객체를 라벨-값 표로. 유형별 union 이라 키를 일반 humanize. */
function calcTable(calc: Record<string, unknown> | null | undefined): string {
  if (!calc) return ''
  const rows = Object.entries(calc)
    .filter(([, v]) => (typeof v === 'number' && Number.isFinite(v)) || (typeof v === 'string' && v !== ''))
    .map(([k, v]) => `<tr><th>${esc(humanizeKey(k))}</th><td>${esc(String(v))}</td></tr>`).join('')
  return rows ? `<h2>코드 확정 수치</h2><table>${rows}</table>` : ''
}

/** 심화분석 결과를 단독 HTML 로. 화면 DocResult 와 동일 정보(표·불릿·플래그·출처). */
export function docAnalysisHtml(res: DocAnalyzeResponse, label: string): string {
  const a = res.analysis
  const facts = (res.marketFacts ?? []).map((f) => `<li><b>${esc(f.source)}</b> - ${esc(f.detail)}</li>`).join('')
  const flags = (a?.flags ?? []).map((f) => `<li><b>${esc(f.label)}</b>${f.severity ? ` [${esc(f.severity)}]` : ''}</li>`).join('')
  const guides = (a?.guides ?? []).map((g) =>
    `<div class="blk"><b>${g.kind ? `[${esc(g.kind)}] ` : ''}${esc(g.title)}</b>${g.impact ? ` [${esc(g.impact)}]` : ''}` +
    `${g.detail ? `<br/>${esc(g.detail)}` : ''}${g.basis ? `<br/><i>근거 · ${esc(g.basis)}</i>` : ''}</div>`).join('')
  const sections = (a?.sections ?? []).map((s) => {
    const body = s.text ? `<p>${esc(s.text)}</p>` : ''
    const bullets = s.bullets?.length ? `<ul>${s.bullets.map((b) => `<li>${esc(b)}</li>`).join('')}</ul>` : ''
    const table = s.table?.headers
      ? `<table><thead><tr>${s.table.headers.map((h) => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>` +
        `${s.table.rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>` : ''
    return `<div class="blk"><h3>${esc(s.title)}</h3>${body}${bullets}${table}</div>`
  }).join('')
  const recommend = a?.recommend
    ? `<h2>권장 입력 가정</h2><table>${Object.entries(a.recommend).map(([k, v]) => `<tr><th>${esc(humanizeKey(k))}</th><td>${esc(String(v))}</td></tr>`).join('')}</table>` : ''

  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>${esc(a?.headline) || esc(label)}</title>
<style>
  body{font-family:'Malgun Gothic','맑은 고딕',sans-serif;color:#1a1a1a;line-height:1.55;max-width:840px;margin:32px auto;padding:0 24px}
  h1{font-size:22px;margin:0 0 4px} .meta{color:#777;font-size:12px;margin-bottom:18px}
  h2{font-size:15px;border-bottom:2px solid #222;padding-bottom:4px;margin:26px 0 12px}
  h3{font-size:13px;margin:0 0 4px;color:#0b5}
  .verdict{display:inline-block;font-weight:700;background:#eef;color:#225;padding:4px 12px;border-radius:8px;margin:6px 0 14px}
  table{width:100%;border-collapse:collapse;font-size:13px;margin-bottom:12px}
  th,td{border:1px solid #ddd;padding:6px 8px;text-align:left} th{background:#f2f2f2}
  .blk{margin:0 0 12px} ul{padding-left:18px} i{color:#666}
  .disc{color:#999;font-size:11px;margin-top:28px;border-top:1px solid #eee;padding-top:10px}
  @media print{body{margin:0}}
</style></head><body>
  <h1>${esc(a?.headline) || esc(label)}</h1>
  <div class="meta">${esc(label)} · ${esc(res.provider)} · aixnative · 생성 ${new Date().toLocaleString('ko-KR')}</div>
  ${a?.verdict ? `<div class="verdict">${esc(a.verdict)}${a.confidence ? ` · 신뢰도 ${esc(a.confidence)}` : ''}</div>` : ''}
  ${a?.priceComment ? `<p>${esc(a.priceComment)}</p>` : ''}
  ${flags ? `<h2>주요 플래그</h2><ul>${flags}</ul>` : ''}
  ${calcTable(res.calc as Record<string, unknown> | null)}
  ${recommend}
  ${a?.rationale ? `<p>${esc(a.rationale)}</p>` : ''}
  ${guides ? `<h2>진단</h2>${guides}` : ''}
  ${a?.im_markdown ? `<h2>보고</h2><pre style="white-space:pre-wrap;font-family:inherit">${esc(a.im_markdown)}</pre>` : ''}
  ${sections ? `<h2>분석</h2>${sections}` : ''}
  ${facts ? `<h2>실측·확정 데이터</h2><ul>${facts}</ul>` : ''}
  <p class="disc">데이터 출처 - 한국은행 ECOS · 국토교통부 RTMS · 한국부동산원 R-ONE · V-World. ${esc(a?.disclaimer ?? res.disclaimer)}</p>
</body></html>`
}

/** 심화분석 PDF 저장(인쇄). */
export function printDocAnalysis(res: DocAnalyzeResponse, label: string): void {
  const w = window.open('', '_blank', 'width=900,height=1000')
  if (!w) return
  w.document.write(docAnalysisHtml(res, label))
  w.document.close()
  w.focus()
  setTimeout(() => w.print(), 300)
}

/** 심화분석 Word(.doc) 다운로드. */
export function downloadDocAnalysisDoc(res: DocAnalyzeResponse, label: string): void {
  downloadBlob(docAnalysisHtml(res, label), 'application/msword', `${fileSafe(a_title(res, label))}.doc`)
}

function a_title(res: DocAnalyzeResponse, label: string): string {
  return res.analysis?.headline || label
}

function safeName(headline: string | null): string {
  const base = (headline ?? '시장심층리포트').replace(/[\\/:*?"<>|]/g, '').slice(0, 40).trim()
  return base || 'market-deep-report'
}

/** Word(.doc) 다운로드 - HTML 을 application/msword 로 저장(Word 가 HTML 을 연다). */
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

/** PDF 저장 - 새 창에 리포트를 띄우고 인쇄 대화상자(사용자가 'PDF로 저장' 선택). */
export function printDeepReport(r: MarketDeepReport): void {
  const w = window.open('', '_blank', 'width=900,height=1000')
  if (!w) return
  w.document.write(deepReportHtml(r))
  w.document.close()
  w.focus()
  // 렌더 후 인쇄.
  setTimeout(() => w.print(), 300)
}
