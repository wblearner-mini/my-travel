package com.mytravel

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelParseTest {
    private val full = """
        {"isHotelOrder": true, "hotelName": " 示例酒店 ", "branch": "王府井店", "address": "北京市东城区示例路 1 号",
         "checkIn": {"year": 2026, "month": 11, "day": 20}, "checkOut": {"year": null, "month": 11, "day": 22},
         "checkInTime": "14:00", "checkOutTime": "12:00", "roomType": "大床房", "breakfast": "含双早",
         "cancelPolicy": null, "latestArrival": "", "otherNotes": null, "uncertain": ["branch", "checkOut"]}
    """

    @Test fun readsEveryStatedField() {
        val e = toExtract(JSONObject(full))
        assertTrue(e.isHotelOrder)
        assertEquals("示例酒店", e.hotelName)
        assertEquals("王府井店", e.branch)
        assertEquals(PrintedDate(2026, 11, 20), e.checkIn)
        assertEquals("14:00", e.checkInTime)
        assertEquals(listOf("branch", "checkOut"), e.uncertain)
    }

    @Test fun datePartsTheOrderDoesNotShowStayNull() =
        assertEquals(PrintedDate(null, 11, 22), toExtract(JSONObject(full)).checkOut)

    @Test fun nullAndBlankStringsBecomeNull() {
        val e = toExtract(JSONObject(full))
        assertNull(e.cancelPolicy)
        assertNull(e.latestArrival)
        assertNull(e.otherNotes)
    }

    @Test fun malformedTimesAreTreatedAsNotStated() {
        val o = JSONObject(full).put("checkInTime", "下午两点").put("checkOutTime", "24:00")
        val e = toExtract(o)
        assertNull(e.checkInTime)
        assertNull(e.checkOutTime)
    }

    @Test fun aScreenshotThatIsNotAnOrderKeepsEveryFieldEmpty() {
        val e = toExtract(JSONObject("""{"isHotelOrder": false, "hotelName": null, "checkIn": null, "uncertain": []}"""))
        assertFalse(e.isHotelOrder)
        assertNull(e.hotelName)
        assertNull(e.checkIn)
        assertTrue(e.uncertain.isEmpty())
    }

    @Test(expected = JSONException::class)
    fun aReplyWithoutTheUncertainListIsRejected() {
        // parseHotelOrder turns this into a failure, and the user fills the form by hand.
        toExtract(JSONObject("""{"isHotelOrder": true}"""))
    }
}
