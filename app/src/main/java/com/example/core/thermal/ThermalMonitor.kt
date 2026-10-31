package com.example.core.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ThermalLevel {
    NORMAL,    // < 38°C
    ELEVATED,  // 38°C to 42.9°C
    CRITICAL,  // >= 43°C
    EMERGENCY  // >= 45°C for > 3 continuous minutes
}

data class ThermalState(
    val temperatureC: Float = 0.0f,
    val powerThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val level: ThermalLevel = ThermalLevel.NORMAL,
    val criticalDurationMs: Long = 0L,
    val emergencyShutdownTriggered: Boolean = false
)

class ThermalMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onEmergencyShutdownRequired: () -> Unit
) {
    companion object {
        private const val TAG = "ThermalMonitor"
        private const val EMERGENCY_DURATION_MS = 180_000L // 3 continuous minutes
    }

    private val _thermalState = MutableStateFlow(ThermalState())
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private var emergencyStartTimeMs: Long? = null
    private var pollerJob: Job? = null
    private var lastValidTempC: Float = 32.0f

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                processRawTemperature(tempRaw)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering battery receiver", e)
        }

        pollerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                    processRawTemperature(tempRaw)
                } catch (e: Exception) {
                    Log.w(TAG, "Polling thermal state error", e)
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        pollerJob?.cancel()
        pollerJob = null
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    private fun processRawTemperature(tempRaw: Int) {
        // Android battery temperature is reported in tenths of a degree Celsius (e.g. 350 = 35.0°C)
        // Guard against missing, uninitialized or sensor dummy values (< 1°C or > 95°C)
        if (tempRaw in 10..950) {
            val tempC = tempRaw / 10.0f
            lastValidTempC = tempC
            evaluateThermalState(tempC)
        } else {
            // Keep evaluating based on the last verified valid reading
            evaluateThermalState(lastValidTempC)
        }
    }

    private fun evaluateThermalState(tempC: Float) {
        val powerStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                powerManager.currentThermalStatus
            } catch (_: Exception) {
                PowerManager.THERMAL_STATUS_NONE
            }
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val level = when {
            tempC >= 43.0f || powerStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.CRITICAL
            tempC >= 38.0f || powerStatus >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.ELEVATED
            else -> ThermalLevel.NORMAL
        }

        var durationMs = 0L
        var triggeredEmergency = false

        // Continuous overheating threshold: strictly requires verified real temp >= 45°C
        if (tempC >= 45.0f && tempC < 95.0f) {
            val now = SystemClock.elapsedRealtime()
            val start = emergencyStartTimeMs ?: now.also { emergencyStartTimeMs = it }
            durationMs = now - start

            if (durationMs >= EMERGENCY_DURATION_MS && !_thermalState.value.emergencyShutdownTriggered) {
                triggeredEmergency = true
                Log.e(TAG, "EMERGENCY: Sustained overheating >= 45°C for > 3 minutes. Triggering safe shutdown.")
                onEmergencyShutdownRequired()
            }
        } else {
            emergencyStartTimeMs = null
        }

        _thermalState.value = ThermalState(
            temperatureC = tempC,
            powerThermalStatus = powerStatus,
            level = if (triggeredEmergency) ThermalLevel.EMERGENCY else level,
            criticalDurationMs = durationMs,
            emergencyShutdownTriggered = triggeredEmergency || _thermalState.value.emergencyShutdownTriggered
        )
    }
}
