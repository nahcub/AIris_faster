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
import androidx.compose.ui.platform.LocalContext
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
    private var connectedSSID by mutableStateOf("")

    private val ESP32_SSID_PATTERN = "AIDocentGlass"
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

        // [추가됨] 앱 실행 즉시 백그라운드에서 DB 로딩 시작
        // lifecycleScope를 사용하면 앱이 살아있는 동안 로딩이 계속됩니다.
        lifecycleScope.launch(Dispatchers.Default) {
            ArtRepository.initialize(applicationContext)
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        // 라이프사이클 옵저버 등록
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                Log.d("MainActivity", "onResume - shouldCheck: $shouldCheckConnection")

                lifecycleScope.launch {
                    delay(1000)
                    val previousState = isConnected
                    checkEsp32Connection()

                    if (previousState && !isConnected) {
                        shouldCheckConnection = false
                        Log.d("MainActivity", "Connection lost, stopping monitoring")
                    }
                }
            }
        })

        setContent {
            MaterialTheme {
                AppNavigation(
                    isConnected = isConnected,
                    connectedSSID = connectedSSID,
                    batteryLevel = batteryLevel,
                    onConnectClick = { handleConnectClick() },
                    onDisconnectClick = { handleDisconnectClick() }
                )
            }
        }

        startNetworkMonitoring()
    }

    // ... (기존 handleConnectClick, checkEsp32Connection 등 메서드들은 그대로 유지)
    // 코드가 너무 길어지므로 생략된 부분은 기존 코드와 동일합니다.
    // 기존에 작성했던 handleConnectClick, openWifiSettings, checkEsp32Connection 등은 그대로 두세요.

    private fun handleConnectClick() {
        shouldCheckConnection = true
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)

        val notGrantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isEmpty()) openWifiSettings()
        else permissionLauncher.launch(notGrantedPermissions.toTypedArray())
    }

    private fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        startActivity(intent)
    }

    private fun handleDisconnectClick() {
        stopBatteryMonitoring()
        openWifiSettings()
    }

    private fun startNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { checkEsp32Connection() }
            override fun onLost(network: Network) {
                isConnected = false
                shouldCheckConnection = false
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { checkEsp32Connection() }
        }
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    private var batteryMonitoringJob: kotlinx.coroutines.Job? = null

    private fun checkEsp32Connection() {
        lifecycleScope.launch {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid.replace("\"", "")
                val isEsp32Network = ssid.contains(ESP32_SSID_PATTERN, ignoreCase = true) || ssid == "<unknown ssid>"

                if (isEsp32Network) {
                    val connected = checkEsp32Status()
                    connectedSSID = ssid
                    isConnected = connected
                    if (connected) {
                        shouldCheckConnection = false
                        startBatteryMonitoring()
                    }
                } else {
                    connectedSSID = ""
                    isConnected = false
                }
            } catch (e: Exception) {
                isConnected = false
            }
        }
    }

    private suspend fun checkEsp32Status(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ESP32_IP:$ESP32_PORT/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val batteryMatch = Regex("\"battery\":(\\d+)").find(response)
                    batteryMatch?.let { batteryLevel = it.groupValues[1].toInt() }
                    true
                } else false
            } catch (e: Exception) { false }
        }
    }

    private fun startBatteryMonitoring() {
        stopBatteryMonitoring()
        batteryMonitoringJob = lifecycleScope.launch {
            while (isConnected) {
                delay(5000)
                if (!checkEsp32Status()) {
                    isConnected = false
                    break
                }
            }
        }
    }

    private fun stopBatteryMonitoring() {
        batteryMonitoringJob?.cancel()
        batteryMonitoringJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkMonitoring()
        stopBatteryMonitoring()
    }
}

// [수정된 부분] AppNavigation 함수
@Composable
fun AppNavigation(
    isConnected: Boolean,
    batteryLevel: Int,
    connectedSSID: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current // Context 가져오기

    NavHost(
        navController = navController,
        startDestination = "splash" // 시작은 항상 Splash
    ) {
        // 1. 스플래시 화면
        composable("splash") {
            SplashScreen(
                onInitializationComplete = {
                    // SharedPreferences를 확인하여 온보딩 완료 여부 체크
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val isMsgShown = prefs.getBoolean("is_onboarding_shown", false)

                    if (isMsgShown) {
                        // 이미 본 적이 있다면 -> 홈으로 이동
                        navController.navigate("home") {
                            popUpTo("splash") { inclusive = true }
                        }
                    } else {
                        // 처음이라면 -> 온보딩으로 이동
                        navController.navigate("onboarding") {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                }
            )
        }

        // 2. 온보딩 화면
        composable("onboarding") {
            OnboardingScreen(
                onStartClick = {
                    // 시작하기 버튼을 누르면 '봤음'으로 저장
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("is_onboarding_shown", true).apply()

                    // 홈으로 이동
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        // 3. 메인(홈) 화면
        composable("home") {
            MainScreen(
                isConnected = isConnected,
                batteryLevel = batteryLevel,
                connectedSSID = connectedSSID,
                onConnectionChange = { connect ->
                    if (connect) onConnectClick() else onDisconnectClick()
                },
                onStartClick = {
                    if (isConnected) onDisconnectClick() else onConnectClick()
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
            CameraPreviewScreen(onBackClick = { navController.popBackStack() })
        }

        composable("myhistory") {
            MyHistoryScreen(onBackClick = { navController.popBackStack() })
        }
    }
}