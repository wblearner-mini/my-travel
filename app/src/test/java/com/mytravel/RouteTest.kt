package com.mytravel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private val a = Stop("天安门", 39.9087, 116.3975, placeId = 1)
    private val b = Stop("王府井", 39.9150, 116.4110, placeId = 2)

    @Test fun legKeyChangesWhenAStopMoves() =
        assertNotEquals(legKey(a, b, TravelMode.Drive), legKey(a, b.copy(lat = 39.9200), TravelMode.Drive))

    @Test fun legKeyChangesWithTheTravelMode() =
        assertNotEquals(legKey(a, b, TravelMode.Drive), legKey(a, b, TravelMode.Walk))

    @Test fun legKeyDependsOnDirection() =
        assertNotEquals(legKey(a, b, TravelMode.Drive), legKey(b, a, TravelMode.Drive))

    @Test fun legKeyIgnoresTheStopName() =
        assertEquals(legKey(a, b, TravelMode.Drive), legKey(a, b.copy(name = "王府井大街"), TravelMode.Drive))

    @Test fun drivingWithUpToSixteenViasGoesToAmapInOneCall() {
        assertTrue(canNavigateAll(TravelMode.Drive, stopCount = 2))
        assertTrue(canNavigateAll(TravelMode.Drive, stopCount = AMAP_MAX_VIAS + 2))
    }

    @Test fun moreThanSixteenViasMustBeNavigatedLegByLeg() =
        assertFalse(canNavigateAll(TravelMode.Drive, stopCount = AMAP_MAX_VIAS + 3))

    @Test fun walkingIsAlwaysLegByLeg() =
        assertFalse(canNavigateAll(TravelMode.Walk, stopCount = 2))

    @Test fun distancesUnderOneKilometreAreInMetres() {
        assertEquals("999 米", formatDistance(999f))
        assertEquals("1.0 公里", formatDistance(1000f))
        assertEquals("12.3 公里", formatDistance(12_340f))
    }

    @Test fun durationsRoundUpToTheMinuteAndDropZeroMinutes() {
        assertEquals("1 分钟", formatDuration(1))
        assertEquals("59 分钟", formatDuration(59 * 60))
        assertEquals("1 小时", formatDuration(3600))
        assertEquals("1 小时 1 分钟", formatDuration(3601))
    }
}
