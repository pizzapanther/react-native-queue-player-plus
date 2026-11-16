package com.margelo.nitro.queueplayer.cast.airplay

/**
 * Parse the TXT records emitted by AirPlay receivers into the typed
 * fields the picker UI consumes. Both `_raop._tcp.` and `_airplay._tcp.`
 * carry essentially the same set of records, with the keys we care
 * about being:
 *
 * - `am=` — model identifier (e.g. `"AppleTV5,3"`, `"AudioAccessory1,1"`,
 *   `"AirportExpress2,1"`, `"Speaker1,1"`). The leading word maps to
 *   `extras.deviceClass` and the picker uses it to pick the right icon.
 * - `model=` — alternative HomePod-style model string. Sometimes
 *   present alongside `am=`, sometimes the only model field. Treated
 *   as a fallback for `am=`.
 * - `vs=` — server version (e.g. `"360.20"`). Diagnostic only — surfaced
 *   in `extras["serverVersion"]` for power users / debugging.
 * - `features=` — hex bitfield (e.g. `"0x4A7FCA00,0x1C340"`) describing
 *   protocol capabilities. We care about:
 *   - **bit 27** — AirPlay 2 support. When set, the receiver speaks AP2
 *     and we surface `extras["supportsAirPlay2"] = "true"`.
 *   - **bit 19** — pairing required (HomePod, modern Apple TVs). When
 *     set, `Receiver.capabilities.requiresPairing = true`.
 * - `deviceid=` — MAC-address-shaped stable identifier
 *   (e.g. `"AA:BB:CC:DD:EE:FF"`). Used by `AirPlayDiscovery` to dedupe
 *   the same physical receiver across `_raop._tcp.` and `_airplay._tcp.`
 *   advertisements.
 *
 * Source for the bit definitions: shairport-sync `mdns_features` table
 * and Apple's AirPlay 2 protocol writeups.
 *
 * Returned by [parse] as a [Parsed] data class. Failures degrade
 * gracefully — an unparsable TXT field becomes a `null` or sensible
 * default rather than throwing, because a single garbled receiver should
 * not break discovery for the rest of the LAN.
 */
internal object AirPlayTxtParser {

    /**
     * Result of parsing a TXT record bag. Mirrors the fields the
     * `AirPlayDiscovery` layer needs to construct a [com.margelo.nitro.queueplayer.cast.Receiver].
     *
     * @property deviceId Stable hardware id from `deviceid=`. `null` if
     *   the receiver did not advertise it (rare). Used for cross-service
     *   dedupe.
     * @property deviceClass One of `"speaker" | "tv" | "display" |
     *   "phone" | "computer" | "unknown"`, derived from `am=` / `model=`.
     * @property requiresPairing `true` when features bit 19 is set
     *   (HomePod / modern Apple TV).
     * @property supportsAirPlay2 `true` when features bit 27 is set.
     * @property serverVersion The `vs=` value verbatim, or `null`.
     * @property modelString The `am=` or `model=` value verbatim, used
     *   for [com.margelo.nitro.queueplayer.cast.Receiver.extras] under
     *   the `"model"` key.
     */
    data class Parsed(
        val deviceId: String?,
        val deviceClass: String,
        val requiresPairing: Boolean,
        val supportsAirPlay2: Boolean,
        val serverVersion: String?,
        val modelString: String?,
    )

    /**
     * Parse a TXT record bag (key/value, all values byte arrays from
     * NsdManager).  Caller is responsible for decoding bytes to strings;
     * NsdManager hands strings on API 21+ via
     * `NsdServiceInfo.attributes` once the trailing API 33 wrinkle is
     * accounted for.
     *
     * @param attrs The TXT record map already decoded to UTF-8 strings.
     *   Keys are case-sensitive — AirPlay implementations are
     *   inconsistent so we look up under both `am` / `AM` etc.
     */
    fun parse(attrs: Map<String, String?>): Parsed {
        val deviceId = lookupCaseInsensitive(attrs, "deviceid")?.uppercase()
        val am = lookupCaseInsensitive(attrs, "am")
        val model = lookupCaseInsensitive(attrs, "model")
        val vs = lookupCaseInsensitive(attrs, "vs")
        val features = lookupCaseInsensitive(attrs, "features")
        val manufacturer = lookupCaseInsensitive(attrs, "manufacturer")

        val featureBits = parseFeatures(features)
        val supportsAirPlay2 = featureBitSet(featureBits, AIRPLAY2_BIT)
        val requiresPairing = featureBitSet(featureBits, PAIRING_REQUIRED_BIT)

        return Parsed(
            deviceId = deviceId,
            deviceClass = mapDeviceClass(am ?: model, manufacturer),
            requiresPairing = requiresPairing,
            supportsAirPlay2 = supportsAirPlay2,
            serverVersion = vs,
            modelString = am ?: model,
        )
    }

    /**
     * Map an AirPlay model string to one of the documented device-class
     * strings. The first lower-case token (`"AudioAccessory"`,
     * `"AppleTV"`, `"AirportExpress"`, `"Speaker"`, `"iMac"`, `"iPhone"`,
     * etc.) drives the classification. Anything we don't recognise
     * falls through to `"unknown"`.
     */
    private fun mapDeviceClass(modelString: String?, manufacturer: String?): String {
        if (!modelString.isNullOrBlank()) {
            val token = modelString.takeWhile { it.isLetter() }.lowercase()
            val fromToken = when (token) {
                "audioaccessory",          // HomePod, HomePod mini
                "speaker",                 // shairport-sync default
                "airportexpress",          // AirPort Express
                "raop",                    // generic RAOP receivers
                "homepod",                 // some firmware reports this
                "audio" -> "speaker"

                "appletv",
                "atv" -> "tv"

                "mac",                     // Mac17,4 etc (bare "Mac" prefix)
                "imac",
                "macbook",
                "macbookpro",
                "macbookair",
                "macmini",
                "macpro",
                "macstudio",
                "computer" -> "computer"

                "iphone" -> "phone"

                "ipad",
                "display",
                "studiodisplay",
                "promac",                  // Pro Display XDR variants
                "monitor" -> "display"

                else -> null
            }
            if (fromToken != null) return fromToken
        }

        // Third-party devices (Samsung, Bose, etc.) use product model IDs
        // that don't follow Apple naming. Fall back to manufacturer if
        // available — audio-brand manufacturers advertising AirPlay are
        // overwhelmingly speakers/soundbars.
        if (!manufacturer.isNullOrBlank()) {
            val mfr = manufacturer.lowercase()
            if (mfr in SPEAKER_MANUFACTURERS) return "speaker"
            if (mfr in TV_MANUFACTURERS) return "tv"
        }

        return "unknown"
    }

    private val SPEAKER_MANUFACTURERS = setOf(
        "samsung", "bose", "sonos", "denon", "harman", "jbl",
        "bang & olufsen", "bang&olufsen", "b&o", "marantz",
        "yamaha", "bluesound", "devialet", "naim", "libratone",
        "ikea", "belkin",
    )

    private val TV_MANUFACTURERS = setOf(
        "lg", "vizio", "roku", "hisense", "tcl",
    )

    /**
     * Parse the `features=` field. The wire format is one or two
     * comma-separated 32-bit hex words (`"0xHHHHHHHH"` or
     * `"0xHHHHHHHH,0xHHHHHHHH"`). The first word holds bits 0..31, the
     * second word holds bits 32..63. We pack the result into a single
     * `Long` because we only need bits in the low 32 (bits 19 and 27).
     *
     * Returns `0L` if the field is missing or malformed — equivalent
     * to "no features advertised", which is a safe default.
     */
    private fun parseFeatures(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val words = raw.split(",").map { it.trim() }
        if (words.isEmpty()) return 0L
        val low = parseHexWord(words[0])
        val high = if (words.size >= 2) parseHexWord(words[1]) else 0L
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    private fun parseHexWord(word: String): Long {
        val cleaned = word.removePrefix("0x").removePrefix("0X")
        return runCatching { java.lang.Long.parseLong(cleaned, 16) }.getOrDefault(0L)
    }

    private fun featureBitSet(bits: Long, bit: Int): Boolean =
        (bits and (1L shl bit)) != 0L

    private fun lookupCaseInsensitive(map: Map<String, String?>, key: String): String? {
        // NsdManager attribute keys are usually lower-cased, but
        // shairport-sync and a handful of third-party receivers use
        // mixed case. Walk the map once instead of allocating a new
        // case-folded copy.
        map.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { return it.value }
        return null
    }

    private const val PAIRING_REQUIRED_BIT = 19
    private const val AIRPLAY2_BIT = 27
}
