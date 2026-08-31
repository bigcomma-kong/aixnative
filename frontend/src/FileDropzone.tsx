import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, type DocumentLimits, type ExtractedDocument } from './api'

interface FileDropzoneProps {
  /** 추출 성공 시 호출 - 보통 부모의 documentText state 를 채운다. */
  onExtracted: (doc: ExtractedDocument) => void
  /** 상위에서 비활성화(분석 진행 중 등). */
  disabled?: boolean
  /** 드롭존 위에 붙일 안내 문구. */
  label?: string
}

/**
 * 문서 업로드 → 텍스트 추출 드롭존.
 *
 * 추출과 분석을 **별개 요청**으로 나눈 설계라, 이 컴포넌트는 텍스트만 받아 부모에게 넘긴다.
 * 덕분에 사용자가 결과를 눈으로 확인하고 고친 뒤 분석을 돌릴 수 있고(입력 품질 = 결과 품질),
 * 스캔본·암호화 문서는 크레딧을 쓰기 **전에** 걸러진다.
 *
 * 붙여넣기 입력을 대체하지 않고 **병존**시킨다 - 추출이 실패하는 문서(스캔본 등)에서
 * 사용자가 막히지 않도록 항상 폴백 경로를 남겨 둔다.
 */
export function FileDropzone({ onExtracted, disabled = false, label }: FileDropzoneProps) {
  const [limits, setLimits] = useState<DocumentLimits | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<ExtractedDocument | null>(null)
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    let alive = true
    api.documentLimits()
      .then((l) => { if (alive) setLimits(l) })
      .catch(() => { /* 안내값 조회 실패는 치명적이지 않다 - 업로드 자체는 서버가 다시 검증한다 */ })
    return () => { alive = false }
  }, [])

  const upload = useCallback(async (file: File) => {
    setBusy(true); setError(null); setDone(null)
    try {
      const doc = await api.extractDocument(file)
      setDone(doc)
      onExtracted(doc)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : '파일을 읽지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }, [onExtracted])

  const pick = () => { if (!disabled && !busy) inputRef.current?.click() }

  const onDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setDragging(false)
    if (disabled || busy) return
    const file = e.dataTransfer.files?.[0]
    if (file) void upload(file)
  }

  return (
    <div className="dropzone-wrap">
      <div
        className={`dropzone${dragging ? ' on' : ''}${disabled || busy ? ' off' : ''}`}
        onClick={pick}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick() } }}
        onDragOver={(e) => { e.preventDefault(); if (!disabled && !busy) setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        role="button"
        tabIndex={disabled || busy ? -1 : 0}
        aria-disabled={disabled || busy}
        aria-label="문서 파일 업로드"
      >
        <input
          ref={inputRef}
          type="file"
          hidden
          accept={limits?.accept}
          disabled={disabled || busy}
          onChange={(e) => {
            const file = e.target.files?.[0]
            // 같은 파일을 다시 고를 수 있도록 값을 비운다(change 이벤트가 안 나는 문제 방지).
            e.target.value = ''
            if (file) void upload(file)
          }}
        />
        {busy ? (
          <span className="dropzone-main">문서에서 글을 뽑고 있습니다…</span>
        ) : (
          <>
            <span className="dropzone-main">{label ?? '파일을 끌어다 놓거나 클릭해서 올리기'}</span>
            <span className="dropzone-sub">
              {limits?.supportedLabel ?? 'PDF · DOCX · XLSX · PPTX · HWP · TXT · MD · CSV · HTML'}
              {limits ? ` · PDF ${limits.maxPdfMb}MB · 한글 ${limits.maxHwpMb}MB` : ''}
            </span>
          </>
        )}
      </div>

      {error && <p className="error dropzone-msg">{error}</p>}

      {done && (
        <p className="dropzone-msg dropzone-ok">
          <strong>{done.fileName}</strong> 에서 {done.charCount.toLocaleString()}자를 가져왔습니다
          {done.pageCount ? ` (${done.pageCount}쪽)` : ''}.
          {done.truncated && ' 문서가 길어 앞부분만 담겼습니다 - 필요한 부분은 아래에서 직접 고쳐 주세요.'}
        </p>
      )}
    </div>
  )
}
