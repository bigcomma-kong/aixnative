import { useEffect, useState } from 'react'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { api, ApiError, type CreditPack } from './api'

interface CheckoutProps {
  /** 현재 잔여 크레딧(안내용). */
  creditBalance: number
  /** 구매자 식별 — 토스에 전달(취소·CS 매칭용). 로그인 이메일. */
  customerEmail: string
  onClose: () => void
}

/**
 * 크레딧 충전 결제 모달. 팩을 고르면 토스 결제창을 띄운다.
 * 흐름: 서버 주문생성(금액 서버 권위) → 토스 SDK requestPayment → successUrl 로 리다이렉트 →
 * App 부팅 시 콜백(paymentKey/orderId/amount)을 서버가 검증·충전. (승인 검증은 전부 서버에서)
 */
export function Checkout({ creditBalance, customerEmail, onClose }: CheckoutProps) {
  const [packs, setPacks] = useState<CreditPack[]>([])
  const [clientKey, setClientKey] = useState<string | null>(null)
  const [configured, setConfigured] = useState(true)
  const [selected, setSelected] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    Promise.all([api.creditPacks(), api.paymentConfig()])
      .then(([p, cfg]) => {
        if (!alive) return
        setPacks(p)
        setClientKey(cfg.clientKey)
        setConfigured(cfg.configured)
        setSelected(p.find((x) => x.id === 'PRO')?.id ?? p[0]?.id ?? null)
      })
      .catch((e) => alive && setError(e instanceof Error ? e.message : '가격 정보를 불러오지 못했습니다.'))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [])

  async function pay() {
    if (!selected || !clientKey) return
    const pack = packs.find((p) => p.id === selected)
    if (!pack) return
    setPaying(true)
    setError(null)
    try {
      const order = await api.createOrder(pack.id)
      const toss = await loadTossPayments(clientKey)
      const payment = toss.payment({ customerKey: order.customerKey })
      // 성공/실패 시 토스가 쿼리(paymentKey/orderId/amount 또는 code/message)를 붙여 리다이렉트한다.
      await payment.requestPayment({
        method: 'CARD',
        amount: { currency: 'KRW', value: order.amountKrw },
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl: `${window.location.origin}/?pay=1`,
        failUrl: `${window.location.origin}/?pay=1`,
        // 구매자 식별 — 토스 대시보드/취소 시 매칭(이메일). 이름은 미수집이라 이메일 앞부분으로.
        customerEmail,
        customerName: customerEmail.split('@')[0],
        card: { useEscrow: false, flowMode: 'DEFAULT', useCardPoint: false, useAppCardOnly: false },
      })
      // requestPayment 는 리다이렉트하므로 정상 흐름에선 여기 이후가 실행되지 않는다.
    } catch (e) {
      // 사용자가 결제창을 닫으면 SDK 가 에러를 던진다(취소). 메시지를 부드럽게.
      const msg =
        e instanceof ApiError
          ? e.message
          : e instanceof Error && /cancel|닫|user/i.test(e.message)
            ? '결제가 취소되었습니다.'
            : e instanceof Error
              ? e.message
              : '결제를 시작하지 못했습니다.'
      setError(msg)
      setPaying(false)
    }
  }

  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="크레딧 충전" onClick={onClose}>
      <div className="checkout-modal" onClick={(e) => e.stopPropagation()}>
        <div className="checkout-head">
          <h3>크레딧 충전</h3>
          <p className="muted">
            현재 잔여 <b>{creditBalance}</b>크레딧 · AI 분석 1회 = 분석별 1~5크레딧
          </p>
        </div>

        {loading ? (
          <div className="checkout-loading">불러오는 중…</div>
        ) : !configured ? (
          <div className="checkout-soon">
            결제 준비 중입니다. 잠시 후 다시 시도해 주세요.
          </div>
        ) : (
          <>
            <div className="pack-grid">
              {packs.map((p) => {
                const per = Math.round(p.amountKrw / p.credits)
                const best = p.id === 'PRO'
                return (
                  <button
                    type="button"
                    key={p.id}
                    className={`pack-card ${selected === p.id ? 'is-selected' : ''} ${best ? 'is-best' : ''}`}
                    onClick={() => setSelected(p.id)}
                    aria-pressed={selected === p.id}
                  >
                    {best && <span className="pack-badge">인기</span>}
                    <span className="pack-credits">{p.credits}<small>크레딧</small></span>
                    <span className="pack-price">{p.amountKrw.toLocaleString()}원</span>
                    <span className="pack-per">크레딧당 {per.toLocaleString()}원</span>
                  </button>
                )
              })}
            </div>

            {error && <div className="checkout-error" role="alert">{error}</div>}

            <div className="checkout-actions">
              <button type="button" className="btn-ghost" onClick={onClose} disabled={paying}>
                닫기
              </button>
              <button type="button" className="btn-primary" onClick={pay} disabled={paying || !selected}>
                {paying ? '결제창 여는 중…' : '결제하기'}
              </button>
            </div>
            <div className="checkout-policy">
              <p><b>환불 안내</b></p>
              <ul>
                <li>일회성 충전이며 자동 결제·구독이 아닙니다.</li>
                <li>환불은 <b>결제 후 7일 이내</b>, <b>구매한 크레딧을 전혀 사용하지 않은 경우에만</b> 가능합니다.</li>
                <li><b>크레딧을 일부라도 사용하면 해당 결제 건은 환불되지 않습니다.</b> (부분 환불 없음)</li>
                <li>환불 요청은 결제 계정 이메일로 문의해 주세요.</li>
              </ul>
              <p className="checkout-fineprint">결제는 토스페이먼츠로 안전하게 처리됩니다.</p>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
