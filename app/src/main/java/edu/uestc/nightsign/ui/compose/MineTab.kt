package edu.uestc.nightsign.ui.compose

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.uestc.nightsign.AppLinks
import edu.uestc.nightsign.BuildConfig
import edu.uestc.nightsign.ui.viewmodel.MainUiState

@Composable
fun MineTab(
    state: MainUiState,
    enabled: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onWebLogin: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val status = state.overview?.status
    Text("我的", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.signedIn && status != null) {
                ProfileRow("学号", status.studentSn.ifBlank { "暂未获取" })
                ProfileRow("姓名", status.studentName.ifBlank { "暂未获取到姓名" })
            } else if (state.signedIn) {
                Text("已登录，刷新后可同步学号与姓名。")
            } else {
                Text("未登录，登录后可在此查看学号与姓名。")
            }
            HorizontalDivider()
            if (state.signedIn) {
                OutlinedButton(onClick = onLogout, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("退出登录")
                }
                Text(
                    "退出后会停止本机后台签到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = onLogin, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("账号登录")
                }
            }
            OutlinedButton(onClick = onWebLogin, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("网页登录")
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(top = 8.dp))
            Text(
                "启动后会按间隔自动检查。也可以在这里手动检查 GitHub 发布。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedButton(onClick = onCheckUpdate, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("检查更新")
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("开源", style = MaterialTheme.typography.titleMedium)
            Text("晚安成电", modifier = Modifier.padding(top = 8.dp))
            Text(
                AppLinks.GITHUB_REPO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.GITHUB_REPO)))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("GitHub: UESTC-NightSign-APP") }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Text("$label：$value", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
}
