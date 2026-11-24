# BLE Wi-Fi 配网模块（`data/ble`）

YI M1 的 Wi-Fi 默认是**关闭**的，而且热点密码**每次会话随机**。唯一能打开它并拿到
SSID/密码的通道是 BLE —— 官方 App 就是这么做的。本模块把这条通道重新实现出来：

```
BLE 配对/开 Wi-Fi → 读取本次会话的 SSID+密码 → 手机加入该热点 → 之后的 HTTP/UDP 控制
```

没有本模块，相机 Wi-Fi 根本不存在，后面的遥控/取景/相册都无从谈起。

---

## 1. 验证状态（重要，先读这段）

| 项 | 状态 |
|---|---|
| 协议来源 | 社区逆向项目 `xiaobaiwud12/yi-m1-controller-app`（Apache-2.0），其维护者在**真机**（固件 `1,3.1-cn,M1CN,0.0`）上实测通过；同时与官方 App 反编译 `common/d.java` 的实现逐条交叉吻合（APK 侧独立复核见 `analysis/apk-re/PROTOCOL-CONTRACT.md` §7、§11.1） |
| 本模块代码 | 纯协议逻辑有 **61 个 JVM 单元测试**覆盖；Android GATT 部分**没有在真机上运行过**（开发环境无相机、无 BLE 外设） |
| 无法验证的部分 | 真实 GATT 时序、写入方式协商在真机上的实际表现、相机屏幕确认流程、`dae/daf/dad` 可选特征、配对 key 的相机侧校验行为 |

> **结论：这是"按已验证协议实现、但未经过本团队真机验证"的模块。** 上真机时按
> §8 的清单逐项确认，尤其是"写入方式协商"和"相机屏幕确认"两步。

协议本身**没有安全机制**（明文整数 key + CRC32 校验和，物理确认才是授权），
不是实现缺陷，不要试图"修好"它。

---

## 2. 协议事实（来源与置信度）

来源优先级：官方 APK 反编译 + 真机实测 > 社区逆向文档。

### 2.1 服务与特征

主服务 `41106dd9-25ad-477b-a884-5038b6de4649`（12 个特征，stride 0x1C，固件镜像里找到同一张表）：

| 特征 | 方向 | 用途 | 载荷 |
|---|---|---|---|
| `41106da0` | 写 | 发起配对 | `"<protocol>,<key>,android"` |
| `41106da1` | — | 官方 App 源码声明了，**真机不存在**（容忍缺失） | — |
| `41106da2` | 读 | 固件/型号信息 | `"1,M1,M1INT,1.1"` |
| `41106da3` | 读 | 状态字符串（相机回 `"OK"`，参考客户端用作存活探测） | `"OK"` |
| `41106da4` | 写 | 开始认证会话 | `"<protocol>,<key>,<crc32>"` |
| `41106da5` | 写 | **开/关相机 Wi-Fi** | `"ON"` / `"OFF"` |
| `41106da6` | 读 | 读 Wi-Fi 凭据 | `"<ssid>,<password>"` |
| `41106da7` | 通知 | 配对结果 | token ASCII；**空载荷 = 被拒绝** |
| `41106da9` | 写 | 解除配对 | 官方 App 写 refId（配对 key） |
| `41106dac` | 写 | **对时** | ASCII Unix 纪元秒 |
| `41106dad` | 写 | 写 `"3"`（用途未公开，官方 App 在发现 `daf` 后写） | `"3"` |
| `41106dae` | 通知 | 镜头信息（可选） | `"3,<...>,<lensType>,<lensVersion>"` |
| `41106daf` | 通知 | 相机状态（可选） | `"ac,<code>"`，code 3/7/8/9 |

CCCD：`00002902-0000-1000-8000-00805f9b34fb`。
标准设备信息特征（`0x2A00/2A01/2A24/2A29`）也在。

### 2.2 握手顺序（**严格按序**，不能提前、不能并行）

```
1. 读 41106da2                     → "1,M1,M1INT,1.1"
                                    字段：BLE 协议版本 / 机身型号 / 区域 / 镜头版本
2. 订阅 41106da7（通知）           ← 必须在写配对请求之前订阅，否则 token 会丢
   （可选）订阅 41106daf / 41106dae，向 41106dad 写 "3"   ← 失败可忽略
3. 写 41106da0                     → "<protocol>,<key>,android"
                                     key ∈ 0..99998 随机
                                     相机屏幕弹出确认框，需人工按 Accept
4. 等待 41106da7 通知              → 非空 = token；空 = 相机拒绝
5. 写 41106da4                     → "<protocol>,<key>,<crc32>"
                                     crc32 = zlib.crc32(ascii("1" + key + token))
6. 读 41106da6                     → "<ssid>,<password>"
7. 写 41106da5                     → "ON"        打开相机 Wi-Fi
8. 写 41106dac                     → "<本地墙钟纪元秒>"  对时（相机无 RTC）
                                     官方 App = (now + 时区偏移 + DST 偏移)/1000
                                     公开资料写的是 plain epoch，两者差一个时区偏移（见 §2.3）
```

CRC32 是 **zlib/IEEE 802.3** 变体（与 Python `zlib.crc32` 相同，多项式
`0xEDB88320`），结果是**无符号 32 位十进制**（`0..4294967295`）。官方 App 用
`java.util.zip.CRC32.getValue()`，即同一变体；`common/d.java:879-883`。

### 2.3 与另一份社区记录的已知矛盾（已处理）

| 矛盾点 | 处理 |
|---|---|
| `41106da2` 第 2 字段是区域还是固件版本 | 官方 App 与社区文档都按"第 2 字段=区域、第 3 字段=镜头版本"解析；另一维护者真机报告"第 2=固件版本、第 3=区域"。`CameraIdentity` **保留全部原始字段**（`rawFields`/`raw`），区域标记取尾部第一个像 `M1*` 的字段，因此两种布局都能解析；真机上应以诊断日志里的原始串为准 |
| 社区文档称"必须无响应写入" vs 官方 APK 用默认（带响应）写入 | 本模块**运行时协商**：先按特征真实属性选（优先无响应），失败换另一种并记住。见 §4 |
| `41106da1` 是否存在 | 真机不存在；代码只做诊断，不依赖它 |
| 对时 epoch 约定：公开资料 = plain `now/1000`；官方 APK = `(now + 时区 + DST)/1000`（本地墙钟） | 以 APK 为准（CONTRACT.md 优先级：APK > 公开资料），默认写**本地墙钟纪元秒**；纯函数同时保留 plain 版本。两者相差时区偏移，影响照片时间显示，真机必须验证（见 §4.8 / OPEN-QUESTIONS Q26） |

---

## 3. 公开 API（给 UI / ViewModel）

```kotlin
class BleProvisioner(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val state: StateFlow<ProvisionState>          // 进度，中文 label 见 ProvisionState.label
    val diagnostics: StateFlow<List<String>>      // 诊断日志（写入方式/GATT 状态/可选特征失败）

    suspend fun findCameras(timeoutMs: Long = 12_000L): List<BleDeviceInfo>
    suspend fun provision(
        device: BleDeviceInfo,
        pairingKey: Int = BleProtocol.generatePairingKey(),  // 0..99998 随机
        turnWifiOn: Boolean = true,
        syncTime: Boolean = true,
        connectTimeoutMs: Long = 20_000L,
        stepTimeoutMs: Long = 10_000L,
        confirmationTimeoutMs: Long = 45_000L,              // 等用户在相机屏幕上确认
    ): Result<WifiCredentials>
    suspend fun setWifi(device: BleDeviceInfo, on: Boolean): Result<Unit>
    suspend fun syncTime(device: BleDeviceInfo): Result<Unit>
    suspend fun unpair(device: BleDeviceInfo, pairingKey: Int): Result<Unit>

    fun close()
    fun isBluetoothSupported(): Boolean
    fun isBluetoothEnabled(): Boolean
    fun requiredPermissions(): List<String>       // 按 API level 返回
    fun missingPermissions(): List<String>
    fun clearDiagnostics()
}
```

失败类型：`BleException.error: BleError`，`error.userMessage()` 是可直接上屏的中文，
`error.isRetryable` 决定是否给"重试"按钮，`error.requiresCameraAction` 表示需要用户
去相机上操作。

```kotlin
// ---- ViewModel 集成（ui-dev 的 ConnectScreen 用；本模块不依赖任何 UI 代码）----
class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val provisioner = BleProvisioner(app)

    val bleState = provisioner.state          // collectAsState() 直接显示 ProvisionState.label
    val bleLog = provisioner.diagnostics

    fun bleProvision() = viewModelScope.launch {
        val camera = provisioner.findCameras().firstOrNull()
            ?: return@launch /* 提示：未找到相机蓝牙信号 */

        provisioner.provision(camera)
            .onSuccess { creds ->
                // 1) 用 creds.ssid / creds.password 走 net/ 的 WifiNetworkSpecifier 入网
                // 2) 入网成功后再 repository.connect() 连 HTTP
            }
            .onFailure { error ->
                val message = (error as? BleException)?.error?.userMessage() ?: error.message
                /* 显示 message */
            }
    }

    override fun onCleared() = provisioner.close()
}
```

权限请求（Compose）：

```kotlin
val requestPermissions = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
) { /* 拒绝后显示 missingPermissions 提示 */ }
Button(onClick = {
    val missing = provisioner.missingPermissions()
    if (missing.isEmpty()) viewModel.bleProvision()
    else requestPermissions.launch(missing.toTypedArray())
}) { Text("蓝牙配网（自动读取相机 Wi-Fi 密码）") }
```

### 3.1 结果与状态

`WifiCredentials(ssid, password, cameraIdentity?, wifiEnabled?, timeSynced)`
- `wifiEnabled = null`：没要求开 Wi-Fi；`false`：写了但相机拒绝（凭据仍然有效，可稍后
  `setWifi(device, true)` 重试）；`true`：已写入 `ON`。
- `maskedPassword` 用于日志；**不要把 password 写日志**。

`ProvisionState`（`state` 流）：`Idle / Scanning / Connecting / Discovering /
ReadingIdentity / IdentityRead / Pairing / AwaitingCameraConfirmation /
StartingSession / ReadingCredentials / EnablingWifi / SyncingTime / Success / Failed`，
每个都有 `.label` 中文短语（如"等待相机屏幕确认配对…"、"已忽略相机端蓝牙配对/请在相机屏幕确认"）。

---

## 4. Android 专有坑（本模块已处理）

### 4.1 写入方式必须运行时协商，不能硬编码

相机特征**只声明 `write`，不声明 `write_no_response`**；但固件在某些路径上对
**带响应写入**返回应用层错误 `0x80`，而无响应写入能被接受。同一份协议在 Windows 上
验证时（栈不校验声明属性）无响应写入直接可用，Android 上则要按 `properties` 位图判断。

本模块（`BleWriteNegotiation.plan` + `BleGattSession.writeCharacteristic`）：

1. 读特征真实 `properties`：同时声明两种 → 优先 `WRITE_TYPE_NO_RESPONSE`；只声明
   `write` → `WRITE_TYPE_DEFAULT`；都没声明 → 先 `DEFAULT`。
2. 选中的方式失败（GATT 返回 false / 状态非 0 / 应用层 0x80）→ **换另一种方式重试**。
3. 成功的方式**记住**（按特征 UUID，跨会话复用，供 `setWifi`/`syncTime` 使用）。

> 超时不算"失败可换写"：带响应写入发出后若回调丢失，再发一次可能写两遍（相机弹两次确认），
> 因此超时直接报错。只有明确被拒（返回 false / 收到非零状态）才换方式。

### 4.2 先订阅再配对

`41106da7` 通知必须在写 `41106da0` **之前**订阅完成（CCCD 写回调返回后才算完成）。
相机可能在一个连接间隔内就回复，后订阅会丢 token。`provision()` 已按此顺序实现。

### 4.3 GATT 服务发现慢 / 133 错误

相机在连接建立后约 1 秒内才发布特征表；参考客户端固定等待 1200 ms。本模块连接成功后
`delay(1200)` 再 `discoverServices()`，并且连接失败（含 Android 常见 `GATT 133`）
**重试一次**（600 ms 后），仍失败才报 `ConnectionFailed`。

### 4.4 相机只保存一个配对

- 新配对会**静默作废**官方 App（或其他手机）的配对；官方 App 配对回来也会作废本 App。
- 相机的解绑（`41106da9`）要求会话状态允许，遗留绑定常见结果是**死锁**：只能在相机
  菜单里"忘记设备/解除绑定"或恢复出厂设置。
- **UI 必须显式告知**（建议文案见 §6）。

### 4.5 相机屏幕需要人工确认 + 区域版本

- 写 `41106da0` 后相机屏幕弹确认框；用户不按 → 等满 `confirmationTimeoutMs`（45 s）
  超时。官方 App 提示 `请查看相机屏幕提示`。
- 相机回**空通知** = 拒绝 → 官方 App 错误码 110 → 文案
  `已忽略相机端蓝牙配对`。常见原因：相机仍绑定别的设备 / 不在允许配对的界面。
- 区域：`M1INT` 国际版 vs `M1CN`/`M1` 国内版。官方 App 对区域不匹配直接报错 112
  （`相机与App版本不兼容`）；本 App 不做区域限制（通用第三方），但**配对可能被相机拒绝**，
  此时提示用户检查区域/固件。BLE 协议版本 `> 1` 时官方 App 报错 113，本模块
  `BleError.CameraUnsupported`（同语义）。
- 配对还可能需要相机处于特定界面（官方文案 `请确保相机开机并插入SD卡`）。

### 4.6 可选特征

`41106daf`、`41106dae` 在实测固件上都存在，但**按可选处理**：订阅失败只记日志，
不影响配网。`41106dad` 写 `"3"` 同样可选。`41106da1` 不做任何依赖。

### 4.7 Wi-Fi 开关先"配对"再"裸写"

`41106da5` 用 `"ON"`/`"OFF"` 切换相机 Wi-Fi。官方 App 在已绑定状态下**不重做会话**，
直接在一条新连接里写 `ON`（`common/d.java:1012-1021`）。所以：

- `provision()` 在会话内直接写 `ON`（最可靠）。
- `setWifi()` 是独立连接裸写，**要求相机仍信任本手机的配对**；若配对已被别的设备顶掉，
  相机回应用层 `0x80`，错误信息会提示重新配网。
- 关 Wi-Fi 也建议走 BLE 的 `"OFF"` 而不是 HTTP `CloseAP`：同一条通道、语义对称，
  且不引入"关了之后 HTTP 没有 OpenAP、只能靠 BLE 恢复"的单向依赖。

### 4.8 对时（约定有争议，按官方 App 实现）

相机**没有 RTC**（固件里 `rtc`/`settimeofday` 零命中，时间只在 RAM）。每次开机都要
对时，否则照片 `capture_date` 全错。载荷 = ASCII 十进制串（固件 `strtol` base 10 后
拆 6 段日历字段，说明它按"纪元秒"解释）。

但"哪个纪元秒"两种资料矛盾：

| 来源 | 载荷 |
|---|---|
| 公开硬件资料（`research/verified/ble-pairing.md`） | `System.currentTimeMillis() / 1000`（plain UTC epoch） |
| **官方 APK**（`$SRC/b/p.java:77-81` + `$SRC/common/d.java:1030`） | `(System.currentTimeMillis() + zoneOffset + dstOffset) / 1000`，即**本地墙钟当作纪元秒** |

对时必须在 **BLE 断开之前**、同一会话内完成（官方 App 在 "AP opened" 回调里立刻 `k()`）；`provision()` 的顺序即是如此。CONTRACT.md 规定 APK 优先，所以本模块默认 `BleProtocol.buildCameraClockPayloadNow()`
（本地墙钟）。理由：相机没有时区概念，固件把收到的数当 UTC 拆日历；加时区偏移后相机
显示的才是本地时间。若真机实测发现时区差，把调用换成
`BleProtocol.buildTimeSyncPayloadNow()` 即可（只有这一个调用点：
`BleProvisioner.syncCameraClock` / `syncTime`）。此差异已记入
`analysis/apk-re/OPEN-QUESTIONS.md` Q26。

### 4.9 BLE 与 Wi-Fi 共用 2.4 GHz 射频

手机同时开 BLE 连接 + 2.4G Wi-Fi 取景时，部分机型会卡顿（社区实测）。入网成功、
开始 HTTP/取景后建议停止 BLE 活动；本模块所有方法都在结束时断开 GATT，不常驻连接。

另外两个环境依赖：
- **API ≤ 30 扫描 BLE 需要系统定位开关打开**（不只是授予 `ACCESS_FINE_LOCATION`）。
  没开时 `startScan` 可能静默无结果；UI 应在无结果时提示用户检查定位开关。
- 写入 `"ON"` 只表示相机接受命令；**热点实际起播需要几秒**。入网失败时应在
  3~5 秒后重试扫描/入网，而不是立刻判定失败。

### 4.10 线程与清理

- 所有公开方法都是 `suspend`，内部 `withContext(ioDispatcher)`，**不阻塞主线程**；
  GATT 回调在 binder 线程，通过 `CompletableDeferred` 恢复协程。
- 每个操作串行化（`Mutex`）：Android 只允许一个 outstanding GATT 操作，相机也只服务
  一个客户端。
- 每个会话在 `finally` 里 `disconnect()` + `close()`，取消/失败/超时都不会泄漏 GATT。
- 失败一律 `Result.failure(BleException)`，从不抛到 UI 线程（`CancellationException` 除外）。

---

## 5. 需要的 Manifest 权限（**未自动添加，请 Lead 加**）

`AndroidManifest.xml`（本模块的写入范围不含 manifest）：

```xml
<!-- BLE 配网：API 31+ 拆分为运行时权限；SCAN 声明 neverForLocation（仅扫 BLE，不推导位置） -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="31" />
<uses-permission
    android:name="android.permission.BLUETOOTH_CONNECT"
    tools:targetApi="31" />

<!-- API <= 30 的旧权限（均需 maxSdkVersion=30，否则新系统上多余） -->
<uses-permission
    android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />

<!-- BLE 只作可选增强：没有 BLE 的设备仍可手动连相机 Wi-Fi 使用 App -->
<uses-feature
    android:name="android.hardware.bluetooth_le"
    android:required="false" />
```

已存在的 `ACCESS_FINE_LOCATION` 需要加 `android:maxSdkVersion="30"`；文档里已经声明
`neverForLocation`，所以 API 31+ 不需要定位权限。运行时请求用
`BleProvisioner.missingPermissions()`。

---

## 6. 建议的 UI 文案（中文，可直接进 `strings.xml`）

| key | 文案 |
|---|---|
| `ble_title` | 蓝牙配网（自动开启相机 Wi-Fi） |
| `ble_scanning` | 正在扫描相机蓝牙… |
| `ble_not_found` | 未找到相机蓝牙信号，请确认相机已开机且蓝牙未被其他设备连接 |
| `ble_await_confirm` | 请查看相机屏幕提示，按"确认/接受"完成配对 |
| `ble_rejected` | 已忽略相机端蓝牙配对/请在相机屏幕确认（空 token，官方错误 110） |
| `ble_confirm_timeout` | 等待相机屏幕确认超时（官方错误 111）；请确认相机屏幕有配对提示后重试 |
| `ble_one_pairing_warning` | 相机只保存一个蓝牙配对：使用本 App 配网会作废官方 App 或其他手机的配对，反之亦然。 |
| `ble_old_binding_hint` | 若一直配对失败，请在相机菜单中「忘记设备/解除绑定」或恢复出厂设置后重试。 |
| `ble_sd_hint` | 请确保相机开机并插入 SD 卡。 |
| `ble_success` | 已读取相机 Wi-Fi：%1$s（密码已自动填写） |
| `ble_wifi_on_failed` | 相机 Wi-Fi 开启失败，可稍后重试或手动在相机菜单打开 Wi-Fi |
| `ble_wrong_area` | 相机与 App 区域/协议版本可能不兼容（官方 App 错误 112/113） |
| `ble_radio_note` | 相机 Wi-Fi 与蓝牙共用 2.4GHz 射频；入网后建议关闭蓝牙以减少取景卡顿。 |

> 安全说明也必须出现在 UI（官方协议无安全机制）：**"该协议为明文协议，配对确认是唯一的
> 授权环节；不要把它当作安全的配对方式。"**

---

## 7. 错误目录与中文提示

`BleError`（`data/ble/BleModels.kt`）→ `userMessage()`：

| 类型 | 何时出现 | 中文提示（要点） |
|---|---|---|
| `BluetoothUnsupported` | 无 BLE 硬件/特性 | 此手机不支持蓝牙低功耗（BLE） |
| `BluetoothOff` | 蓝牙关闭 | 蓝牙未开启，请先在系统设置中打开蓝牙 |
| `PermissionDenied` | 缺运行时权限 | 缺少蓝牙权限（列出权限名） |
| `CameraNotFound` | 扫描无结果 / 地址无效 | 未找到相机蓝牙信号… |
| `ScanFailed(code)` | `onScanFailed` | 蓝牙扫描失败（错误码 N） |
| `ConnectionFailed(status)` | 连不上/连接中断 | 无法连接相机蓝牙（GATT 状态 N） |
| `PairingRejected` | 配对通知为空 | **已忽略相机端蓝牙配对/请在相机屏幕确认** + 旧绑定提示 |
| `Timeout(step)` | 单步超时 | 等待超时：<步骤>（N 秒），提醒看相机屏幕 |
| `GattError(op, char, status)` | 读写失败/0x80 | 蓝牙通信失败：<操作>；0x80 额外提示"配对可能已失效"并给出**相机机身解除旧绑定/恢复出厂**的指引（APP 内无法自解，官方 App 也一样） |
| `ProtocolError(msg)` | 载荷无法解析 | 相机返回了无法识别的数据：… |
| `ServiceMissing` | 无 `41106dd9` 服务 | 这看起来不是 YI M1（或固件不支持 BLE 配网） |
| `CameraUnsupported(msg)` | 缺特征 / 协议版本 >1 | 相机与 App 版本不兼容：… |

---

## 8. 真机验证清单（部署前必须做）

1. 相机开机、插 SD 卡、屏幕停留在任意可配对界面；手机蓝牙开、定位开（API ≤30）。
2. `findCameras()` 能扫到 `YI_M1_*`（诊断日志会打印名字/地址/是否按服务 UUID 命中）。
3. 点配网 → 相机屏幕出现确认框 → 按 Accept。
4. 诊断日志确认：
   - `41106da2` 原始串（记录区域/固件布局）；
   - 每个特征实际 `properties`；
   - 每个特征最终成功的写入方式（`无响应写入` / `带响应写入`）；
   - `Proceeding to read 41106da6` 后拿到的 SSID（密码长度）。
5. 用拿到的 SSID/密码加入相机热点，`192.168.0.10` 的 HTTP 能通。
6. 断开重连/重新开机后：`setWifi(on=true)` 是否仍能打开 Wi-Fi（验证"裸写需有效配对"）；
   若返回 0x80 属预期，重新配网。
7. 换另一台手机配网 → 确认第一台的配对被作废（预期行为）。
8. 记录相机是否弹出两次确认（写入方式协商是否发生回退）。
9. **对时验证**：拍一张照片，检查相机/EXIF 时间是否等于手机**本地时间**；若正好差一个
   时区，说明该相机固件要 plain UTC epoch，把 `BleProvisioner.syncCameraClock` /
   `syncTime` 里的 `buildCameraClockPayloadNow()` 换成 `buildTimeSyncPayloadNow()` 后复测。
10. 官方 App 等待相机屏幕确认的超时是 **20 秒**（错误码 111）；本模块默认放宽到
    `confirmationTimeoutMs = 45 s`，真机上如发现相机侧提示过早消失，可回调该参数。

---

## 9. 测试

纯协议逻辑（`BleProtocol` / `BleWriteNegotiation` / `BleModels`）有 61 个 JVM 测试：

- CRC32 对 zlib 已知值（`""`→0、`"123456789"`→3421780262、CRC 全材料 `"1"+key+token`）；
- 配对/会话/Wi-Fi/对时载荷的精确字符串；
- 固件信息四种布局（标准 / 字段 2-3 互换矛盾 / NUL 填充 / 3 字段）与非法输入；
- 配对通知空=拒绝、token 去 NUL；
- SSID/密码解析（含密码里带逗号、空字段、全 NUL）；
- 配对 key 范围与随机性；相机名启发式；
- 写入方式协商顺序（双声明/仅 write/仅 no-response/未声明/已学习）；
- 错误中文映射、`isRetryable`、`requiresCameraAction`、状态 label。

验证（2026-11-23，本机）：`gradle -p yim1-remote --no-daemon --offline compileDebugKotlin testDebugUnitTest` → **BUILD SUCCESSFUL**（50 s）；Gradle 汇总全项目 138 个单元测试 0 失败，其中 BLE 模块 61 个。

运行：`gradle -p yim1-remote --no-daemon --offline testDebugUnitTest`

Android GATT 部分（`BleProvisioner`/`BleGattSession`）**无法在 JVM 上测试**，只能真机验证。

---

## 10. 文件

| 文件 | 内容 |
|---|---|
| `data/ble/BleProtocol.kt` | UUID 常量、纯 Kotlin CRC32、载荷编解码、`CameraIdentity`/`WifiCredentials`、写入方式协商 |
| `data/ble/BleModels.kt` | `BleDeviceInfo`、`BleError` + 中文消息、`BleException`、`ProvisionState` + label、GATT 状态描述 |
| `data/ble/BleGattSession.kt` | 串行化 GATT 会话：连接/发现/读/写（协商）/通知/清理，回调→协程桥接 |
| `data/ble/BleProvisioner.kt` | 公开协程 API、扫描、严格握手编排、`setWifi`/`syncTime`/`unpair`、诊断日志 |
| `app/src/test/.../data/ble/*.kt` | 61 个 JVM 测试 |
| `docs/BLE-PROVISIONING.md` | 本文档 |

依赖：仅 Android framework `android.bluetooth.*` + 已有 `kotlinx-coroutines-android`；
**没有新增任何 Gradle 依赖**。
