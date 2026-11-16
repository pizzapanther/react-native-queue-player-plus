import Foundation

/// FLAC STREAMINFO header values relevant to bitrate computation.
/// Reading these + the file's byte size gives an accurate average
/// encoded bitrate without involving AVFoundation.
///
/// The only source of a **local** FLAC bitrate:
/// `estimatedDataRate` reports 0 for FLAC and Core Audio's
/// `AudioFileStream` probe does not model FLAC's variable-frame
/// container, so `NowPlayingFormatExtractor.readLocalFLACBitrate`
/// derives duration from STREAMINFO and divides the file size by it.
struct FLACStreamInfo {
  let sampleRate: Int      // Hz, 1..655350
  let channelCount: Int    // 1..8
  let bitsPerSample: Int   // 4..32
  let totalSamples: Int64  // 0 = unknown

  /// Parse a FLAC STREAMINFO block from a `fLaC`-prefixed buffer.
  /// STREAMINFO is type 0 and is required by the FLAC spec to be the
  /// FIRST metadata block. Returns nil when the buffer doesn't start
  /// with the FLAC magic or the block layout is malformed.
  ///
  /// STREAMINFO body layout (34 bytes after the 4-byte block header):
  /// - 16 bits: minBlockSize (samples)
  /// - 16 bits: maxBlockSize
  /// - 24 bits: minFrameSize (bytes)
  /// - 24 bits: maxFrameSize
  /// - 20 bits: sampleRate
  /// -  3 bits: channels - 1
  /// -  5 bits: bitsPerSample - 1
  /// - 36 bits: totalSamples
  /// - 128 bits: MD5
  static func parse(_ data: Data) -> FLACStreamInfo? {
    guard data.count >= 4 + 4 + 34 else { return nil }
    guard data[0] == 0x66, data[1] == 0x4c,
          data[2] == 0x61, data[3] == 0x43 else { return nil }
    let blockType = data[4] & 0x7f
    guard blockType == 0 else { return nil }
    let blockSize = Int(data[5]) << 16 | Int(data[6]) << 8 | Int(data[7])
    guard blockSize >= 34, data.count >= 8 + blockSize else { return nil }
    // STREAMINFO body starts at offset 8.
    // Bits 64..83 (offset 10, 11, 12 in the body — i.e. data offsets
    // 18, 19, 20): sampleRate (20 bits, big-endian).
    let b18 = UInt64(data[18])
    let b19 = UInt64(data[19])
    let b20 = UInt64(data[20])
    let sampleRate = Int((b18 << 12) | (b19 << 4) | (b20 >> 4))
    let channels = Int((data[20] >> 1) & 0x07) + 1
    let bitsPerSample = Int(((data[20] & 0x01) << 4) | (data[21] >> 4)) + 1
    // totalSamples — 36 bits starting at byte 21 bit 4.
    let t21 = Int64(data[21] & 0x0f)
    let t22 = Int64(data[22])
    let t23 = Int64(data[23])
    let t24 = Int64(data[24])
    let t25 = Int64(data[25])
    let totalSamples =
      (t21 << 32) | (t22 << 24) | (t23 << 16) | (t24 << 8) | t25
    return FLACStreamInfo(
      sampleRate: sampleRate,
      channelCount: channels,
      bitsPerSample: bitsPerSample,
      totalSamples: totalSamples
    )
  }
}
