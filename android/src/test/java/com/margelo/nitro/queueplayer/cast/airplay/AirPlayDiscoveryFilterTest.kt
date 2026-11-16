package com.margelo.nitro.queueplayer.cast.airplay

import com.margelo.nitro.queueplayer.cast.Receiver
import com.margelo.nitro.queueplayer.cast.ReceiverCapabilities
import com.margelo.nitro.queueplayer.cast.StreamingModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [visibleReceivers] drops Mac-computer AirPlay receivers (classified
 * `deviceClass == "computer"`) when hiding is on, and passes every other
 * device class through untouched.
 */
class AirPlayDiscoveryFilterTest {

  private fun receiver(id: String, deviceClass: String): Receiver =
    Receiver(
      id = id,
      protocol = "airplay",
      name = id,
      iconHint = deviceClass,
      capabilities = ReceiverCapabilities(
        supportsRemoteVolume = true,
        streamingModel = StreamingModel.PUSH_PCM,
        requiresPairing = false,
      ),
      extras = mapOf("deviceClass" to deviceClass, "model" to id),
    )

  @Test
  fun `hiding drops computer-class receivers and keeps everything else`() {
    val all = listOf(
      receiver("Mac17,4", "computer"),
      receiver("AppleTV11,1", "tv"),
      receiver("AudioAccessory5,1", "speaker"),
      receiver("Shairport", "speaker"),
    )

    val visible = visibleReceivers(all, hideComputers = true)

    assertEquals(listOf("AppleTV11,1", "AudioAccessory5,1", "Shairport"), visible.map { it.id })
    assertTrue("no computer-class receiver survives", visible.none { it.extras["deviceClass"] == "computer" })
  }

  @Test
  fun `not hiding passes computer-class receivers through`() {
    val all = listOf(
      receiver("Mac17,4", "computer"),
      receiver("AppleTV11,1", "tv"),
    )

    val visible = visibleReceivers(all, hideComputers = false)

    assertEquals(all.map { it.id }, visible.map { it.id })
  }

  @Test
  fun `an all-computer list becomes empty when hiding`() {
    val all = listOf(
      receiver("Mac17,4", "computer"),
      receiver("MacBookPro18,3", "computer"),
      receiver("iMac21,1", "computer"),
    )

    assertTrue(visibleReceivers(all, hideComputers = true).isEmpty())
  }

  @Test
  fun `a receiver missing deviceClass extras is never treated as a computer`() {
    val noClass = Receiver(
      id = "unknown-receiver",
      protocol = "airplay",
      name = "unknown-receiver",
      iconHint = "unknown",
      capabilities = ReceiverCapabilities(
        supportsRemoteVolume = true,
        streamingModel = StreamingModel.PUSH_PCM,
        requiresPairing = false,
      ),
      extras = emptyMap(),
    )

    assertEquals(listOf("unknown-receiver"), visibleReceivers(listOf(noClass), hideComputers = true).map { it.id })
  }
}
