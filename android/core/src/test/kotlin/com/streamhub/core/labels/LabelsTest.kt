package com.streamhub.core.labels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 1:1 port of the tests in `tizen/core/src/labels.rs`. */
class LabelsTest {

    private fun parts(main: String, rest: String) = SeasonParts(main, rest)

    @Test
    fun `bare digits get the episode word and keep their padding`() {
        assertEquals("第1集", Labels.episodeCaption("1"))
        assertEquals("第01集", Labels.episodeCaption("01"))
        assertEquals("第12集", Labels.episodeCaption(" 12 "))
    }

    @Test
    fun `labels with words are shown as given`() {
        for (label in listOf("第04集", "第37集已完結", "EP1", "預告片", "HD中字", "全", "第5期", "第3話")) {
            assertEquals(label, label, Labels.episodeCaption(label))
        }
        assertEquals("", Labels.episodeCaption(""))
    }

    @Test
    fun `season numbers are pulled out of site labels`() {
        assertEquals(parts("第 2 季", "201510集全"), Labels.seasonParts("Season 2 201510集全"))
        assertEquals(parts("第 1 季", "2013更新第11集"), Labels.seasonParts("Season 1 2013更新第11集"))
        assertEquals(parts("第 1 季", "2022已完結 共8集"), Labels.seasonParts("Season 1 2022已完結 共8集"))
        assertEquals(parts("第 3 季", ""), Labels.seasonParts("season 3"))
        assertEquals(parts("第 3 季", ""), Labels.seasonParts("第3季"))
        assertEquals(parts("第 2 季", "黑暗榮耀"), Labels.seasonParts("黑暗榮耀 第二季"))
        assertEquals(parts("第 12 季", "完結"), Labels.seasonParts("第十二季 完結"))
        assertEquals(parts("第 2 季", ""), Labels.seasonParts("S02"))
        assertEquals(parts("第 2 季", "2019"), Labels.seasonParts("S2 2019"))
    }

    @Test
    fun `unrecognised season labels are truncated`() {
        assertEquals(parts("特別篇", ""), Labels.seasonParts("特別篇"))
        val (main, rest) = Labels.seasonParts("這是一個非常非常長的季標籤沒有任何數字可以抓")
        assertEquals(15, main.codePointCount(0, main.length))
        assertTrue(main.endsWith('…'))
        assertEquals("", rest)
        // "Super" starts with S but is not a season token.
        assertEquals(parts("Super", ""), Labels.seasonParts("Super"))
    }
}
