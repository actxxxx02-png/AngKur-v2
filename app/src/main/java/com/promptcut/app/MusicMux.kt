package com.promptcut.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adds background music to a rendered video in three simple, reliable steps:
 * decode (clip sound + song) → mix in Kotlin ([Mixer]) → encode AAC and put it next to the untouched video track.
 * The picture is copied as-is (no second video encode), so this is fast and can't break the video.
 */
object MusicMux {
    const val SR = 44100

    /** Where the music comes from: a song or the built-in music written to a WAV file. */
    sealed class Source {
        class File(val uri: Uri, val startSec: Double) : Source()
    }

    fun addMusic(context: Context, video: java.io.File, music: Source, musicGain: Float, out: java.io.File) {
        music as Source.File
        val durationSec = videoDuration(video)
        val totalFrames = (durationSec * SR).toInt()
        val tmp = java.io.File(context.cacheDir, "mix").apply { mkdirs() }
        val clipRaw = java.io.File(tmp, "clip.pcm")
        val musicRaw = java.io.File(tmp, "music.pcm")
        try {
            val clipFrames = decodeToFile(context, Uri.fromFile(video), 0.0, durationSec + 0.5, clipRaw)
            val musicFrames = decodeToFile(context, music.uri, music.startSec, durationSec + 1.0, musicRaw)
            if (musicFrames <= 0) throw RenderException("Couldn't read the song file.")
            val aac = encodeAac(totalFrames) { mixer ->
                PcmReader(if (clipFrames > 0) clipRaw else null, musicRaw, mixer)
            }.let { it(MixerStream(totalFrames, SR, musicGain)) }
            remux(video, aac, out)
        } finally {
            clipRaw.delete(); musicRaw.delete()
        }
    }

    /** Reads clip sound and (looping) music from raw files and mixes them chunk by chunk. */
    private class PcmReader(clip: java.io.File?, private val music: java.io.File, private val mixer: MixerStream) {
        private val clipIn = clip?.let { java.io.DataInputStream(it.inputStream().buffered(1 shl 16)) }
        private var musicIn = open()
        private var clipDone = clip == null
        private val cBuf = FloatArray(8192)
        private val mBuf = FloatArray(8192)
        private val bytes = ByteArray(8192 * 2)

        private fun open() = music.inputStream().buffered(1 shl 16)

        private fun readInto(input: java.io.InputStream, dst: FloatArray, frames: Int): Int {
            val want = frames * 4
            var got = 0
            while (got < want) { val n = input.read(bytes, got, want - got); if (n < 0) break; got += n }
            val bb = ByteBuffer.wrap(bytes, 0, got).order(ByteOrder.LITTLE_ENDIAN)
            val samples = got / 2
            for (i in 0 until samples) dst[i] = bb.short / 32768f
            return samples / 2
        }

        /** Produces [frames] mixed frames into [out]. */
        fun next(frames: Int, out: ShortArray) {
            var clipFrames = 0
            if (!clipDone) { clipFrames = readInto(clipIn!!, cBuf, frames); if (clipFrames < frames) clipDone = true }
            var got = readInto(musicIn, mBuf, frames)
            while (got < frames) { // loop the song
                musicIn.close(); musicIn = open()
                val extra = FloatArray((frames - got) * 2)
                val n = readInto(musicIn, extra, frames - got)
                if (n == 0) break
                System.arraycopy(extra, 0, mBuf, got * 2, n * 2); got += n
            }
            mixer.process(if (clipFrames > 0) cBuf else null, clipFrames, mBuf, got, frames, out)
        }

        fun close() { runCatching { clipIn?.close() }; runCatching { musicIn.close() } }
    }

    private fun videoDuration(video: java.io.File): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(video.absolutePath)
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(MediaFormat.KEY_DURATION)) {
                    dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            return dur / 1_000_000.0
        } finally {
            ex.release()
        }
    }

    /**
     * Decodes up to [maxSec] of audio starting at [startSec] into a raw file of 16-bit stereo at [SR].
     * Returns the number of frames written, or 0 when there is no audio.
     */
    fun decodeToFile(context: Context, uri: Uri, startSec: Double, maxSec: Double, out: java.io.File): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val os = out.outputStream().buffered(1 shl 16)
        var written = 0L
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val startUs = (startSec * 1_000_000).toLong()
            val endUs = startUs + (maxSec * 1_000_000).toLong()
            val maxFrames = (maxSec * SR).toLong()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var resampler: Resampler? = null
            var tmp = FloatArray(16384)
            val outBytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            loop@ while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = null
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bps = if (isFloat) 4 else 2
                    val count = info.size / bps
                    val skip = if (info.presentationTimeUs < startUs) {
                        (((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt() * channels).coerceAtMost(count)
                    } else 0
                    if (count > tmp.size) tmp = FloatArray(count)
                    var n = 0
                    for (k in 0 until count) {
                        val v = if (isFloat) buf.float else buf.short / 32768f
                        if (k >= skip) tmp[n++] = v
                    }
                    codec.releaseOutputBuffer(oi, false)
                    val rs = resampler ?: Resampler(channels.coerceAtLeast(1), rate, SR).also { resampler = it }
                    rs.feed(tmp, n) { l, r ->
                        if (written < maxFrames) {
                            if (outBytes.remaining() < 4) { os.write(outBytes.array(), 0, outBytes.position()); outBytes.clear() }
                            outBytes.putShort((l.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            outBytes.putShort((r.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            written++
                        }
                    }
                    if (outBytes.position() > 0) { os.write(outBytes.array(), 0, outBytes.position()); outBytes.clear() }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || written >= maxFrames) break@loop
                }
            }
            return written
        } catch (e: Exception) {
            return 0
        } finally {
            runCatching { os.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private class Encoded(val format: MediaFormat, val chunks: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /** Encodes [totalFrames] frames produced by a [PcmReader] (built by [makeReader]) into AAC. */
    private fun encodeAac(totalFrames: Int, makeReader: (MixerStream) -> PcmReader): (MixerStream) -> Encoded = { mixer ->
        val reader = makeReader(mixer)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val chunks = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var frame = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(1024 * 2)
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val pts = frame * 1_000_000L / SR
                        if (frame >= totalFrames) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            val count = minOf(1024, totalFrames - frame, buf.capacity() / 4)
                            reader.next(count, pcm)
                            for (k in 0 until count * 2) buf.putShort(pcm[k])
                            codec.queueInputBuffer(ii, 0, count * 4, pts, 0)
                            frame += count
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (oi >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buf.get(it) }
                        chunks += bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            reader.close()
            runCatching { codec.stop() }
            codec.release()
        }
        Encoded(outFormat ?: throw RenderException("Audio encoder produced no format."), chunks)
    }

    /** Copies the video track untouched and adds the new audio track, interleaved by time. */
    private fun remux(video: java.io.File, audio: Encoded, out: java.io.File) {
        val ex = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(video.absolutePath)
            val vTrack = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(vTrack)
            val vFormat = ex.getTrackFormat(vTrack)
            // Keep the picture upright: Transformer stores portrait video as landscape + a rotation flag.
            val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION)
                else android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(video.absolutePath)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                }
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val dstV = muxer.addTrack(vFormat)
            val dstA = muxer.addTrack(audio.format)
            muxer.start()
            val buf = ByteBuffer.allocate(maxOf(4 * 1024 * 1024, if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
            val vInfo = MediaCodec.BufferInfo()
            var ai = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                while (ai < audio.chunks.size && audio.chunks[ai].second.presentationTimeUs <= t) {
                    val (bytes, info) = audio.chunks[ai++]
                    muxer.writeSampleData(dstA, ByteBuffer.wrap(bytes), info)
                }
                vInfo.set(0, size, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(dstV, buf, vInfo)
                ex.advance()
            }
            while (ai < audio.chunks.size) {
                val (bytes, info) = audio.chunks[ai++]
                muxer.writeSampleData(dstA, ByteBuffer.wrap(bytes), info)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }
}
