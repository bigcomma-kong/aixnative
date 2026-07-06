import { useEffect, useState } from 'react'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { api, ApiError, track, type CreditPack } from './api'
import { InfoModal, type InfoPage } from './SiteFooter'

/**
 * 결제 오픈 여부. false 동안은 결제창 대신 "곧 오픈 + 이메일 문의" 안내를 노출하고,
 * 크레딧은 운영자가 메일 확인 후 수동 지급한다. PG 심사·사업자 등록 완료 후 true 로 전환.
 */
const PAYMENT_OPEN: boolean = false
/** 크레딧 추가 문의처(푸터·개인정보 표기와 동일). */
const CONTACT_EMAIL = 'admin@aixnative.com'

interface CheckoutProps {
  /** 현재 잔여 크레딧(안내용). */
  creditBalance: number
  /** 구매자 식별 - 토스에 전달(취소·CS 매칭용). 로그인 이메일. */
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
  const [infoPage, setInfoPage] = useState<InfoPage | null>(null)
  const [loading, setLoading] = useState<boolean>(PAYMENT_OPEN)
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 결제 오픈 전 크레딧 문의 시 선택 수신동의(기본 off). mailto 본문에 동의 여부를 표기.
  const [marketingOptIn, setMarketingOptIn] = useState(false)

  // 결제/크레딧요청 화면 노출 이벤트(퍼널).
  useEffect(() => { track('checkout_view') }, [])

  useEffect(() => {
    if (!PAYMENT_OPEN) return // 결제 닫힘 - 가격/설정 조회 불필요, 안내만 노출
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
        // 구매자 식별 - 토스 대시보드/취소 시 매칭(이메일). 이름은 미수집이라 이메일 앞부분으로.
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

  const selectedPack = packs.find((p) => p.id === selected) ?? null
  // 크레딧당 최고 단가(=가장 비싼 팩) 기준으로 절약률 표시 → "묶음 살수록 이득" 을 직관적으로.
  const basePer = packs.length ? Math.max(...packs.map((p) => p.amountKrw / p.credits)) : 0
  // 결제 오픈 전 수동 크레딧 지급 문의 - 계정 이메일을 본문에 미리 채워 회신 매칭을 쉽게.
  const inquiryHref = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(
    '[AixNative] 크레딧 추가 요청',
  )}&body=${encodeURIComponent(
    `계정 이메일: ${customerEmail}\n요청 크레딧 수: \n사용 목적(선택): \n\n` +
      `마케팅·서비스 소식 수신: ${marketingOptIn ? '동의함' : '미동의'}\n`,
  )}`

  return (
    <div className="analyze-overlay" role="dialog" aria-modal="true" aria-label="크레딧 충전" onClick={onClose}>
      <div className="checkout-modal" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="checkout-x" aria-label="닫기" onClick={onClose} disabled={paying}>
          <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true" focusable="false">
            <path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
        </button>

        <div className="checkout-head">
          <span className="checkout-emblem" aria-hidden>₩</span>
          <div className="checkout-head-text">
            <h3>크레딧 충전</h3>
          </div>
          <span className="checkout-balance" title="현재 보유 크레딧">
            <small>보유</small>
            <b>{creditBalance.toLocaleString()}</b>
            크레딧
          </span>
        </div>

        {loading ? (
          <div className="checkout-loading">
            <span className="checkout-spinner" aria-hidden /> 가격 정보를 불러오는 중…
          </div>
        ) : !PAYMENT_OPEN ? (
          <div className="checkout-notice">
            <div className="checkout-notice-icon" aria-hidden>✉</div>
            <p className="checkout-notice-lead">
              크레딧 결제는 <b>오픈 준비 중</b>입니다
            </p>
            <p className="checkout-notice-body">
              지금은 가입 시 지급된 <b>무료 크레딧</b>으로 모든 AI 분석을 이용하실 수 있어요.<br />
              크레딧이 부족하시면 아래로 요청해 주세요.<br />
              검토 후 <b>크레딧을 지급</b>해 드립니다.
            </p>
            <label className="checkout-notice-optin">
              <input

                type="checkbox"
                checked={marketingOptIn}
                onChange={(e) => setMarketingOptIn(e.target.checked)}
              />
              <span>(선택) 마케팅·서비스 소식 이메일 수신에 동의합니다</span>
            </label>
            <a className="btn-primary checkout-notice-mail" href={inquiryHref} onClick={() => track('credit_request', { meta: marketingOptIn ? 'optin' : 'no-optin' })}>크레딧 요청하기</a>
            <p className="checkout-notice-fine">
              문의 <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
            </p>
          </div>
        ) : !configured ? (
          <div className="checkout-soon">결제 준비 중입니다. 잠시 후 다시 시도해 주세요.</div>
        ) : (
          <>
            <div className="pack-grid">
              {packs.map((p) => {
                const per = Math.round(p.amountKrw / p.credits)
                const best = p.id === 'PRO'
                const isSel = selected === p.id
                const save = basePer > 0 ? Math.round((1 - p.amountKrw / p.credits / basePer) * 100) : 0
                return (
                  <button
                    type="button"
                    key={p.id}
                    className={`pack-card ${isSel ? 'is-selected' : ''} ${best ? 'is-best' : ''}`}
                    onClick={() => setSelected(p.id)}
                    aria-pressed={isSel}
                  >
                    {best && <span className="pack-badge">인기</span>}
                    <span className="pack-check" aria-hidden>✓</span>
                    <span className="pack-credits">
                      {p.credits.toLocaleString()}
                      <small>크레딧</small>
                    </span>
                    <span className="pack-price">{p.amountKrw.toLocaleString()}원</span>
                    <span className="pack-per">
                      크레딧당 {per.toLocaleString()}원
                      {save > 0 && <em className="pack-save">{save}%↓</em>}
                    </span>
                  </button>
                )
              })}
            </div>

            {error && <div className="checkout-error" role="alert">{error}</div>}

            <div className="checkout-actions">
              <button
                type="button"
                className="btn-primary checkout-pay"
                onClick={pay}
                disabled={paying || !selectedPack}
              >
                {paying
                  ? '결제창 여는 중…'
                  : selectedPack
                    ? `${selectedPack.amountKrw.toLocaleString()}원 결제하기`
                    : '팩을 선택하세요'}
              </button>
              <p className="checkout-trust">
                <span aria-hidden>🔒</span> 토스페이먼츠 안전결제 · 카드 · 간편결제
              </p>
            </div>

            <div className="checkout-policy">
              <p className="checkout-policy-head">
                <span aria-hidden>🛡</span> 환불 안내
              </p>
              <ul>
                <li>일회성 충전이며 자동 결제·구독이 아닙니다.</li>
                <li>환불은 <b>결제 후 7일 이내</b>, <b>구매한 크레딧을 전혀 사용하지 않은 경우에만</b> 가능합니다.</li>
                <li><b>크레딧을 일부라도 사용하면 해당 결제 건은 환불되지 않습니다.</b></li>
                <li>환불 요청은 결제 계정 이메일로 문의해 주세요.</li>
              </ul>
              <p className="checkout-fineprint">
                결제 진행 시{' '}
                <button type="button" className="btn-link" onClick={() => setInfoPage('terms')}>이용약관</button>·
                <button type="button" className="btn-link" onClick={() => setInfoPage('privacy')}>개인정보 처리방침</button>에 동의하게 됩니다.
              </p>
            </div>
          </>
        )}
      </div>
      {infoPage && <InfoModal page={infoPage} onClose={() => setInfoPage(null)} />}
    </div>
  )
}
