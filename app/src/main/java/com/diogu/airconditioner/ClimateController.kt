package com.diogu.airconditioner

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.IOException

/** One session and serial network executor for app, jobs and widgets. Public methods run on main. */
class ClimateController private constructor(private val context: Context) {
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Stores applicationContext only, never an Activity.
        private var instance: ClimateController? = null
        fun get(context: Context): ClimateController = instance ?: ClimateController(context.applicationContext).also { instance = it }
        const val JOB = 81
    }
    private val prefs = context.getSharedPreferences("climate_state",Context.MODE_PRIVATE)
    private val selection = context.getSharedPreferences("device",Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cloud = Cloud(SecureVault(context))
    private val queue = CommandQueue(runCatching { JSONObject(prefs.getString("drafts","{}")!!) }.getOrDefault(JSONObject()))
    val listeners = linkedSetOf<() -> kotlin.Unit>()
    var devices = runCatching { parseDevices(JSONObject(prefs.getString("context","{}")!!)) }.getOrDefault(emptyList()); private set
    var selectedId: String? = selection.getString("selected",null); private set
    var signedIn = cloud.hasSession(); private set
    var busy = false; private set
    var message = prefs.getString("message", "Ready when you are")!!; private set
    var updated = prefs.getLong("updated",0); private set
    private var blockedUntil = prefs.getLong("blocked",0)
    val energyReports = linkedMapOf<String,EnergyReport>()
    val energyStatus = linkedMapOf<String,String>()
    private val energyAttempts = linkedMapOf<String,Long>()
    private var generation = 0
    private var inFlight: Pair<String,Map<String,Any>>? = null
    private var lastWrite = prefs.getLong("lastWrite",0)
    private val flushRunnable = Runnable { processDue() }
    init { if(queue.stale(System.currentTimeMillis()).isNotEmpty()) message = "Unsent changes expired. Adjust again."; persist(); arm(false) }
    fun selected() = devices.find { it.id == selectedId } ?: devices.firstOrNull()
    fun effective(unit: UnitState): UnitState = unit.withUpdates((inFlight?.takeIf { it.first == unit.id }?.second ?: emptyMap()) + queue.values(unit.id))
    fun pending(id: String) = queue.values(id).isNotEmpty() || inFlight?.first == id
    fun select(id: String) { selectedId = id; selection.edit().putString("selected",id).apply(); notifyViews() }
    private fun persist() {
        val contextJson = JSONObject().put("buildings",JSONArray().put(JSONObject().put("airToAirUnits",JSONArray().also { a -> devices.forEach { a.put(it.toJson()) } })))
        prefs.edit().putString("drafts",queue.toJson().toString()).putString("context",contextJson.toString()).putString("message",message).putLong("updated",updated).putLong("lastWrite",lastWrite).putLong("blocked",blockedUntil).apply()
    }
    private fun notifyViews() { persist(); listeners.toList().forEach { it() }; ClimateWidget.updateAll(context) }
    fun enqueue(id: String, changes: Map<String,Any>): Boolean {
        if(!signedIn) { message = "Open the app to sign in."; notifyViews(); return false }
        if(System.currentTimeMillis() < blockedUntil) { message = "Cloud rate limit. Try again later."; notifyViews(); return false }
        val unit = devices.find { it.id == id } ?: run { message="Device unavailable. Open the app and refresh."; notifyViews(); return false }
        try {
            val combined = (queue.values(id) + changes).toMutableMap()
            if(changes.containsKey("operationMode")) {
                val desired = effective(unit).withUpdates(changes)
                if(!desired.canSetTemperature()) combined.remove("setTemperature")
                else if(combined["setTemperature"] is Number) {
                    val bounds = desired.bounds()
                    combined["setTemperature"] = (combined.getValue("setTemperature") as Number).toDouble().coerceIn(bounds.first,bounds.second)
                }
            }
            validateDesired(effective(unit),combined)
            queue.replace(id,combined,System.currentTimeMillis())
            message = "Changes queued · sending after 3 seconds"
        } catch(e: Exception) { message = e.message ?: "Unsupported change"; notifyViews(); return false }
        notifyViews(); arm(); return true
    }
    fun cancelPendingTemperature(id: String) {
        queue.removeField(id,"setTemperature")
        notifyViews(); arm()
    }
    fun changeTemperature(delta: Double) {
        val unit = selected()?.let { effective(it) } ?: return
        val target = unit.temperature("SetTemperature") ?: return
        enqueue(unit.id,mapOf("setTemperature" to (target + delta).coerceIn(unit.bounds().first,unit.bounds().second)))
    }
    fun widgetAction(action: String) {
        if(action == "refresh") { requestBackgroundRefresh(); return }
        val unit = selected()?.let { effective(it) }
        if(unit == null || !signedIn) { message = "Open Air Conditioner to sign in and select a device."; notifyViews(); return }
        when(action) {
            "power" -> if(unit.value("Power").equals("true",true) || unit.value("Power").equals("false",true)) enqueue(unit.id,mapOf("power" to !unit.value("Power").equals("true",true)))
            "minus" -> changeTemperature(-unit.step())
            "plus" -> changeTemperature(unit.step())
            "mode" -> {
                val modes = unit.modes()
                if(modes.isNotEmpty()) enqueue(unit.id,mapOf("operationMode" to modes[(modes.indexOf(unit.value("OperationMode")) + 1).mod(modes.size)]))
            }
        }
    }
    private fun arm(backup: Boolean = true) {
        main.removeCallbacks(flushRunnable)
        val draft = queue.first() ?: return
        val delay = (maxOf(draft.due,lastWrite + COMMAND_GAP,blockedUntil) - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed(flushRunnable,delay.coerceAtLeast(100))
        // Durable short-lived fallback when the launcher is visible and our process is reclaimed.
        if(backup) scheduleJob(delay)
    }
    private fun scheduleJob(delay: Long) {
        val job = JobInfo.Builder(JOB,ComponentName(context,ClimateJobService::class.java)).setMinimumLatency(delay.coerceAtLeast(0)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
        if(context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_FAILURE) {
            message = "Open the app to finish sending queued changes."
        }
    }
    fun requestBackgroundRefresh() {
        prefs.edit().putBoolean("refreshRequested",true).apply()
        message = "Refresh queued"; notifyViews(); scheduleJob(0)
    }
    fun processDue(done: () -> kotlin.Unit = {}) {
        if(busy) { main.postDelayed({ processDue(done) },500); return }
        if(queue.stale(System.currentTimeMillis()).isNotEmpty()) { message = "Unsent changes expired. Adjust again."; notifyViews() }
        val draft = queue.first()
        if(draft == null) {
            if(prefs.getBoolean("refreshRequested",false)) { prefs.edit().putBoolean("refreshRequested",false).apply(); refresh(done) } else done()
            return
        }
        if(System.currentTimeMillis() < maxOf(draft.due,lastWrite + COMMAND_GAP,blockedUntil)) { arm(); done(); return }
        queue.take(draft.id); inFlight = draft.id to draft.values
        // Persist removal before a PUT: a killed process must never replay an uncertain command.
        lastWrite = System.currentTimeMillis(); busy = true; message = "Sending changes…"; notifyViews()
        if(!prefs.edit().putString("drafts",queue.toJson().toString()).putLong("lastWrite",lastWrite).commit()) {
            busy = false; inFlight = null; message = "Could not save command state. Nothing sent."; notifyViews(); done(); return
        }
        val epoch = generation
        io.execute {
            var accepted = false
            val result = runCatching {
                val latest = cloud.devices()
                val current = latest.find { it.id == draft.id } ?: error("Device no longer available")
                val desired = validateDesired(current,draft.values)
                cloud.control(draft.id,desired); accepted = true
                latest.map { if(it.id == draft.id) it.withUpdates(desired) else it }
            }
            val session = cloud.hasSession()
            main.post {
                if(epoch != generation) { done(); return@post }
                signedIn = session; busy = false; inFlight = null; lastWrite = System.currentTimeMillis()
                result.onSuccess { devices = it; message = "Sent to cloud · awaiting unit update" }
                    .onFailure { handleFailure(it,accepted) }
                notifyViews(); arm(); done()
                main.postDelayed({ if(!busy && queue.first() == null) refresh() },5000)
            }
        }
    }
    fun refresh(done: () -> kotlin.Unit = {}) {
        if(!signedIn || busy || System.currentTimeMillis() < blockedUntil) { done(); return }
        busy = true; message = "Refreshing cloud state…"; notifyViews()
        val epoch = generation
        io.execute {
            val result = runCatching { cloud.devices() }; val session = cloud.hasSession()
            main.post {
                if(epoch != generation) { done(); return@post }
                busy = false; signedIn = session
                result.onSuccess { found ->
                    devices = found; updated = System.currentTimeMillis()
                    if(found.none { it.id == selectedId }) selectedId = found.firstOrNull()?.id
                    selection.edit().putString("selected",selectedId).apply()
                    message = if(found.isEmpty()) "No air-to-air units found" else "Cloud state updated"
                }.onFailure { handleFailure(it) }
                notifyViews(); arm(); done()
            }
        }
    }
    fun login(callback: String? = null, openBrowser: (String) -> kotlin.Unit = {}) {
        if(busy) return
        busy = true; message = if(callback == null) "Opening secure sign in…" else "Completing sign in…"; notifyViews()
        val epoch = generation
        io.execute {
            val result = runCatching { if(callback == null) cloud.beginLogin() else { cloud.finishLogin(callback); "" } }
            val session = cloud.hasSession()
            main.post {
                if(epoch != generation) return@post
                busy = false; signedIn = session
                result.onSuccess { if(callback == null) { message = "Continue in your browser"; openBrowser(it) } else { message = "Signed in"; refresh() } }
                    .onFailure { handleFailure(it) }
                notifyViews()
            }
        }
    }
    fun loadEnergy(id: String) {
        val unit=devices.find { it.id==id } ?: return
        if(!signedIn || busy || System.currentTimeMillis()<blockedUntil) return
        if(!unit.energySupported()) { energyStatus[id]="This unit does not report an energy meter."; notifyViews(); return }
        val now=System.currentTimeMillis()
        if(now-(energyAttempts[id] ?: 0)<30000) { energyStatus[id]="Wait 30 seconds before refreshing energy again."; notifyViews(); return }
        energyAttempts[id]=now
        busy=true; energyStatus[id]="Loading the last 7 days…"; notifyViews()
        val zone=unitZone(unit.timeZone); val end=java.time.Instant.ofEpochMilli(now)
        val start=end.atZone(zone).toLocalDate().minusDays(6)
        val epoch=generation
        io.execute {
            val result=runCatching { parseEnergy(cloud.energy(id,start.atStartOfDay(zone).toInstant(),end),id,zone,start,end,now) }
            val session=cloud.hasSession()
            main.post {
                if(epoch!=generation) return@post
                busy=false; signedIn=session
                result.onSuccess {
                    energyReports[id]=it
                    energyStatus[id]=if(it.buckets==0) "No energy readings returned for this period." else "Updated from MELCloud · ${it.buckets} hourly readings"
                }.onFailure { handleFailure(it); energyStatus[id]=message }
                notifyViews(); arm()
            }
        }
    }
    fun logout() {
        PhoneSchedules.disableAll(context)
        energyReports.clear(); energyStatus.clear(); energyAttempts.clear()
        generation++; signedIn = false; devices = emptyList(); inFlight = null
        queue.clear(); main.removeCallbacks(flushRunnable); context.getSystemService(JobScheduler::class.java).cancel(JOB)
        busy = true; notifyViews()
        io.execute { cloud.logout(); main.post { signedIn = false; busy = false; devices = emptyList(); updated = 0; message = "Signed out"; notifyViews() } }
    }
    private fun handleFailure(error: Throwable, accepted: Boolean = false) {
        if(error is ApiFailure && error.status == 429) blockedUntil = System.currentTimeMillis() + error.retrySeconds.coerceAtLeast(60) * 1000
        message = when {
            accepted -> "Sent; state refresh failed. Tap Refresh."
            error is IOException -> "Connection failed. Command not confirmed; refresh before retrying."
            error is ApiFailure -> error.message ?: "Cloud request failed"
            error is IllegalArgumentException || error is IllegalStateException -> error.message ?: "Change unavailable"
            else -> "Unexpected cloud response. Try Refresh."
        }
    }
}
