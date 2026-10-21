package com.aixnative.calc.web

import com.aixnative.calc.domain.HoldingCostCalculator
import com.aixnative.calc.domain.TaxCalculator
import com.aixnative.common.web.ApiResponse
import com.aixnative.common.web.BadRequestException
import com.aixnative.integration.marketdata.service.BuildingRegisterClient
import com.aixnative.integration.marketdata.service.JusoClient
import com.aixnative.integration.marketdata.service.MarketDataProperties
import com.aixnative.integration.marketdata.service.VWorldClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 무료 계산기 - AI·크레딧 미사용. 세금 계산은 순수 계산이라 무인증 공개(/api/public, 리드마그넷),
 * 보유세는 주소로 공공 API(공시지가·건축물대장)를 부르므로 로그인 사용자만.
 */
@RestController
class CalcController(
    private val juso: JusoClient,
    private val vWorld: VWorldClient,
    private val buildingRegister: BuildingRegisterClient,
    private val marketProps: MarketDataProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/public/calc/acquisition-tax")
    fun acquisitionTax(@RequestBody req: AcquisitionTaxRequest): ApiResponse<TaxCalcResponse<TaxCalculator.Acquisition>> {
        val type = runCatching { TaxCalculator.AcquisitionType.valueOf(req.type) }.getOrElse { throw BadRequestException("취득 유형을 확인해 주세요.") }
        return ApiResponse.ok(TaxCalcResponse(guard { TaxCalculator.acquisition(req.priceWon, type) }))
    }

    @PostMapping("/api/public/calc/transfer-tax")
    fun transferTax(@RequestBody req: TransferTaxRequest): ApiResponse<TaxCalcResponse<TaxCalculator.Transfer>> =
        ApiResponse.ok(TaxCalcResponse(guard { TaxCalculator.transferIndividual(req.acquisitionWon, req.saleWon, req.expensesWon, req.holdYears) }))

    @PostMapping("/api/public/calc/after-tax")
    fun afterTax(@RequestBody req: AfterTaxRequest): ApiResponse<TaxCalcResponse<TaxCalculator.AfterTaxReturn>> =
        ApiResponse.ok(
            TaxCalcResponse(
                guard {
                    TaxCalculator.afterTaxReturn(
                        purchaseWon = req.purchaseWon, equityWon = req.equityWon, annualNoiWon = req.annualNoiWon,
                        annualInterestWon = req.annualInterestWon, saleWon = req.saleWon, holdYears = req.holdYears,
                        corporate = req.corporate, acquisitionCostWon = req.acquisitionCostWon,
                    )
                },
            ),
        )

    /**
     * 보유세 추정. 주소가 오면 공시지가·대지면적(V-World)·연면적·주용도·준공연도(건축물대장)를 채우고,
     * 사용자가 직접 넣은 값이 있으면 그 값이 우선한다(공공 API 가 비거나 틀린 경우 대비).
     */
    @PostMapping("/api/calc/holding-cost")
    fun holdingCost(@RequestBody req: HoldingCostRequest): ApiResponse<HoldingCostResponse> {
        var price = req.landPricePerSqm
        var landArea = req.landAreaSqm
        var floorArea = req.totalFloorAreaSqm
        var use = req.mainUse
        var year = req.buildYear
        val filled = mutableListOf<String>()
        val address = req.address?.trim()?.take(MAX_ADDRESS_LEN)?.takeIf { it.isNotEmpty() }
        if (address != null) {
            val parcel = runCatching { juso.resolveParcel(address) }.getOrNull()
            if (parcel != null && marketProps.landPriceEnabled && (price == null || landArea == null)) {
                runCatching { vWorld.landPrice(parcel.pnu()) }.getOrNull()?.let { lp ->
                    if (price == null) lp.pricePerSqm.toDoubleOrNull()?.let { price = it; filled += "개별공시지가(${lp.year}년, V-World)" }
                    if (landArea == null) lp.landArea.toDoubleOrNull()?.let { landArea = it; filled += "대지면적(V-World)" }
                }
            }
            if (parcel != null && (floorArea == null || use == null || year == null)) {
                runCatching { buildingRegister.titleInfo(parcel) }.getOrNull()?.let { b ->
                    if (floorArea == null) b.totAreaSqm?.toDoubleOrNull()?.let { floorArea = it; filled += "연면적(건축물대장)" }
                    if (use == null) b.mainPurpose?.let { use = it; filled += "주용도(건축물대장)" }
                    if (year == null) b.useAprYear?.toIntOrNull()?.let { year = it; filled += "준공연도(건축물대장)" }
                }
            }
            if (parcel == null) log.info("[calc] 보유세 - 주소 해석 실패: {}", address.replace(Regex("[\\r\\n]"), " "))
        }
        if ((price == null || landArea == null) && floorArea == null) {
            throw BadRequestException("공시지가·대지면적 또는 연면적 중 하나는 필요합니다. 주소로 찾지 못했다면 직접 입력해 주세요.")
        }
        val result = HoldingCostCalculator.estimate(price, landArea, floorArea, req.structure, use, year, LocalDate.now().year)
        return ApiResponse.ok(HoldingCostResponse(result, filled, price, landArea, floorArea, use, year))
    }

    private companion object {
        const val MAX_ADDRESS_LEN = 200
    }

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw BadRequestException(e.message ?: "입력값을 확인해 주세요.")
    }
}

data class AcquisitionTaxRequest(val priceWon: Long = 0, val type: String = "COMMERCIAL")

data class TransferTaxRequest(
    val acquisitionWon: Long = 0,
    val saleWon: Long = 0,
    val expensesWon: Long = 0,
    val holdYears: Double = 0.0,
)

data class AfterTaxRequest(
    val purchaseWon: Long = 0,
    val equityWon: Long = 0,
    val annualNoiWon: Long = 0,
    val annualInterestWon: Long = 0,
    val saleWon: Long = 0,
    val holdYears: Int = 5,
    val corporate: Boolean = false,
    val acquisitionCostWon: Long = 0,
)

data class TaxCalcResponse<T>(
    val result: T,
    val basis: String = TaxCalculator.BASIS,
    val disclaimer: String = "간이 계산입니다. 감면·중과·필요경비에 따라 실제 세액이 달라지므로 세무 전문가 확인이 필요합니다.",
)

data class HoldingCostRequest(
    val address: String? = null,
    val landPricePerSqm: Double? = null,
    val landAreaSqm: Double? = null,
    val totalFloorAreaSqm: Double? = null,
    val structure: String? = null,
    val mainUse: String? = null,
    val buildYear: Int? = null,
)

data class HoldingCostResponse(
    val result: HoldingCostCalculator.Result,
    /** 주소로 자동 채운 항목(출처 포함). */
    val autoFilled: List<String>,
    val landPricePerSqm: Double?,
    val landAreaSqm: Double?,
    val totalFloorAreaSqm: Double?,
    val mainUse: String?,
    val buildYear: Int?,
)
