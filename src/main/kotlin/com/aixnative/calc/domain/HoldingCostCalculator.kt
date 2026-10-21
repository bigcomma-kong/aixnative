package com.aixnative.calc.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 상업·업무용 건물 보유세 추정 - 개별공시지가·대지면적·연면적 등 공개 데이터로 코드가 계산한다(실제 고지액 아님).
 *
 *  - 토지분 재산세: 상업용 부속토지 **별도합산** 누진(0.2~0.4%), 공정시장가액비율 70%, 도시지역분 0.14%, 지방교육세 20%.
 *  - 건물분 재산세: 시가표준액 = 건물신축가격기준액 × 구조·용도·위치지수 × 잔가율 × 연면적(행안부 산식 근사), 세율 0.25%.
 *  - 종합부동산세: 별도합산 토지 공시가격 80억 초과분(단일 필지 기준 근사, 법인 전체 합산과 다를 수 있음).
 * 주택은 별도 체계라 대상이 아니다.
 */
object HoldingCostCalculator {

    const val LAND_FAIR_RATIO = 0.70
    const val BUILDING_FAIR_RATIO = 0.70
    const val URBAN_RATE = 0.0014
    const val EDU_RATE = 0.20
    const val BUILDING_TAX_RATE = 0.0025

    /** 건물신축가격기준액(원/㎡) - 행안부 연도 고시. ⚠ 매년 갱신 필요(근사값). */
    const val STD_BUILD_PRICE_PER_SQM = 830_000L

    const val JONGBU_DEDUCTION = 8_000_000_000L
    const val JONGBU_RURAL_RATE = 0.20

    data class LandTax(val taxBaseWon: Long, val propertyTaxWon: Long, val urbanTaxWon: Long, val eduTaxWon: Long, val totalWon: Long)

    data class BuildingTax(
        val stdValueWon: Long,
        val taxBaseWon: Long,
        val structureIndex: Double,
        val useIndex: Double,
        val locationIndex: Double,
        val ageYears: Int,
        val residualRate: Double,
        val propertyTaxWon: Long,
        val urbanTaxWon: Long,
        val eduTaxWon: Long,
        val totalWon: Long,
    )

    data class Jongbu(val publicValueWon: Long, val applicable: Boolean, val taxWon: Long, val ruralTaxWon: Long, val totalWon: Long)

    data class Result(
        val land: LandTax?,
        val building: BuildingTax?,
        val jongbu: Jongbu?,
        val annualTotalWon: Long,
        val assumptions: List<String>,
    )

    fun estimate(
        landPricePerSqm: Double?,
        landAreaSqm: Double?,
        totalFloorAreaSqm: Double?,
        structure: String?,
        mainUse: String?,
        buildYear: Int?,
        currentYear: Int,
    ): Result {
        val land = if ((landPricePerSqm ?: 0.0) > 0 && (landAreaSqm ?: 0.0) > 0) landTax(landPricePerSqm!!, landAreaSqm!!) else null
        val building = if ((totalFloorAreaSqm ?: 0.0) > 0) {
            buildingTax(totalFloorAreaSqm!!, structure, mainUse, buildYear, currentYear, landPricePerSqm ?: 0.0)
        } else null
        val jongbu = if (land != null) jongbu(landPricePerSqm!!, landAreaSqm!!, land.propertyTaxWon) else null
        val assumptions = buildList {
            add("상업·업무용 부속토지(별도합산과세) 가정, 공정시장가액비율 70%.")
            add("건물분은 행안부 시가표준액 산식의 근사(건물신축가격기준액 %,d원/㎡). 실제 지자체 산정과 오차가 있습니다.".format(STD_BUILD_PRICE_PER_SQM))
            if (structure.isNullOrBlank()) add("구조 미입력 - 철근콘크리트(구조지수 1.0) 가정.")
            if (land == null) add("개별공시지가·대지면적이 없어 토지분은 계산하지 않았습니다.")
            if (building == null) add("연면적이 없어 건물분은 계산하지 않았습니다.")
            add("종부세는 단일 필지 기준 근사입니다(법인 보유 토지 전체 합산과 다를 수 있음).")
        }
        val total = (land?.totalWon ?: 0) + (building?.totalWon ?: 0) + (jongbu?.totalWon ?: 0)
        return Result(land, building, jongbu, total, assumptions)
    }

    fun landTax(pricePerSqm: Double, landAreaSqm: Double): LandTax {
        val base = pricePerSqm * landAreaSqm * LAND_FAIR_RATIO
        val property = separateSumPropertyTax(base)
        val urban = base * URBAN_RATE
        val edu = property * EDU_RATE
        return LandTax(w(base), w(property), w(urban), w(edu), w(property + urban + edu))
    }

    /** 별도합산 토지 재산세 본세 - 2억 이하 0.2% · 10억 이하 40만원+0.3% · 초과 280만원+0.4%. */
    fun separateSumPropertyTax(base: Double): Double = when {
        base <= 0 -> 0.0
        base <= 200_000_000 -> base * 0.002
        base <= 1_000_000_000 -> 400_000 + (base - 200_000_000) * 0.003
        else -> 2_800_000 + (base - 1_000_000_000) * 0.004
    }

    fun buildingTax(floorAreaSqm: Double, structure: String?, mainUse: String?, buildYear: Int?, currentYear: Int, landPricePerSqm: Double): BuildingTax {
        val si = structureIndex(structure)
        val ui = useIndex(mainUse)
        val li = locationIndex(landPricePerSqm)
        val age = if (buildYear != null && buildYear > 1900 && currentYear >= buildYear) currentYear - buildYear else 0
        val rr = residualRate(structure, age)
        val stdValue = STD_BUILD_PRICE_PER_SQM * si * ui * li * rr * floorAreaSqm
        val base = stdValue * BUILDING_FAIR_RATIO
        val property = base * BUILDING_TAX_RATE
        val urban = base * URBAN_RATE
        val edu = property * EDU_RATE
        return BuildingTax(w(stdValue), w(base), si, ui, li, age, (rr * 1000).roundToLong() / 1000.0, w(property), w(urban), w(edu), w(property + urban + edu))
    }

    /**
     * @param landPropertyTaxWon 같은 토지에 낸 재산세 본세 - 종부세 과세표준에 해당하는 몫만큼 종부세에서 공제한다
     *   (공제하지 않으면 같은 과세표준에 두 번 과세한 것처럼 합계가 부풀려진다). 비례 근사.
     */
    fun jongbu(pricePerSqm: Double, landAreaSqm: Double, landPropertyTaxWon: Long = 0): Jongbu {
        val publicValue = pricePerSqm * landAreaSqm
        if (publicValue <= JONGBU_DEDUCTION) return Jongbu(w(publicValue), false, 0, 0, 0)
        val base = publicValue - JONGBU_DEDUCTION
        val b1 = 12_000_000_000.0
        val b2 = 32_000_000_000.0
        val gross = when {
            base <= b1 -> base * 0.005
            base <= b2 -> b1 * 0.005 + (base - b1) * 0.006
            else -> b1 * 0.005 + (b2 - b1) * 0.006 + (base - b2) * 0.007
        }
        val credit = min(gross, landPropertyTaxWon * (base / publicValue))
        val tax = gross - credit
        val rural = tax * JONGBU_RURAL_RATE
        return Jongbu(w(publicValue), true, w(tax), w(rural), w(tax + rural))
    }

    fun structureIndex(s: String?): Double {
        val t = s.orEmpty()
        return when {
            t.contains("경량철골") || t.contains("조립") || t.contains("판넬") || t.contains("샌드위치") || t.contains("블록") -> 0.80
            t.contains("철근콘크리트") || t.contains("철골") || t.contains("SRC") || t.contains("RC") -> 1.00
            t.contains("연와") || t.contains("벽돌") || t.contains("석조") -> 0.90
            t.contains("목") -> 0.85
            else -> 1.00
        }
    }

    fun useIndex(u: String?): Double {
        val t = u.orEmpty()
        return when {
            t.contains("숙박") || t.contains("위락") || t.contains("관광") -> 1.10
            t.contains("의료") || t.contains("병원") || t.contains("판매") || t.contains("상업") -> 1.05
            t.contains("공장") || t.contains("창고") || t.contains("주차") || t.contains("자동차") -> 0.80
            else -> 1.00
        }
    }

    fun locationIndex(p: Double): Double = when {
        p <= 0 -> 1.00
        p <= 500_000 -> 0.85
        p <= 1_000_000 -> 0.90
        p <= 3_000_000 -> 0.95
        p <= 7_000_000 -> 1.00
        p <= 15_000_000 -> 1.05
        p <= 30_000_000 -> 1.10
        p <= 60_000_000 -> 1.15
        else -> 1.20
    }

    /** 경과연수별 잔가율 - 내용연수(RC·철골·석조 50년, 그 외 40년)·최종잔가율 10% 선형 근사. */
    fun residualRate(s: String?, ageYears: Int): Double {
        val t = s.orEmpty()
        val life = if (t.contains("철근콘크리트") || t.contains("철골") || t.contains("SRC") || t.contains("RC") || t.contains("석조") || t.isBlank()) 50 else 40
        val age = max(0, ageYears)
        return max(0.10, 1.0 - 0.9 * min(age, life) / life.toDouble())
    }

    private fun w(v: Double) = v.roundToLong()
}
