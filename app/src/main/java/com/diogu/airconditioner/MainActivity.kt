package com.diogu.airconditioner

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var controller: ClimateController
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var airflowExpanded = false
    private var dragging = false
    private var foreground = false
    private var preview = false
    private var demo: UnitState? = null
    private var liveUnit: UnitState? = null
    private var viewSignature: String? = null
    private val bindings = mutableListOf<() -> kotlin.Unit>()
    private fun bind(update: () -> kotlin.Unit) { bindings.add(update); update() }
    private fun currentUnit() = liveUnit ?: error("No selected device")
    private val listener: () -> kotlin.Unit = { if(!dragging && !isDestroyed && foreground) render() }
    private val poll = object : Runnable {
        override fun run() {
            if(foreground && !preview) { if(!dragging) controller.refresh(); handler.postDelayed(this,60000) }
        }
    }
    private val bg = Color.rgb(14,23,25)
    private val card = Color.rgb(24,39,40)
    private val border = Color.rgb(45,65,65)
    private val ink = Color.rgb(240,246,237)
    private val muted = Color.rgb(148,171,168)
    private val mint = Color.rgb(205,232,183)
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ClimateController.get(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        // Internal debug-only fixture preview, never reads or sends cloud commands.
        preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview",false)
        if(preview) demo = demoUnit()
        airflowExpanded = savedInstanceState?.getBoolean("airflow") ?: false
        render(); callback(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("airflow",airflowExpanded); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); callback(intent) }
    private fun callback(intent: Intent) {
        val data = intent.dataString ?: return; intent.data = null
        if(data.startsWith(REDIRECT) && !preview) controller.login(data)
    }
    override fun onResume() {
        super.onResume(); foreground = true; controller.listeners.add(listener); render()
        if(!preview) { PhoneSchedules.rearm(this); controller.refresh(); handler.postDelayed(poll,60000) }
    }
    override fun onPause() { foreground = false; controller.listeners.remove(listener); handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun rounded(color: Int, radius: Int = 24, outline: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if(outline) setStroke(dp(1),border)
    }
    private fun stack(parent: LinearLayout, background: Boolean = false): LinearLayout = LinearLayout(this).also {
        it.orientation = LinearLayout.VERTICAL
        if(background) { it.background = rounded(card,28,true); it.setPadding(dp(20),dp(20),dp(20),dp(20)) }
        parent.addView(it,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(16) })
    }
    private fun row(parent: LinearLayout) = LinearLayout(this).also { it.gravity = Gravity.CENTER_VERTICAL; it.orientation = LinearLayout.HORIZONTAL; parent.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    private fun label(parent: LinearLayout, text: String, size: Float = 16f, color: Int = ink, face: String = "sans-serif"): TextView = TextView(this).also {
        it.text = text; it.textSize = size; it.setTextColor(color); it.typeface = Typeface.create(face,Typeface.NORMAL)
        parent.addView(it,LinearLayout.LayoutParams(-1,-2))
    }
    private fun gap(parent: LinearLayout, height: Int = 12) { parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun action(parent: LinearLayout, text: String, active: Boolean = false, enabled: Boolean = true, weight: Boolean = false, onClick: () -> kotlin.Unit): Button = Button(this).also {
        it.text = text; it.isAllCaps = false; it.textSize = 14f; it.typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        it.setTextColor(if(active) bg else ink); it.background = RippleDrawable(ColorStateList.valueOf(Color.argb(55,200,232,183)),rounded(if(active) mint else Color.rgb(34,51,52),16),null)
        it.minHeight = dp(48); it.minimumHeight = dp(48); it.setPadding(dp(8),0,dp(8),0)
        it.isEnabled = enabled; it.alpha = if(enabled) 1f else .38f
        parent.addView(it,LinearLayout.LayoutParams(if(weight) 0 else -1,dp(52),if(weight) 1f else 0f).apply { if(weight) { marginStart=dp(3); marginEnd=dp(3) } })
        it.setOnClickListener { onClick() }
    }
    private fun send(unit: UnitState, changes: Map<String,Any>) {
        if(preview) { demo = (demo ?: unit).withUpdates(changes); render() } else controller.enqueue(unit.id,changes)
    }
    private fun render() {
        val unit = if(preview) demo else controller.selected()?.let { controller.effective(it) }
        liveUnit = unit
        val phoneSchedules = if(preview) emptyList() else PhoneSchedules.list(this).filter { it.unitId==unit?.id }
        val signature = listOf(preview,if(preview) true else controller.signedIn,unit?.id,
            unit?.caps?.let { c -> c.keys().asSequence().toList().sorted().joinToString { "$it=${c.opt(it)}" } },
            unit?.modes(),unit?.fans(),unit?.verticalVanes(),unit?.horizontalVanes(),unit?.canSetTemperature(),unit?.bounds(),airflowExpanded,
            unit?.schedules?.toString(),unit?.schedulesEnabled,
            phoneSchedules.map { it.toJson().toString()+PhoneSchedules.next(this,it.id)+PhoneSchedules.last(this,it.id) },
            if(preview) true else PhoneSchedules.permitted(this),controller.energyReports[unit?.id]?.toJson()?.toString()).joinToString("|")
        if(viewSignature==signature && ::scroll.isInitialized) { bindings.toList().forEach { it() }; return }
        viewSignature=signature
        bindings.clear()
        if(!::scroll.isInitialized) {
            scroll=ScrollView(this).apply { setBackgroundColor(bg); isFillViewport=true; clipToPadding=false }
            column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(20),dp(22),dp(28)) }
            scroll.addView(column); setContentView(scroll)
            scroll.setOnApplyWindowInsetsListener { view,insets ->
                if(android.os.Build.VERSION.SDK_INT>=30) {
                    val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                }; insets
            }
        } else column.removeAllViews()
        label(column,"M E L   /   H O M E",12f,mint,"sans-serif-medium")
        gap(column,24)
        if(!preview && !controller.signedIn) { loginScreen(); return }
        if(unit == null) {
            label(column,"Your climate",32f,ink,"sans-serif-condensed")
            gap(column); val emptyStatus=label(column,controller.message,15f,muted); bind { emptyStatus.text=controller.message }; gap(column)
            val emptyRefresh=action(column,"Refresh devices",enabled=!controller.busy) { controller.refresh() }; bind { emptyRefresh.isEnabled=!controller.busy }
            gap(column); action(column,"Sign out") { controller.logout() }; return
        }
        val titleRow = row(column)
        val heading = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titleRow.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        val nameLabel=label(heading,unit.name,30f,ink,"sans-serif-condensed")
        val connectionLabel=label(heading,"",12f,muted)
        bind {
            val u=currentUnit(); nameLabel.text=u.name
            connectionLabel.text=when(u.connected) { true -> "●  Connected"; false -> "●  Offline"; null -> "●  Connection unknown" }
            connectionLabel.setTextColor(if(u.connected==false) Color.rgb(237,169,150) else muted)
        }
        val power = unit.value("Power").equals("true",true)
        val powerButton = action(titleRow,if(power) "On" else "Off",power,unit.connected != false) { val u=currentUnit(); send(u,mapOf("power" to !u.value("Power").equals("true",true))) }
        powerButton.layoutParams = LinearLayout.LayoutParams(dp(100),dp(50)).apply { marginStart = dp(12) }
        bind {
            val u=currentUnit(); val on=u.value("Power").equals("true",true)
            powerButton.text=if(on) "On" else "Off"
            powerButton.contentDescription=if(on) "Turn air conditioner off" else "Turn air conditioner on"
            updateButton(powerButton,on,u.connected!=false)
        }
        gap(column,20)
        val hero = stack(column,true)
        val mode = unit.value("OperationMode") ?: "Unknown"
        val target = unit.temperature("SetTemperature")
        val bounds = unit.bounds()
        val dial = ThermostatView(this).apply { this.target=target ?: bounds.first; minimum=bounds.first; maximum=bounds.second; this.mode=display(mode); powered=power; available=target != null; accent=if(mode=="Heat") Color.rgb(242,201,152) else mint }
        hero.addView(dial,LinearLayout.LayoutParams(-1,dp(250)))
        bind {
            val u=currentUnit(); dial.target=u.temperature("SetTemperature") ?: u.bounds().first
            dial.available=u.temperature("SetTemperature")!=null; dial.mode=display(u.value("OperationMode") ?: "Unknown")
            dial.powered=u.value("Power").equals("true",true)
            dial.accent=if(u.value("OperationMode")=="Heat") Color.rgb(242,201,152) else mint; dial.invalidate()
        }
        if(target != null && unit.canSetTemperature()) {
            val targetLabel = label(hero,"${temperature(target)}  ·  slide to adjust",14f,muted).apply { gravity=Gravity.CENTER }
            val slider = SeekBar(this).apply {
                id = R.id.temperature_slider
                max = ((bounds.second-bounds.first)/unit.step()).toInt(); progress=((target-bounds.first)/unit.step()).toInt().coerceIn(0,max)
                progressTintList=ColorStateList.valueOf(mint); thumbTintList=ColorStateList.valueOf(mint); progressBackgroundTintList=ColorStateList.valueOf(border)
                contentDescription="Target temperature"; minimumHeight=dp(48); isEnabled=unit.connected != false
            }
            hero.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
            bind {
                if(!dragging) {
                    val value=currentUnit().temperature("SetTemperature") ?: bounds.first
                    slider.progress=((value-bounds.first)/currentUnit().step()).toInt().coerceIn(0,slider.max)
                    targetLabel.text=getString(R.string.adjust_temperature,temperature(value))
                }
                slider.isEnabled=currentUnit().connected!=false
            }
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { dragging=true; if(!preview) controller.cancelPendingTemperature(unit.id) }
                override fun onProgressChanged(seekBar: SeekBar,progress: Int,fromUser: Boolean) {
                    if(!fromUser) return
                    val value = bounds.first+progress*unit.step(); dial.target=value; dial.invalidate()
                    targetLabel.text=getString(R.string.release_temperature,temperature(value))
                    // Accessibility/keyboard adjustments do not produce a touch-stop callback.
                    if(!dragging) send(unit,mapOf("setTemperature" to value))
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging=false; send(unit,mapOf("setTemperature" to (bounds.first+seekBar.progress*unit.step())))
                }
            })
            val limits=row(hero)
            label(limits,temperature(bounds.first),12f,muted).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            label(limits,temperature(bounds.second),12f,muted).apply { gravity=Gravity.END }.layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        } else label(hero,if(mode=="Fan") "Fan only · no target temperature" else "Target temperature unavailable",14f,muted).gravity=Gravity.CENTER
        gap(hero,20)
        val readings=row(hero)
        val room = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; readings.addView(room,LinearLayout.LayoutParams(0,-2,1f))
        label(room,"ROOM NOW",10f,muted,"sans-serif-medium")
        val roomValue=label(room,"",24f); bind { roomValue.text=temperature(currentUnit().temperature("RoomTemperature")) }
        val state = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; readings.addView(state,LinearLayout.LayoutParams(0,-2,1f))
        label(state,"SYSTEM",10f,muted,"sans-serif-medium")
        val systemValue=label(state,"",24f); bind { systemValue.text=if(currentUnit().value("Power").equals("true",true)) display(currentUnit().value("OperationMode") ?: "Unknown") else "Off" }
        val statusValue=label(column,"",12f,muted)
        bind { statusValue.text=if(preview) "Preview · sample device" else controller.message; statusValue.setTextColor(if(!preview && controller.pending(unit.id)) mint else muted) }
        gap(column,22)
        val modeCard=stack(column,true)
        label(modeCard,"Operating mode",17f,ink,"sans-serif-medium"); gap(modeCard,14)
        choices(modeCard,unit.modes(),{ currentUnit().value("OperationMode") }) { send(unit,mapOf("operationMode" to it)) }
        val airflow=stack(column,true)
        action(airflow,if(airflowExpanded) "Airflow & vanes   −" else "Airflow & vanes   +") { airflowExpanded=!airflowExpanded; render() }
        gap(airflow,12)
        val airflowValue=label(airflow,"",13f,muted)
        bind { val u=currentUnit(); airflowValue.text=getString(R.string.airflow_summary,display(u.value("SetFanSpeed") ?: "Unknown"),display(u.value("VaneVerticalDirection") ?: "Unknown")) }
        if(airflowExpanded) {
            if(unit.fans().isNotEmpty()) { gap(airflow,20); label(airflow,"Fan speed",14f,muted); gap(airflow,8); choices(airflow,unit.fans(),{ currentUnit().value("SetFanSpeed") }) { send(unit,mapOf("setFanSpeed" to it)) } }
            if(unit.verticalVanes().isNotEmpty()) { gap(airflow,20); label(airflow,"Vertical vane",14f,muted); gap(airflow,8); choices(airflow,unit.verticalVanes(),{ currentUnit().value("VaneVerticalDirection") }) { send(unit,mapOf("vaneVerticalDirection" to it)) } }
            if(unit.horizontalVanes().isNotEmpty()) { gap(airflow,20); label(airflow,"Horizontal vane",14f,muted); gap(airflow,8); choices(airflow,unit.horizontalVanes(),{ currentUnit().value("VaneHorizontalDirection") }) { send(unit,mapOf("vaneHorizontalDirection" to it)) } }
            if(unit.fans().isEmpty() && unit.verticalVanes().isEmpty() && unit.horizontalVanes().isEmpty()) label(airflow,"No airflow controls reported by this unit.",14f,muted)
        }
        val faultLabel=label(column,"Unit reports a fault. Check the official app.",14f,Color.rgb(237,169,150))
        bind { faultLabel.visibility=if(currentUnit().value("IsInError").equals("true",true)) View.VISIBLE else View.GONE }
        energySection(unit)
        scheduleSection(unit)
        val footer=row(column)
        val refreshButton=action(footer,"Refresh",enabled=!controller.busy,weight=true) { if(!preview) controller.refresh() }
        bind { updateButton(refreshButton,false,!controller.busy) }
        action(footer,"Device",weight=true) { chooseDevice() }
        gap(column)
        action(column,"Add home-screen widget") {
            val manager=AppWidgetManager.getInstance(this)
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,ClimateWidget::class.java),null,null)
            else Toast.makeText(this,"Long-press your home screen → Widgets → Air Conditioner",Toast.LENGTH_LONG).show()
        }
        gap(column)
        label(column,"Changes settle for 3 seconds before sending. Cloud state can take a moment to catch up.",12f,muted)
        gap(column)
        action(column,"Account & sign out") {
            AlertDialog.Builder(this).setTitle("MELCloud Home").setMessage("Signed in on this device. Signing out also disables widget controls.").setPositiveButton("Sign out") { _,_ -> if(!preview) controller.logout() }.setNegativeButton("Keep signed in",null).show()
        }
    }
    private fun energySection(unit: UnitState) {
        val panel=stack(column,true)
        label(panel,"Energy",19f,ink,"sans-serif-medium"); gap(panel,10)
        if(!unit.energySupported()) { label(panel,"This unit does not report an energy meter to MELCloud.",14f,muted); return }
        val report=controller.energyReports[unit.id]
        if(report!=null && report.buckets>0) {
            label(panel,String.format(java.util.Locale.getDefault(),"%.2f kWh",report.total),30f)
            label(panel,"Reported consumption · last 7 days",12f,muted); gap(panel)
            val today=java.time.Instant.ofEpochMilli(report.fetched).atZone(unitZone(unit.timeZone)).toLocalDate()
            val high=report.days.maxOfOrNull { it.kwh }?.coerceAtLeast(0.01) ?: 0.01
            val chart=row(panel)
            (6L downTo 0L).forEach { offset ->
                val date=today.minusDays(offset); val day=report.days.find { it.date==date }
                val barColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
                chart.addView(barColumn,LinearLayout.LayoutParams(0,dp(120),1f))
                label(barColumn,day?.let { String.format(java.util.Locale.getDefault(),"%.1f",it.kwh) } ?: "—",11f,muted).gravity=Gravity.CENTER
                barColumn.addView(View(this).apply { background=rounded(if(day==null) border else mint,4) },LinearLayout.LayoutParams(dp(16),dp(if(day==null) 3 else (day.kwh/high*65).toInt().coerceAtLeast(3))).apply { topMargin=dp(5); bottomMargin=dp(5) })
                label(barColumn,date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW,java.util.Locale.getDefault()),11f,muted).gravity=Gravity.CENTER
            }
            gap(panel); label(panel,"Hourly cloud data in ${unit.timeZone}. Missing readings are shown as —, not zero.",12f,muted)
            if(report.rejected>0) label(panel,"${report.rejected} invalid readings excluded.",12f,muted)
        } else label(panel,"View the last seven days of reported consumption.",14f,muted)
        gap(panel)
        val status=label(panel,"",12f,muted); bind { status.text=controller.energyStatus[unit.id] ?: "Load energy when you need it." }
        gap(panel)
        val load=action(panel,if(report==null) "Load energy" else "Refresh energy") { if(!preview) controller.loadEnergy(unit.id) }
        bind { updateButton(load,false,!controller.busy && !preview) }
    }
    private fun scheduleSection(unit: UnitState) {
        val panel=stack(column,true)
        label(panel,"Phone schedules",19f,ink,"sans-serif-medium"); gap(panel,8)
        label(panel,"Runs from this phone in ${unit.timeZone}. Keep this phone online; Android may delay network work.",13f,muted); gap(panel)
        val schedules=if(preview) emptyList() else PhoneSchedules.list(this).filter { it.unitId==unit.id }
        if(!preview && !PhoneSchedules.permitted(this) && schedules.any { it.enabled }) {
            label(panel,"Paused until Alarms & reminders access is allowed.",13f,Color.rgb(242,201,152)); gap(panel)
            action(panel,"Allow scheduled alarms") { alarmPermission() }; gap(panel)
        }
        if(schedules.isEmpty()) label(panel,"No phone schedules yet.",14f,muted)
        schedules.sortedWith(compareBy<WeeklySchedule> { it.hour }.thenBy { it.minute }).forEach { item ->
            gap(panel,16); val on=item.commands["power"]==true
            label(panel,"${item.timeText()}  ·  ${if(on) "On" else "Off"}",21f); label(panel,item.daysText(),13f,muted)
            if(on) label(panel,listOfNotNull(item.commands["operationMode"]?.toString()?.let { display(it) },(item.commands["setTemperature"] as? Number)?.toDouble()?.let { temperature(it) }).joinToString(" · "),13f,mint)
            val next=PhoneSchedules.next(this,item.id)
            label(panel,when {
                !item.enabled -> "Disabled"
                !PhoneSchedules.permitted(this) -> "Paused · allow alarms"
                next>0 -> "Next: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).apply { timeZone=java.util.TimeZone.getTimeZone(item.zone) }.format(java.util.Date(next))}"
                else -> "Not armed · open the app to check access"
            },12f,muted)
            PhoneSchedules.last(this,item.id)?.let { label(panel,it,12f,muted) }; gap(panel,8)
            val buttons=row(panel)
            action(buttons,"Edit",weight=true) { editSchedule(currentUnit(),item) }
            action(buttons,if(item.enabled) "Disable" else "Enable",weight=true) {
                PhoneSchedules.save(this,item.copy(enabled=!item.enabled)); render()
                if(!item.enabled && !PhoneSchedules.permitted(this)) alarmPermission()
            }
            action(buttons,"Delete",weight=true) {
                AlertDialog.Builder(this).setTitle("Delete phone schedule?").setMessage("${item.timeText()} · ${item.daysText()}").setPositiveButton("Delete") { _,_ -> PhoneSchedules.delete(this,item.id); render() }.setNegativeButton("Cancel",null).show()
            }
        }
        gap(panel,16); action(panel,"Add schedule",enabled=!preview) { editSchedule(currentUnit(),null) }
        if(unit.schedules.length()>0) {
            gap(panel,18)
            label(panel,"MELCloud schedules · ${if(unit.schedulesEnabled==true) "enabled" else if(unit.schedulesEnabled==false) "disabled" else "status unknown"}",14f)
            for(i in 0 until unit.schedules.length()) {
                val raw=unit.schedules.optJSONObject(i) ?: continue
                val days=raw.optJSONArray("days")
                val names=if(days==null) "Days unavailable" else (0 until days.length()).mapNotNull { index -> days.optInt(index,-1).takeIf { it in 0..6 }?.let { java.time.DayOfWeek.of(if(it==0) 7 else it).getDisplayName(java.time.format.TextStyle.SHORT,java.util.Locale.getDefault()) } }.joinToString(" · ")
                label(panel,"${raw.optString("time","Time unavailable")} · $names · ${if(raw.optBoolean("power")) "On" else "Off"}",12f,muted)
            }
            label(panel,"Cloud schedules are read-only here. Manage them in the official app.",12f,muted)
        }
    }
    private fun alarmPermission() {
        if(android.os.Build.VERSION.SDK_INT<31 || PhoneSchedules.permitted(this)) return
        AlertDialog.Builder(this).setTitle("Allow scheduled alarms").setMessage("Phone schedules need Alarms & reminders access to wake the app at the chosen time. Your phone still needs internet to reach MELCloud.")
            .setPositiveButton("Open settings") { _,_ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }
                catch(_:Exception) { Toast.makeText(this,"Open Android settings → Apps → Special access → Alarms & reminders",Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Later",null).show()
    }
    private fun editSchedule(unit: UnitState,existing: WeeklySchedule?) {
        var hour=existing?.hour ?: 7; var minute=existing?.minute ?: 0
        val days=(existing?.days ?: (1..7).toSet()).toMutableSet()
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(16)) }
        val wrapper=ScrollView(this).apply { addView(form) }
        val time=action(form,String.format(java.util.Locale.getDefault(),"Time  %02d:%02d",hour,minute)) {}
        time.setOnClickListener { android.app.TimePickerDialog(this,{ _,h,m -> hour=h; minute=m; time.text=String.format(java.util.Locale.getDefault(),"Time  %02d:%02d",hour,minute) },hour,minute,true).show() }
        gap(form)
        fun dayNames()=if(days.size==7) "Repeat · every day" else "Repeat · "+days.sorted().joinToString(" ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT,java.util.Locale.getDefault()) }
        val repeat=action(form,dayNames()) {}
        repeat.setOnClickListener {
            val names=(1..7).map { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.getDefault()) }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Repeat on").setMultiChoiceItems(names,BooleanArray(7) { it+1 in days }) { _,index,on -> if(on) days.add(index+1) else days.remove(index+1) }.setPositiveButton("Done") { _,_ -> repeat.text=dayNames() }.show()
        }
        gap(form)
        val enabled=CheckBox(this).apply { setText(R.string.schedule_enabled); isChecked=existing?.enabled ?: true }; form.addView(enabled)
        val power=CheckBox(this).apply { setText(R.string.schedule_power); isChecked=existing?.commands?.get("power") as? Boolean ?: true }; form.addView(power)
        gap(form); label(form,"Operating mode",14f,muted)
        val modes=unit.modes()
        val spinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modes.map { display(it) })
            setSelection(modes.indexOf(existing?.commands?.get("operationMode")?.toString() ?: unit.value("OperationMode")).coerceAtLeast(0))
        }; form.addView(spinner)
        val tempLabel=label(form,"",16f)
        val slider=SeekBar(this).apply { progressTintList=ColorStateList.valueOf(mint); thumbTintList=ColorStateList.valueOf(mint) }; form.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
        var target=(existing?.commands?.get("setTemperature") as? Number)?.toDouble() ?: unit.temperature("SetTemperature") ?: 22.0
        var minimum=10.0; var adjusting=false
        fun updateEditor() {
            adjusting=true
            val mode=modes.getOrNull(spinner.selectedItemPosition)
            val state=if(mode==null) unit else unit.withUpdates(mapOf("operationMode" to mode))
            spinner.isEnabled=power.isChecked
            val show=power.isChecked && state.canSetTemperature()
            tempLabel.visibility=if(show) View.VISIBLE else View.GONE; slider.visibility=tempLabel.visibility
            val bounds=state.bounds(); minimum=bounds.first; target=target.coerceIn(bounds.first,bounds.second)
            slider.max=((bounds.second-bounds.first)/unit.step()).toInt(); slider.progress=((target-bounds.first)/unit.step()).toInt()
            tempLabel.text=temperature(target); adjusting=false
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(view: SeekBar) {}
            override fun onStopTrackingTouch(view: SeekBar) {}
            override fun onProgressChanged(view: SeekBar,progress: Int,user: Boolean) { if(!adjusting && user) { target=minimum+progress*unit.step(); tempLabel.text=temperature(target) } }
        })
        spinner.onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { updateEditor() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        power.setOnCheckedChangeListener { _,_ -> updateEditor() }; updateEditor()
        gap(form); label(form,"Time zone: ${unit.timeZone}. This phone must be powered on, signed in and online.",12f,muted)
        val error=label(form,"",13f,Color.rgb(237,169,150))
        val dialog=AlertDialog.Builder(this).setTitle(if(existing==null) "Add phone schedule" else "Edit phone schedule").setView(wrapper).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    require(days.isNotEmpty()) { "Select at least one day." }
                    val commands=mutableMapOf<String,Any>("power" to power.isChecked)
                    if(power.isChecked) {
                        modes.getOrNull(spinner.selectedItemPosition)?.let { commands["operationMode"]=it }
                        if(slider.visibility==View.VISIBLE) commands["setTemperature"]=target
                    }
                    validateDesired(unit.copy(connected=true),commands)
                    val schedule=WeeklySchedule(existing?.id ?: java.util.UUID.randomUUID().toString(),unit.id,hour,minute,days.toSet(),unitZone(unit.timeZone).id,enabled.isChecked,commands)
                    PhoneSchedules.save(this,schedule); dialog.dismiss(); render()
                    if(enabled.isChecked && !PhoneSchedules.permitted(this)) alarmPermission()
                } catch(e:Exception) { error.text=e.message ?: "Could not save schedule." }
            }
        }; dialog.show()
    }
    private fun updateButton(button: Button,active: Boolean,enabled: Boolean) {
        button.setTextColor(if(active) bg else ink)
        button.background=RippleDrawable(ColorStateList.valueOf(Color.argb(55,200,232,183)),rounded(if(active) mint else Color.rgb(34,51,52),16),null)
        button.isEnabled=enabled; button.alpha=if(enabled) 1f else .38f
    }
    private fun choices(parent: LinearLayout,options: List<String>,current: () -> String?,select: (String) -> kotlin.Unit) {
        if(options.isEmpty()) { label(parent,"Not reported by this unit",13f,muted); return }
        options.chunked(3).forEach { group ->
            val line=row(parent)
            group.forEach { value ->
                val button=action(line,display(value),value==current(),weight=true) { select(value) }
                bind { updateButton(button,value==current(),currentUnit().connected!=false) }
            }
            gap(parent,6)
        }
    }
    private fun loginScreen() {
        gap(column,52); label(column,"Your space,\nyour climate.",46f,ink,"sans-serif-condensed-light"); gap(column,24)
        label(column,"A little more comfort.\nA little less effort.",19f,muted); gap(column,42)
        val panel=stack(column,true)
        label(panel,"Welcome home",23f); gap(panel)
        label(panel,"Connect your existing MELCloud Home account to bring your rooms into view.",15f,muted); gap(panel,24)
        val loginButton=action(panel,"Connect MELCloud Home",true,!controller.busy) {
            controller.login { url -> try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:Exception) { Toast.makeText(this,"Enable a web browser to sign in",Toast.LENGTH_LONG).show() } }
        }
        bind { updateButton(loginButton,true,!controller.busy) }
        gap(panel,12); val loginStatus=label(panel,controller.message,12f,muted); bind { loginStatus.text=controller.message }
        label(column,"Secure browser sign in. Your password stays with MELCloud.",12f,muted)
    }
    private fun chooseDevice() {
        AlertDialog.Builder(this).setTitle("Choose a room").setItems(controller.devices.map { it.name }.toTypedArray()) { _,i -> controller.select(controller.devices[i].id) }.setNegativeButton("Cancel",null).show()
    }
}
fun display(value: String) = when(value) {
    "Automatic" -> "Auto"; "One" -> "1"; "Two" -> "2"; "Three" -> "3"; "Four" -> "4"; "Five" -> "5"; "LeftCentre" -> "Left mid"; "RightCentre" -> "Right mid"; else -> value
}
fun temperature(value: Double?) = value?.let { if(it%1.0==0.0) "${it.toInt()}°C" else String.format(java.util.Locale.getDefault(),"%.1f°C",it) } ?: "—"
fun demoUnit() = UnitState("preview","Living room",mapOf("Power" to "True","OperationMode" to "Heat","SetTemperature" to "22","RoomTemperature" to "20.5","SetFanSpeed" to "Auto","VaneVerticalDirection" to "Auto","VaneHorizontalDirection" to "Centre"),org.json.JSONObject("""{"hasHeatOperationMode":true,"hasCoolOperationMode":true,"hasAutoOperationMode":true,"hasDryOperationMode":true,"hasFanOperationMode":true,"hasHalfDegreeIncrements":true,"hasAutomaticFanSpeed":true,"numberOfFanSpeeds":5,"hasVaneVertical":true,"hasVaneHorizontal":true,"hasSwing":true}"""),true)
