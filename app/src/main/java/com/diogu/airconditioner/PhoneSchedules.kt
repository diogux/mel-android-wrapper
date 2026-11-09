package com.diogu.airconditioner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import java.time.Instant

object PhoneSchedules {
    private fun prefs(context: Context)=context.getSharedPreferences("phone_schedules",Context.MODE_PRIVATE)
    fun list(context: Context): List<WeeklySchedule> = runCatching {
        val a=JSONArray(prefs(context).getString("items","[]"))
        (0 until a.length()).mapNotNull { runCatching { WeeklySchedule.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())
    fun permitted(context: Context)=Build.VERSION.SDK_INT<31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun save(context: Context,schedule: WeeklySchedule) {
        val all=list(context).filter { it.id!=schedule.id }+schedule
        require(all.size<=40) { "Keep at most 40 phone schedules." }
        check(prefs(context).edit().putString("items",JSONArray().also { a -> all.forEach { a.put(it.toJson()) } }.toString()).commit()) { "Could not save schedule." }
        arm(context,schedule)
    }
    fun delete(context: Context,id: String) {
        cancel(context,id)
        val all=list(context).filter { it.id!=id }
        prefs(context).edit().putString("items",JSONArray().also { a -> all.forEach { a.put(it.toJson()) } }.toString()).apply()
    }
    fun disableAll(context: Context) { list(context).forEach { save(context,it.copy(enabled=false)) } }
    fun rearm(context: Context) { list(context).forEach { arm(context,it) } }
    private fun intent(context: Context,id: String,at: Long=0)=Intent(context,ScheduleAlarmReceiver::class.java).setAction("com.diogu.airconditioner.SCHEDULE_FIRE").setData(Uri.parse("climate://schedule/$id")).putExtra("id",id).putExtra("at",at)
    private fun cancel(context: Context,id: String) {
        val pending=PendingIntent.getBroadcast(context,0,intent(context,id),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if(pending!=null) { context.getSystemService(AlarmManager::class.java).cancel(pending); pending.cancel() }
        prefs(context).edit().remove("next:$id").apply()
    }
    private fun arm(context: Context,schedule: WeeklySchedule,after: Instant=Instant.now()) {
        cancel(context,schedule.id)
        if(!schedule.enabled || !permitted(context)) return
        val at=schedule.next(after).toEpochMilli()
        val pending=PendingIntent.getBroadcast(context,0,intent(context,schedule.id,at),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending)
            prefs(context).edit().putLong("next:${schedule.id}",at).apply()
        } catch(_:SecurityException) { /* Permission may have been revoked between check and scheduling. UI shows paused. */ }
    }
    fun next(context: Context,id: String)=prefs(context).getLong("next:$id",0)
    fun last(context: Context,id: String)=prefs(context).getString("last:$id",null)
    @android.annotation.SuppressLint("ApplySharedPref") // Commit the consumed alarm marker before queuing a command.
    fun deliver(context: Context,id: String,at: Long,now: Long=System.currentTimeMillis()) {
        val schedule=list(context).find { it.id==id && it.enabled } ?: return
        val expected=next(context,id)
        if(at<=0 || at!=expected || now<at) return // Old/duplicated/cancelled alarm.
        prefs(context).edit().remove("next:$id").commit()
        val message=if(now-at>120000) "Missed: phone delivered the alarm too late" else {
            val controller=ClimateController.get(context)
            if(controller.enqueue(schedule.unitId,schedule.commands)) "Queued ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(now))} · check cloud status" else "Not queued: ${controller.message}"
        }
        prefs(context).edit().putString("last:$id",message).apply()
        arm(context,schedule,Instant.ofEpochMilli(now))
    }
}
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action=="com.diogu.airconditioner.SCHEDULE_FIRE") PhoneSchedules.deliver(context,intent.getStringExtra("id") ?: return,intent.getLongExtra("at",0))
    }
}
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) PhoneSchedules.rearm(context)
    }
}
