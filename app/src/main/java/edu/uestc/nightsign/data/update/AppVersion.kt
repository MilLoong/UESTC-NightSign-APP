package edu.uestc.nightsign.data.update

/** 比较语义化版本号。 */
object AppVersion {
    fun normalizeTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun parseParts(version: String): IntArray {
        val nums = Regex("\\d+").findAll(normalizeTag(version)).map { it.value.toInt() }.toList()
        return intArrayOf(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
    }

    fun isRemoteNewer(remote: String, local: String): Boolean {
        val newer = parseParts(remote)
        val current = parseParts(local)
        for (index in newer.indices) {
            if (newer[index] != current[index]) return newer[index] > current[index]
        }
        return false
    }
}
