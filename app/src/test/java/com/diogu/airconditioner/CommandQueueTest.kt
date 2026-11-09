package com.diogu.airconditioner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandQueueTest {
    @Test fun rapidSliderEditsBecomeOneFinalTemperatureAndResetQuietPeriod() {
        val q=CommandQueue()
        q.put("u",mapOf("setTemperature" to 21.0),1000)
        q.put("u",mapOf("setTemperature" to 22.0),2000)
        q.put("u",mapOf("setTemperature" to 23.5),3500)
        assertEquals(6500L,q.first()!!.due)
        assertEquals(mapOf("setTemperature" to 23.5),q.take("u")!!.values)
        assertNull(q.first())
    }
    @Test fun independentFieldsMergeAndRepeatedPowerStoresDesiredState() {
        val q=CommandQueue()
        q.put("u",mapOf("power" to true,"operationMode" to "Heat"),0)
        q.put("u",mapOf("power" to false,"setFanSpeed" to "Two"),500)
        assertEquals(mapOf("power" to false,"operationMode" to "Heat","setFanSpeed" to "Two"),q.values("u"))
    }
    @Test fun queueRoundTripKeepsValuesAndDevicesSeparate() {
        val q=CommandQueue()
        q.put("a",mapOf("setTemperature" to 22.5),1000)
        q.put("b",mapOf("vaneVerticalDirection" to "Swing"),2000)
        val restored=CommandQueue(JSONObject(q.toJson().toString()))
        assertEquals("a",restored.first()!!.id)
        restored.take("a")
        assertEquals(mapOf("vaneVerticalDirection" to "Swing"),restored.values("b"))
        assertTrue(restored.values("a").isEmpty())
    }
    @Test fun startingAnotherSliderDragDropsOldTemperatureButKeepsOtherControls() {
        val q=CommandQueue(); q.put("u",mapOf("setTemperature" to 20.0,"setFanSpeed" to "Two"),1000)
        q.removeField("u","setTemperature")
        assertEquals(mapOf("setFanSpeed" to "Two"),q.values("u"))
        q.removeField("u","setFanSpeed"); assertNull(q.first())
    }
    @Test fun oldCommandsExpireRatherThanApplyingHoursLater() {
        val q=CommandQueue(); q.put("a",mapOf("power" to false),0)
        assertEquals(listOf("a"),q.stale(120001)); assertNull(q.first())
    }
    @Test fun vaneSupportAndValidationRespectCapabilities() {
        val unit=demoState()
        assertTrue("Swing" in unit.verticalVanes())
        assertTrue("LeftCentre" in unit.horizontalVanes())
        assertEquals("Swing",validateDesired(unit,mapOf("vaneVerticalDirection" to "Swing"))["vaneVerticalDirection"])
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("vaneHorizontalDirection" to "invented")) }
        val unsupported=unit.copy(caps=JSONObject("{\"hasVaneVertical\":false,\"hasVaneHorizontal\":false}"))
        assertTrue(unsupported.verticalVanes().isEmpty()); assertTrue(unsupported.horizontalVanes().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unsupported,mapOf("vaneVerticalDirection" to "Auto")) }
    }
    @Test fun finalTemperatureValidatesAgainstSelectedModeAndStep() {
        val unit=demoState()
        assertEquals(22.5,validateDesired(unit,mapOf("setTemperature" to 22.5))["setTemperature"])
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("setTemperature" to 22.3)) }
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("operationMode" to "Fan","setTemperature" to 22.0)) }
        assertThrows(IllegalArgumentException::class.java) { validateDesired(unit,mapOf("setTemperature" to 32.0)) }
        assertEquals(true,validateDesired(unit,mapOf("power" to true))["power"])
    }
    private fun demoState()=UnitState("u","Room",mapOf("Power" to "False","OperationMode" to "Heat","SetTemperature" to "22","VaneVerticalDirection" to "Auto","VaneHorizontalDirection" to "Centre"),JSONObject("""{"hasHeatOperationMode":true,"hasFanOperationMode":true,"hasHalfDegreeIncrements":true,"hasSwing":true,"hasVaneVertical":true,"hasVaneHorizontal":true}"""),true)
}
