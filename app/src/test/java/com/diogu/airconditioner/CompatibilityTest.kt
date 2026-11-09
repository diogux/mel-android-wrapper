package com.diogu.airconditioner

import android.content.Intent
import android.view.View
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[26])
class CompatibilityTest {
    @Test fun android8DashboardAndWidgetInflateWithoutNewApiCalls() {
        ClimateController::class.java.getDeclaredField("instance").apply { isAccessible=true }.set(null,null)
        val app=RuntimeEnvironment.getApplication() as android.content.Context
        val scenario=Robolectric.buildActivity(MainActivity::class.java,Intent(app,MainActivity::class.java).putExtra("preview",true)).setup()
        assertNotNull(scenario.get().findViewById<View>(R.id.temperature_slider))
        val widget=ClimateWidget.views(app,1,ClimateController.get(app)).apply(app,android.widget.FrameLayout(app))
        assertNotNull(widget.findViewById<View>(R.id.widget_mode))
        scenario.pause().stop().destroy()
    }
}
