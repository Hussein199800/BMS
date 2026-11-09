package batari.app.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import batari.core.FrameAssembler
import batari.core.FrameResult
import batari.core.ReadRequest
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Transact {
    class Ok(val frame: FrameResult.Frame, val chunks: List<ByteArray>) : Transact
    class NoFrame(val chunks: List<ByteArray>, val errors: List<String>) : Transact
    data object WriteFailed : Transact
    data object Disconnected : Transact
}

/** قناة البيانات التسلسلية المعروفة في وحدات JBD: الخدمة FF00، إشعارات FF01، كتابة الطلبات FF02. */
class UartLink private constructor(
    val session: GattSession,
    val notifyChar: BluetoothGattCharacteristic,
    val writeChar: BluetoothGattCharacteristic,
) {
    /** يفرّغ ما وصل سابقًا دون طلب (حتى لا يختلط ردّ قديم بطلب جديد). */
    fun drain(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) out += session.incoming.tryReceive().getOrNull() ?: break
        return out
    }

    /** طلب واحد في كل مرة: إرسال ثم انتظار إطار كامل صحيح أو انتهاء المهلة. */
    suspend fun transact(request: ReadRequest, assembler: FrameAssembler, timeoutMs: Long): Transact {
        drain()
        assembler.reset()
        if (!session.connected) return Transact.Disconnected
        if (!session.writeReadRequest(writeChar, request)) {
            return if (session.connected) Transact.WriteFailed else Transact.Disconnected
        }
        val chunks = ArrayList<ByteArray>()
        val errors = ArrayList<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val r = withTimeoutOrNull(left) { session.incoming.receiveCatching() } ?: break
            val chunk = r.getOrNull() ?: return Transact.Disconnected
            chunks += chunk
            for (res in assembler.feed(chunk)) {
                when (res) {
                    is FrameResult.Frame -> return Transact.Ok(res, chunks)
                    is FrameResult.Error -> errors += res.reason
                }
            }
        }
        assembler.reset()
        return Transact.NoFrame(chunks, errors)
    }

    companion object {
        val SERVICE = Ble.uuid16(0xFF00)
        val NOTIFY = Ble.uuid16(0xFF01)
        val WRITE = Ble.uuid16(0xFF02)

        fun find(session: GattSession, services: List<BluetoothGattService>): UartLink? {
            val svc = services.firstOrNull { it.uuid == SERVICE } ?: return null
            val n = svc.getCharacteristic(NOTIFY) ?: return null
            val w = svc.getCharacteristic(WRITE) ?: return null
            val canNotify = n.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val canWrite = w.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            return if (canNotify && canWrite) UartLink(session, n, w) else null
        }
    }
}
