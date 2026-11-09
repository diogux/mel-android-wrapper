package com.diogu.airconditioner

import org.json.JSONObject

const val COMMAND_GAP = 3000L
/** Persistable latest-value queue. Each edit resets the quiet period for that unit. */
class CommandQueue(json: JSONObject = JSONObject()) {
    data class Draft(val id: String, val values: Map<String,Any>, val due: Long, val created: Long)
    private val drafts = linkedMapOf<String,Draft>()
    init {
        json.keys().forEach { id ->
            val item = json.getJSONObject(id); val values = item.getJSONObject("values")
            drafts[id] = Draft(id,values.keys().asSequence().associateWith { values.get(it) },item.getLong("due"),item.getLong("created"))
        }
    }
    fun put(id: String, values: Map<String,Any>, now: Long) {
        val previous = drafts[id]
        drafts[id] = Draft(id,(previous?.values ?: emptyMap()) + values,now + COMMAND_GAP,previous?.created ?: now)
    }
    fun replace(id: String, values: Map<String,Any>, now: Long) {
        val created = drafts[id]?.created ?: now
        drafts[id] = Draft(id,values,now + COMMAND_GAP,created)
    }
    fun values(id: String) = drafts[id]?.values ?: emptyMap()
    fun first() = drafts.values.minByOrNull { it.due }
    fun removeField(id: String, field: String) {
        val draft = drafts[id] ?: return
        val remaining = draft.values - field
        if(remaining.isEmpty()) drafts.remove(id) else drafts[id] = draft.copy(values=remaining)
    }
    fun take(id: String) = drafts.remove(id)
    fun clear() = drafts.clear()
    fun stale(now: Long): List<String> {
        val expired = drafts.values.filter { now - it.created > 120000 }.map { it.id }
        expired.forEach { drafts.remove(it) }; return expired
    }
    fun toJson() = JSONObject().also { root -> drafts.forEach { (id,d) -> root.put(id,JSONObject().put("values",JSONObject(d.values)).put("due",d.due).put("created",d.created)) } }
}
