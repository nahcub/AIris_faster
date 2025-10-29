package com.example.airis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var isConnected by mutableStateOf(false)
    private var batteryLevel by mutableStateOf(98)

    private val ESP32_SSID = "XIAO_S3_CAM_AP"
    private val ESP32_IP = "192.168.4.1"
    private val ESP32_PORT = 80

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var shouldCheckConnection = false

    // 권한 요청 런처
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openWifiSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        // 라이프사이클 옵저버 등록
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                Log.d("MainActivity", "onResume - shouldCheck: $shouldCheckConnection")

                // Wi-Fi 설정에서 돌아온 경우 항상 연결 확인
                lifecycleScope.launch {
                    delay(1000) // Wi-Fi 연결 안정화 대기
                    val previousState = isConnected
                    checkEsp32Connection()

                    // 연결이 끊겼으면 shouldCheckConnection도 false로
                    if (previousState && !isConnected) {
                        shouldCheckConnection = false
                        Log.d("MainActivity", "Connection lost, stopping monitoring")
                    }
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                Log.d("MainActivity", "onPause")
            }
        })

        setContent {
            MaterialTheme {
                AppNavigation(
                    isConnected = isConnected,
                    batteryLevel = batteryLevel,
                    onConnectClick = { handleConnectClick() },
                    onDisconnectClick = { handleDisconnectClick() }
                )
            }
        }

        // 네트워크 변화 모니터링 시작
        startNetworkMonitoring()
    }

    private fun handleConnectClick() {
        shouldCheckConnection = true

        // 필요한 권한 확인 및 요청
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val notGrantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isEmpty()) {
            openWifiSettings()
        } else {
            permissionLauncher.launch(notGrantedPermissions.toTypedArray())
        }
    }

    private fun openWifiSettings() {
        Log.d("MainActivity", "Opening WiFi settings")
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        startActivity(intent)
    }

    private fun handleDisconnectClick() {
        // 배터리 모니터링 중지
        stopBatteryMonitoring()

        // Wi-Fi 설정 화면 열기 (사용자가 수동으로 끊도록)
        openWifiSettings()

        // 상태는 onResume에서 네트워크 상태 감지로 자동 변경됨
    }

    private fun startNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d("MainActivity", "Network available")
                // 항상 연결 체크 (연결/해제 모두 감지)
                checkEsp32Connection()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("MainActivity", "Network lost")
                isConnected = false
                shouldCheckConnection = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                Log.d("MainActivity", "Network capabilities changed")
                // 항상 연결 체크
                checkEsp32Connection()
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        Log.d("MainActivity", "Network callback registered")
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    private var batteryMonitoringJob: kotlinx.coroutines.Job? = null

    private fun checkEsp32Connection() {
        lifecycleScope.launch {
            try {
                // Wi-Fi SSID 확인
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid.replace("\"", "")

                Log.d("MainActivity", "Current SSID: $ssid, Target: $ESP32_SSID")

                if (ssid == ESP32_SSID || ssid == "<unknown ssid>") {
                    // ESP32에 HTTP 요청하여 연결 확인
                    val connected = checkEsp32Status()
                    Log.d("MainActivity", "ESP32 status check result: $connected")

                    isConnected = connected

                    if (connected) {
                        shouldCheckConnection = false // 연결 성공하면 체크 중지
                        startBatteryMonitoring()
                    }
                } else {
                    Log.d("MainActivity", "Not connected to ESP32 AP")
                    isConnected = false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking connection", e)
                isConnected = false
            }
        }
    }

    private suspend fun checkEsp32Status(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("MainActivity", "Attempting to connect to http://$ESP32_IP:$ESP32_PORT/status")
                val url = URL("http://$ESP32_IP:$ESP32_PORT/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                Log.d("MainActivity", "Response code: $responseCode")

                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.d("MainActivity", "Response: $response")

                    // JSON 파싱하여 배터리 레벨 추출
                    val batteryMatch = Regex("\"battery\":(\\d+)").find(response)
                    batteryMatch?.let {
                        batteryLevel = it.groupValues[1].toInt()
                        Log.d("MainActivity", "Battery level: $batteryLevel")
                    }
                    true
                } else {
                    Log.w("MainActivity", "Unexpected response code: $responseCode")
                    false
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking ESP32 status", e)
                false
            }
        }
    }

    private fun startBatteryMonitoring() {
        stopBatteryMonitoring() // 기존 작업 중지

        batteryMonitoringJob = lifecycleScope.launch {
            while (isConnected) {
                delay(5000) // 5초마다 확인
                if (!checkEsp32Status()) {
                    isConnected = false
                    break
                }
            }
        }
        Log.d("MainActivity", "Battery monitoring started")
    }

    private fun stopBatteryMonitoring() {
        batteryMonitoringJob?.cancel()
        batteryMonitoringJob = null
        Log.d("MainActivity", "Battery monitoring stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkMonitoring()
        stopBatteryMonitoring()
    }
}

@Composable
fun AppNavigation(
    isConnected: Boolean,
    batteryLevel: Int,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "onboarding"
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onStartClick = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            MainScreen(
                isConnected = isConnected,
                batteryLevel = batteryLevel,
                onConnectionChange = { connect ->
                    if (connect) {
                        onConnectClick()
                    } else {
                        onDisconnectClick()
                    }
                },
                onStartClick = {
                    if (isConnected) {
                        onDisconnectClick()
                    } else {
                        onConnectClick()
                    }
                },
                onHistoryClick = {
                    navController.navigate("myhistory")
                },
                onCameraTestClick = {
                    navController.navigate("camera_preview")
                }
            )
        }

        composable("camera_preview") {
            CameraPreviewScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable("myhistory") {
            MyHistoryScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}