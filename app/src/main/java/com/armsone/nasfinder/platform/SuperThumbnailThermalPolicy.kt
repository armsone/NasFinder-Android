package com.armsone.nasfinder.platform

/** A platform-neutral decision that a background thumbnail worker can apply at item boundaries. */
internal sealed interface SuperThumbnailThermalDecision {
    data object Continue : SuperThumbnailThermalDecision
    data class Pace(val delayMillis: Long) : SuperThumbnailThermalDecision
    data object RetryWhenCooler : SuperThumbnailThermalDecision
}

/**
 * Maps Android's API 29+ thermal status values to the current iPhone thermal contract.
 *
 * API 26-28 cannot read [android.os.PowerManager.getCurrentThermalStatus], so they use
 * conservative pacing instead of pretending the device is cool or blocking forever.
 */
internal object SuperThumbnailThermalPolicy {
    const val FAIR_PACING_DELAY_MILLIS = 500L
    const val THERMAL_STATUS_API_LEVEL = 29

    fun decision(apiLevel: Int, thermalStatus: Int?): SuperThumbnailThermalDecision {
        if (apiLevel < THERMAL_STATUS_API_LEVEL) return paced()
        return when (thermalStatus) {
            STATUS_NONE, STATUS_LIGHT -> SuperThumbnailThermalDecision.Continue
            STATUS_MODERATE -> paced()
            STATUS_SEVERE,
            STATUS_CRITICAL,
            STATUS_EMERGENCY,
            STATUS_SHUTDOWN,
            null -> SuperThumbnailThermalDecision.RetryWhenCooler
            else -> SuperThumbnailThermalDecision.RetryWhenCooler
        }
    }

    private fun paced() = SuperThumbnailThermalDecision.Pace(FAIR_PACING_DELAY_MILLIS)

    // PowerManager.THERMAL_STATUS_* values. Keeping the mapping here makes the policy a pure JVM unit.
    private const val STATUS_NONE = 0
    private const val STATUS_LIGHT = 1
    private const val STATUS_MODERATE = 2
    private const val STATUS_SEVERE = 3
    private const val STATUS_CRITICAL = 4
    private const val STATUS_EMERGENCY = 5
    private const val STATUS_SHUTDOWN = 6
}
