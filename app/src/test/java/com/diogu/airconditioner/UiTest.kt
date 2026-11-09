package com.diogu.airconditioner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.RemoteViews
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @Before fun resetController() {
        ClimateController::class.java.getDeclaredField("instance").apply { isAccessible=true }.set(null,null)
    }
    @Test fun dashboardRendersAndSliderAndAirflowControlsWork() {
        val activity=Robolectric.buildActivity(MainActivity::class.java,Intent(RuntimeEnvironment.getApplication(),MainActivity::class.java).putExtra("preview",true)).setup().get()
        val root=activity.findViewById<View>(android.R.id.content)
        assertNotNull(root.findViewById<SeekBar>(R.id.temperature_slider))
        screenshot(root,"dashboard",822,1782)
        val slider=root.findViewById<SeekBar>(R.id.temperature_slider)
        assertEquals(42,slider.max) // 10..31, half-degree increments
        val airflow=findText(root,"Airflow & vanes   +")!!
        airflow.performClick()
        assertNotNull(findText(activity.findViewById(android.R.id.content),"Vertical vane"))
        assertNotNull(findText(activity.findViewById(android.R.id.content),"Horizontal vane"))
        screenshot(activity.findViewById(android.R.id.content),"airflow",822,2600)
    }
    @Test fun widgetRemoteViewsInflateWithTemperatureAndControls() {
        val app=RuntimeEnvironment.getApplication() as Context
        val controller=ClimateController.get(app)
        // Signed-out launcher state remains useful and safely opens the app for authentication.
        val view=ClimateWidget.views(app,7,controller).apply(app,android.widget.FrameLayout(app))
        assertEquals("Air Conditioner",view.findViewById<TextView>(R.id.widget_title).text)
        assertNotNull(view.findViewById<TextView>(R.id.widget_power))
        screenshot(view,"widget-signed-out",720,248)
        // Real widget binding against a synthetic signed-in cached device, without network calls.
        ClimateController::class.java.getDeclaredField("devices").apply { isAccessible=true }.set(controller,listOf(demoUnit()))
        ClimateController::class.java.getDeclaredField("signedIn").apply { isAccessible=true }.setBoolean(controller,true)
        controller.select("preview")
        val remote=ClimateWidget.views(app,8,controller)
        val bound=remote.apply(app,android.widget.FrameLayout(app))
        assertEquals("22°C",bound.findViewById<TextView>(R.id.widget_temperature).text)
        assertEquals("Heat",bound.findViewById<TextView>(R.id.widget_mode).text)
        assertTrue(bound.findViewById<TextView>(R.id.widget_plus).isEnabled)
        widgetScreenshot(bound,"widget",720,248)
        val card=bound.findViewById<View>(R.id.widget_root)
        val status=bound.findViewById<TextView>(R.id.widget_status)
        val density=app.resources.displayMetrics.density
        assertEquals("Use the existing host height",bound.height,card.height)
        assertEquals("No extra space inside the card",card.height-card.paddingBottom,status.bottom)
        val compactHeight=card.height
        val header=bound.findViewById<TextView>(R.id.widget_temperature).parent as View
        val controls=bound.findViewById<TextView>(R.id.widget_plus).parent as View
        assertTrue("Header and controls must not overlap",header.bottom<=controls.top)
        widgetScreenshot(bound,"widget-compact",520,248)
        assertEquals(compactHeight,card.height)
        widgetScreenshot(bound,"widget-tall",720,640)
        assertEquals("Fill a tall existing box without changing its allocation",640,card.height)
        controller.widgetAction("plus"); controller.widgetAction("plus")
        assertEquals(23.0,controller.effective(controller.selected()!!).temperature("SetTemperature")!!,0.001)
        controller.widgetAction("power")
        assertEquals("false",controller.effective(controller.selected()!!).value("Power"))
        assertTrue(controller.pending("preview"))

    }
    @Test fun vaneChangesAndStatusUpdatesKeepSameScrollViewAndControls() {
        val scenario=Robolectric.buildActivity(MainActivity::class.java,Intent(RuntimeEnvironment.getApplication(),MainActivity::class.java).putExtra("preview",true)).setup()
        val activity=scenario.get()
        findText(activity.findViewById(android.R.id.content),"Airflow & vanes   +")!!.performClick()
        val root=activity.findViewById<View>(android.R.id.content)
        screenshot(root,"stable-scroll-before",822,1782)
        val scroll=(root as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        scroll.scrollTo(0,650)
        val slider=activity.findViewById<SeekBar>(R.id.temperature_slider)
        val vaneLabel=findText(root,"Vertical vane")!!
        val before=scroll.scrollY
        // First Swing belongs to the vertical vane group shown in the recording.
        findText(root,"Swing")!!.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertSame(scroll,(activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0))
        assertSame(slider,activity.findViewById<SeekBar>(R.id.temperature_slider))
        assertSame(vaneLabel,findText(root,"Vertical vane"))
        assertEquals(before,scroll.scrollY)
        screenshot(root,"stable-scroll-after",822,1782)
        scenario.pause().stop().destroy()
    }
    @Test fun energyPanelAndScheduleEditorRenderAndSaveDefinition() {
        val app=RuntimeEnvironment.getApplication() as Context
        val scenario=Robolectric.buildActivity(MainActivity::class.java,Intent(app,MainActivity::class.java).putExtra("preview",true)).setup()
        val activity=scenario.get(); val unit=demoUnit().copy(caps=org.json.JSONObject(demoUnit().caps.toString()).put("hasEnergyConsumedMeter",true),timeZone="Europe/Lisbon")
        MainActivity::class.java.getDeclaredField("demo").apply { isAccessible=true }.set(activity,unit)
        val today=java.time.LocalDate.of(2026,11,8)
        val report=EnergyReport((0L..6L).map { EnergyDay(today.minusDays(6-it),0.4+it*0.2) },40,0,java.time.Instant.parse("2026-11-08T12:00:00Z").toEpochMilli())
        ClimateController.get(app).energyReports["preview"]=report
        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
        val root=activity.findViewById<View>(android.R.id.content)
        assertNotNull(findText(root,"Reported consumption · last 7 days"))
        assertNotNull(findText(root,"Phone schedules"))
        screenshot(root,"energy-and-schedules",822,3200)
        MainActivity::class.java.getDeclaredMethod("editSchedule",UnitState::class.java,WeeklySchedule::class.java).apply { isAccessible=true }.invoke(activity,unit,null)
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(findText(dialog.window!!.decorView,"Schedule enabled"))
        screenshot(dialog.window!!.decorView,"schedule-editor",822,1400)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1,PhoneSchedules.list(app).size)
        assertEquals("preview",PhoneSchedules.list(app).single().unitId)
        scenario.pause().stop().destroy()
    }
    @Test fun shortWidgetUsesExistingBoxWithLargerTextAndTouchTargets() {
        val app=RuntimeEnvironment.getApplication() as Context
        val controller=ClimateController.get(app)
        ClimateController::class.java.getDeclaredField("devices").apply { isAccessible=true }.set(controller,listOf(demoUnit()))
        ClimateController::class.java.getDeclaredField("signedIn").apply { isAccessible=true }.setBoolean(controller,true)
        controller.select("preview")
        val parent=android.widget.FrameLayout(app)
        val small=ClimateWidget.views(app,9,controller,compact=true,heightDp=74).apply(app,parent)
        widgetScreenshot(small,"widget-one-row",720,148)
        val smallButton=small.findViewById<View>(R.id.widget_power).height
        val smallText=small.findViewById<TextView>(R.id.widget_temperature).textSize
        val large=ClimateWidget.views(app,9,controller,compact=true,heightDp=104).apply(app,android.widget.FrameLayout(app))
        widgetScreenshot(large,"widget-filled",720,208)
        assertEquals("Fill the same host box",208,large.height)
        assertTrue(large.findViewById<View>(R.id.widget_power).height>smallButton)
        assertTrue(large.findViewById<TextView>(R.id.widget_temperature).textSize>smallText)
        val controls=large.findViewById<View>(R.id.widget_power).parent as View
        assertEquals("No unused vertical space below controls",large.height-large.paddingBottom,controls.bottom)
        assertEquals(View.GONE,large.findViewById<View>(R.id.widget_status).visibility)
    }
    private fun widgetScreenshot(view: View,name: String,width: Int,availableHeight: Int) {
        val host=android.widget.FrameLayout(view.context)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        host.addView(view)
        host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(availableHeight,View.MeasureSpec.EXACTLY))
        host.layout(0,0,width,availableHeight)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file=File("build/reports/ui/$name.png"); file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun findText(view: View,text: String): TextView? {
        if(view is TextView && view.text.toString()==text) return view
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findText(view.getChildAt(i),text)?.let { return it }
        return null
    }
    private fun screenshot(view: View,name: String,width: Int,height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file=File("build/reports/ui/$name.png"); file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
