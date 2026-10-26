package edu.uestc.nightsign.ui.compose

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.uestc.nightsign.BuildConfig
import edu.uestc.nightsign.MapPickerActivity
import edu.uestc.nightsign.WebLoginActivity
import edu.uestc.nightsign.domain.*
import edu.uestc.nightsign.data.wechat.ServerPairingOffer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import edu.uestc.nightsign.QrScannerActivity
import edu.uestc.nightsign.ui.theme.AppTheme
import edu.uestc.nightsign.ui.theme.UestcHelperTheme
import edu.uestc.nightsign.ui.viewmodel.MainUiState
import edu.uestc.nightsign.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val web = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) viewModel.refresh()
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    UestcHelperTheme(themeKey = state.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Text("晚安成电") }, actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) { Icon(Icons.Default.Refresh, "刷新") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        "签到" to Icons.Default.DateRange,
                        "设置" to Icons.Default.Settings,
                        "记录" to Icons.Default.List,
                        "我的" to Icons.Default.Person,
                    ).forEachIndexed { index, item ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                        }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (tab) {
                        0 -> CheckinTab(state, onCheckin = { confirm = true })
                        1 -> SettingsTab(state, viewModel)
                        2 -> RecordsTab(state, viewModel)
                        else -> MineTab(
                            state = state,
                            enabled = !state.busy,
                            onLogin = viewModel::showLogin,
                            onLogout = viewModel::logout,
                            onWebLogin = { web.launch(Intent(context, WebLoginActivity::class.java)) },
                            onCheckUpdate = { viewModel.checkAppUpdate(force = true) },
                        )
                    }
                }
            }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认提交签到？") },
            text = { Text("将使用已保存的 GCJ-02 坐标和地址提交。请确认它们对应你当前的实际位置。提交后会自动复查结果。") },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.checkin() }) { Text("提交并复查") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
        if (state.showLogin) LoginDialog(state, viewModel)
        state.updatePrompt?.let { update ->
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdatePrompt,
                title = { Text("发现新版本 ${update.versionLabel}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("当前版本：${BuildConfig.VERSION_NAME}")
                        Text(
                            update.releaseNotes.ifBlank { "建议下载安装最新版本。" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))) }
                        viewModel.clearUpdatePrompt()
                    }) { Text("前往下载") }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissUpdatePrompt) { Text("稍后") } },
            )
        }
    }
}

@Composable
private fun RecordsTab(state: MainUiState, viewModel: MainViewModel) {
    Text("本机运行记录", style = MaterialTheme.typography.titleLarge)
    Text("仅保留最近 40 条，不记录密码、Cookie 或接口原始响应。", style = MaterialTheme.typography.bodySmall)
    if (state.events.isEmpty()) Text("尚无运行记录")
    state.events.forEach { Text(it, style = MaterialTheme.typography.bodyMedium); HorizontalDivider() }
    TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新平台状态") }
}

@Composable
private fun CheckinTab(state: MainUiState, onCheckin: () -> Unit) {
    val overview = state.overview
    Text("今日签到", style = MaterialTheme.typography.titleLarge)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val status = overview?.status
            Text(when {
                status == null -> "尚未获取状态"
                status.abnormal -> "签到异常，请到平台确认"
                status.checkedIn -> "今日已签到"
                status.recordStatusCode == "PENDING" -> "等待签到"
                else -> status.recordStatusCode
            }, style = MaterialTheme.typography.headlineSmall)
            Text(status?.businessDate ?: "登录后读取学校平台状态")
            status?.checkinTime?.let { Text("签到时间：$it") }
            if (!status?.locationName.isNullOrBlank()) Text("签到地点：${status?.locationName}")
            if (status?.checkedIn != true) Button(onClick = onCheckin, enabled = !state.busy && overview != null,
                modifier = Modifier.fillMaxWidth()) { Text("立即签到") }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("平台时段")
        Text(overview?.schedule?.label ?: "待获取")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("本机后台签到")
        Text(if (state.settings.autoEnabled) "已开启 · ${formatMinute(state.settings.targetMinute)} 后" else "未开启")
    }
    HorizontalDivider()
    Text("允许的签到地点", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (overview?.locations.isNullOrEmpty()) Text("登录并刷新后显示平台分配的地点")
        overview?.locations?.forEach { AssignedLocationRow(it) }
    }
    if (!state.signedIn) Text("请先在「我的」登录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("签到时段和结果以学校平台为准。本机调度可能被省电策略延迟，请留意通知并及时确认结果。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsTab(state: MainUiState, viewModel: MainViewModel) {
    WechatCodeSection(state, viewModel)
    HorizontalDivider()
    var latitude by rememberSaveable(state.settings.latitude) { mutableStateOf(state.settings.latitude) }
    var longitude by rememberSaveable(state.settings.longitude) { mutableStateOf(state.settings.longitude) }
    var address by rememberSaveable(state.settings.address) { mutableStateOf(state.settings.address) }
    var time by rememberSaveable(state.settings.targetMinute) { mutableStateOf(formatMinute(state.settings.targetMinute)) }
    var auto by rememberSaveable(state.settings.autoEnabled) { mutableStateOf(state.settings.autoEnabled) }
    var invalidTime by remember { mutableStateOf(false) }
    fun settings() = SignSettings(latitude.trim(), longitude.trim(), address.trim(), parseMinute(time) ?: -1, auto)
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) viewModel.loadCurrentLocation { lat, lng, resolved -> latitude = lat; longitude = lng; address = resolved }
        else viewModel.showMessage("需要定位权限才能加载当前位置")
    }
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        latitude = data.getStringExtra(MapPickerActivity.EXTRA_LAT).orEmpty()
        longitude = data.getStringExtra(MapPickerActivity.EXTRA_LNG).orEmpty()
        address = data.getStringExtra(MapPickerActivity.EXTRA_ADDR).orEmpty()
        viewModel.showMessage("已从地图填入坐标和地址，请保存设置")
    }
    fun loadLocation() {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.loadCurrentLocation { lat, lng, resolved -> latitude = lat; longitude = lng; address = resolved }
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Text("签到位置", style = MaterialTheme.typography.titleLarge)
    Text("打开地图选点，或加载当前位置。坐标和地址会一起填入，结果为 GCJ-02。",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度 latitude") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(longitude, { longitude = it }, label = { Text("经度 longitude") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(address, { address = it }, label = { Text("地址") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { mapPicker.launch(MapPickerActivity.createIntent(context, latitude, longitude, state.theme)) },
            enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("打开地图") }
        Button(onClick = { loadLocation() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("当前位置") }
    }
    HorizontalDivider()
    Text("本机后台调度", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(time, { time = it; invalidTime = false }, label = { Text("目标时间 HH:mm") },
        isError = invalidTime, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("开启后台签到", modifier = Modifier.weight(1f))
        Switch(checked = auto, onCheckedChange = { auto = it }, enabled = !state.busy)
    }
    Text("每约 15 分钟本机检查一次，目标时间后、平台时段内尝试签到。系统休眠或强行停止应用可能导致延迟或漏签，不能保证整点执行。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { invalidTime = parseMinute(time) == null; if (!invalidTime) viewModel.saveSettings(settings()) },
        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
    HorizontalDivider()
    Text("主题", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(AppTheme.BLUE, AppTheme.GREEN, AppTheme.PINK).forEach { theme ->
            FilterChip(selected = state.theme == theme.key, onClick = { viewModel.setTheme(theme.key) }, label = { Text(theme.displayName) })
        }
    }
}

@Composable
private fun WechatCodeSection(state: MainUiState, viewModel: MainViewModel) {
    Text("微信授权", style = MaterialTheme.typography.titleLarge)
    var offer by remember { mutableStateOf<ServerPairingOffer?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            runCatching { ServerPairingOffer.parse(raw) }.onSuccess { offer = it }
                .onFailure { viewModel.showMessage(it.message ?: "绑定码无效") }
        }
    }
    val connected = state.wechatCode.accessToken.isNotBlank()
    if (connected) Text("已连接：${state.wechatCode.endpoint}", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(QrScannerActivity::class.java)
            .setPrompt("扫描服务器管理页面的绑定二维码").setBeepEnabled(false).setOrientationLocked(true)) },
            enabled = !state.busy && !state.qrBusy) { Text(if (connected) "重新绑定服务器" else "扫码连接服务器") }
        if (connected) TextButton(onClick = viewModel::disconnectServer,
            enabled = !state.busy && !state.qrBusy) { Text("断开连接") }
    }
    if (connected) {
        WechatAuthorizationSection(state, viewModel)
        Text("测试授权不会提交签到。", style = MaterialTheme.typography.bodySmall)
    }
    offer?.let { pending -> AlertDialog(onDismissRequest = { offer = null },
        title = { Text("连接服务器") }, text = { Text("确认信任此服务器？\n${pending.endpoint}") },
        confirmButton = { TextButton(onClick = { offer = null; viewModel.pairServer(pending) }) { Text("信任并连接") } },
        dismissButton = { TextButton(onClick = { offer = null }) { Text("取消") } }) }
}

@Composable
private fun AssignedLocationRow(location: CheckinLocation) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(location.name, modifier = Modifier.weight(1f))
        Text(
            "半径 ${location.radius.toInt()} 米",
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LoginDialog(state: MainUiState, viewModel: MainViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var rememberAccount by remember { mutableStateOf(false) }
    val sms = state.smsPrompt != null
    AlertDialog(onDismissRequest = viewModel::closeLogin, title = { Text(if (sms) "短信二次认证" else "统一身份认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sms) {
                    Text(state.smsPrompt.orEmpty().lines().filterNot { it.startsWith("SMS_") }.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it }, label = { Text("短信验证码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                    TextButton(onClick = viewModel::resendSms, enabled = !state.busy && state.resendSeconds == 0) {
                        Text(if (state.resendSeconds > 0) "重新发送 (${state.resendSeconds}s)" else "重新发送验证码")
                    }
                } else {
                    Text("使用学校统一认证账号。需要图形验证时，请改用「我的」里的网页登录。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it }, label = { Text("学号") }, singleLine = true,
                        enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                        enabled = !state.busy, visualTransformation = PasswordVisualTransformation())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberAccount, { rememberAccount = it }, enabled = !state.busy)
                        Text("加密保存账号，用于后台续登", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("不勾选则只保存会话。后台遇到短信验证会暂停并通知，不会自动发送短信。", style = MaterialTheme.typography.bodySmall)
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (sms) viewModel.submitSms(code) else viewModel.login(username.trim(), password, rememberAccount) },
                enabled = !state.busy && if (sms) code.isNotBlank() else username.isNotBlank() && password.isNotBlank()) {
                Text(if (sms) "提交验证码" else "登录")
            }
        }, dismissButton = { TextButton(onClick = viewModel::closeLogin) { Text("取消") } })
}
