package edu.uestc.nightsign.ui.compose

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.uestc.nightsign.ui.viewmodel.MainUiState
import edu.uestc.nightsign.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WechatAuthorizationSection(state: MainUiState, viewModel: MainViewModel,
    canGenerate: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bytes = remember(state.wechatQr) {
        state.wechatQr?.let { runCatching { Base64.decode(it.image.substringAfter(','), Base64.DEFAULT) }.getOrNull() }
    }
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val image = pendingImage
        pendingImage = null
        if (uri != null && image != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(image) }
            } }
            viewModel.showMessage(if (result.isSuccess) "二维码已保存" else "保存失败，请重试")
        }
    }
    val authorized = state.wechatCode.enabled
    Text(if (authorized) "微信已授权" else "尚未授权微信")
    Text("在另一台设备上展示二维码，用微信摄像头扫码并确认登录。",
        style = MaterialTheme.typography.bodySmall)
    if (state.qrStatus.isNotBlank()) Text(state.qrStatus)
    if (bitmap != null) Image(bitmap, "微信登录二维码", Modifier.fillMaxWidth().height(240.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.createWechatQr(state.wechatCode) },
            enabled = !state.busy && !state.qrBusy && canGenerate) {
            Text(if (authorized) "重新授权" else "生成二维码")
        }
        if (state.qrBusy) OutlinedButton(onClick = viewModel::cancelWechatQr) { Text("取消") }
    }
    if (bytes != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val image = bytes
            if (Build.VERSION.SDK_INT < 29) {
                pendingImage = image
                saveDocument.launch("微信授权-${System.currentTimeMillis()}.jpg")
            } else scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "微信授权-${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UESTCNightSign")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    try {
                        checkNotNull(resolver.openOutputStream(uri)).use { it.write(image) }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        check(resolver.update(uri, values, null, null) == 1)
                    } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
                } }
                viewModel.showMessage(if (result.isSuccess) "二维码已保存到相册" else "保存失败，请重试")
            }
        }) { Text("保存二维码") }
        OutlinedButton(onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure)
                viewModel.showMessage("无法打开微信，请手动打开")
        }) { Text("打开微信") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::testWechatAuthorization,
            enabled = authorized && !state.busy && !state.qrBusy && canGenerate) { Text("仅测试授权") }
    }
}
