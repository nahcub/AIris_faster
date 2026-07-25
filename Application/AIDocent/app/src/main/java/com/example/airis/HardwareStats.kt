package com.example.airis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import java.io.File

// 벤치 회차마다 찍는 OS 자원 지표(RAM·발열) 읽기 헬퍼.
// 엔진(InferenceEngine)과 무관한 프로세스/기기 지표라 측정 계층(BenchmarkRunner)에서만 쓴다.
// 전력은 외부 계측 없이는 근사라 제외. CPU/SoC sysfs 온도(/sys/class/thermal)는
// 앱 프로세스에서 SELinux로 막히는 기기가 많아 배제하고, 앱에서 확실히 읽히는
// 배터리 온도 + thermal status 조합만 쓴다.
// 어떤 read든 실패하면 null(또는 0)을 돌려 측정이 벤치 자체를 깨지 않게 한다.
object HardwareStats {
    private const val TAG = "HardwareStats"
    private const val BYTES_PER_MB = 1024.0 * 1024.0

    // 프로세스 누적 최고 물리메모리(peak RSS). /proc/self/status 의 VmHWM(kB) 파싱.
    // 자기 프로세스라 권한 불필요. 누적 peak라 회차별 완전 격리는 아니지만(모델이 메모리
    // 대부분을 차지해 회차 간 거의 평탄) v0 대표값으로 충분.
    fun peakRssMb(): Double? = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.let { line ->
                    // 예: "VmHWM:\t 1234567 kB"
                    line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
                }
        }?.let { kb -> kb / 1024.0 }
    } catch (e: Exception) {
        Log.w(TAG, "peakRssMb read failed", e)
        null
    }

    // 네이티브 힙 할당량(MB). llama.cpp C++/모델 할당 몫 = 엔진 자원의 핵심 지표.
    fun nativeHeapMb(): Double = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB

    // 배터리 온도(°C). sticky ACTION_BATTERY_CHANGED 의 EXTRA_TEMPERATURE(0.1°C 단위) / 10.
    // SoC 코어 온도보다 느리게 반응하지만 앱에서 안정적으로 읽히는 유일한 실측.
    fun batteryTempC(context: Context): Double? = try {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10.0
    } catch (e: Exception) {
        Log.w(TAG, "batteryTempC read failed", e)
        null
    }

    // 스로틀링(성능 저하) 단계. PowerManager.getCurrentThermalStatus() (API 29+, minSdk 29라 가드 불필요).
    // 발열로 성능이 실제로 깎이는 시점을 직접 알려주는 지표.
    fun thermalStatus(context: Context): String? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    } catch (e: Exception) {
        Log.w(TAG, "thermalStatus read failed", e)
        null
    }
}
