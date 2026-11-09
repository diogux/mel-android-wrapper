package com.diogu.airconditioner

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class ClimateWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        val controller=ClimateController.get(context)
        if(controller.signedIn) controller.requestBackgroundRefresh()
    }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: Bundle) { updateAll(context) }
    override fun onReceive(context: Context,intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action != ACTION) return
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
        val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ClimateWidget::class.java))
        if(id !in ids) return
        val action=intent.getStringExtra("control") ?: return
        ClimateController.get(context).widgetAction(action)
    }
    companion object {
        const val ACTION = "com.diogu.airconditioner.WIDGET_CONTROL"
        fun updateAll(context: Context) {
            val manager=AppWidgetManager.getInstance(context)
            val ids=manager.getAppWidgetIds(ComponentName(context,ClimateWidget::class.java))
            if(ids.isEmpty()) return
            val controller=ClimateController.get(context)
            ids.forEach { id ->
                val height=manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,74)
                val width=manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360)
                manager.updateAppWidget(id,views(context,id,controller,compact=height<112,heightDp=height,widthDp=width))
            }
        }
        fun views(context: Context,id: Int,controller: ClimateController,compact: Boolean=false,heightDp: Int=if(compact) 74 else 124,widthDp: Int=360): RemoteViews {
            val unit=controller.selected()?.let { controller.effective(it) }
            val ready=controller.signedIn && unit != null
            val power=unit?.value("Power").equals("true",true)
            return RemoteViews(context.packageName,if(compact) R.layout.climate_widget_short else R.layout.climate_widget).apply {
                // Scale within the host's existing bounds; never change launcher size/options.
                val growth=((heightDp-(if(compact) 74 else 124)).coerceAtLeast(0)/50f).coerceAtMost(1f)
                val narrow=widthDp<300
                setTextViewTextSize(R.id.widget_temperature,android.util.TypedValue.COMPLEX_UNIT_SP,if(compact) 20f+growth*(if(narrow) 6f else 12f) else 30f+growth*(if(narrow) 4f else 12f))
                setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,if(compact) 14f+growth*2 else 15f+growth*2)
                listOf(R.id.widget_mode,R.id.widget_power).forEach { view -> setTextViewTextSize(view,android.util.TypedValue.COMPLEX_UNIT_SP,(if(narrow) 14f else 15f)+growth*(if(narrow) 1f else 3f)) }
                listOf(R.id.widget_minus,R.id.widget_plus).forEach { view -> setTextViewTextSize(view,android.util.TypedValue.COMPLEX_UNIT_SP,26f+growth*4) }
                setTextViewText(R.id.widget_title,unit?.name ?: "Air Conditioner")
                setTextViewText(R.id.widget_temperature,temperature(unit?.temperature("SetTemperature")))
                setTextViewText(R.id.widget_room,"Room ${temperature(unit?.temperature("RoomTemperature"))}")
                setTextViewText(R.id.widget_mode,display(unit?.value("OperationMode") ?: "Mode"))
                setTextViewText(R.id.widget_power,if(power) "On" else "Off")
                setInt(R.id.widget_power,"setBackgroundResource",if(power) R.drawable.widget_accent else R.drawable.widget_button)
                setTextColor(R.id.widget_power,if(power) Color.rgb(14,23,25) else Color.rgb(240,246,237))
                val time=if(controller.updated>0) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(controller.updated)) else "not refreshed"
                val status=if(!ready) "Open app to sign in and select a room" else if(controller.pending(unit!!.id)) controller.message else "$time · ${controller.message}"
                setTextViewText(R.id.widget_status,status)
                setContentDescription(R.id.widget_root,status)
                val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_title,open)
                setOnClickPendingIntent(R.id.widget_temperature,open)
                listOf(R.id.widget_power to "power",R.id.widget_mode to "mode",R.id.widget_minus to "minus",R.id.widget_plus to "plus",R.id.widget_refresh to "refresh").forEach { (view,action) ->
                    val enabled=ready && (action == "refresh" || unit!!.connected != false) && when(action) {
                        "minus","plus" -> unit!!.canSetTemperature()
                        "mode" -> unit!!.modes().isNotEmpty()
                        "power" -> unit!!.value("Power").equals("true",true) || unit.value("Power").equals("false",true)
                        else -> true
                    }
                    setBoolean(view,"setEnabled",enabled)
                    setInt(view,"setVisibility",if((action=="minus" || action=="plus") && unit != null && !unit.canSetTemperature()) View.INVISIBLE else View.VISIBLE)
                    val intent=Intent(context,ClimateWidget::class.java).setAction(ACTION).setData(Uri.parse("climate://widget/$id/$action")).putExtra("control",action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
                    setOnClickPendingIntent(view,if(ready) PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else open)
                }
            }
        }
    }
}
