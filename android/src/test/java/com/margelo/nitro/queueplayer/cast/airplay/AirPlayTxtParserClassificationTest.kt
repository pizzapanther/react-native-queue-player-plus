package com.margelo.nitro.queueplayer.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AirPlayTxtParser] classifies AirPlay `am=`/`model=` identifiers into the
 * `deviceClass` the discovery filter keys on. Mac models must land in
 * `"computer"` (so `hideAirPlayComputers` can drop them) while Apple TV and
 * HomePod must not.
 */
class AirPlayTxtParserClassificationTest {

  private fun classOf(model: String): String =
    AirPlayTxtParser.parse(mapOf("am" to model)).deviceClass

  @Test
  fun `Mac models classify as computer`() {
    for (model in listOf("Mac17,4", "MacBookPro18,3", "MacBookAir10,1", "Macmini9,1", "MacPro7,1", "iMac21,1")) {
      assertEquals("$model should be a computer", "computer", classOf(model))
    }
  }

  @Test
  fun `Apple TV and HomePod do not classify as computer`() {
    assertEquals("tv", classOf("AppleTV11,1"))
    assertEquals("speaker", classOf("AudioAccessory5,1"))
  }

  @Test
  fun `the model key is a fallback when am is absent`() {
    assertEquals("computer", AirPlayTxtParser.parse(mapOf("model" to "Mac17,4")).deviceClass)
  }
}
