package com.example.callbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.callbridge.ui.theme.CallBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

data class PostCallRequest(val lead: Lead, val durationSeconds: Int, val nonce: Long = System.nanoTime())
private data class ActiveMiniCall(val lead: Lead, val startedElapsed: Long)

class MainActivity : ComponentActivity() {

    private lateinit var crmApi: CrmApi
    private val bridgeStatus = mutableStateOf("Bridge standby")
    private val postCallRequest = mutableStateOf<PostCallRequest?>(null)
    private var isPollingActive by mutableStateOf(false)
    private var bridgeServerUrl by mutableStateOf("http://10.54.233.135:5000")

    private var activeMiniCall: ActiveMiniCall? = null
    private var miniCallPaused = false
    private var pendingCallAction: (() -> Unit)? = null
    private var pollingJob: Job? = null

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingCallAction?.invoke()
            } else {
                bridgeStatus.value = "SIM call permission not granted. Dialing via phone keypad."
                // Graceful fallback to dialer
                pendingCallAction?.invoke()
            }
            pendingCallAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        crmApi = CrmApi(this)

        val prefs = getSharedPreferences("callbridge_bridge", Context.MODE_PRIVATE)
        bridgeServerUrl = prefs.getString("bridge_server_url", "http://10.54.233.135:5000") ?: "http://10.54.233.135:5000"

        setContent {
            CallBridgeTheme {
                val status by bridgeStatus
                val postCall by postCallRequest

                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    CallBridgeApp(
                        api = crmApi,
                        bridgeStatus = status,
                        bridgeServerUrl = bridgeServerUrl,
                        isPollingActive = isPollingActive,
                        onTogglePolling = { togglePolling() },
                        onUpdateBridgeUrl = { updateBridgeUrl(it) },
                        postCall = postCall,
                        onPostCallConsumed = { postCallRequest.value = null },
                        onCallLead = { requestMiniCrmCall(it) },
                        onCheckBridge = { checkPendingCallOnce() },
                    )
                }
            }
        }
    }

    private fun updateBridgeUrl(newUrl: String) {
        bridgeServerUrl = newUrl.trim()
        val prefs = getSharedPreferences("callbridge_bridge", Context.MODE_PRIVATE)
        prefs.edit().putString("bridge_server_url", bridgeServerUrl).apply()
        if (isPollingActive) {
            startPolling()
        }
    }

    private fun togglePolling() {
        if (isPollingActive) {
            stopPolling()
        } else {
            startPolling()
        }
    }

    private fun startPolling() {
        stopPolling()
        isPollingActive = true
        bridgeStatus.value = "Polling $bridgeServerUrl..."
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isPollingActive) {
                queryPendingCall()
                delay(3500)
            }
        }
    }

    private fun stopPolling() {
        isPollingActive = false
        pollingJob?.cancel()
        pollingJob = null
        bridgeStatus.value = "Polling stopped"
    }

    private fun checkPendingCallOnce() {
        lifecycleScope.launch(Dispatchers.IO) {
            queryPendingCall(isSingleCheck = true)
        }
    }

    private suspend fun queryPendingCall(isSingleCheck: Boolean = false) {
        try {
            val url = URL("$bridgeServerUrl/api/pending-call")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val regex = """"phone_number"\s*:\s*"([^"]+)""".toRegex()
                val phoneNumber = regex.find(response)?.groupValues?.get(1)

                if (!phoneNumber.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        bridgeStatus.value = "Dispatching call to $phoneNumber..."
                        makeBridgeCall(phoneNumber)
                    }
                    reportCallComplete()
                } else if (isSingleCheck) {
                    withContext(Dispatchers.Main) {
                        bridgeStatus.value = "No pending calls found on bridge server."
                    }
                }
            } else {
                connection.disconnect()
                if (isSingleCheck) {
                    withContext(Dispatchers.Main) {
                        bridgeStatus.value = "Bridge server returned HTTP $responseCode"
                    }
                }
            }
        } catch (e: Exception) {
            if (isSingleCheck) {
                withContext(Dispatchers.Main) {
                    bridgeStatus.value = "Cannot reach bridge at $bridgeServerUrl"
                }
            }
        }
    }

    private fun reportCallComplete() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$bridgeServerUrl/api/call-complete")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                connection.responseCode
                connection.disconnect()
                withContext(Dispatchers.Main) {
                    bridgeStatus.value = "Call request processed successfully."
                }
            } catch (_: Exception) {
                // Silently ignore completion status reporting issues
            }
        }
    }

    private fun makeBridgeCall(phoneNumber: String) {
        val permission = Manifest.permission.CALL_PHONE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
                startActivity(intent)
            } catch (_: Exception) {
                openDialer(phoneNumber)
            }
        } else {
            openDialer(phoneNumber)
        }
    }

    private fun requestMiniCrmCall(lead: Lead) {
        activeMiniCall = ActiveMiniCall(lead, SystemClock.elapsedRealtime())
        miniCallPaused = false

        val permission = Manifest.permission.CALL_PHONE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${lead.phone}"))
                startActivity(intent)
            } catch (_: Exception) {
                openDialer(lead.phone)
            }
        } else {
            pendingCallAction = {
                try {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${lead.phone}"))
                    startActivity(intent)
                } catch (_: Exception) {
                    openDialer(lead.phone)
                }
            }
            callPermissionLauncher.launch(permission)
        }
    }

    private fun openDialer(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No phone dialer app installed on this device.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (activeMiniCall != null) miniCallPaused = true
    }

    override fun onResume() {
        super.onResume()
        val active = activeMiniCall
        if (active != null && miniCallPaused) {
            val seconds = max(0L, (SystemClock.elapsedRealtime() - active.startedElapsed) / 1000L).toInt()
            postCallRequest.value = PostCallRequest(active.lead, seconds)
            activeMiniCall = null
            miniCallPaused = false
        }
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }
}
