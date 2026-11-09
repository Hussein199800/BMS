package batari.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import batari.core.ReadRequest
import batari.core.ReadRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

object Ble {
    fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
    val CCCD: UUID = uuid16(0x2902)

    fun shortName(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8).uppercase() else s
    }

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** الأذونات المطلوبة للبلوتوث بحسب إصدار أندرويد فقط. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(context: Context): Boolean =
        requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

class OpResult(val status: Int, val value: ByteArray? = null) {
    val ok: Boolean get() = status == BluetoothGatt.GATT_SUCCESS
}

/**
 * جلسة GATT واحدة: عملية واحدة في كل مرة (Mutex)، والإشعارات تصل عبر [incoming].
 * الكتابة الوحيدة الممكنة على الخصائص هي [writeReadRequest] التي لا تقبل إلا طلب قراءة مدرجًا في القائمة المسموحة.
 */
@SuppressLint("MissingPermission")
class GattSession(private val context: Context, private val device: BluetoothDevice) {
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile var connected = false; private set
    @Volatile var lastDisconnectStatus: Int? = null; private set

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    @Volatile private var connectDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var pending: CompletableDeferred<OpResult>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connected = false
                lastDisconnectStatus = status
                connectDeferred?.complete(false)
                pending?.complete(OpResult(BluetoothGatt.GATT_FAILURE))
                incoming.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(OpResult(status)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(OpResult(status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(OpResult(status)) }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) pending?.complete(OpResult(status, c.value?.copyOf()))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(OpResult(status, value.copyOf()))
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { incoming.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }
    }

    suspend fun connect(timeoutMs: Long): Boolean {
        val d = CompletableDeferred<Boolean>()
        connectDeferred = d
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
    }

    suspend fun discoverServices(): List<BluetoothGattService>? {
        val r = op(10_000) { gatt?.discoverServices() == true }
        return if (r?.ok == true) gatt?.services else null
    }

    @Suppress("DEPRECATION")
    suspend fun enableNotifications(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(Ble.CCCD) ?: return true // بعض الوحدات ترسل الإشعارات دون واصف CCCD
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val r = op {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            else { d.value = value; g.writeDescriptor(d) }
        }
        return r?.ok == true
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray? {
        val r = op { gatt?.readCharacteristic(c) == true }
        return if (r?.ok == true) r.value else null
    }

    /** الكتابة الوحيدة على خصائص الجهاز: طلب قراءة مُتحقق منه. */
    @Suppress("DEPRECATION")
    suspend fun writeReadRequest(c: BluetoothGattCharacteristic, request: ReadRequest): Boolean {
        require(ReadRequests.isAllowedRead(request.bytes)) { "محاولة إرسال إطار غير مدرج في طلبات القراءة المسموحة" }
        val g = gatt ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val r = op {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, request.bytes, type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = request.bytes; g.writeCharacteristic(c) }
        }
        return r?.ok == true
    }

    private suspend fun op(timeoutMs: Long = 3_000, start: () -> Boolean): OpResult? = opMutex.withLock {
        if (!connected) return null
        val d = CompletableDeferred<OpResult>()
        pending = d
        try {
            if (!start()) return null
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        incoming.close()
    }
}
