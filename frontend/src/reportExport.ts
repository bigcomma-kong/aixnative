import type {
  DocAnalyzeResponse, MarketDeepReport, PmAmReport, PmCalendarEvent, PmLease, PmRentRoll,
  ProForma, Scenario, UnderwriteInput,
} from './api'

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
  const rows = p.proForma ?? []
  const scns = scenarios ?? []
  const sens = p.exitCapSensitivity ?? []
  const md = rows.map((r) => r.dscr).filter((d) => Number.isFinite(d) && d > 0)
  const minDscr = md.length ? Math.min(...md) : null

  // 셀에서 재계산 가능하도록 원시 수치 유지(소수 2자리 정리). 값 없으면 '-'.
  const n = (v: number | null | undefined): string =>
    v == null || !Number.isFinite(v) ? '-' : String(Math.round(v * 100) / 100)

  // 파생값(모델 원시필드에서 계산) - 매각비용·대출상환·자본비율.
  const sellingCostEok = Number.isFinite(p.exitValueEok) && Number.isFinite(p.netSaleEok)
    ? p.exitValueEok - p.netSaleEok : null
  const loanPayoffEok = Number.isFinite(p.netSaleEok) && Number.isFinite(p.exitEquityEok)
    ? p.netSaleEok - p.exitEquityEok : null
  const debtRatio = p.totalInvestEok ? (p.debtEok / p.totalInvestEok) * 100 : null
  const equityRatio = p.totalInvestEok ? (p.equityEok / p.totalInvestEok) * 100 : null

  const kv = (kvRows: Array<[string, string | number]>): string =>
    kvRows.map(([k, v]) => `<tr><th>${esc(String(k))}</th><td>${esc(String(v))}</td></tr>`).join('')

  const inputRows = kv([
    ['자산유형', inputs?.assetType ?? ''],
    ['위치', inputs?.location ?? ''],
    ['매입가(억)', inputs?.askingPriceEok ?? ''],
    ['NOI(억)', inputs?.noiEok ?? ''],
    ['LTV(%)', inputs?.ltvPct ?? ''],
    ['대출금리(%)', inputs?.loanRatePct ?? ''],
    ['Exit Cap(%)', inputs?.exitCapPct ?? ''],
    ['보유기간(년)', inputs?.holdYears ?? ''],
    ['임대성장률(%)', inputs?.rentGrowthPct ?? ''],
  ])

  // 2. 자본구조 (Sources / Uses)
  const sourcesRows = kv([
    ['대출(억)', `${n(p.debtEok)}  (${n(debtRatio)}%)`],
    ['자기자본(억)', `${n(p.equityEok)}  (${n(equityRatio)}%)`],
    ['합계 · 총투자비(억)', n(p.totalInvestEok)],
  ])

  // 3. 핵심 지표 (Unlevered IRR 포함)
  const metricRows = kv([
    ['Going-in Cap(%)', n(p.goingInCapPct)],
    ['Yield-on-Cost(%)', n(p.yieldOnCostPct)],
    ['연이자(억)', n(p.annualInterestEok)],
    ['최소 DSCR', minDscr == null ? '-' : n(minDscr)],
    ['Unlevered IRR(%)', n(p.unleveredIrrPct)],
    ['Levered IRR(%)', n(p.leveredIrrPct)],
    ['Equity Multiple(x)', n(p.equityMultiple)],
  ])

  // 4. 매각(Exit) 정산 워터폴
  const exitRows = kv([
    ['Exit NOI(억)', n(p.exitNoiEok)],
    ['Exit Cap(%)', n(p.exitCapPct)],
    ['매각가(억)', n(p.exitValueEok)],
    ['(-) 매각비용(억)', n(sellingCostEok)],
    ['순매각액(억)', n(p.netSaleEok)],
    ['(-) 대출 상환(억)', n(loanPayoffEok)],
    ['지분 회수(억)', n(p.exitEquityEok)],
  ])

  // 5. 연차별 운영 (CapEx 열 포함)
  const yearRows = rows.map((r) =>
    `<tr><td>Y${r.year}</td><td>${n(r.noi)}</td><td>${n(r.capex)}</td><td>${n(r.interest)}</td>` +
    `<td>${n(r.leveredCf)}</td><td>${n(r.dscr)}</td><td>${n(r.cocPct)}</td></tr>`,
  ).join('')

  // 6. 지분 현금흐름 타임라인 (IRR 근거): Y0 = -자기자본, 보유말 = 해당연도 CF + 지분회수
  const lastYear = rows.length ? rows[rows.length - 1].year : 0
  let cum = -p.equityEok
  const eqLines = [`<tr><td>Y0</td><td>${n(-p.equityEok)}</td><td>${n(cum)}</td></tr>`]
  rows.forEach((r) => {
    const cf = r.leveredCf + (r.year === lastYear ? p.exitEquityEok : 0)
    cum += cf
    eqLines.push(`<tr><td>Y${r.year}</td><td>${n(cf)}</td><td>${n(cum)}</td></tr>`)
  })
  const equityFlowRows = eqLines.join('')

  // 7. Exit Cap 민감도
  const sensRows = sens.map((s) =>
    `<tr><td>${n(s.exitCapPct)}</td><td>${n(s.saleValueEok)}</td><td>${n(s.leveredIrrPct)}</td><td>${n(s.em)}</td></tr>`,
  ).join('')

  // 8. 시나리오
  const scnRows = scns.map((s) =>
    `<tr><td>${esc(s.name)}</td><td>${n(s.rentGrowthPct)}</td><td>${n(s.exitCapPct)}</td>` +
    `<td>${n(s.leveredIrrPct)}</td><td>${n(s.equityMultiple)}</td><td>${n(s.minDscr)}</td></tr>`,
  ).join('')

  const html = `<!doctype html><html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">
<head><meta charset="utf-8">
<style>table{border-collapse:collapse;font-family:'Malgun Gothic',sans-serif;font-size:12px;margin-bottom:18px}
th,td{border:1px solid #bbb;padding:4px 8px;text-align:right}th{background:#eef;text-align:left}
h1{font-size:16px;margin:0 0 4px}h2{font-size:13px;margin:16px 0 6px;color:#223}
.note{font-size:11px;color:#666;margin:4px 0 0}</style></head><body>
  <h1>${esc(name)} · 언더라이팅 모델</h1>
  <p class="note">aixnative · 결정론적 ProForma 계산값 (수치는 원시값 - 셀에서 바로 재계산 가능)</p>
  <h2>1. 입력 가정</h2><table>${inputRows}</table>
  <h2>2. 자본구조 (Sources / Uses)</h2><table>${sourcesRows}</table>
  <h2>3. 핵심 지표</h2><table>${metricRows}</table>
  <h2>4. 매각(Exit) 정산</h2><table>${exitRows}</table>
  <h2>5. 연차별 운영</h2><table><tr><th>연차</th><th>NOI</th><th>CapEx</th><th>이자</th><th>Levered CF</th><th>DSCR</th><th>CoC%</th></tr>${yearRows}</table>
  <h2>6. 지분 현금흐름 (IRR 근거)</h2><table><tr><th>연차</th><th>지분 현금흐름</th><th>누적</th></tr>${equityFlowRows}</table>
  <p class="note">Y0 = 자기자본 유출(-), 보유기간 말 = 해당연도 CF + 지분 회수. Excel 에서 =IRR(지분 현금흐름 열) 로 Levered IRR 재현.</p>
  <h2>7. Exit Cap 민감도</h2><table><tr><th>Exit Cap%</th><th>매각가(억)</th><th>Levered IRR%</th><th>EM(x)</th></tr>${sensRows}</table>
  <h2>8. 시나리오</h2><table><tr><th>케이스</th><th>임대성장%</th><th>Exit Cap%</th><th>IRR%</th><th>EM(x)</th><th>최소 DSCR</th></tr>${scnRows}</table>
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
  <div class="meta">AI 심층 시장 리포트 · aixnative</div>
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
  <div class="meta">${esc(label)} · aixnative · 생성 ${new Date().toLocaleString('ko-KR')}</div>
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

/* ── 자산관리(PM) AM 제출 보고서 export - 렌트롤 + 일정 + 리스크 + (선택)AI 서술 ── */

const EVENT_LABEL_KO: Record<string, string> = {
  EXPIRY: '만기', ESCALATION: '임대료 인상', RENT_FREE_END: '렌트프리 종료',
}
const numOr = (v: number | null | undefined): string => (v == null ? '-' : v.toLocaleString('ko-KR'))
const dateOr = (s: string | null | undefined): string => s ?? '-'

/** 렌트롤·일정·리스크(+선택 AI 서술)를 단독 HTML 로. AM 제출용 문서(PDF/Word 공통 소스). */
export function leaseReportHtml(rr: PmRentRoll, events: PmCalendarEvent[], am?: PmAmReport | null): string {
  const summaryRows = [
    ['임차 건수', `${rr.leaseCount}건`],
    ['총 월임대료(만원)', numOr(rr.totalMonthlyRentManwon)],
    ['연 환산 임대료(만원)', numOr(rr.annualRentManwon)],
    ['총 보증금(만원)', numOr(rr.totalDepositManwon)],
    ['총 월관리비(만원)', numOr(rr.totalMgmtFeeManwon)],
    ...(rr.waltYears != null ? [['WALT(가중평균 잔여기간, 년)', String(rr.waltYears)]] : []),
    ...(rr.topTenantName != null ? [['최대 임차인', `${rr.topTenantName} (${rr.topTenantPct ?? '-'}%)`]] : []),
    ...(rr.avgRentPerPyeongManwon != null ? [['평당 월임대료(만원/평)', String(rr.avgRentPerPyeongManwon)]] : []),
  ].map(([k, v]) => `<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join('')

  const leaseRows = rr.leases.map((l: PmLease) =>
    `<tr><td>${esc(l.tenantName)}</td><td>${esc(l.unitLabel)}</td><td style="text-align:right">${numOr(l.areaPyeong)}</td>` +
    `<td style="text-align:right">${numOr(l.monthlyRentManwon)}</td><td style="text-align:right">${numOr(l.depositManwon)}</td>` +
    `<td>${dateOr(l.leaseStartDate)}</td><td>${dateOr(l.leaseEndDate)}</td><td>${esc(l.status)}</td></tr>`,
  ).join('')

  const eventRows = events.map((e) =>
    `<tr><td>${dateOr(e.dueDate)}</td><td>D-${e.daysUntil}</td><td>${esc(EVENT_LABEL_KO[e.eventType] ?? e.eventType)}</td><td>${esc(e.tenantName)}</td></tr>`,
  ).join('')

  const flagRows = rr.flags.map((f) => `<li><b>[${esc(f.severity)}]</b> ${esc(f.label)}</li>`).join('')

  const a = am?.analysis
  const aiSections = (a?.sections ?? []).map((s) => {
    const body = s.text ? `<p>${esc(s.text)}</p>` : ''
    const bullets = s.bullets?.length ? `<ul>${s.bullets.map((b) => `<li>${esc(b)}</li>`).join('')}</ul>` : ''
    const table = s.table?.headers
      ? `<table><thead><tr>${s.table.headers.map((h) => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>` +
        `${s.table.rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>` : ''
    return `<div class="blk"><h3>${esc(s.title)}</h3>${body}${bullets}${table}</div>`
  }).join('')

  return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>${esc(rr.buildingName)} · 임대 관리 보고서</title>
<style>
  body{font-family:'Malgun Gothic','맑은 고딕',sans-serif;color:#1a1a1a;line-height:1.55;max-width:860px;margin:32px auto;padding:0 24px}
  h1{font-size:22px;margin:0 0 4px} .meta{color:#777;font-size:12px;margin-bottom:18px}
  h2{font-size:15px;border-bottom:2px solid #222;padding-bottom:4px;margin:26px 0 12px}
  h3{font-size:13px;margin:0 0 4px;color:#0b5}
  .verdict{display:inline-block;font-weight:700;background:#eef;color:#225;padding:4px 12px;border-radius:8px;margin:6px 0 14px}
  table{width:100%;border-collapse:collapse;font-size:13px;margin-bottom:12px}
  th,td{border:1px solid #ddd;padding:6px 8px;text-align:left} th{background:#f2f2f2}
  .blk{margin:0 0 12px} ul{padding-left:18px}
  .disc{color:#999;font-size:11px;margin-top:28px;border-top:1px solid #eee;padding-top:10px}
  @media print{body{margin:0}}
</style></head><body>
  <h1>${esc(rr.buildingName)} · 임대 관리 보고서</h1>
  <div class="meta">AM 제출용 · aixnative 자산관리(PM) · 생성 ${new Date().toLocaleString('ko-KR')}</div>
  ${a?.verdict ? `<div class="verdict">${esc(a.verdict)}${a.confidence ? ` · 신뢰도 ${esc(a.confidence)}` : ''}</div>` : ''}
  ${a?.headline ? `<p><b>${esc(a.headline)}</b></p>` : ''}
  <h2>임대 현황 요약</h2><table>${summaryRows}</table>
  <h2>렌트롤</h2><table><thead><tr><th>임차인</th><th>층/호</th><th>면적(평)</th><th>월임대료(만원)</th><th>보증금(만원)</th><th>시작</th><th>만기</th><th>상태</th></tr></thead><tbody>${leaseRows}</tbody></table>
  ${eventRows ? `<h2>다가오는 일정</h2><table><thead><tr><th>일자</th><th>D-day</th><th>이벤트</th><th>임차인</th></tr></thead><tbody>${eventRows}</tbody></table>` : ''}
  ${flagRows ? `<h2>리스크</h2><ul>${flagRows}</ul>` : ''}
  ${aiSections ? `<h2>AI 분석</h2>${aiSections}` : ''}
  <p class="disc">본 보고서는 입력된 임대차 데이터의 코드 집계와 AI 서술로 구성됩니다. ${esc(am?.disclaimer ?? '투자자문이 아니며 참고용입니다.')}</p>
</body></html>`
}

/** AM 보고서 PDF 저장(인쇄). */
export function printLeaseReport(rr: PmRentRoll, events: PmCalendarEvent[], am?: PmAmReport | null): void {
  const w = window.open('', '_blank', 'width=900,height=1000')
  if (!w) return
  w.document.write(leaseReportHtml(rr, events, am))
  w.document.close()
  w.focus()
  setTimeout(() => w.print(), 300)
}

/** AM 보고서 Word(.doc) 다운로드. */
export function downloadLeaseReportDoc(rr: PmRentRoll, events: PmCalendarEvent[], am?: PmAmReport | null): void {
  downloadBlob(leaseReportHtml(rr, events, am), 'application/msword', `${fileSafe(rr.buildingName)}-임대관리.doc`)
}

/** 렌트롤을 Excel(.xls) 로 export - 실무 재가공용(임차인별 명세). */
export function downloadRentRollXls(rr: PmRentRoll): void {
  const rows = rr.leases.map((l) =>
    `<tr><td>${esc(l.tenantName)}</td><td>${esc(l.unitLabel)}</td><td>${l.areaPyeong ?? ''}</td><td>${l.monthlyRentManwon ?? ''}</td>` +
    `<td>${l.depositManwon ?? ''}</td><td>${l.mgmtFeeManwon ?? ''}</td><td>${dateOr(l.leaseStartDate)}</td><td>${dateOr(l.leaseEndDate)}</td>` +
    `<td>${l.escalationPct ?? ''}</td><td>${esc(l.status)}</td></tr>`,
  ).join('')
  const html = `<!doctype html><html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">
<head><meta charset="utf-8">
<style>table{border-collapse:collapse;font-family:'Malgun Gothic',sans-serif;font-size:12px}
th,td{border:1px solid #bbb;padding:4px 8px;text-align:right}th{background:#eef;text-align:left}</style></head><body>
  <h1>${esc(rr.buildingName)} · 렌트롤</h1>
  <table><tr><th>임차인</th><th>층/호</th><th>면적(평)</th><th>월임대료(만원)</th><th>보증금(만원)</th><th>관리비(만원)</th><th>시작</th><th>만기</th><th>인상률(%)</th><th>상태</th></tr>${rows}</table>
</body></html>`
  downloadBlob(html, 'application/vnd.ms-excel', `${fileSafe(rr.buildingName)}-rentroll.xls`)
}
