package com.diogu.airconditioner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class ProtocolTest {
    class Memory(initial: JSONObject = JSONObject()) : Vault {
        var data = initial
        override fun read() = JSONObject(data.toString())
        override fun write(value: JSONObject) { data = JSONObject(value.toString()) }
    }
    data class Request(val method: String, val url: String, val body: String?, val token: String?, val type: String)
    class Mock(vararg replies: Reply) : Transport {
        val requests = mutableListOf<Request>()
        val answers = ArrayDeque(replies.toList())
        override fun request(method: String, url: String, body: String?, bearer: String?, contentType: String): Reply {
            requests.add(Request(method,url,body,bearer,contentType)); return answers.removeFirst()
        }
    }
    private fun decode(body: String?) = body!!.split('&').associate { URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('='),"UTF-8") }
    private fun session(expiry: Long = 999999999L) = JSONObject().put("access_token","old").put("refresh_token","refresh").put("expires",expiry)
    private val empty = Reply(200,"{\"buildings\":[]}")
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        repeat(10) { assertTrue(randomValue().matches(Regex("[A-Za-z0-9_-]{43}"))) }
    }
    @Test fun loginUsesParStateAndVerifierThenConsumesPendingLogin() {
        val vault = Memory()
        val http = Mock(Reply(201,"{\"request_uri\":\"urn:test:par\"}"),Reply(200,"{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}"))
        val cloud = Cloud(vault,http) { 1000 }
        assertTrue(cloud.beginLogin().contains("request_uri=urn%3Atest%3Apar"))
        val fields = decode(http.requests.first().body)
        assertEquals("homemobile",fields["client_id"])
        assertEquals(REDIRECT, fields["redirect_uri"])
        assertEquals(challenge(vault.data.getString("verifier")),fields["code_challenge"])
        val verifier = vault.data.getString("verifier")
        cloud.finishLogin("melcloudhome://?code=hello&state=${fields["state"]}")
        assertEquals(verifier,decode(http.requests.last().body)["code_verifier"])
        assertTrue(cloud.hasSession()); assertFalse(cloud.pending())
        assertEquals(3601000L,vault.data.getLong("expires"))
    }
    @Test fun mismatchedStateNeverExchangesCode() {
        val http = Mock(Reply(201,"{\"request_uri\":\"urn:par\"}"))
        val cloud = Cloud(Memory(),http) { 1000 }; cloud.beginLogin()
        assertThrows(IllegalArgumentException::class.java) { cloud.finishLogin("melcloudhome://?code=bad&state=attacker") }
        assertEquals(1,http.requests.size)
    }
    @Test fun expiredLoginRejected() {
        var time = 1000L
        val http = Mock(Reply(201,"{\"request_uri\":\"urn:par\"}")); val vault = Memory(); val cloud = Cloud(vault,http) { time }
        cloud.beginLogin(); time += 600001
        assertThrows(IllegalArgumentException::class.java) { cloud.finishLogin("melcloudhome://?code=bad&state=${vault.data.optString("state")}") }
        assertEquals(1,http.requests.size)
    }
    @Test fun expiryRefreshRotatesAndPersistsTokens() {
        val vault = Memory(session(1000))
        val http = Mock(Reply(200,"{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":3600}"),empty)
        Cloud(vault,http) { 100000 }.devices()
        assertEquals("refresh_token",decode(http.requests[0].body)["grant_type"])
        assertEquals("new",http.requests[1].token)
        assertEquals("rotated",vault.data.getString("refresh_token"))
    }
    @Test fun refreshWithoutRotationRetainsRefreshToken() {
        val vault = Memory(session(1))
        val http = Mock(Reply(200,"{\"access_token\":\"new\"}"),empty)
        Cloud(vault,http) { 100000 }.devices()
        assertEquals("refresh",vault.data.getString("refresh_token"))
    }
    @Test fun unauthorizedRetriesOnceWithRefreshedToken() {
        val http = Mock(Reply(401,"{}"),Reply(200,"{\"access_token\":\"new\"}"),empty)
        Cloud(Memory(session()),http) { 100000 }.devices()
        assertEquals(3,http.requests.size); assertEquals("new",http.requests.last().token)
    }
    @Test fun rejectedRefreshClearsSession() {
        val vault = Memory(session(1)); val http = Mock(Reply(400,"{}")); val cloud = Cloud(vault,http) { 100000 }
        assertThrows(ApiFailure::class.java) { cloud.devices() }; assertFalse(cloud.hasSession())
    }
    @Test fun networkFailurePreservesSession() {
        val vault = Memory(session(1))
        val cloud = Cloud(vault,Transport { _,_,_,_,_ -> throw java.io.IOException() }) { 100000 }
        assertThrows(java.io.IOException::class.java) { cloud.devices() }; assertTrue(cloud.hasSession())
    }
    @Test fun payloadHasEveryRequiredFieldAndNullDefaults() {
        val p = payload(mapOf("power" to true,"operationMode" to "Heat"))
        assertEquals(CONTROL_FIELDS.toSet(),p.keySet()); assertTrue(p.getBoolean("power")); assertEquals("Heat",p.getString("operationMode"))
        assertTrue(p.isNull("setFanSpeed")); assertTrue(p.isNull("setTemperature"))
        assertThrows(IllegalArgumentException::class.java) { payload(mapOf("invented" to 1)) }
    }
    @Test fun discoveryParsesOwnedAndGuestUnitsAndCapabilities() {
        val unit = """{"id":"u1","givenDisplayName":"Bedroom","settings":[{"name":"Power","value":"True"},{"name":"RoomTemperature","value":"20.5"},{"name":"SetTemperature","value":22},{"name":"OperationMode","value":"Heat"}],"capabilities":{"hasHeatOperationMode":true,"hasCoolOperationMode":true,"hasDryOperationMode":false,"numberOfFanSpeeds":3,"hasAutomaticFanSpeed":true,"minTempHeat":10,"maxTempHeat":31}}"""
        val root = JSONObject("""{"buildings":[{"airToAirUnits":[$unit]}],"guestBuildings":[{"airToAirUnits":[{"id":"u2","givenDisplayName":"Guest"}]}]}""")
        val found = parseDevices(root); assertEquals(2,found.size)
        assertEquals(20.5,found[0].temperature("RoomTemperature")!!,0.01)
        assertEquals(listOf("Heat","Cool"),found[0].modes())
        assertEquals(listOf("Auto","One","Two","Three"),found[0].fans())
        assertEquals(10.0 to 31.0,found[0].bounds())
        assertTrue(found[1].modes().isEmpty()); assertTrue(found[1].fans().isEmpty())
    }
    @Test fun commandsUseMobileEndpointPutAndStringFanSpeed() {
        val http = Mock(Reply(204,""))
        Cloud(Memory(session()),http) { 100000 }.control("abc",mapOf("setFanSpeed" to "Three"))
        val req = http.requests.single(); assertEquals("PUT",req.method); assertEquals(API+"/monitor/ataunit/abc",req.url)
        assertEquals("Three",JSONObject(req.body!!).getString("setFanSpeed"))
    }
    @Test fun rateLimitDoesNotRetryOrDiscardSession() {
        val http = Mock(Reply(429,"{}",120)); val cloud = Cloud(Memory(session()),http) { 100000 }
        val error = assertThrows(ApiFailure::class.java) { cloud.devices() }
        assertEquals(120,error.retrySeconds); assertEquals(1,http.requests.size); assertTrue(cloud.hasSession())
    }

    @Test fun upstreamContextFixtureParsesRealResponseShape() {
        val fixture = javaClass.getResource("/upstream-context.json")!!.readText()
        val units = parseDevices(JSONObject(fixture))
        assertTrue(units.isNotEmpty())
        val first = units.first()
        assertEquals("Living Room AC",first.name)
        assertEquals(true,first.connected)
        assertEquals(20.0,first.temperature("RoomTemperature")!!,0.01)
        assertEquals(6,first.fans().size)
    }
    @Test fun desiredValuesPreserveIntentAndRespectFreshStateLimits() {
        val unit = UnitState("u","AC",mapOf("Power" to "True","OperationMode" to "Heat","SetTemperature" to "22"),JSONObject("{\"hasHeatOperationMode\":true,\"hasHalfDegreeIncrements\":true}"),true)
        assertEquals(mapOf("power" to true,"operationMode" to "Heat"),validateDesired(unit,mapOf("power" to true)))
        assertEquals(22.5,validateDesired(unit,mapOf("setTemperature" to 22.5))["setTemperature"])
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit.copy(connected=false),mapOf("power" to true)) }
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("operationMode" to "Dry")) }
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("setTemperature" to 31.5)) }
    }
}
