package com.diogu.airconditioner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val AUTH = "https://auth.melcloudhome.com"
const val API = "https://mobile.bff.melcloudhome.com"
const val REDIRECT = "melcloudhome://"
const val CLIENT = "homemobile"
fun form(values: Map<String, String>) = values.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}
fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
class ApiFailure(val status: Int, val retrySeconds: Long = 0) : Exception(when(status) {
    400, 401, 403 -> "Session rejected. Please sign in again."
    429 -> "Too many requests. Wait at least ${retrySeconds.coerceAtLeast(60)} seconds."
    else -> "Cloud request failed (HTTP $status). Try again later."
})
data class Reply(val status: Int, val body: String, val retrySeconds: Long = 0)
fun interface Transport { fun request(method: String, url: String, body: String?, bearer: String?, contentType: String): Reply }
class HttpsTransport : Transport {
    override fun request(method: String, url: String, body: String?, bearer: String?, contentType: String): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "AirConditioner/1.0")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Reply(status, text, c.getHeaderField("Retry-After")?.toLongOrNull() ?: 60)
        } finally { c.disconnect() }
    }
}
interface Vault { fun read(): JSONObject; fun write(value: JSONObject) }
class Cloud(private val vault: Vault, private val transport: Transport = HttpsTransport(), private val now: () -> Long = { System.currentTimeMillis() }) {
    private var saved = vault.read()
    fun hasSession() = saved.optString("refresh_token").isNotEmpty()
    fun pending() = saved.optString("verifier").isNotEmpty() && now() - saved.optLong("login_started") < 600000
    fun logout() { saved = JSONObject(); vault.write(saved) }
    private fun call(method: String, url: String, body: String? = null, token: String? = null, type: String = "application/json"): JSONObject {
        val r = transport.request(method, url, body, token, type)
        if (r.status == 304 && method == "GET") return JSONObject()
        if (r.status !in 200..299) throw ApiFailure(r.status, r.retrySeconds)
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }
    private fun post(path: String, data: Map<String, String>) = call("POST", AUTH + path, form(data), type = "application/x-www-form-urlencoded")
    fun beginLogin(): String {
        val verifier = randomValue(); val state = randomValue()
        val response = post("/connect/par", mapOf("client_id" to CLIENT, "redirect_uri" to REDIRECT, "response_type" to "code", "scope" to "openid profile email offline_access IdentityServerApi", "state" to state, "code_challenge" to challenge(verifier), "code_challenge_method" to "S256"))
        saved.put("verifier", verifier).put("state", state).put("login_started", now()); vault.write(saved)
        return AUTH + "/connect/authorize?" + form(mapOf("client_id" to CLIENT, "request_uri" to response.getString("request_uri")))
    }
    fun finishLogin(callback: String) {
        require(callback.startsWith(REDIRECT) && pending()) { "Login expired. Please start sign in again." }
        val fields = callback.substringAfter('?', "").substringBefore('#').split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        require(fields["state"] == saved.getString("state")) { "Login state mismatch. Please start sign in again." }
        require(!fields.containsKey("error") && !fields["code"].isNullOrEmpty()) { "Sign in was cancelled or rejected." }
        val verifier = saved.getString("verifier")
        saved.remove("verifier"); saved.remove("state"); saved.remove("login_started"); vault.write(saved)
        storeTokens(post("/connect/token", mapOf("grant_type" to "authorization_code", "client_id" to CLIENT, "code" to fields.getValue("code"), "redirect_uri" to REDIRECT, "code_verifier" to verifier)))
    }
    private fun storeTokens(tokens: JSONObject) {
        val access = tokens.getString("access_token"); require(access.isNotBlank())
        saved.put("access_token", access)
        if (tokens.has("refresh_token")) saved.put("refresh_token", tokens.getString("refresh_token"))
        saved.put("expires", now() + tokens.optLong("expires_in", 3600) * 1000); vault.write(saved)
    }
    private fun refresh() {
        try { storeTokens(post("/connect/token", mapOf("grant_type" to "refresh_token", "client_id" to CLIENT, "refresh_token" to saved.getString("refresh_token")))) }
        catch (e: ApiFailure) { if (e.status in listOf(400,401,403)) logout(); throw e }
    }
    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        check(hasSession()) { "Please sign in." }
        if (now() >= saved.optLong("expires") - 60000) refresh()
        try { return call(method, API + path, body?.toString(), saved.getString("access_token")) }
        catch(e: ApiFailure) {
            if (e.status != 401) throw e
            refresh()
            return call(method, API + path, body?.toString(), saved.getString("access_token"))
        }
    }
    fun energy(id: String, from: java.time.Instant, to: java.time.Instant): JSONObject = api("GET", "/telemetry/telemetry/energy/" + URLEncoder.encode(id,"UTF-8") + "?" + energyQuery(from,to))
    fun devices(): List<UnitState> = parseDevices(api("GET", "/context"))
    fun control(id: String, updates: Map<String, Any>) { api("PUT", "/monitor/ataunit/" + URLEncoder.encode(id,"UTF-8"), payload(updates)) }
}
val CONTROL_FIELDS = listOf("power", "operationMode", "setFanSpeed", "vaneHorizontalDirection", "vaneVerticalDirection", "setTemperature", "temperatureIncrementOverride", "inStandbyMode")
fun payload(updates: Map<String, Any>): JSONObject {
    require(updates.isNotEmpty() && updates.keys.all { it in CONTROL_FIELDS })
    return JSONObject().also { json -> CONTROL_FIELDS.forEach { json.put(it, updates[it] ?: JSONObject.NULL) } }
}
data class UnitState(val id: String, val name: String, val settings: Map<String, String>, val caps: JSONObject, val connected: Boolean? = null, val timeZone: String = java.time.ZoneId.systemDefault().id, val schedules: JSONArray = JSONArray(), val schedulesEnabled: Boolean? = null) {
    fun value(key: String) = settings[key]
    fun temperature(key: String) = value(key)?.toDoubleOrNull()
    fun modes(): List<String> = listOf("Heat" to "hasHeatOperationMode", "Cool" to "hasCoolOperationMode", "Automatic" to "hasAutoOperationMode", "Dry" to "hasDryOperationMode", "Fan" to "hasFanOperationMode").filter { (mode, cap) -> caps.optBoolean(cap, false) || (!caps.has(cap) && value("OperationMode") == mode) }.map { it.first }
    fun fans(): List<String> {
        val choices = mutableListOf<String>()
        if (caps.optBoolean("hasAutomaticFanSpeed",false)) choices.add("Auto")
        val count = caps.optInt("numberOfFanSpeeds", 0).coerceIn(0,5)
        // Capability count 3 uses One, Two, Three, matching the inspected client.
        choices.addAll(listOf("One","Two","Three","Four","Five").take(count))
        return choices
    }
    fun energySupported() = caps.optBoolean("hasEnergyConsumedMeter",false)
    fun step() = if(caps.optBoolean("hasHalfDegreeIncrements")) 0.5 else 1.0
    fun canSetTemperature() = temperature("SetTemperature") != null && value("OperationMode") in listOf("Heat","Cool","Automatic","Dry")
    fun verticalVanes(): List<String> {
        if(caps.has("hasVaneVertical") && !caps.optBoolean("hasVaneVertical")) return emptyList()
        if(!caps.optBoolean("hasVaneVertical") && !caps.optBoolean("hasAirDirection") && value("VaneVerticalDirection") == null) return emptyList()
        return listOf("Auto") + (if(caps.optBoolean("hasSwing")) listOf("Swing") else emptyList()) + listOf("One","Two","Three","Four","Five")
    }
    fun horizontalVanes(): List<String> {
        if(caps.has("hasVaneHorizontal") && !caps.optBoolean("hasVaneHorizontal")) return emptyList()
        if(!caps.optBoolean("hasVaneHorizontal") && !caps.optBoolean("supportsWideVane") && value("VaneHorizontalDirection") == null) return emptyList()
        return listOf("Auto") + (if(caps.optBoolean("hasSwing")) listOf("Swing") else emptyList()) + listOf("Left","LeftCentre","Centre","RightCentre","Right")
    }
    fun withUpdates(updates: Map<String,Any>): UnitState {
        val names = mapOf("power" to "Power","operationMode" to "OperationMode","setTemperature" to "SetTemperature","setFanSpeed" to "SetFanSpeed","vaneVerticalDirection" to "VaneVerticalDirection","vaneHorizontalDirection" to "VaneHorizontalDirection")
        return copy(settings = settings + updates.mapNotNull { (key,value) -> names[key]?.let { it to value.toString() } }.toMap())
    }
    fun toJson() = JSONObject().put("id",id).put("givenDisplayName",name).put("capabilities",caps)
        .put("timeZone",timeZone).put("schedule",schedules).also { if(schedulesEnabled != null) it.put("scheduleEnabled",schedulesEnabled) }
        .put("settings",JSONArray().also { a -> settings.forEach { (name,value) -> a.put(JSONObject().put("name",name).put("value",value)) } })
        .also { if(connected != null) it.put("isConnected",connected) }
    fun bounds(): Pair<Double,Double> {
        val suffix = when(value("OperationMode")) { "Heat" -> "Heat"; "Automatic" -> "Automatic"; else -> "CoolDry" }
        return caps.optDouble("minTemp$suffix", if(suffix == "Heat") 10.0 else 16.0) to caps.optDouble("maxTemp$suffix",31.0)
    }
}
fun parseDevices(root: JSONObject): List<UnitState> {
    val result = linkedMapOf<String,UnitState>()
    for (group in listOf("buildings", "guestBuildings")) {
        val buildings = root.optJSONArray(group) ?: JSONArray()
        for (i in 0 until buildings.length()) {
            val building = buildings.getJSONObject(i)
            val units = building.optJSONArray("airToAirUnits") ?: JSONArray()
            for (j in 0 until units.length()) {
                val u = units.getJSONObject(j); val settings = linkedMapOf<String,String>(); val entries = u.optJSONArray("settings") ?: JSONArray()
                for (k in 0 until entries.length()) { val item = entries.getJSONObject(k); if (!item.isNull("value")) settings[item.getString("name")] = item.get("value").toString() }
                settings["SetFanSpeed"]?.toIntOrNull()?.let { code -> listOf("Auto","One","Two","Three","Four","Five").getOrNull(code)?.let { settings["SetFanSpeed"] = it } }
                settings["VaneVerticalDirection"]?.let { raw -> mapOf("0" to "Auto","1" to "One","2" to "Two","3" to "Three","4" to "Four","5" to "Five","7" to "Swing")[raw]?.let { settings["VaneVerticalDirection"] = it } }
                val id = u.getString("id")
                result[id] = UnitState(id, u.optString("givenDisplayName","Air conditioner"), settings, u.optJSONObject("capabilities") ?: JSONObject(), if(u.isNull("isConnected")) null else u.optBoolean("isConnected"),u.optString("timeZone",building.optString("timezone",java.time.ZoneId.systemDefault().id)),u.optJSONArray("schedule") ?: JSONArray(),if(u.isNull("scheduleEnabled")) null else u.optBoolean("scheduleEnabled"))
            }
        }
    }
    return result.values.toList()
}

/** Absolute desired values make coalesced app/widget commands idempotent. */
fun validateDesired(current: UnitState, updates: Map<String,Any>): Map<String,Any> {
    require(current.connected != false) { "Unit is offline. Refresh when it reconnects." }
    require(updates.isNotEmpty() && updates.keys.all { it in CONTROL_FIELDS })
    val result = updates.toMutableMap()
    if(result.containsKey("power")) {
        require(result["power"] is Boolean)
        if(!result.containsKey("operationMode")) current.value("OperationMode")?.takeIf { it in current.modes() }?.let { result["operationMode"] = it }
    }
    result["operationMode"]?.let { require(it in current.modes()) { "Mode is not supported." } }
    result["setFanSpeed"]?.let { require(it in current.fans()) { "Fan speed is not supported." } }
    result["vaneVerticalDirection"]?.let { require(it in current.verticalVanes()) { "Vertical vane is not supported." } }
    result["vaneHorizontalDirection"]?.let { require(it in current.horizontalVanes()) { "Horizontal vane is not supported." } }
    result["setTemperature"]?.let { raw ->
        val effective = current.withUpdates(result)
        require(effective.canSetTemperature()) { "Temperature is unavailable in this mode." }
        val temperature = (raw as? Number)?.toDouble() ?: error("Invalid temperature")
        val (min,max) = effective.bounds()
        require(temperature.isFinite() && temperature in min..max && kotlin.math.abs(temperature / effective.step() - kotlin.math.round(temperature / effective.step())) < 0.001) { "Temperature is outside this mode's range." }
    }
    return result
}
