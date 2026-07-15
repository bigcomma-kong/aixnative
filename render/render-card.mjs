// 공감랭킹 카드뉴스 캐러셀 렌더러 - stdin(JSON) -> 표지+건별 슬라이드 여러 장 -> stdout(base64 배열 JSON).
// JVM(ImageCardRenderer)이 프로세스로 호출한다. 순수 JS + native resvg, 브라우저 불필요.
//
// 입력 JSON: { topic, title, slides:[{rank,title,summary,sourceName,imageUrl?}] }
// 출력: JSON 배열(문자열) - 각 원소가 PNG base64. index0=표지, 이후 항목별 1장.
import satori from 'satori'
import { html } from 'satori-html'
import { Resvg } from '@resvg/resvg-js'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const fontRegular = readFileSync(join(HERE, 'fonts', 'Pretendard-Regular.ttf'))
const fontBold = readFileSync(join(HERE, 'fonts', 'Pretendard-Bold.ttf'))

const WIDTH = 1080
const HEIGHT = 1350 // 인스타 세로(4:5)
const IMG_TIMEOUT = 6000
const MAX_IMG_BYTES = 8 * 1024 * 1024
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'

// 순위별 악센트(동일 레이아웃 반복 방지 - 능동적 변주).
const ACCENTS = ['#4f46e5', '#e11d48', '#0891b2', '#d97706', '#7c3aed', '#059669', '#db2777', '#2563eb', '#c026d3', '#0d9488']

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 원격 이미지 → data URI(satori 임베드용). 실패/비이미지/과대 시 null(디자인 폴백). */
async function fetchImageDataUri(url) {
  if (!url || !/^https?:\/\//.test(url)) return null
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), IMG_TIMEOUT)
    const res = await fetch(url, { signal: ctrl.signal, headers: { 'User-Agent': UA } })
    clearTimeout(timer)
    if (!res.ok) return null
    const type = (res.headers.get('content-type') || 'image/jpeg').split(';')[0].trim()
    if (!type.startsWith('image/')) return null
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length === 0 || buf.length > MAX_IMG_BYTES) return null
    return `data:${type};base64,${buf.toString('base64')}`
  } catch {
    return null
  }
}

/** 표지 슬라이드. */
function coverMarkup(data, count) {
  const topic = esc(data.topic || '')
  const title = esc(data.title || '')
  return html(`
    <div style="display:flex; flex-direction:column; width:${WIDTH}px; height:${HEIGHT}px; padding:88px 76px; background:linear-gradient(150deg, #111827 0%, #4f46e5 100%); font-family:Pretendard;">
      <div style="display:flex; align-items:center; justify-content:space-between;">
        <div style="display:flex; background:rgba(255,255,255,0.16); color:#ffffff; padding:12px 30px; border-radius:999px; font-size:30px; font-weight:700;">${topic}</div>
        <div style="display:flex; font-size:30px; color:rgba(255,255,255,0.85); font-weight:700;">공감랭킹</div>
      </div>
      <div style="display:flex; flex-direction:column; flex:1; justify-content:center;">
        <div style="display:flex; font-size:82px; font-weight:700; color:#ffffff; line-height:1.18;">${title}</div>
        <div style="display:flex; font-size:34px; color:rgba(255,255,255,0.75); margin-top:34px;">👉 넘겨서 TOP ${count} 확인</div>
      </div>
      <div style="display:flex; font-size:24px; color:rgba(255,255,255,0.6);">@gonggamranking · 출처는 각 원문 참조</div>
    </div>`)
}

/** 항목 슬라이드 - 이미지 있으면 상단 사진+하단 텍스트, 없으면 디자인형. */
function itemMarkup(slide, accent, dataUri) {
  const rank = esc(slide.rank ?? '')
  const title = esc(slide.title || '')
  const summary = esc(slide.summary || '')
  const src = esc(slide.sourceName || '')

  if (dataUri) {
    return html(`
      <div style="display:flex; flex-direction:column; width:${WIDTH}px; height:${HEIGHT}px; background:#ffffff; font-family:Pretendard;">
        <div style="display:flex; width:${WIDTH}px; height:648px; overflow:hidden;">
          <img src="${dataUri}" width="${WIDTH}" height="648" style="object-fit:cover;" />
        </div>
        <div style="display:flex; flex-direction:column; flex:1; padding:48px 68px;">
          <div style="display:flex; align-items:center;">
            <div style="display:flex; width:74px; height:74px; border-radius:18px; background:${accent}; color:#ffffff; font-size:40px; font-weight:700; align-items:center; justify-content:center; margin-right:24px;">${rank}</div>
            <div style="display:flex; font-size:26px; color:#94a3b8; font-weight:700;">${src}</div>
          </div>
          <div style="display:flex; font-size:46px; font-weight:700; color:#0f172a; margin-top:30px; line-height:1.25;">${title}</div>
          <div style="display:flex; font-size:30px; color:#475569; margin-top:22px; line-height:1.42;">${summary}</div>
        </div>
        <div style="display:flex; padding:0 68px 40px; font-size:22px; color:#cbd5e1;">공감랭킹 · @gonggamranking</div>
      </div>`)
  }

  // 디자인형 폴백(사진 없음) - 거대한 순위 워터마크 + 텍스트.
  return html(`
    <div style="display:flex; flex-direction:column; width:${WIDTH}px; height:${HEIGHT}px; padding:88px 76px; background:linear-gradient(150deg, #f8fafc 0%, ${accent}22 100%); font-family:Pretendard;">
      <div style="display:flex; align-items:center; justify-content:space-between;">
        <div style="display:flex; width:96px; height:96px; border-radius:24px; background:${accent}; color:#ffffff; font-size:52px; font-weight:700; align-items:center; justify-content:center;">${rank}</div>
        <div style="display:flex; font-size:26px; color:#94a3b8; font-weight:700;">${src}</div>
      </div>
      <div style="display:flex; flex-direction:column; flex:1; justify-content:center;">
        <div style="display:flex; font-size:60px; font-weight:700; color:#0f172a; line-height:1.24;">${title}</div>
        <div style="display:flex; font-size:34px; color:#475569; margin-top:30px; line-height:1.45;">${summary}</div>
      </div>
      <div style="display:flex; font-size:22px; color:#94a3b8;">공감랭킹 · @gonggamranking</div>
    </div>`)
}

const FONTS = [
  { name: 'Pretendard', data: fontRegular, weight: 400, style: 'normal' },
  { name: 'Pretendard', data: fontBold, weight: 700, style: 'normal' },
]

async function renderPng(markup) {
  const svg = await satori(markup, { width: WIDTH, height: HEIGHT, fonts: FONTS })
  return new Resvg(svg, { fitTo: { mode: 'width', value: WIDTH } }).render().asPng().toString('base64')
}

async function main() {
  const raw = readFileSync(0, 'utf-8') // fd 0 = stdin
  const data = JSON.parse(raw)
  const slides = (data.slides || []).slice(0, 9) // 표지 포함 인스타 캐러셀 10장 상한

  // 항목 이미지 병렬 프리페치(각각 graceful).
  const dataUris = await Promise.all(slides.map((s) => fetchImageDataUri(s.imageUrl)))

  const pages = []
  pages.push(await renderPng(coverMarkup(data, slides.length)))
  for (let i = 0; i < slides.length; i++) {
    const accent = ACCENTS[i % ACCENTS.length]
    pages.push(await renderPng(itemMarkup(slides[i], accent, dataUris[i])))
  }

  process.stdout.write(JSON.stringify(pages))
}

main().catch((e) => {
  process.stderr.write(`render-card 실패: ${e?.stack || e}\n`)
  process.exit(1)
})
