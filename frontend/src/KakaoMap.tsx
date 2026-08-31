import { useEffect, useRef, useState } from 'react'
import { api } from './api'

interface KakaoMapProps {
  latitude: number
  longitude: number
  /** 중심 핀 라벨(검색한 주소). */
  label?: string
  /** 지도 높이(CSS 값). */
  height?: string
}

/**
 * 지도에 얹을 카테고리. 카카오 카테고리 그룹 코드를 쓴다.
 *
 * **일부러 적게 고른다.** 편의점·카페까지 찍으면 핀이 수십 개가 되어 지도가 정보가 아니라 소음이 된다.
 * "이 동네가 어떤 동네인지" 를 한눈에 판단하게 하는 큼지막한 것만 남겼다:
 * 지하철(교통)·학교(학군)·대형마트(생활)·병원(의료).
 */
const CATEGORIES = [
  { code: 'SW8', key: 'subway', label: '지하철', max: 4 },
  { code: 'SC4', key: 'school', label: '학교', max: 4 },
  { code: 'MT1', key: 'mart', label: '마트', max: 3 },
  { code: 'HP8', key: 'hosp', label: '병원', max: 2 },
] as const

/** 검색 반경(m). 도보 생활권 기준. */
const SEARCH_RADIUS_M = 1500

/* 카카오 SDK 는 전역(window.kakao)에 붙는다. 타입 패키지를 새로 들이는 대신
   여기서 쓰는 만큼만 좁게 선언한다(사용하지 않는 API 까지 떠안지 않기 위함). */
interface KakaoLatLng { getLat(): number; getLng(): number }
interface KakaoBounds { extend(latlng: KakaoLatLng): void }
interface KakaoMapInstance {
  setCenter(latlng: KakaoLatLng): void
  setBounds(bounds: KakaoBounds): void
  relayout(): void
}
interface Disposable { setMap(map: KakaoMapInstance | null): void }
interface PlaceDoc { place_name: string; x: string; y: string; distance?: string }
type PlacesStatus = 'OK' | 'ZERO_RESULT' | 'ERROR'

interface KakaoNamespace {
  maps: {
    load(cb: () => void): void
    LatLng: new (lat: number, lng: number) => KakaoLatLng
    LatLngBounds: new () => KakaoBounds
    Map: new (container: HTMLElement, options: { center: KakaoLatLng; level: number }) => KakaoMapInstance
    Marker: new (options: { map?: KakaoMapInstance; position: KakaoLatLng; title?: string }) => Disposable
    CustomOverlay: new (options: {
      map?: KakaoMapInstance; position: KakaoLatLng; content: string | HTMLElement; yAnchor?: number; zIndex?: number
    }) => Disposable
    services?: {
      Status: Record<PlacesStatus, PlacesStatus>
      Places: new () => {
        categorySearch(
          code: string,
          cb: (data: PlaceDoc[], status: PlacesStatus) => void,
          opts: { location: KakaoLatLng; radius: number; sort?: string },
        ): void
      }
    }
  }
}
declare global {
  interface Window { kakao?: KakaoNamespace }
}

const SDK_ID = 'kakao-maps-sdk'

/**
 * SDK 를 한 번만 로드한다. 여러 컴포넌트가 동시에 마운트돼도 script 태그는 하나만 남기고,
 * 이미 로드 중이면 같은 Promise 를 재사용한다(중복 로드 시 kakao 전역이 두 번 초기화되며 깨진다).
 */
let sdkPromise: Promise<KakaoNamespace> | null = null

function loadSdk(jsKey: string): Promise<KakaoNamespace> {
  if (window.kakao?.maps) return Promise.resolve(window.kakao)
  if (sdkPromise) return sdkPromise

  sdkPromise = new Promise<KakaoNamespace>((resolve, reject) => {
    const script = document.createElement('script')
    script.id = SDK_ID
    script.async = true
    // autoload=false + maps.load(): 스크립트 로드와 지도 엔진 초기화를 분리해 "kakao is not defined" 레이스를 없앤다.
    // libraries=services: 주변 시설 카테고리 검색용(없으면 지도만 뜨고 핀이 안 찍힌다).
    script.src =
      `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(jsKey)}&autoload=false&libraries=services`
    script.addEventListener('load', () => {
      const kakao = window.kakao
      if (!kakao) { reject(new Error('지도 SDK 로드 실패')); return }
      kakao.maps.load(() => resolve(kakao))
    })
    script.addEventListener('error', () => {
      sdkPromise = null // 재시도 가능하게 되돌린다.
      reject(new Error('지도 SDK 로드 실패'))
    })
    document.head.appendChild(script)
  })
  return sdkPromise
}

/**
 * 동네 지도 - 검색한 주소를 중심으로 지도를 그리고 **지하철·학교·마트·병원만** 핀으로 찍는다.
 *
 * 주변 시설을 서버(리포트 API)에서 가져오지 않고 지도 SDK 로 직접 찾는 이유는, 리포트의
 * 주변시설 목록에는 좌표가 없기 때문이다(이름·거리만 내려온다). 지도에 찍으려면 좌표가 필요하고,
 * SDK 의 카테고리 검색은 그 좌표를 바로 준다.
 *
 * 키는 서버(`/api/public/residential/map-config`)에서 받는다. 카카오맵 JavaScript 키는 브라우저에
 * 노출될 수밖에 없는 공개 키이고 보호는 콘솔의 도메인 등록으로 하므로, 프런트에 하드코딩하는 대신
 * 서버를 단일 소스로 두어 키 교체 시 재빌드가 필요 없게 했다.
 *
 * 키 미설정·SDK 로드 실패·좌표 없음은 모두 **지도를 그리지 않고 조용히 넘어간다** -
 * 지도는 리포트의 보조 요소이고, 여기서 실패해도 본문(실거래·단지·주변시설)은 그대로 쓸 수 있어야 한다.
 * 카테고리 검색만 실패하면 지도와 중심 핀은 그대로 두고 시설 핀만 생략한다.
 */
export function KakaoMap({ latitude, longitude, label, height = '360px' }: KakaoMapProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState(false)
  const [ready, setReady] = useState(false)
  const [foundKeys, setFoundKeys] = useState<string[]>([])

  useEffect(() => {
    let alive = true
    const drawn: Disposable[] = []

    async function draw() {
      try {
        const cfg = await api.residentialMapConfig()
        if (!alive) return
        if (!cfg.enabled || !cfg.jsKey) { setFailed(true); return }

        const kakao = await loadSdk(cfg.jsKey)
        if (!alive || !boxRef.current) return

        const center = new kakao.maps.LatLng(latitude, longitude)
        const map = new kakao.maps.Map(boxRef.current, { center, level: 5 })
        drawn.push(new kakao.maps.Marker({ map, position: center, title: label }))
        if (label) {
          drawn.push(new kakao.maps.CustomOverlay({
            map, position: center, yAnchor: 2.2, zIndex: 10,
            content: `<div class="map-pin-label">${escapeHtml(label)}</div>`,
          }))
        }
        setReady(true)

        // 카테고리 검색은 SDK 의 services 라이브러리가 필요하다. 키 권한이 없거나 라이브러리가
        // 로드되지 않았으면 지도만 남기고 조용히 건너뛴다.
        const places = kakao.maps.services ? new kakao.maps.services.Places() : null
        if (!places) return

        const bounds = new kakao.maps.LatLngBounds()
        bounds.extend(center)
        const hit: string[] = []

        CATEGORIES.forEach((cat) => {
          places.categorySearch(cat.code, (data, status) => {
            if (!alive || status !== 'OK' || data.length === 0) return
            hit.push(cat.key)
            setFoundKeys((prev) => (prev.includes(cat.key) ? prev : [...prev, cat.key]))
            data.slice(0, cat.max).forEach((p) => {
              const pos = new kakao.maps.LatLng(Number(p.y), Number(p.x))
              drawn.push(new kakao.maps.CustomOverlay({
                map, position: pos, yAnchor: 1, zIndex: 5,
                content: `<span class="map-poi ${cat.key}">${escapeHtml(p.place_name)}</span>`,
              }))
              bounds.extend(pos)
            })
            // 찾은 시설이 모두 보이도록 화면을 맞춘다(중심만 보이면 "주변"이 안 보인다).
            map.setBounds(bounds)
          }, { location: center, radius: SEARCH_RADIUS_M, sort: 'distance' })
        })
      } catch {
        if (alive) setFailed(true)
      }
    }
    void draw()

    return () => {
      alive = false
      drawn.forEach((o) => o.setMap(null))
    }
    // 주소가 바뀔 때만 다시 그린다(같은 리포트 안에서 label 은 좌표와 함께 고정).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latitude, longitude])

  if (failed) return null

  const legend = CATEGORIES.filter((c) => foundKeys.includes(c.key))

  return (
    <div className="map-wrap">
      <div ref={boxRef} className="map-box" style={{ height }} aria-label="주변 지도" role="img" />
      {!ready && <p className="map-loading">지도를 불러오는 중…</p>}
      {legend.length > 0 && (
        <ul className="map-legend" aria-label="지도 범례">
          {legend.map((c) => (
            <li key={c.key}><span className={`dot ${c.key}`} aria-hidden="true" />{c.label}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** 오버레이는 HTML 문자열로 들어가므로 라벨을 반드시 이스케이프한다(주소·상호는 외부 입력이다). */
function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] ?? c
  ))
}
