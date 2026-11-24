package com.yim1remote.app.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One serialized GATT session with the camera.
 *
 * Android allows only **one outstanding GATT operation** per connection, so every
 * read/write/descriptor call is funnelled through an [opMutex] and matched to its
 * callback by (operation kind, characteristic UUID). All calls suspend; callbacks
 * arrive on binder threads and resume the matching coroutine through a
 * [CompletableDeferred], so nothing ever blocks the main thread.
 *
 * Cleanup is centralised in [close]: the GATT object is always disconnected and
 * closed by the caller's `finally`, and any in-flight operation fails with a
 * typed error instead of hanging.
 *
 * This class is Android-only and therefore not covered by JVM unit tests; the
 * protocol decisions it feeds (payloads, write-type order) live in pure
 * [BleProtocol]/[BleWriteNegotiation].
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class BleGattSession(
    private val log: (String) -> Unit,
    /**
     * Write flavour that worked, keyed by characteristic UUID. The provisioner
     * passes one shared map so a flavour learned in one session is reused by later
     * sessions (e.g. [BleProvisioner.setWifi] after a successful pairing).
     */
    private val writeTypes: MutableMap<UUID, BleWriteKind> = mutableMapOf(),
) : BluetoothGattCallback() {

    private sealed interface GattEvent {
        data class Notification(val uuid: UUID, val value: ByteArray) : GattEvent
        data object Disconnected : GattEvent
    }

    private enum class OpKind { DISCOVER, READ, WRITE, DESCRIPTOR }

    private sealed interface OpResult {
        data class Ok(val value: ByteArray? = null) : OpResult
        data class Failed(val status: Int) : OpResult
    }

    private data class PendingOp(
        val kind: OpKind,
        val uuid: UUID,
        val deferred: CompletableDeferred<OpResult>,
    )

    private sealed interface WriteOutcome {
        data object Success : WriteOutcome
        data class Failure(val status: Int?, val detail: String) : WriteOutcome
    }

    private val events = Channel<GattEvent>(Channel.UNLIMITED)
    private val opMutex = Mutex()

    /** Completed with `null` on connect, or with the GATT failure status. */
    private val connectionSignal = CompletableDeferred<Int?>()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var pending: PendingOp? = null

    @Volatile
    private var closed = false

    fun attach(bluetoothGatt: BluetoothGatt) {
        gatt = bluetoothGatt
    }

    // ------------------------------------------------------------------ connect

    /** Suspends until the GATT connection is up; throws [BleError.Timeout]/[BleError.ConnectionFailed]. */
    suspend fun awaitConnected(timeoutMs: Long) {
        val failure = try {
            withTimeout(timeoutMs) { connectionSignal.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException(BleError.Timeout("连接相机蓝牙", timeoutMs))
        }
        if (failure != null) {
            throw BleException(BleError.ConnectionFailed(failure, BleGattStatus.describe(failure)))
        }
    }

    // --------------------------------------------------------------- discovery

    suspend fun discoverServices(timeoutMs: Long) {
        opMutex.withLock {
            val result = try {
                await("发现相机蓝牙服务", timeoutMs, OpKind.DISCOVER, BleUuids.SERVICE) {
                    requireGatt().discoverServices()
                }
            } catch (e: SecurityException) {
                throw BleException(BleError.PermissionDenied(emptyList()), e)
            }
            if (result is OpResult.Failed) {
                throw BleException(
                    BleError.GattError(
                        operation = "发现 GATT 服务",
                        characteristic = "-",
                        gattStatus = result.status,
                        detail = BleGattStatus.describe(result.status),
                    ),
                )
            }
            val service = requireGatt().getService(BleUuids.SERVICE)
                ?: throw BleException(BleError.ServiceMissing())
            log(
                "发现控制服务：${service.characteristics.joinToString(", ") { BleUuids.shortName(it.uuid) }}",
            )
        }
    }

    // -------------------------------------------------------------------- read

    suspend fun readCharacteristic(uuid: UUID, step: String, timeoutMs: Long): ByteArray =
        opMutex.withLock {
            val characteristic = requireCharacteristic(uuid, step)
            val result = try {
                await(step, timeoutMs, OpKind.READ, uuid) {
                    requireGatt().readCharacteristic(characteristic)
                }
            } catch (e: SecurityException) {
                throw BleException(BleError.PermissionDenied(emptyList()), e)
            }
            when (result) {
                is OpResult.Ok -> result.value ?: ByteArray(0)
                is OpResult.Failed -> throw gattFailure(step, uuid, result.status)
            }
        }

    // ------------------------------------------------------------------- write

    /**
     * Writes [data] using the runtime-negotiated write flavour:
     * learned type first, otherwise the advertised one (no-response preferred
     * when both are advertised), then the other flavour as fallback. The flavour
     * that succeeds is remembered for the rest of the session.
     */
    suspend fun writeCharacteristic(uuid: UUID, data: ByteArray, step: String, timeoutMs: Long) {
        opMutex.withLock {
            val characteristic = requireCharacteristic(uuid, step)
            val plan = BleWriteNegotiation.plan(
                properties = characteristic.properties,
                learned = writeTypes[uuid],
            )
            log("$step：写入 ${data.size} 字节（尝试 ${plan.joinToString(" / ") { it.label }}）")
            var lastStatus: Int? = null
            var lastDetail: String? = null
            for (kind in plan) {
                when (val outcome = attemptWrite(characteristic, uuid, data, kind, step, timeoutMs)) {
                    WriteOutcome.Success -> {
                        writeTypes[uuid] = kind
                        return
                    }

                    is WriteOutcome.Failure -> {
                        lastStatus = outcome.status
                        lastDetail = outcome.detail
                        log("$step：${kind.label}失败（${outcome.detail}）")
                        if (outcome.status == BleGattStatus.DISCONNECTED) {
                            throw gattFailure(step, uuid, outcome.status)
                        }
                    }
                }
            }
            throw BleException(
                BleError.GattError(
                    operation = step,
                    characteristic = BleUuids.label(uuid),
                    gattStatus = lastStatus,
                    detail = lastDetail,
                ),
            )
        }
    }

    private suspend fun attemptWrite(
        characteristic: BluetoothGattCharacteristic,
        uuid: UUID,
        data: ByteArray,
        kind: BleWriteKind,
        step: String,
        timeoutMs: Long,
    ): WriteOutcome {
        return try {
            characteristic.writeType = when (kind) {
                BleWriteKind.NO_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                BleWriteKind.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            characteristic.setValue(data)
            if (kind == BleWriteKind.NO_RESPONSE) {
                // Fire-and-forget: Android does not guarantee a callback for a
                // write-without-response, so "queued successfully" is the strongest
                // signal available. Pause briefly so the radio can flush before the
                // next GATT operation is issued.
                if (requireGatt().writeCharacteristic(characteristic)) {
                    delay(NO_RESPONSE_SETTLE_MS)
                    WriteOutcome.Success
                } else {
                    WriteOutcome.Failure(null, "Android 拒绝发起无响应写入")
                }
            } else {
                when (val result = await(step, timeoutMs, OpKind.WRITE, uuid) {
                    requireGatt().writeCharacteristic(characteristic)
                }) {
                    is OpResult.Ok -> WriteOutcome.Success
                    is OpResult.Failed -> WriteOutcome.Failure(result.status, BleGattStatus.describe(result.status))
                }
            }
        } catch (e: BleException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            WriteOutcome.Failure(null, e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------ notifications

    /**
     * Enables notifications/indications on [uuid] and writes the CCCD descriptor.
     *
     * @return true when subscribed; false when [required] is false and the camera
     *         does not offer the characteristic/descriptor or rejects the subscription.
     */
    suspend fun enableNotifications(
        uuid: UUID,
        required: Boolean,
        step: String,
        timeoutMs: Long,
    ): Boolean = opMutex.withLock {
        val characteristic = findCharacteristic(uuid)
        if (characteristic == null) {
            if (required) {
                throw BleException(
                    BleError.CameraUnsupported("相机缺少特征 ${BleUuids.label(uuid)}，无法完成配对"),
                )
            }
            log("$step：相机没有该特征，跳过")
            return@withLock false
        }
        val localEnabled = try {
            requireGatt().setCharacteristicNotification(characteristic, true)
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            log("$step：打开本地通知开关失败（${e.message}）")
            false
        }
        if (!localEnabled) {
            if (required) {
                throw BleException(
                    BleError.GattError(step, BleUuids.label(uuid), null, "无法打开本地通知开关"),
                )
            }
            return@withLock false
        }
        val descriptor = characteristic.getDescriptor(BleUuids.CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            if (required) {
                throw BleException(
                    BleError.CameraUnsupported(
                        "特征 ${BleUuids.label(uuid)} 没有 CCCD 描述符，无法订阅通知",
                    ),
                )
            }
            log("$step：缺少 CCCD 描述符，跳过")
            return@withLock false
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val result = try {
            await(step, timeoutMs, OpKind.DESCRIPTOR, uuid) {
                requireGatt().writeDescriptor(descriptor)
            }
        } catch (e: BleException) {
            if (required) throw e
            log("$step：订阅失败（${e.message}），可选通知跳过")
            return@withLock false
        }
        when (result) {
            is OpResult.Ok -> true
            is OpResult.Failed -> {
                if (required) throw gattFailure(step, uuid, result.status)
                log("$step：订阅失败（${BleGattStatus.describe(result.status)}），可选通知跳过")
                false
            }
        }
    }

    /** Suspends until a notification from [uuid] arrives; throws on timeout/disconnect. */
    suspend fun awaitNotification(uuid: UUID, step: String, timeoutMs: Long): ByteArray {
        try {
            return withTimeout(timeoutMs) {
                var received: ByteArray? = null
                while (received == null) {
                    when (val event = events.receive()) {
                        is GattEvent.Notification -> if (event.uuid == uuid) received = event.value
                        GattEvent.Disconnected ->
                            throw BleException(BleError.ConnectionFailed(null, "等待通知时蓝牙连接断开"))
                    }
                }
                received ?: ByteArray(0)
            }
        } catch (e: TimeoutCancellationException) {
            throw BleException(BleError.Timeout(step, timeoutMs))
        } catch (e: ClosedReceiveChannelException) {
            throw BleException(BleError.ConnectionFailed(null, "蓝牙会话已关闭"))
        }
    }

    fun hasCharacteristic(uuid: UUID): Boolean = findCharacteristic(uuid) != null

    // ------------------------------------------------------------------- close

    /** Idempotent cleanup: fails pending operations, disconnects and releases the GATT object. */
    fun close() {
        if (closed) return
        closed = true
        events.close()
        failPending(BleGattStatus.DISCONNECTED)
        val localGatt = gatt
        gatt = null
        if (localGatt != null) {
            try {
                localGatt.disconnect()
            } catch (e: RuntimeException) {
                log("断开 GATT 失败（忽略）：${e.message}")
            }
            try {
                localGatt.close()
            } catch (e: RuntimeException) {
                log("释放 GATT 失败（忽略）：${e.message}")
            }
        }
    }

    // --------------------------------------------------------- GATT callbacks

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when {
            newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                log("GATT 已连接")
                connectionSignal.complete(null)
            }

            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                log("GATT 已断开（status=$status）")
                connectionSignal.complete(status.takeIf { it != BluetoothGatt.GATT_SUCCESS }
                    ?: BleGattStatus.DISCONNECTED)
                failPending(if (status == BluetoothGatt.GATT_SUCCESS) BleGattStatus.DISCONNECTED else status)
                events.trySend(GattEvent.Disconnected)
            }

            status != BluetoothGatt.GATT_SUCCESS -> {
                log("GATT 连接失败（status=$status, newState=$newState）")
                connectionSignal.complete(status)
                failPending(status)
                events.trySend(GattEvent.Disconnected)
            }

            else -> log("GATT 状态变化（status=$status, newState=$newState）")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        completePending(OpKind.DISCOVER, BleUuids.SERVICE, status, null)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        completePending(OpKind.READ, characteristic.uuid, status, characteristic.value)
    }

    /** Android 13+ overload; the framework may deliver only this one on newer APIs. */
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        completePending(OpKind.READ, characteristic.uuid, status, value)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        completePending(OpKind.WRITE, characteristic.uuid, status, null)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        completePending(OpKind.DESCRIPTOR, descriptor.characteristic.uuid, status, null)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        events.trySend(GattEvent.Notification(characteristic.uuid, characteristic.value ?: ByteArray(0)))
    }

    /** Android 13+ overload; the framework may deliver only this one on newer APIs. */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        events.trySend(GattEvent.Notification(characteristic.uuid, value))
    }

    // ------------------------------------------------------------------ helpers

    private fun completePending(kind: OpKind, uuid: UUID, status: Int, value: ByteArray?) {
        val op = pending
        if (op == null || op.kind != kind || op.uuid != uuid) return
        pending = null
        op.deferred.complete(
            if (status == BluetoothGatt.GATT_SUCCESS) OpResult.Ok(value) else OpResult.Failed(status),
        )
    }

    private fun failPending(status: Int) {
        val op = pending ?: return
        pending = null
        op.deferred.complete(OpResult.Failed(status))
    }

    private suspend fun await(
        step: String,
        timeoutMs: Long,
        kind: OpKind,
        uuid: UUID,
        start: (() -> Boolean)?,
    ): OpResult {
        val deferred = CompletableDeferred<OpResult>()
        val op = PendingOp(kind, uuid, deferred)
        pending = op
        try {
            if (start != null && !start()) {
                throw BleException(
                    BleError.GattError(
                        operation = step,
                        characteristic = BleUuids.label(uuid),
                        detail = "Android 拒绝发起 GATT 操作（设备忙或已断开）",
                    ),
                )
            }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                if (closed) throw BleException(BleError.ConnectionFailed(null, "蓝牙会话已关闭"), e)
                throw BleException(BleError.Timeout(step, timeoutMs), e)
            }
        } finally {
            if (pending === op) pending = null
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleException(BleError.ConnectionFailed(null, "GATT 已释放"))

    private fun requireCharacteristic(uuid: UUID, step: String): BluetoothGattCharacteristic {
        val service = requireGatt().getService(BleUuids.SERVICE)
            ?: throw BleException(BleError.ServiceMissing())
        return service.getCharacteristic(uuid)
            ?: throw BleException(
                BleError.CameraUnsupported(
                    "相机缺少特征 ${BleUuids.label(uuid)}（$step 无法继续）",
                ),
            )
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(BleUuids.SERVICE)?.getCharacteristic(uuid)

    private fun gattFailure(step: String, uuid: UUID, status: Int): BleException {
        if (status == BleGattStatus.DISCONNECTED) {
            return BleException(BleError.ConnectionFailed(null, "操作进行中蓝牙连接断开"))
        }
        return BleException(
            BleError.GattError(
                operation = step,
                characteristic = BleUuids.label(uuid),
                gattStatus = status,
                detail = BleGattStatus.describe(status),
            ),
        )
    }

    private companion object {
        const val NO_RESPONSE_SETTLE_MS = 120L
    }
}
