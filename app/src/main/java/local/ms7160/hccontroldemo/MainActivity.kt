package local.ms7160.hccontroldemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class PendingAction { CONNECT }

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private val logBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readerThread: Thread? = null
    @Volatile private var isConnecting = false

    private var pendingAction: PendingAction? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.entries.all { it.value }
        if (granted) {
            when (pendingAction) {
                PendingAction.CONNECT -> connectToDevice()
                null -> Unit
            }
        } else {
            showToast("Bluetooth permission is required")
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.rootLayout)
        val initialPadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPadding[0] + systemBars.left,
                initialPadding[1] + systemBars.top,
                initialPadding[2] + systemBars.right,
                initialPadding[3] + systemBars.bottom
            )
            insets
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, root)?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = false
        }

        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        connectButton = findViewById(R.id.btn_connect)
        disconnectButton = findViewById(R.id.btn_disconnect)

        registerCommandButtons()

        connectButton.setOnClickListener { attemptConnection() }
        disconnectButton.setOnClickListener { disconnectFromDevice() }

        appendLog("Awaiting connection...")

        if (bluetoothAdapter == null) {
            appendLog("Bluetooth not supported on this device")
            connectButton.isEnabled = false
        }

        updateConnectionButtons()
    }

    private fun attemptConnection() {
        if (bluetoothAdapter == null) {
            showToast("Bluetooth adapter unavailable")
            return
        }

        if (!hasBluetoothPermission()) {
            pendingAction = PendingAction.CONNECT
            permissionLauncher.launch(requiredPermissions())
            return
        }

        connectToDevice()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        if (bluetoothAdapter?.isEnabled != true) {
            showToast("Enable Bluetooth in system settings and try again")
            return
        }

        if (bluetoothSocket?.isConnected == true || isConnecting) {
            showToast("Already connected or connecting")
            return
        }

        val targetDevice = findTargetDevice()
        if (targetDevice == null) {
            appendLog("HC-05 not paired. Pair it in Android settings first.")
            return
        }

        isConnecting = true
        appendLog("Connecting to ${targetDevice.name} ...")
        updateConnectionButtons()

        executor.execute {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                bluetoothSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream
                startListeningForInput()
                handler.post {
                    appendLog("Connected to ${targetDevice.name}")
                    updateConnectionButtons()
                }
            } catch (e: IOException) {
                closeConnectionResources()
                handler.post {
                    appendLog("Connection failed: ${e.message}")
                    showToast("Unable to connect")
                    updateConnectionButtons()
                }
            } finally {
                isConnecting = false
            }
        }
    }

    private fun disconnectFromDevice() {
        executor.execute {
            val wasConnected = bluetoothSocket != null
            closeConnectionResources()
            handler.post {
                if (wasConnected) {
                    appendLog("Disconnected from HC-05")
                }
                updateConnectionButtons()
            }
        }
    }

    private fun registerCommandButtons() {
        val commands = mapOf(
            R.id.btn_box1_led_on to "Box1_LED_ON",
            R.id.btn_box2_led_on to "Box2_LED_ON",
            R.id.btn_box3_led_on to "Box3_LED_ON",
            R.id.btn_box1_led_off to "Box1_LED_OFF",
            R.id.btn_box2_led_off to "Box2_LED_OFF",
            R.id.btn_box3_led_off to "Box3_LED_OFF",
            R.id.btn_box1_open to "Box1_OPEN",
            R.id.btn_box2_open to "Box2_OPEN",
            R.id.btn_box3_open to "Box3_OPEN",
            R.id.btn_box1_close to "Box1_CLOSE",
            R.id.btn_box2_close to "Box2_CLOSE",
            R.id.btn_box3_close to "Box3_CLOSE"
        )

        commands.forEach { (viewId, command) ->
            findViewById<Button>(viewId).setOnClickListener { sendCommand(command) }
        }
    }

    private fun sendCommand(command: String) {
        if (bluetoothSocket?.isConnected != true) {
            showToast("Connect to HC-05 first")
            return
        }

        executor.execute {
            try {
                outputStream?.write("$command\n".toByteArray())
                outputStream?.flush()
                handler.post { appendLog("TX: $command") }
            } catch (e: IOException) {
                handler.post {
                    appendLog("Failed to send '$command': ${e.message}")
                    showToast("Send failed")
                }
            }
        }
    }

    private fun startListeningForInput() {
        readerThread?.interrupt()
        readerThread = Thread {
            val buffer = ByteArray(1024)
            while (!Thread.currentThread().isInterrupted) {
                val stream = inputStream ?: break
                try {
                    val count = stream.read(buffer)
                    if (count <= 0) break
                    val incoming = String(buffer, 0, count).trim()
                    if (incoming.isNotEmpty()) {
                        handler.post { appendLog("RX: $incoming") }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            handler.post {
                appendLog("Bluetooth link closed")
                updateConnectionButtons()
            }
        }.apply { isDaemon = true }
        readerThread?.start()
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logBuffer.append("[$timestamp] ").append(message).append('\n')
        logView.text = logBuffer.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionButtons() {
        val connected = bluetoothSocket?.isConnected == true
        connectButton.isEnabled = !connected && !isConnecting && bluetoothAdapter != null
        disconnectButton.isEnabled = connected
    }

    @SuppressLint("MissingPermission")
    private fun findTargetDevice(): BluetoothDevice? {
        return bluetoothAdapter?.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
    }

    private fun closeConnectionResources() {
        try {
            readerThread?.interrupt()
        } catch (_: Exception) {
        }
        readerThread = null
        try {
            inputStream?.close()
        } catch (_: IOException) {
        }
        try {
            outputStream?.close()
        } catch (_: IOException) {
        }
        try {
            bluetoothSocket?.close()
        } catch (_: IOException) {
        }
        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    private fun hasBluetoothPermission(): Boolean {
        val permissions = requiredPermissions()
        if (permissions.isEmpty()) return true
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptyArray()
        }
    }

    override fun onDestroy() {
        disconnectFromDevice()
        super.onDestroy()
    }

    companion object {
        private const val DEVICE_NAME = "HC-05"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
