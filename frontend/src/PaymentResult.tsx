import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'

/** 토스 successUrl/failUrl 리다이렉트 콜백 파싱 결과. */
export interface PaymentCallback {
  paymentKey: string | null
  orderId: string | null
  amount: number | null
  /** 실패 시 토스가 주는 코드/메시지. */
  failCode: string | null
  failMessage: string | null
}

/** 부팅 시 URL 의 결제 콜백(`?pay=1&...`)을 읽는다. 없으면 null. */
export function readPaymentCallback(): PaymentCallback | null {
  const p = new URLSearchParams(window.location.search)
  if (p.get('pay') !== '1') return null
  const amountStr = p.get('amount')
  return {
    paymentKey: p.get('paymentKey'),
    orderId: p.get('orderId'),
    amount: amountStr ? Number(amountStr) : null,
    failCode: p.get('code'),
    failMessage: p.get('message'),
  }
}

interface PaymentResultProps {
  cb: PaymentCallback
  /** 충전 성공 → 새 잔액 반영. */
  onConfirmed: (creditBalance: number) => void
  /** 화면 닫기(URL 쿼리 정리). */
  onDone: () => void
}

type State =
  | { kind: 'confirming' }
  | { kind: 'success'; credits: number; balance: number; orderName: string }
  | { kind: 'failed'; message: string }

/**
 * 결제 완료 화면. 성공 콜백이면 서버에 승인검증(confirm)을 요청해 충전을 확정한다.
 * 서버가 멱등하므로 새로고침으로 재호출돼도 이중 충전되지 않는다.
 */
export function PaymentResult({ cb, onConfirmed, onDone }: PaymentResultProps) {
  const [state, setState] = useState<State>(
    cb.paymentKey && cb.orderId && cb.amount != null
      ? { kind: 'confirming' }
      : { kind: 'failed', message: cb.failMessage ?? '결제가 취소되었거나 실패했습니다.' },
  )
  const ran = useRef(false)

  useEffect(() => {
    if (ran.current) return
    ran.current = true
    if (!cb.paymentKey || !cb.orderId || cb.amount == null) return
    api
      .confirmPayment(cb.paymentKey, cb.orderId, cb.amount)
      .then((r) => {
        setState({ kind: 'success', credits: r.credits, balance: r.creditBalance, orderName: r.orderName })
        onConfirmed(r.creditBalance)
      })
      .catch((e) => {
        const msg = e instanceof ApiError ? e.message : '결제 승인에 실패했습니다.'
        setState({ kind: 'failed', message: msg })
      })
  }, [cb, onConfirmed])

  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="결제 결과">
      <div className="checkout-modal pay-result">
        {state.kind === 'confirming' && (
          <>
            <div className="pay-result-icon spin">⏳</div>
            <h3>결제 확인 중…</h3>
            <p className="muted">잠시만 기다려 주세요. 충전을 확정하고 있습니다.</p>
          </>
        )}
        {state.kind === 'success' && (
          <>
            <div className="pay-result-icon ok">✓</div>
            <h3>충전 완료</h3>
            <p className="pay-result-msg">
              <b>{state.orderName}</b> · <b>+{state.credits}</b>크레딧이 충전되었습니다.
            </p>
            <p className="muted">현재 잔액 <b>{state.balance}</b>크레딧</p>
            <button type="button" className="btn-primary" onClick={onDone}>
              분석 계속하기
            </button>
          </>
        )}
        {state.kind === 'failed' && (
          <>
            <div className="pay-result-icon fail">!</div>
            <h3>결제가 완료되지 않았습니다</h3>
            <p className="pay-result-msg">{state.message}</p>
            <p className="muted">결제가 진행되지 않았다면 요금이 청구되지 않습니다.</p>
            <button type="button" className="btn-primary" onClick={onDone}>
              닫기
            </button>
          </>
        )}
      </div>
    </div>
  )
}
