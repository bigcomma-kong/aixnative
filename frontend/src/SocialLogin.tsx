import { useEffect, useState } from 'react'
import { api, oauthAuthorizeUrl } from './api'

/** 노출 순서(국내 친숙도) + 라벨. 백엔드가 설정된 제공자만 내려준다. */
const ORDER = ['kakao', 'naver', 'google'] as const
const LABEL: Record<string, string> = {
  kakao: '카카오로 시작하기',
  naver: '네이버로 시작하기',
  google: 'Google로 시작하기',
}

/** 브랜드 마크(외부 라이브러리 없이 인라인). */
function Mark({ provider }: { provider: string }) {
  if (provider === 'google') {
    return (
      <svg className="social-icon" viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
        <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z" />
        <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.34A9 9 0 0 0 9 18z" />
        <path fill="#FBBC05" d="M3.97 10.72A5.4 5.4 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.94H.96A9 9 0 0 0 0 9c0 1.45.35 2.82.96 4.06l3.01-2.34z" />
        <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.47.89 11.43 0 9 0A9 9 0 0 0 .96 4.94l3.01 2.34C4.68 5.16 6.66 3.58 9 3.58z" />
      </svg>
    )
  }
  // 카카오·네이버는 워드마크 글자(브랜드 색은 버튼 배경).
  return <span className="social-icon social-wordmark" aria-hidden="true">{provider === 'kakao' ? 'K' : 'N'}</span>
}

/**
 * 간편 소셜 로그인 버튼. 설정된 제공자가 없으면 아무것도 렌더하지 않는다(graceful).
 * 클릭 시 백엔드 authorize 로 전체 이동 → 제공자 인증 → 콜백에서 우리 JWT 발급.
 */
export function SocialLogin() {
  const [providers, setProviders] = useState<string[] | null>(null)

  useEffect(() => {
    let alive = true
    api.oauthProviders().then((p) => alive && setProviders(p)).catch(() => alive && setProviders([]))
    return () => {
      alive = false
    }
  }, [])

  if (!providers || providers.length === 0) return null
  const ordered = ORDER.filter((p) => providers.includes(p))
  if (ordered.length === 0) return null

  return (
    <div className="social-login">
      <div className="social-divider"><span>또는</span></div>
      <div className="social-btns">
        {ordered.map((p) => (
          <button
            key={p}
            type="button"
            className={`social-btn social-${p}`}
            onClick={() => { window.location.href = oauthAuthorizeUrl(p) }}
          >
            <Mark provider={p} />
            <span className="social-label">{LABEL[p]}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
