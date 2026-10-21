package edu.uestc.nightsign.data.repository

import edu.uestc.nightsign.data.network.NightSignApi
import edu.uestc.nightsign.domain.*
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** 查询 -> 时段判断 -> 单次提交 -> 有限复查，便于离线验证提交边界。 */
class CheckinFlow(
    private val api: NightSignApi,
    private val now: () -> ZonedDateTime = CheckinPolicy::now,
    private val pause: suspend () -> Unit = { delay(1_500) },
) {
    suspend fun run(
        settings: SignSettings,
        background: Boolean,
        onSchedule: (CheckinSchedule) -> Unit = {},
    ): CheckinStatus? {
        val time = now().withZoneSameInstant(CheckinPolicy.zone)
        val before = api.today()
        require(before.businessDate == time.toLocalDate().toString()) { "平台业务日期与今天不一致，已停止提交" }
        if (before.checkedIn) {
            require(!before.abnormal) { "今日签到存在异常，请到平台确认" }
            return before
        }
        val schedule = api.schedule()
        onSchedule(schedule)
        if (background && !CheckinPolicy.shouldRun(settings, schedule, time)) return null
        require(schedule.contains(time.hour * 60 + time.minute)) { "当前不在签到时段 ${schedule.label}" }
        require(before.recordStatusCode == "PENDING") { "当前状态 ${before.recordStatusCode} 不允许自动提交" }
        api.submit(settings)
        var after = api.today()
        repeat(2) {
            if (!after.checkedIn) { pause(); after = api.today() }
        }
        require(after.businessDate == before.businessDate && after.checkedIn) { "提交已发送，但尚未确认成功，请刷新状态" }
        require(!after.abnormal) { "平台标记签到异常，请打开网页确认" }
        return after
    }
}
