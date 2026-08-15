package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class SuperThumbnailThermalPolicyTest {
    @Test
    fun `api 29 thermal values match nominal fair and serious iPhone behavior`() {
        assertEquals(SuperThumbnailThermalDecision.Continue, decision(status = 0))
        assertEquals(SuperThumbnailThermalDecision.Continue, decision(status = 1))
        assertEquals(
            SuperThumbnailThermalDecision.Pace(500),
            decision(status = 2),
        )
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = 3))
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = 4))
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = 5))
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = 6))
    }

    @Test
    fun `unknown modern thermal state fails safe`() {
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = null))
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = 99))
        assertEquals(SuperThumbnailThermalDecision.RetryWhenCooler, decision(status = -1))
    }

    @Test
    fun `api 26 through 28 use conservative fair pacing`() {
        for (apiLevel in 26..28) {
            assertEquals(
                SuperThumbnailThermalDecision.Pace(500),
                SuperThumbnailThermalPolicy.decision(apiLevel, thermalStatus = null),
            )
        }
    }

    private fun decision(status: Int?) =
        SuperThumbnailThermalPolicy.decision(apiLevel = 29, thermalStatus = status)
}
