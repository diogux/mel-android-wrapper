package com.diogu.airconditioner

import org.json.JSONObject
import org.json.JSONArray
import java.time.*
import java.util.UUID

/** Phone-local definition, not an unverified cloud schedule payload. Monday=1, Sunday=7. */
data class WeeklySchedule(val id: String=UUID.randomUUID().toString(),val unitId: String,val hour: Int,val minute: Int,val days: Set<Int>,val zone: String,val enabled: Boolean=true,val commands: Map<String,Any>) {
    init {
        require(hour in 0..23 && minute in 0..59 && days.isNotEmpty() && days.all { it in 1..7 })
        ZoneId.of(zone)
        require(commands["power"] is Boolean)
        require(commands.keys.all { it in CONTROL_FIELDS })
    }
    fun next(after: Instant): Instant {
        val tz=ZoneId.of(zone); val today=after.atZone(tz).toLocalDate()
        for(offset in 0L..7L) {
            val date=today.plusDays(offset)
            if(date.dayOfWeek.value !in days) continue
            // atZone moves nonexistent spring times forward and picks the earlier autumn offset.
            val time=date.atTime(hour,minute).atZone(tz).toInstant()
            if(time>after) return time
        }
        error("No next occurrence")
    }
    fun toJson()=JSONObject().put("id",id).put("unitId",unitId).put("hour",hour).put("minute",minute).put("days",JSONArray(days.sorted())).put("zone",zone).put("enabled",enabled).put("commands",JSONObject(commands))
    fun timeText()=String.format(java.util.Locale.getDefault(),"%02d:%02d",hour,minute)
    fun daysText()=if(days.size==7) "Every day" else days.sorted().joinToString(" · ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT,java.util.Locale.getDefault()) }
    companion object {
        fun fromJson(j: JSONObject): WeeklySchedule {
            val days=j.getJSONArray("days"); val commands=j.getJSONObject("commands")
            return WeeklySchedule(j.getString("id"),j.getString("unitId"),j.getInt("hour"),j.getInt("minute"),(0 until days.length()).map { days.getInt(it) }.toSet(),j.getString("zone"),j.optBoolean("enabled",true),commands.keys().asSequence().associateWith { commands.get(it) })
        }
    }
}
