import { useState } from 'react'
import { api, ApiError, type AfterTaxResult, type HoldingCostResponse, type TaxCalcResponse, type TransferTaxResult, type AcquisitionTaxResult } from './api'

type Calc = 'acq' | 'transfer' | 'aftertax' | 'holding'

const EOK = 100_000_000

/** "12.5" 억 입력 → 원. 비었거나 숫자가 아니면 0. */
function eokToWon(v: string): number {
  const n = Number(v.replace(/,/g, ''))
  return Number.isFinite(n) ? Math.round(n * EOK) : 0
}

function won(v?: number | null): string {
  if (v == null) return '-'
  if (Math.abs(v) >= EOK) return `${(v / EOK).toLocaleString(undefined, { maximumFractionDigits: 2 })}억원`
  return `${Math.round(v).toLocaleString()}원`
}

/**
 * 무료 계산기 - 취득세·양도세·세후 수익률·보유세. 전부 코드 계산(AI·크레딧 미사용).
 * 결과 아래에 기준 연도와 "간이 계산" 안내를 항상 붙인다.
 */
export function CalculatorsPanel() {
  const [tab, setTab] = useState<Calc>('acq')
  return (
    <section className="calc-section" aria-labelledby="calc-title">
      <div className="page-head">
        <div>
          <span className="eyebrow">FREE CALCULATORS</span>
          <h2 id="calc-title">세금·보유세 계산기</h2>
          <p className="page-sub">상업용 부동산 취득세·양도세·세후 수익률·보유세를 코드로 바로 계산합니다. 크레딧을 쓰지 않습니다.</p>
        </div>
      </div>
      <div className="card">
        <div className="seg" role="group" aria-label="계산기 종류">
          <button type="button" aria-pressed={tab === 'acq'} onClick={() => setTab('acq')}>취득세</button>
          <button type="button" aria-pressed={tab === 'transfer'} onClick={() => setTab('transfer')}>양도세</button>
          <button type="button" aria-pressed={tab === 'aftertax'} onClick={() => setTab('aftertax')}>세후 수익률</button>
          <button type="button" aria-pressed={tab === 'holding'} onClick={() => setTab('holding')}>보유세</button>
        </div>
        {tab === 'acq' && <AcquisitionCalc />}
        {tab === 'transfer' && <TransferCalc />}
        {tab === 'aftertax' && <AfterTaxCalc />}
        {tab === 'holding' && <HoldingCalc />}
      </div>
    </section>
  )
}

function useCalc<T>() {
  const [result, setResult] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  async function run(call: () => Promise<T>) {
    setBusy(true); setError(null)
    try { setResult(await call()) } catch (e: unknown) {
      setResult(null)
      setError(e instanceof ApiError ? e.message : '계산에 실패했습니다.')
    } finally { setBusy(false) }
  }
  return { result, error, busy, run }
}

function Field({ id, label, value, onChange, hint }: { id: string; label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      <input id={id} inputMode="decimal" value={value} onChange={(e) => onChange(e.target.value)} />
      {hint && <span className="field-hint">{hint}</span>}
    </div>
  )
}

function Basis({ r }: { r: { basis: string; disclaimer: string } }) {
  return <p className="field-hint calc-basis">{r.basis} · {r.disclaimer}</p>
}

function AcquisitionCalc() {
  const [price, setPrice] = useState('100')
  const [type, setType] = useState('COMMERCIAL')
  const c = useCalc<TaxCalcResponse<AcquisitionTaxResult>>()
  return (
    <form className="calc-form" onSubmit={(e) => { e.preventDefault(); void c.run(() => api.calcAcquisitionTax(eokToWon(price), type)) }}>
      <div className="form-grid">
        <Field id="acqPrice" label="취득가액(억원)" value={price} onChange={setPrice} />
        <div>
          <label htmlFor="acqType">유형</label>
          <select id="acqType" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="COMMERCIAL">비주거(상가·오피스·토지)</option>
            <option value="FARMLAND">농지</option>
            <option value="NEW_BUILD">신축(원시취득)</option>
          </select>
        </div>
      </div>
      <button type="submit" className="btn-primary" disabled={c.busy}>계산</button>
      {c.error && <p className="error">{c.error}</p>}
      {c.result && (
        <>
          <dl className="kv-grid">
            <dt>취득세</dt><dd>{won(c.result.result.acquisitionTaxWon)}</dd>
            <dt>지방교육세</dt><dd>{won(c.result.result.eduTaxWon)}</dd>
            <dt>농어촌특별세</dt><dd>{won(c.result.result.ruralTaxWon)}</dd>
            <dt>합계</dt><dd><b>{won(c.result.result.totalWon)}</b> (실효 {c.result.result.effectivePct}%)</dd>
          </dl>
          <Basis r={c.result} />
        </>
      )}
    </form>
  )
}

function TransferCalc() {
  const [buy, setBuy] = useState('100')
  const [sell, setSell] = useState('130')
  const [cost, setCost] = useState('5')
  const [hold, setHold] = useState('5')
  const c = useCalc<TaxCalcResponse<TransferTaxResult>>()
  return (
    <form className="calc-form" onSubmit={(e) => { e.preventDefault(); void c.run(() => api.calcTransferTax(eokToWon(buy), eokToWon(sell), eokToWon(cost), Number(hold) || 0)) }}>
      <div className="form-grid">
        <Field id="trBuy" label="취득가액(억원)" value={buy} onChange={setBuy} />
        <Field id="trSell" label="양도가액(억원)" value={sell} onChange={setSell} />
        <Field id="trCost" label="필요경비(억원)" value={cost} onChange={setCost} hint="취득세·중개수수료·자본적 지출 등" />
        <Field id="trHold" label="보유기간(년)" value={hold} onChange={setHold} />
      </div>
      <button type="submit" className="btn-primary" disabled={c.busy}>계산</button>
      {c.error && <p className="error">{c.error}</p>}
      {c.result && (() => {
        const r = c.result.result
        return (
          <>
            <dl className="kv-grid">
              <dt>양도차익</dt><dd>{won(r.gainWon)}</dd>
              <dt>장기보유특별공제</dt><dd>{r.longTermDeductionPct}% · {won(r.longTermDeductionWon)}</dd>
              <dt>과세표준</dt><dd>{won(r.taxBaseWon)}</dd>
              <dt>세율</dt><dd>{r.rateLabel}</dd>
              <dt>양도소득세 + 지방소득세</dt><dd><b>{won(r.totalWon)}</b> (차익 대비 {r.effectivePctOfGain}%)</dd>
            </dl>
            <p className="field-hint">개인 기준. {r.notes.join(' ')}</p>
            <Basis r={c.result} />
          </>
        )
      })()}
    </form>
  )
}

function AfterTaxCalc() {
  const [f, setF] = useState({ buy: '100', equity: '50', noi: '5', interest: '2', sell: '120', hold: '5', corp: 'Y' })
  const set = (k: keyof typeof f) => (v: string) => setF((s) => ({ ...s, [k]: v }))
  const c = useCalc<TaxCalcResponse<AfterTaxResult>>()
  return (
    <form className="calc-form" onSubmit={(e) => {
      e.preventDefault()
      void c.run(() => api.calcAfterTax({
        purchaseWon: eokToWon(f.buy), equityWon: eokToWon(f.equity), annualNoiWon: eokToWon(f.noi),
        annualInterestWon: eokToWon(f.interest), saleWon: eokToWon(f.sell), holdYears: Number(f.hold) || 5, corporate: f.corp === 'Y',
      }))
    }}>
      <div className="form-grid">
        <Field id="atBuy" label="매입가(억원)" value={f.buy} onChange={set('buy')} />
        <Field id="atEq" label="자기자본(억원)" value={f.equity} onChange={set('equity')} />
        <Field id="atNoi" label="연 NOI(억원)" value={f.noi} onChange={set('noi')} />
        <Field id="atInt" label="연 이자(억원)" value={f.interest} onChange={set('interest')} />
        <Field id="atSell" label="예상 매각가(억원)" value={f.sell} onChange={set('sell')} />
        <Field id="atHold" label="보유기간(년)" value={f.hold} onChange={set('hold')} />
        <div>
          <label htmlFor="atCorp">보유 주체</label>
          <select id="atCorp" value={f.corp} onChange={(e) => set('corp')(e.target.value)}>
            <option value="Y">법인</option>
            <option value="N">개인</option>
          </select>
        </div>
      </div>
      <button type="submit" className="btn-primary" disabled={c.busy}>계산</button>
      {c.error && <p className="error">{c.error}</p>}
      {c.result && (() => {
        const r = c.result.result
        return (
          <>
            <div className="calc-hero">
              <div><span>세전 IRR</span><b>{r.irrPreTaxPct ?? '-'}%</b></div>
              <div className="calc-hero-main"><span>세후 IRR</span><b>{r.irrAfterTaxPct ?? '-'}%</b></div>
              <div><span>세금 Drag</span><b className="neg">-{r.taxDragPct ?? '-'}%p</b></div>
            </div>
            <dl className="kv-grid">
              <dt>연 세전 소득</dt><dd>{won(r.annualPreTaxIncomeWon)}</dd>
              <dt>연 운영 세금</dt><dd>{won(r.annualTaxWon)}</dd>
              <dt>Cash-on-Cash</dt><dd>세전 {r.cashOnCashPreTaxPct}% / 세후 {r.cashOnCashAfterTaxPct}%</dd>
              <dt>매각 시 세금</dt><dd>{won(r.exitTaxWon)}</dd>
              <dt>총 세금(보유+매각)</dt><dd><b>{won(r.totalTaxWon)}</b></dd>
            </dl>
            <p className="field-hint">{r.entity} 기준. {r.notes.join(' ')}</p>
            <Basis r={c.result} />
          </>
        )
      })()}
    </form>
  )
}

function HoldingCalc() {
  const [address, setAddress] = useState('')
  const [manual, setManual] = useState({ price: '', land: '', floor: '', year: '' })
  const c = useCalc<HoldingCostResponse>()
  const num = (v: string) => { const n = Number(v.replace(/,/g, '')); return Number.isFinite(n) && n > 0 ? n : undefined }
  return (
    <form className="calc-form" onSubmit={(e) => {
      e.preventDefault()
      void c.run(() => api.calcHoldingCost({
        address: address.trim() || undefined,
        landPricePerSqm: num(manual.price), landAreaSqm: num(manual.land), totalFloorAreaSqm: num(manual.floor), buildYear: num(manual.year),
      }))
    }}>
      <div className="form-grid">
        <div className="full">
          <label htmlFor="hcAddr">지번 주소 <span className="opt">(주소로 공시지가·건축물대장 자동 조회)</span></label>
          <input id="hcAddr" value={address} onChange={(e) => setAddress(e.target.value)} placeholder="예: 서울 강남구 역삼동 737" />
        </div>
        <Field id="hcPrice" label="개별공시지가(원/㎡, 선택)" value={manual.price} onChange={(v) => setManual((s) => ({ ...s, price: v }))} />
        <Field id="hcLand" label="대지면적(㎡, 선택)" value={manual.land} onChange={(v) => setManual((s) => ({ ...s, land: v }))} />
        <Field id="hcFloor" label="연면적(㎡, 선택)" value={manual.floor} onChange={(v) => setManual((s) => ({ ...s, floor: v }))} />
        <Field id="hcYear" label="준공연도(선택)" value={manual.year} onChange={(v) => setManual((s) => ({ ...s, year: v }))} />
      </div>
      <button type="submit" className="btn-primary" disabled={c.busy}>{c.busy ? '조회·계산 중…' : '계산'}</button>
      {c.error && <p className="error">{c.error}</p>}
      {c.result && (() => {
        const r = c.result.result
        return (
          <>
            <div className="calc-hero"><div className="calc-hero-main"><span>연간 보유세 추정</span><b>{won(r.annualTotalWon)}</b></div></div>
            <dl className="kv-grid">
              {r.land && (<><dt>토지분 재산세</dt><dd>{won(r.land.totalWon)} (과표 {won(r.land.taxBaseWon)})</dd></>)}
              {r.building && (<><dt>건물분 재산세</dt><dd>{won(r.building.totalWon)} (시가표준액 {won(r.building.stdValueWon)}, 경과 {r.building.ageYears}년)</dd></>)}
              {r.jongbu && (<><dt>종합부동산세</dt><dd>{r.jongbu.applicable ? won(r.jongbu.totalWon) : `비대상(공시가격 ${won(r.jongbu.publicValueWon)} ≤ 80억)`}</dd></>)}
            </dl>
            {c.result.autoFilled.length > 0 && <p className="field-hint">자동 조회: {c.result.autoFilled.join(', ')}</p>}
            <ul className="bullet-list calc-assume">{r.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
          </>
        )
      })()}
    </form>
  )
}
