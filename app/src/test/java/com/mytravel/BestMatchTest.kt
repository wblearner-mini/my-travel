package com.mytravel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestMatchTest {
    private fun hit(title: String) = SearchHit(title, address = null, city = null, lat = 0.0, lng = 0.0, poiId = title)
    private fun best(name: String, vararg titles: String) = bestMatch(name, titles.map(::hit))?.title

    @Test fun exactNameWinsOverEverything() =
        assertEquals("西湖", best("西湖", "西湖风景区", "杭州西湖", "西湖"))

    @Test fun nameEndingWithItComesNext() =
        assertEquals("杭州西湖风景区", best("西湖风景区", "西湖风景区停车场", "杭州西湖风景区"))

    @Test fun amongContainingNamesTheShortestWins() =
        assertEquals("故宫博物院", best("故宫", "故宫博物院东门售票处", "故宫博物院"))

    @Test fun whenTheModelNameIsLongerTheLongestContainedTitleWins() =
        assertEquals("灵隐寺", best("灵隐寺景区", "寺", "灵隐", "灵隐寺"))

    @Test fun singleCharacterTitlesAreNeverUsedAsContainedMatches() =
        assertNull(best("灵隐寺景区", "寺"))

    @Test fun caseAndSpacesAreIgnored() =
        assertEquals("Hello Kitty 乐园", best("hellokitty乐园", "Hello Kitty 乐园"))

    @Test fun noNameLikeItMeansNoMatch() =
        assertNull(best("雷峰塔", "西湖", "灵隐寺"))
}
