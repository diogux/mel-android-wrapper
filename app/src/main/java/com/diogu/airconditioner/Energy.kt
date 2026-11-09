package com.diogu.airconditioner

import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

data class EnergyDay(val date: LocalDate,val kwh: Double)
data class EnergyReport(val days: List<EnergyDay>,val buckets: Int,val rejected: Int,val fetched: Long) {
    val total get()=days.sumOf { it.kwh }
    fun today(date: LocalDate)=days.find { it.date==date }?.kwh
    fun toJson()=JSONObject().put("fetched",fetched).put("buckets",buckets).put("rejected",rejected).put("days",org.json.JSONArray().also { a -> days.forEach { a.put(JSONObject().put("date",it.date.toString()).put("kwh",it.kwh)) } })
    companion object {
        fun fromJson(json: JSONObject): EnergyReport {
            val a=json.getJSONArray("days")
            return EnergyReport((0 until a.length()).map { val d=a.getJSONObject(it); EnergyDay(LocalDate.parse(d.getString("date")),d.getDouble("kwh")) },json.getInt("buckets"),json.optInt("rejected"),json.getLong("fetched"))
        }
    }
}
fun unitZone(value: String): ZoneId = runCatching { ZoneId.of(value) }.getOrDefault(ZoneId.systemDefault())
fun energyTime(raw: String): Instant? {
    val iso=raw.trim().replace(' ','T')
    return runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }.getOrNull()
}
/** ATA energy values are Wh per hourly bucket, not a lifetime cumulative counter. */
fun parseEnergy(root: JSONObject,id: String,zone: ZoneId,start: LocalDate,end: Instant,fetched: Long): EnergyReport {
    val hours=linkedMapOf<Instant,Double>(); var rejected=0
    val series=root.optJSONArray("measureData") ?: org.json.JSONArray()
    for(i in 0 until series.length()) {
        val item=series.getJSONObject(i)
        val device=item.optString("deviceId","")
        if(device.isNotBlank() && device!=id) continue
        if(item.optString("type") !in listOf("cumulativeEnergyConsumedSinceLastUpload","cumulative_energy_consumed_since_last_upload")) continue
        val points=item.optJSONArray("values") ?: continue
        for(j in 0 until points.length()) {
            val point=points.getJSONObject(j)
            val instant=energyTime(point.optString("time")); val value=point.optString("value").toDoubleOrNull()
            if(instant==null || value==null || !value.isFinite() || value<0 || value>100000) { rejected++; continue }
            if(instant<start.atStartOfDay(zone).toInstant() || instant>end) continue
            // Repeated representations of an hour must not inflate consumption.
            hours[instant]=maxOf(hours[instant] ?: 0.0,value/1000.0)
        }
    }
    val dates=hours.entries.groupBy { it.key.atZone(zone).toLocalDate() }.map { (date,items) -> EnergyDay(date,items.sumOf { it.value }) }.sortedBy { it.date }
    return EnergyReport(dates,hours.size,rejected,fetched)
}
fun energyQuery(from: Instant,to: Instant): String {
    require(to>from)
    val format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).withZone(ZoneOffset.UTC)
    return form(mapOf("from" to format.format(from),"to" to format.format(to),"interval" to "Hour","measure" to "cumulative_energy_consumed_since_last_upload"))
}
