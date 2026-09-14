package com.smsgateway.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.smsgateway.app.data.ConnectionStatus
import com.smsgateway.app.data.GatewayEventBus
import com.smsgateway.app.data.PreferencesManager
import com.smsgateway.app.databinding.ActivityMainBinding
import com.smsgateway.app.service.SmsGatewayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private val logAdapter = LogAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        if (!smsGranted) {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryDisplay(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupUI()
        checkAndRequestPermissions()
        observeEvents()
    }

    private fun setupUI() {
        binding.etServerUrl.setText(prefs.serverUrl)
        binding.etDeviceToken.setText(prefs.deviceToken)

        // Setup SIM Mode Toggle
        when (prefs.simMode) {
            1 -> binding.toggleSimMode.check(R.id.btnSim1)
            2 -> binding.toggleSimMode.check(R.id.btnSim2)
            else -> binding.toggleSimMode.check(R.id.btnSimAuto)
        }

        updateSimModeLabel(prefs.simMode)

        binding.toggleSimMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnSim1 -> 1
                    R.id.btnSim2 -> 2
                    else -> 0
                }
                prefs.simMode = mode
                updateSimModeLabel(mode)
            }
        }

        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
        }

        updateServiceButtonState()

        binding.btnToggleService.setOnClickListener {
            if (SmsGatewayService.isRunning) {
                stopGatewayService()
            } else {
                startGatewayService()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            GatewayEventBus.clearLogs()
        }
    }

    private fun updateSimModeLabel(mode: Int) {
        binding.tvActiveSimLabel.text = when (mode) {
            1 -> "SIM 1"
            2 -> "SIM 2"
            else -> "Авто"
        }
    }

    private fun startGatewayService() {
        val serverUrl = binding.etServerUrl.text?.toString()?.trim().orEmpty()
        val deviceToken = binding.etDeviceToken.text?.toString()?.trim().orEmpty()

        if (serverUrl.isEmpty()) {
            binding.etServerUrl.error = "URL сервера обязателен"
            return
        }
        if (deviceToken.isEmpty()) {
            binding.etDeviceToken.error = "Токен устройства обязателен"
            return
        }

        prefs.serverUrl = serverUrl
        prefs.deviceToken = deviceToken

        // Request battery optimization ignore
        requestBatteryOptimizationIgnore()

        SmsGatewayService.start(this)
        updateServiceButtonState()
    }

    private fun stopGatewayService() {
        SmsGatewayService.stop(this)
        updateServiceButtonState()
    }

    private fun updateServiceButtonState() {
        if (SmsGatewayService.isRunning) {
            binding.btnToggleService.text = getString(R.string.btn_stop)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#BA1A1A"))
            binding.etServerUrl.isEnabled = false
            binding.etDeviceToken.isEnabled = false
        } else {
            binding.btnToggleService.text = getString(R.string.btn_start)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#0B57D0"))
            binding.etServerUrl.isEnabled = true
            binding.etDeviceToken.isEnabled = true
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            GatewayEventBus.connectionStatus.collectLatest { status ->
                updateStatusBadge(status)
                updateServiceButtonState()
            }
        }

        lifecycleScope.launch {
            GatewayEventBus.logs.collectLatest { logs ->
                logAdapter.submitList(logs)

                // Update Material 3 Metrics
                val sentCount = logs.count { it.status == "SENT" || it.status == "DELIVERED" }
                val deliveredCount = logs.count { it.status == "DELIVERED" }

                binding.tvStatSentCount.text = sentCount.toString()
                binding.tvStatDeliveredCount.text = deliveredCount.toString()
            }
        }
    }

    private fun updateStatusBadge(status: ConnectionStatus) {
        val (text, textColor, bgColor, dotColor) = when (status) {
            ConnectionStatus.CONNECTED -> Quadruple(
                getString(R.string.status_connected),
                Color.parseColor("#07522C"),
                Color.parseColor("#C4EED0"),
                Color.parseColor("#146C2E")
            )
            ConnectionStatus.CONNECTING -> Quadruple(
                getString(R.string.status_connecting),
                Color.parseColor("#7C4A00"),
                Color.parseColor("#FFE7B3"),
                Color.parseColor("#BA7500")
            )
            ConnectionStatus.DISCONNECTED -> Quadruple(
                getString(R.string.status_disconnected),
                Color.parseColor("#444746"),
                Color.parseColor("#E3E3E3"),
                Color.parseColor("#8E9192")
            )
            ConnectionStatus.ERROR -> Quadruple(
                getString(R.string.status_error),
                Color.parseColor("#8C1D18"),
                Color.parseColor("#F9DEDC"),
                Color.parseColor("#B3261E")
            )
        }

        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(textColor)

        val pillBg = binding.pillStatus.background as? GradientDrawable
        pillBg?.setColor(bgColor)

        val dotBg = binding.viewStatusDot.background as? GradientDrawable
        dotBg?.setColor(dotColor)
    }

    private fun updateBatteryDisplay(intent: Intent?) {
        val batteryIntent = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargingStr = if (isCharging) " (Зарядка ⚡)" else ""
        binding.tvBatteryInfo.text = "Батарея: $pct%$chargingStr"
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationIgnore() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateServiceButtonState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Unregistered
        }
    }

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
