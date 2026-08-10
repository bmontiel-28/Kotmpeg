package com.braymon.kotmpeg

import com.braymon.kotmpeg.model.MediaPacket
import com.braymon.kotmpeg.model.TrackInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end validation against real FFmpeg: encodes reference files with libx264/libx265/
 * AAC/Opus (including B-frames), remuxes them with this library in both directions, and
 * verifies the results with ffprobe plus a full decode pass. Skipped when ffmpeg is not
 * installed.
 */
class FfmpegIntegrationTest {

    companion object {
        private var available = false

        @JvmStatic
        @BeforeAll
        fun checkFfmpeg() {
            available = try {
                run("ffmpeg", "-version").second == 0
            } catch (_: Exception) {
                false
            }
        }

        private fun run(vararg cmd: String): Pair<String, Int> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            return out to p.exitValue()
        }
    }

    @TempDir
    lateinit var dir: File

    private fun makeReference(name: String, vararg extraArgs: String): File {
        val f = File(dir, name)
        val (out, code) = run(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=3",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
            "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=3",
            *extraArgs,
            f.absolutePath,
        )
        assertEquals(0, code, "ffmpeg reference generation failed: $out")
        return f
    }

    private fun ffprobeStreams(f: File): List<String> {
        val (out, code) = run(
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_name",
            "-of", "csv=p=0", f.absolutePath,
        )
        assertEquals(0, code, "ffprobe failed: $out")
        return out.trim().lines().filter { it.isNotBlank() }
    }

    private fun assertDecodesCleanly(f: File) {
        val (out, code) = run("ffmpeg", "-v", "error", "-i", f.absolutePath, "-f", "null", "-")
        assertEquals(0, code, "decode failed for ${f.name}: $out")
        assertTrue(out.isBlank(), "decoder reported errors for ${f.name}: $out")
    }

    private fun countFrames(f: File, streamSelector: String): Int {
        val (out, code) = run(
            "ffprobe", "-v", "error", "-select_streams", streamSelector,
            "-count_packets", "-show_entries", "stream=nb_read_packets",
            "-of", "csv=p=0", f.absolutePath,
        )
        assertEquals(0, code, "ffprobe count failed: $out")
        return out.trim().lines().first { it.isNotBlank() }.toInt()
    }

    private fun probeDurationSeconds(f: File): Double {
        val (out, code) = run(
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "csv=p=0", f.absolutePath,
        )
        assertEquals(0, code)
        return out.trim().lines().first { it.isNotBlank() }.toDouble()
    }

    @Test
    fun `mp4 with h264 b-frames and two aac tracks remuxes to valid mkv`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "src.mp4",
            "-map", "0:v", "-map", "1:a", "-map", "2:a",
            "-c:v", "libx264", "-profile:v", "high", "-bf", "2", "-g", "30",
            "-c:a", "aac", "-b:a", "128k",
        )

        val srcDemux = MkvKotlin.openDemuxer(src)
        assertEquals(3, srcDemux.tracks.size, "expected video + 2 audio tracks")
        assertEquals(2, srcDemux.tracks.filterIsInstance<TrackInfo.Audio>().size)
        srcDemux.close()

        val out = File(dir, "out.mkv")
        val packets = MkvKotlin.remux(src, out)
        assertTrue(packets > 100, "too few packets: $packets")

        assertEquals(listOf("h264", "aac", "aac"), ffprobeStreams(out))
        assertDecodesCleanly(out)
        assertEquals(countFrames(src, "v:0"), countFrames(out, "v:0"), "video frame count preserved")
        assertEquals(countFrames(src, "a:0"), countFrames(out, "a:0"), "audio packet count preserved")
        val duration = probeDurationSeconds(out)
        assertTrue(Math.abs(duration - 3.0) < 0.2, "duration $duration should be ~3s")
    }

    @Test
    fun `mkv with h265 and opus remuxes to valid mp4`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "src.mkv",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx265", "-x265-params", "log-level=error", "-tag:v", "hvc1",
            "-c:a", "libopus", "-b:a", "96k",
        )

        val out = File(dir, "out.mp4")
        val packets = MkvKotlin.remux(src, out)
        assertTrue(packets > 100, "too few packets: $packets")

        assertEquals(listOf("hevc", "opus"), ffprobeStreams(out))
        assertDecodesCleanly(out)
        assertEquals(countFrames(src, "v:0"), countFrames(out, "v:0"), "video frame count preserved")
        val duration = probeDurationSeconds(out)
        assertTrue(Math.abs(duration - 3.0) < 0.2, "duration $duration should be ~3s")
    }

    @Test
    fun `mkv to mp4 to mkv keeps sync metadata`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "sync.mkv",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-bf", "2", "-g", "30",
            "-c:a", "aac", "-b:a", "128k",
        )
        val mp4 = File(dir, "sync.mp4")
        MkvKotlin.remux(src, mp4)
        val mkv = File(dir, "sync2.mkv")
        MkvKotlin.remux(mp4, mkv)

        assertDecodesCleanly(mp4)
        assertDecodesCleanly(mkv)
        assertEquals(countFrames(src, "v:0"), countFrames(mkv, "v:0"))

        fun firstVideoPts(f: File): Double {
            val (out, _) = run(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "packet=pts_time", "-read_intervals", "%+0.5",
                "-of", "csv=p=0", f.absolutePath,
            )
            return out.trim().lines().first { it.isNotBlank() }.split(",").first().toDouble()
        }
        assertTrue(Math.abs(firstVideoPts(mkv) - firstVideoPts(src)) < 0.01, "start pts preserved")
    }

    @Test
    fun `faststart mp4 decodes cleanly with real streams`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "fs-src.mkv",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-bf", "2", "-g", "30",
            "-c:a", "aac", "-b:a", "128k",
        )
        val out = File(dir, "fs-out.mp4")
        MkvKotlin.remux(src, out, mp4FastStart = true)
        assertEquals(listOf("h264", "aac"), ffprobeStreams(out))
        assertDecodesCleanly(out)
        assertEquals(countFrames(src, "v:0"), countFrames(out, "v:0"))
    }

    @Test
    fun `our fragmented mp4 decodes cleanly with real streams`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "frag-src.mkv",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-bf", "2", "-g", "30",
            "-c:a", "aac", "-b:a", "128k",
        )
        val out = File(dir, "frag-out.mp4")
        MkvKotlin.remux(src, out, mp4Fragmented = true)
        assertEquals(listOf("h264", "aac"), ffprobeStreams(out))
        assertDecodesCleanly(out)
        assertEquals(countFrames(src, "v:0"), countFrames(out, "v:0"), "video frames preserved")
        assertEquals(countFrames(src, "a:0"), countFrames(out, "a:0"), "audio packets preserved")
    }

    @Test
    fun `ffmpeg fragmented mp4 is readable and remuxes to valid mkv`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "fffrag.mp4",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-bf", "2", "-g", "30",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "frag_keyframe+empty_moov",
        )
        val srcDemux = MkvKotlin.openDemuxer(src)
        assertEquals(2, srcDemux.tracks.size, "fMP4 tracks detected")
        srcDemux.close()

        val out = File(dir, "fffrag-out.mkv")
        val packets = MkvKotlin.remux(src, out)
        assertTrue(packets > 100, "too few packets: $packets")
        assertEquals(listOf("h264", "aac"), ffprobeStreams(out))
        assertDecodesCleanly(out)
        assertEquals(countFrames(src, "v:0"), countFrames(out, "v:0"), "video frames preserved")
    }

    @Test
    fun `color metadata survives remux and is visible to ffprobe`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "color.mkv",
            "-map", "0:v",
            "-vf", "setparams=color_primaries=bt2020:color_trc=smpte2084:" +
                "colorspace=bt2020nc:range=tv",
            "-c:v", "libx264",
            "-color_primaries", "bt2020", "-color_trc", "smpte2084",
            "-colorspace", "bt2020nc", "-color_range", "tv",
        )
        val demux = MkvKotlin.openDemuxer(src)
        val video = demux.tracks.single() as TrackInfo.Video
        val color = video.color
        demux.close()
        kotlin.test.assertNotNull(color, "color read from ffmpeg mkv")
        assertEquals(9, color.primaries, "bt2020 primaries")
        assertEquals(16, color.transfer, "PQ transfer")
        assertEquals(9, color.matrix, "bt2020nc matrix")

        val out = File(dir, "color-out.mp4")
        MkvKotlin.remux(src, out)
        val (info, code) = run(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=color_primaries,color_transfer,color_space",
            "-of", "csv=p=0", out.absolutePath,
        )
        assertEquals(0, code)
        assertEquals("bt2020nc,smpte2084,bt2020", info.trim())
        assertDecodesCleanly(out)
    }

    @Test
    fun `rotation metadata is visible to ffprobe`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference("rot-src.mkv", "-map", "0:v", "-c:v", "libx264")
        val out = File(dir, "rot-out.mp4")
        val demux = MkvKotlin.openDemuxer(src)
        val srcTrack = demux.tracks.single() as TrackInfo.Video
        val muxer = MkvKotlin.createMuxer(out)
        val id = muxer.addTrack(srcTrack.copy(rotationDegrees = 90))
        muxer.start()
        while (true) {
            val p = demux.readPacket() ?: break
            muxer.writePacket(MediaPacket(id, p.data, p.ptsUs, p.dtsUs, p.isKeyFrame, p.durationUs))
        }
        muxer.stop()
        demux.close()

        val (info, code) = run(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "side_data=rotation",
            "-of", "csv=p=0", out.absolutePath,
        )
        assertEquals(0, code)
        val rotation = info.trim().lines().firstOrNull { it.isNotBlank() }?.trim(',')
        assertTrue(rotation == "-90" || rotation == "90", "ffprobe rotation was '$rotation'")
        assertDecodesCleanly(out)
    }

    @Test
    fun `concat of real segments doubles duration and decodes cleanly`() {
        assumeTrue(available, "ffmpeg not installed")
        fun segment(name: String) = makeReference(
            name,
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-g", "30", "-force_key_frames", "expr:eq(n,0)",
            "-c:a", "aac", "-b:a", "128k",
        )
        val a = segment("part1.mp4")
        val b = segment("part2.mp4")
        val out = File(dir, "joined.mkv")
        val packets = MkvKotlin.concat(listOf(a, b), out)
        assertTrue(packets > 200, "few packets: $packets")
        assertDecodesCleanly(out)
        assertEquals(
            countFrames(a, "v:0") + countFrames(b, "v:0"),
            countFrames(out, "v:0"),
            "video frames are the sum of both segments",
        )
        val duration = probeDurationSeconds(out)
        assertTrue(Math.abs(duration - 6.0) < 0.3, "joined duration $duration should be ~6s")
    }

    @Test
    fun `our mp4 output seeks and probes correctly`() {
        assumeTrue(available, "ffmpeg not installed")
        val src = makeReference(
            "probe.mp4",
            "-map", "0:v", "-map", "1:a",
            "-c:v", "libx264", "-bf", "2", "-g", "15",
            "-c:a", "aac",
        )
        val out = File(dir, "probe-out.mp4")
        MkvKotlin.remux(src, out)

        val (info, code) = run(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate",
            "-of", "csv=p=0", out.absolutePath,
        )
        assertEquals(0, code)
        val fields = info.trim().split(",")
        assertEquals("320", fields[0])
        assertEquals("240", fields[1])
        assertDecodesCleanly(out)
    }
}
