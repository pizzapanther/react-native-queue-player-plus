package com.margelo.nitro.queueplayer.cast.airplay

/**
 * What an AirPlay session accepts for the receiver's own display while it
 * streams. [AirPlayMetadataSync] pushes into this; AirPlay 1 and AirPlay 2
 * sessions implement it over their respective native senders.
 */
interface MetadataSyncTarget {
    /** Send track metadata (title/artist/album) to the receiver. */
    fun setMetadata(title: String?, artist: String?, album: String?)

    /** Update the progress bar on the receiver. */
    fun setProgress(elapsedMs: Int, durationMs: Int)

    /**
     * Send artwork image bytes to the receiver. A receiver whose sender
     * protocol carries no content type ignores [contentType].
     */
    fun setArtwork(imageData: ByteArray, contentType: String)
}
