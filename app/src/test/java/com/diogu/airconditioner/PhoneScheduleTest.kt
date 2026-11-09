package com.diogu.airconditioner

import android.app.AlarmManager
import android.content.Context
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneScheduleTest {
    private lateinit var app: Context
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication()
        ClimateController::class.java.getDeclaredField("instance").apply { isAccessible=true }.set(null,null)
        app.getSharedPreferences("phone_schedules",Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun item()=WeeklySchedule(unitId="preview",hour=7,minute=30,days=setOf(1,2,3,4,5),zone="Europe/Lisbon",commands=mapOf("power" to true,"operationMode" to "Heat","setTemperature" to 22.0))
    @Test fun saveArmsExactAlarmDisableAndDeleteCancelIt() {
        val s=item(); PhoneSchedules.save(app,s)
        assertTrue(PhoneSchedules.next(app,s.id)>System.currentTimeMillis())
        assertEquals(1,shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        PhoneSchedules.save(app,s.copy(enabled=false))
        assertEquals(0L,PhoneSchedules.next(app,s.id)); assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        PhoneSchedules.delete(app,s.id); assertTrue(PhoneSchedules.list(app).isEmpty())
    }
    @Test fun deniedPermissionLeavesSavedSchedulePausedWithoutCrashing() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val s=item(); PhoneSchedules.save(app,s)
        assertEquals(1,PhoneSchedules.list(app).size); assertEquals(0L,PhoneSchedules.next(app,s.id))
        ShadowAlarmManager.setCanScheduleExactAlarms(true); PhoneSchedules.rearm(app)
        assertTrue(PhoneSchedules.next(app,s.id)>0)
    }
    @Test fun lateDeliveryIsSkippedAndDuplicateDoesNotQueueAgain() {
        val s=item(); PhoneSchedules.save(app,s); val at=PhoneSchedules.next(app,s.id)
        PhoneSchedules.deliver(app,s.id,at,at+120001)
        assertTrue(PhoneSchedules.last(app,s.id)!!.startsWith("Missed:"))
        val last=PhoneSchedules.last(app,s.id)
        PhoneSchedules.deliver(app,s.id,at,at+120002)
        assertEquals(last,PhoneSchedules.last(app,s.id))
    }
    @Test fun dueScheduleQueuesExplicitDesiredValuesForCorrectDevice() {
        val controller=ClimateController.get(app)
        ClimateController::class.java.getDeclaredField("devices").apply { isAccessible=true }.set(controller,listOf(demoUnit()))
        ClimateController::class.java.getDeclaredField("signedIn").apply { isAccessible=true }.setBoolean(controller,true)
        controller.select("preview")
        val s=item(); PhoneSchedules.save(app,s); val at=PhoneSchedules.next(app,s.id)
        PhoneSchedules.deliver(app,s.id,at,at)
        assertTrue(controller.pending("preview")); assertTrue(PhoneSchedules.last(app,s.id)!!.startsWith("Queued"))
        assertEquals("22.0",controller.effective(controller.selected()!!).value("SetTemperature"))
    }
}
