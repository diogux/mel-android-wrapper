package com.diogu.airconditioner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EnergyScheduleTest {
    private fun schedule(days: Set<Int> = setOf(1),zone: String="Europe/Lisbon",hour: Int=7,minute: Int=30)=WeeklySchedule(unitId="u",hour=hour,minute=minute,days=days,zone=zone,commands=mapOf("power" to true,"operationMode" to "Heat","setTemperature" to 22.0))
    @Test fun weeklyScheduleFindsNextDayAndNeverRepeatsSameInstant() {
        val s=schedule()
        assertEquals(Instant.parse("2026-11-09T07:30:00Z"),s.next(Instant.parse("2026-11-08T12:00:00Z")))
        assertEquals(Instant.parse("2026-11-16T07:30:00Z"),s.next(Instant.parse("2026-11-09T07:30:00Z")))
    }
    @Test fun daylightSavingGapMovesForwardAndOverlapRunsOnce() {
        val spring=schedule(setOf(7),"Europe/Berlin",2,30)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),spring.next(Instant.parse("2026-03-28T18:00:00Z")))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),spring.next(Instant.parse("2026-10-24T18:00:00Z")))
        assertEquals(Instant.parse("2026-11-01T01:30:00Z"),spring.next(Instant.parse("2026-10-25T00:31:00Z")))
    }
    @Test fun scheduleSerializationPreservesDayTimeZoneAndExplicitPower() {
        val s=schedule(setOf(1,3,7)); val restored=WeeklySchedule.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.id,restored.id); assertEquals(s.days,restored.days); assertEquals(s.zone,restored.zone)
        assertEquals(true,restored.commands["power"])
        assertEquals(22.0,(restored.commands["setTemperature"] as Number).toDouble(),0.001)
        assertThrows(IllegalArgumentException::class.java) { schedule(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { schedule(setOf(0)) }
    }
    @Test fun energyUsesWhDeduplicatesHoursAndRejectsInvalidAndOtherDevices() {
        val data=JSONObject("""{"measureData":[{"deviceId":"u","type":"cumulativeEnergyConsumedSinceLastUpload","values":[{"time":"2026-11-08 01:00:00.000000000","value":"1000"},{"time":"2026-11-08 01:00:00.000000000","value":"1500"},{"time":"2026-11-08 02:00:00","value":"500"},{"time":"2026-11-08 03:00:00","value":"-2"},{"time":"bad","value":"42"},{"time":"2026-11-08 04:00:00","value":"NaN"},{"time":"2026-11-08 05:00:00","value":"999999"}]},{"deviceId":"different","type":"cumulativeEnergyConsumedSinceLastUpload","values":[{"time":"2026-11-08 02:00:00","value":"1000"}]}]}""")
        val report=parseEnergy(data,"u",ZoneId.of("UTC"),LocalDate.parse("2026-11-08"),Instant.parse("2026-11-08T12:00:00Z"),1000)
        assertEquals(2.0,report.total,0.0001); assertEquals(2,report.buckets); assertEquals(4,report.rejected)
        assertEquals(2.0,EnergyReport.fromJson(JSONObject(report.toJson().toString())).total,0.0001)
    }
    @Test fun energyMissingIsNotZeroAndLocalDayBoundariesAreRespected() {
        val missing=parseEnergy(JSONObject(),"u",ZoneId.of("UTC"),LocalDate.parse("2026-11-08"),Instant.parse("2026-11-08T12:00:00Z"),1000)
        assertEquals(0,missing.buckets); assertNull(missing.today(LocalDate.parse("2026-11-08")))
        val data=JSONObject("""{"measureData":[{"deviceId":"u","type":"cumulativeEnergyConsumedSinceLastUpload","values":[{"time":"2026-07-07T23:00:00Z","value":"1000"},{"time":"2026-07-08T03:00:00Z","value":"0"}]}]}""")
        val local=parseEnergy(data,"u",ZoneId.of("Europe/Lisbon"),LocalDate.parse("2026-07-08"),Instant.parse("2026-07-08T12:00:00Z"),1000)
        assertEquals(LocalDate.parse("2026-07-08"),local.days.single().date); assertEquals(1.0,local.total,0.001)
    }
    @Test fun energyRequestUsesVerifiedMobileEndpointAndUtcQuery() {
        val vault=ProtocolTest.Memory(JSONObject().put("access_token","access").put("refresh_token","refresh").put("expires",999999999L))
        val transport=ProtocolTest.Mock(Reply(304,""))
        val cloud=Cloud(vault,transport) { 1000 }
        val result=cloud.energy("u",Instant.parse("2026-11-02T00:00:00Z"),Instant.parse("2026-11-08T12:00:00Z"))
        assertEquals(0,result.length())
        val request=transport.requests.single()
        assertEquals("GET",request.method); assertTrue(request.url.startsWith(API+"/telemetry/telemetry/energy/u?"))
        assertTrue(request.url.contains("interval=Hour")); assertTrue(request.url.contains("measure=cumulative_energy_consumed_since_last_upload"))
        assertTrue(request.url.contains("from=2026-11-02+00%3A00"))
    }
    @Test fun deviceContextPreservesCloudSchedulesAndTimeZoneForCache() {
        val json=JSONObject("""{"buildings":[{"timezone":"Europe/Lisbon","airToAirUnits":[{"id":"u","scheduleEnabled":true,"schedule":[{"id":"s","days":[1],"time":"07:00:00","power":true}],"capabilities":{"hasEnergyConsumedMeter":true}}]}]}""")
        val unit=parseDevices(json).single()
        assertEquals("Europe/Lisbon",unit.timeZone); assertTrue(unit.energySupported()); assertEquals(true,unit.schedulesEnabled)
        val reread=parseDevices(JSONObject().put("buildings",org.json.JSONArray().put(JSONObject().put("airToAirUnits",org.json.JSONArray().put(unit.toJson()))))).single()
        assertEquals(1,reread.schedules.length()); assertEquals(unit.timeZone,reread.timeZone)
    }
}
