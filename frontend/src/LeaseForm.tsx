import { useState } from 'react'
import { api, ApiError, type PmLease, type PmLeaseExtract, type PmLeaseInput } from './api'

interface LeaseFormProps {
  buildingId: number
  /** 수정 모드면 기존 임대차. 생성 모드면 undefined. */
  initial?: PmLease
  onSaved: (lease: PmLease) => void
  onCancel: () => void
  onCreditBalance: (balance: number) => void
  onNeedCredits: () => void
  /** 계약서 추출 크레딧 단가(라벨용). */
  extractCost?: number
}

/** 폼 필드 값(문자열) 묶음. 날짜는 <input type=date> 의 yyyy-MM-dd. */
type Fields = {
  tenantName: string
  unitLabel: string
  areaPyeong: string
  monthlyRentManwon: string
  depositManwon: string
  mgmtFeeManwon: string
  leaseStartDate: string
  leaseEndDate: string
  rentFreeMonths: string
  escalationPct: string
  nextEscalationDate: string
  notes: string
}

const EMPTY: Fields = {
  tenantName: '', unitLabel: '', areaPyeong: '', monthlyRentManwon: '', depositManwon: '',
  mgmtFeeManwon: '', leaseStartDate: '', leaseEndDate: '', rentFreeMonths: '', escalationPct: '',
  nextEscalationDate: '', notes: '',
}

const str = (v: unknown): string => (v == null ? '' : String(v))
const num = (v: string): number | undefined => {
  if (v.trim() === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

function fromLease(l: PmLease): Fields {
  return {
    tenantName: str(l.tenantName), unitLabel: str(l.unitLabel), areaPyeong: str(l.areaPyeong),
    monthlyRentManwon: str(l.monthlyRentManwon), depositManwon: str(l.depositManwon),
    mgmtFeeManwon: str(l.mgmtFeeManwon), leaseStartDate: str(l.leaseStartDate), leaseEndDate: str(l.leaseEndDate),
    rentFreeMonths: str(l.rentFreeMonths), escalationPct: str(l.escalationPct),
    nextEscalationDate: str(l.nextEscalationDate), notes: str(l.notes),
  }
}

function fromExtract(e: PmLeaseExtract, prev: Fields): Fields {
  // 추출값이 있으면 채우고, 없으면(null) 기존 입력 유지.
  const pick = (v: unknown, cur: string) => (v == null ? cur : String(v))
  return {
    tenantName: pick(e.tenantName, prev.tenantName),
    unitLabel: pick(e.unitLabel, prev.unitLabel),
    areaPyeong: pick(e.areaPyeong, prev.areaPyeong),
    monthlyRentManwon: pick(e.monthlyRentManwon, prev.monthlyRentManwon),
    depositManwon: pick(e.depositManwon, prev.depositManwon),
    mgmtFeeManwon: pick(e.mgmtFeeManwon, prev.mgmtFeeManwon),
    leaseStartDate: pick(e.leaseStartDate, prev.leaseStartDate),
    leaseEndDate: pick(e.leaseEndDate, prev.leaseEndDate),
    rentFreeMonths: pick(e.rentFreeMonths, prev.rentFreeMonths),
    escalationPct: pick(e.escalationPct, prev.escalationPct),
    nextEscalationDate: pick(e.nextEscalationDate, prev.nextEscalationDate),
    notes: pick(e.notes, prev.notes),
  }
}

/**
 * 임대차 추가/수정 폼. 계약서를 붙여넣고 'AI 추출'(1크레딧)로 필드를 프리필하거나 직접 입력한다.
 * 저장 시 생성(POST) 또는 수정(PUT). 추출 원문은 sourceText 로 함께 저장(추후 재추출·근거).
 */
export function LeaseForm({ buildingId, initial, onSaved, onCancel, onCreditBalance, onNeedCredits, extractCost }: LeaseFormProps) {
  const [fields, setFields] = useState<Fields>(initial ? fromLease(initial) : EMPTY)
  const [contract, setContract] = useState('')
  const [extracting, setExtracting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const set = (k: keyof Fields) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setFields((s) => ({ ...s, [k]: e.target.value }))

  async function extract() {
    if (!contract.trim()) { setError('추출할 계약서 텍스트를 붙여넣으세요.'); return }
    setError(null); setNotice(null); setExtracting(true)
    try {
      const res = await api.pmExtractLease(contract.trim())
      onCreditBalance(res.creditBalance)
      if (res.extract) {
        setFields((prev) => fromExtract(res.extract!, prev))
        setNotice(`추출 완료${res.extract.confidence ? ` · 신뢰도 ${res.extract.confidence}` : ''}. 값을 검토·수정한 뒤 저장하세요.`)
      } else {
        setError('구조화 추출에 실패했습니다. 직접 입력하거나 다시 시도하세요.')
      }
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 402) onNeedCredits()
      else if (err instanceof ApiError && err.status === 503) setError(err.message || 'AI 추출 서비스를 사용할 수 없습니다.')
      else setError(err instanceof ApiError ? err.message : '추출 중 오류가 발생했습니다.')
    } finally {
      setExtracting(false)
    }
  }

  async function save() {
    if (!fields.tenantName.trim()) { setError('임차인명을 입력하세요.'); return }
    setError(null); setSaving(true)
    const input: PmLeaseInput = {
      buildingId,
      tenantName: fields.tenantName.trim(),
      unitLabel: fields.unitLabel.trim() || undefined,
      areaPyeong: num(fields.areaPyeong),
      monthlyRentManwon: num(fields.monthlyRentManwon),
      depositManwon: num(fields.depositManwon),
      mgmtFeeManwon: num(fields.mgmtFeeManwon),
      leaseStartDate: fields.leaseStartDate || undefined,
      leaseEndDate: fields.leaseEndDate || undefined,
      rentFreeMonths: num(fields.rentFreeMonths),
      escalationPct: num(fields.escalationPct),
      nextEscalationDate: fields.nextEscalationDate || undefined,
      sourceText: contract.trim() || undefined,
      notes: fields.notes.trim() || undefined,
    }
    try {
      const saved = initial ? await api.pmUpdateLease(initial.id, input) : await api.pmCreateLease(input)
      onSaved(saved)
    } catch (err: unknown) {
      setError(err instanceof ApiError ? err.message : '저장에 실패했습니다.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="lease-form">
      {!initial && (
        <div className="lf-extract">
          <label htmlFor="lf-contract">계약서 붙여넣기 <span className="opt">(선택 · AI 추출)</span></label>
          <textarea id="lf-contract" rows={4} value={contract} onChange={(e) => setContract(e.target.value)}
            placeholder="임대차 계약서 전문을 붙여넣고 'AI로 추출'을 누르면 아래 필드가 자동으로 채워집니다." />
          <button type="button" className="btn-ghost btn-xs" onClick={() => void extract()} disabled={extracting}>
            {extracting ? '추출 중…' : `AI로 추출${extractCost != null ? ` · ${extractCost}크레딧` : ''}`}
          </button>
        </div>
      )}

      <div className="lf-grid">
        <Field label="임차인명 *" v={fields.tenantName} on={set('tenantName')} placeholder="예: (주)한빛물류" />
        <Field label="층/호" v={fields.unitLabel} on={set('unitLabel')} placeholder="예: 10F" />
        <Field label="임대면적(평)" v={fields.areaPyeong} on={set('areaPyeong')} type="number" />
        <Field label="월 임대료(만원)" v={fields.monthlyRentManwon} on={set('monthlyRentManwon')} type="number" />
        <Field label="보증금(만원)" v={fields.depositManwon} on={set('depositManwon')} type="number" />
        <Field label="월 관리비(만원)" v={fields.mgmtFeeManwon} on={set('mgmtFeeManwon')} type="number" />
        <Field label="계약 시작일" v={fields.leaseStartDate} on={set('leaseStartDate')} type="date" />
        <Field label="계약 만기일" v={fields.leaseEndDate} on={set('leaseEndDate')} type="date" />
        <Field label="렌트프리(개월)" v={fields.rentFreeMonths} on={set('rentFreeMonths')} type="number" />
        <Field label="인상률(%)" v={fields.escalationPct} on={set('escalationPct')} type="number" />
        <Field label="다음 인상 예정일" v={fields.nextEscalationDate} on={set('nextEscalationDate')} type="date" />
      </div>
      <div className="lf-notes">
        <label htmlFor="lf-notes">특약·비고</label>
        <textarea id="lf-notes" rows={2} value={fields.notes} onChange={set('notes')} placeholder="특약·갱신 조건 등" />
      </div>

      {notice && <p className="hint">{notice}</p>}
      {error && <p className="error">{error}</p>}
      <div className="lf-actions">
        <button type="button" className="btn-ghost btn-xs" onClick={onCancel} disabled={saving}>취소</button>
        <button type="button" className="btn-primary btn-xs" onClick={() => void save()} disabled={saving}>
          {saving ? '저장 중…' : initial ? '수정 저장' : '임대차 저장'}
        </button>
      </div>
    </div>
  )
}

function Field({ label, v, on, type, placeholder }: {
  label: string
  v: string
  on: (e: React.ChangeEvent<HTMLInputElement>) => void
  type?: string
  placeholder?: string
}) {
  return (
    <div className="lf-field">
      <label>{label}</label>
      <input value={v} onChange={on} type={type ?? 'text'} step={type === 'number' ? 'any' : undefined}
        inputMode={type === 'number' ? 'decimal' : undefined} placeholder={placeholder} />
    </div>
  )
}
