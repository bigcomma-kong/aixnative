/**
 * hero 도메인 — AI 딜 언더라이팅 (Phase 2 자리표시).
 *
 * Phase 2 에서 MASTERN 에서 이식: ProFormaCalculator(IRR·Equity Multiple·DSCR·민감도),
 * CreGuidelines(스크리닝 기준), AiPromptBuilder 의 UNDERWRITING_NARRATIVE/SCREENING 프롬프트.
 * 흐름: 입력 → ProForma(순수 계산) → "AI 분석" → CreditGate.charge → AiServiceManager →
 * 결과를 AiToolRunService 로 저장(테넌트 격리). 출력에 Disclaimer 부착.
 */
package com.underwriteai.underwriting
