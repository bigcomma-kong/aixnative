// 공감랭킹 카드뉴스 렌더러 - stdin(JSON) -> satori(SVG) -> resvg(PNG) -> stdout(base64).
// JVM(ImageCardRenderer)이 프로세스로 호출한다. 순수 JS + native resvg, 브라우저 불필요.
//
// 입력 JSON: { topic, title, slides:[{rank,title,summary,sourceName}] }
// 출력: PNG 이미지의 base64 문자열(stdout).
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

/** HTML 특수문자 이스케이프(텍스트 안전 삽입). */
function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function buildMarkup(data) {
  const topic = esc(data.topic || '')
  const title = esc(data.title || '')
  const slides = (data.slides || []).slice(0, 5).map((s) => `
    <div style="display:flex; align-items:flex-start; margin-bottom:26px;">
      <div style="display:flex; width:60px; height:60px; border-radius:15px; background:#4f46e5; color:#ffffff; font-size:32px; font-weight:700; align-items:center; justify-content:center; margin-right:22px;">${esc(s.rank ?? '')}</div>
      <div style="display:flex; flex-direction:column; flex:1;">
        <div style="display:flex; font-size:33px; font-weight:700; color:#111827; line-height:1.25;">${esc(s.title || '')}</div>
        <div style="display:flex; font-size:23px; color:#4b5563; margin-top:6px; line-height:1.35;">${esc(s.summary || '')}</div>
      </div>
    </div>`).join('')

  return html(`
    <div style="display:flex; flex-direction:column; width:${WIDTH}px; height:${HEIGHT}px; padding:72px 68px; background:linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%); font-family:Pretendard;">
      <div style="display:flex; align-items:center; justify-content:space-between;">
        <div style="display:flex; background:#4f46e5; color:#ffffff; padding:9px 26px; border-radius:999px; font-size:26px; font-weight:700;">${topic}</div>
        <div style="display:flex; font-size:26px; color:#6b7280; font-weight:700;">공감랭킹</div>
      </div>
      <div style="display:flex; font-size:54px; font-weight:700; color:#0f172a; margin:38px 0 46px; line-height:1.22;">${title}</div>
      <div style="display:flex; flex-direction:column; flex:1;">${slides}</div>
      <div style="display:flex; font-size:21px; color:#94a3b8;">출처는 각 원문 참조 · @gonggamranking</div>
    </div>`)
}

async function main() {
  const raw = readFileSync(0, 'utf-8') // fd 0 = stdin
  const data = JSON.parse(raw)

  const svg = await satori(buildMarkup(data), {
    width: WIDTH,
    height: HEIGHT,
    fonts: [
      { name: 'Pretendard', data: fontRegular, weight: 400, style: 'normal' },
      { name: 'Pretendard', data: fontBold, weight: 700, style: 'normal' },
    ],
  })

  const png = new Resvg(svg, { fitTo: { mode: 'width', value: WIDTH } }).render().asPng()
  process.stdout.write(png.toString('base64'))
}

main().catch((e) => {
  process.stderr.write(`render-card 실패: ${e?.stack || e}\n`)
  process.exit(1)
})
