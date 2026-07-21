// aixnative 앱 아이콘 - 기존 파비콘 마크(인디고 라운드스퀘어 + 흰 봉우리 라인 + 정점 점)를 고해상도로.
// 순수 SVG → resvg 렌더(satori 불필요, 벡터 라인 정밀). 1024 투명 라운드 코너 PNG.
import { Resvg } from '@resvg/resvg-js'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const S = 1024
// 코너 반경 - argv[4]로 오버라이드(apple-touch 는 0=풀스퀘어·불투명, iOS 가 알아서 라운딩).
const R = process.argv[4] != null ? Number(process.argv[4]) : 232

// 정점 점 색: 'white'(파비콘 충실) 또는 'gold'(브랜드 악센트). 인자로 선택.
const dotMode = process.argv[3] || 'white'
const DOT = dotMode === 'gold' ? '#f5c518' : '#ffffff'

// 봉우리/차트 라인(파비콘 M6 21…26 21 패턴을 1024 공간에 중앙 배치·확대). 정점 = 4번째 점.
const PTS = '230,652 400,452 512,548 672,376 794,652'
const PEAK = { x: 672, y: 376 }

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${S}" height="${S}" viewBox="0 0 ${S} ${S}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#2a2f83"/>
      <stop offset="0.55" stop-color="#2d3aa8"/>
      <stop offset="1" stop-color="#4f46e5"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="${S}" height="${S}" rx="${R}" fill="url(#bg)"/>
  <polyline points="${PTS}" fill="none" stroke="#ffffff" stroke-width="66"
            stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="${PEAK.x}" cy="${PEAK.y}" r="40" fill="${DOT}"/>
</svg>`

const png = new Resvg(svg, { fitTo: { mode: 'width', value: S }, background: 'rgba(0,0,0,0)' })
  .render().asPng()
const out = process.argv[2] || join(HERE, 'aixnative-app-icon.png')
writeFileSync(out, png)
console.log('wrote', out, png.length, 'bytes', '(dot:', dotMode + ')')
