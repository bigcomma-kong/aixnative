/**
 * 베타(가오픈) 기간 피드백 수집 - 전 화면 우하단 플로팅 버튼.
 * 클릭 시 제목·본문 템플릿이 채워진 메일 클라이언트를 연다(별도 서버/폼 불필요).
 * 모바일에선 아이콘만 표시(폭 절약).
 */
const FEEDBACK_EMAIL = 'admin@aixnative.com'
const FEEDBACK_SUBJECT = '[AixNative 베타 피드백]'
const FEEDBACK_BODY = [
  '어떤 화면·기능인가요:',
  '',
  '불편했던 점:',
  '',
  '개선 아이디어:',
  '',
  '---',
  '베타 기간 피드백은 서비스 개선에 큰 힘이 됩니다. 감사합니다!',
].join('\n')

export function FeedbackButton() {
  const href = `mailto:${FEEDBACK_EMAIL}?subject=${encodeURIComponent(FEEDBACK_SUBJECT)}&body=${encodeURIComponent(FEEDBACK_BODY)}`
  return (
    <a className="feedback-fab" href={href} title="의견·불편·개선점을 메일로 보내주세요">
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
      <span className="feedback-fab-text">피드백</span>
    </a>
  )
}
