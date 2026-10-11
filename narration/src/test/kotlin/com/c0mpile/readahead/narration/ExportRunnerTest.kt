package com.c0mpile.readahead.narration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExportRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val log = ArrayList<String>()
    private var failures = 0
    private var resets = 0

    private fun wav(file: File) {
        val data = ByteArray(2_000)
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        b
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(22_050)
            .putInt(44_100)
            .putShort(2)
            .putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        file.writeBytes(b.array())
    }

    private val renderer =
        object : ChunkRenderer {
            override suspend fun render(
                text: String,
                file: File,
            ): Boolean {
                if (failures > 0) {
                    failures--
                    return false
                }
                log += "render:${text.take(12)}"
                wav(file)
                return true
            }

            override fun reset() {
                resets++
            }
        }

    private val encoder =
        object : AudioEncoder {
            override fun open(
                file: File,
                sampleRate: Int,
                channels: Int,
            ): ChapterAudio =
                object : ChapterAudio {
                    override val sampleRate = sampleRate
                    override val channels = channels

                    override fun writeWav(file: File) {
                        log += "write"
                    }

                    override fun writeSilence(ms: Int) = Unit

                    override fun finish(): Long {
                        file.writeText("audio")
                        log += "finish:${file.name}"
                        return 1
                    }

                    override fun close() = Unit
                }
        }

    private var joined: List<String>? = null
    private var joinedTitles: List<String>? = null
    private val joiner =
        object : BookJoiner {
            override fun join(
                chapters: List<File>,
                titles: List<String>,
                output: File,
                bookTitle: String?,
                author: String?,
            ) {
                joined = chapters.map { it.name }
                joinedTitles = titles
                output.writeText("m4b")
            }
        }

    private fun runner() = ExportRunner(renderer, encoder, joiner, File(tmp.root, "work"), retryDelayMs = 0)

    private fun request(
        vararg chapters: ExportChapter,
        opening: Boolean = true,
        keep: Boolean = false,
    ) = ExportRequest("My Book", "Jane Doe", chapters.toList(), opening, File(tmp.root, "out"), keep)

    private fun chapter(
        title: String,
        text: String,
    ) = ExportChapter(title, text, Matter.STORY)

    private val sentence = "The quick brown fox jumps over the lazy dog. "

    @Test
    fun rendersTheOpeningThenEachChapterInOrderAndJoinsThem() =
        runTest {
            val file = runner().run(request(chapter("Chapter 1", "One. ".repeat(10)), chapter("Chapter 2", "Two. ".repeat(10))))
            assertEquals("My Book - Jane Doe.m4b", file.name)
            assertEquals(listOf("00 - Opening.m4a", "01 - Chapter 1.m4a", "02 - Chapter 2.m4a"), joined)
            assertEquals(listOf("Opening", "Chapter 1", "Chapter 2"), joinedTitles)
            assertEquals(
                listOf("render:My Book. By ", "render:One. One. On", "render:Two. Two. Tw"),
                log.filter { it.startsWith("render") },
            )
        }

    @Test
    fun aLongChapterIsRenderedInPiecesInReadingOrder() =
        runTest {
            val text = (1..100).joinToString(" ") { "Sentence number $it of the story goes here." }
            runner().run(request(chapter("Chapter 1", text), opening = false))
            val renders = log.filter { it.startsWith("render") }
            assertTrue(renders.size > 2)
            assertEquals("render:Sentence num", renders.first())
            assertEquals(1, log.count { it.startsWith("finish") })
        }

    @Test
    fun anExistingChapterFileIsNotRenderedAgain() =
        runTest {
            val out = File(tmp.root, "out").apply { mkdirs() }
            File(out, "00 - Opening.m4a").writeText("done earlier")
            runner().run(request(chapter("Chapter 1", sentence), keep = true))
            assertEquals(listOf("render:The quick br"), log.filter { it.startsWith("render") })
            assertEquals("done earlier", File(out, "00 - Opening.m4a").readText())
        }

    @Test
    fun aFailedPieceIsRetriedWithTheEngineRebuilt() =
        runTest {
            failures = 2
            runner().run(request(chapter("Chapter 1", sentence), opening = false))
            assertEquals(2, resets)
            assertEquals(1, log.count { it.startsWith("render") })
        }

    @Test
    fun threeFailuresStopTheExportAndKeepFinishedChapters() =
        runTest {
            val out = File(tmp.root, "out")
            try {
                var first = true
                val flaky =
                    object : ChunkRenderer {
                        override suspend fun render(
                            text: String,
                            file: File,
                        ): Boolean {
                            if (first) {
                                first = false
                                wav(file)
                                return true
                            }
                            return false
                        }

                        override fun reset() {
                            resets++
                        }
                    }
                ExportRunner(flaky, encoder, joiner, File(tmp.root, "work"), retryDelayMs = 0)
                    .run(request(chapter("Chapter 1", sentence), keep = true))
                throw AssertionError("expected ExportException")
            } catch (e: ExportException) {
                assertTrue(e.message.orEmpty().contains("Chapter 1"))
            }
            assertTrue(File(out, "00 - Opening.m4a").exists())
            assertFalse(File(out, "01 - Chapter 1.m4a").exists())
            assertFalse(out.listFiles().orEmpty().any { it.name.endsWith(".part") })
            assertEquals(null, joined)
            assertEquals(3, resets)
        }

    @Test
    fun chapterFilesAreDeletedAfterTheJoinUnlessKept() =
        runTest {
            runner().run(request(chapter("Chapter 1", sentence), opening = false))
            assertFalse(File(tmp.root, "out/00 - Chapter 1.m4a").exists())
            log.clear()
            runner().run(request(chapter("Chapter 1", sentence), opening = false, keep = true))
            assertTrue(File(tmp.root, "out/00 - Chapter 1.m4a").exists())
        }

    @Test
    fun aFailedJoinKeepsTheChaptersAndSaysSo() =
        runTest {
            val broken =
                object : BookJoiner {
                    override fun join(
                        chapters: List<File>,
                        titles: List<String>,
                        output: File,
                        bookTitle: String?,
                        author: String?,
                    ) = throw IOException("disk full")
                }
            try {
                ExportRunner(
                    renderer,
                    encoder,
                    broken,
                    File(tmp.root, "work"),
                    retryDelayMs = 0,
                ).run(request(chapter("Chapter 1", sentence)))
                throw AssertionError("expected ExportException")
            } catch (e: ExportException) {
                assertTrue(e.message.orEmpty().contains("disk full"))
            }
            assertTrue(File(tmp.root, "out/01 - Chapter 1.m4a").exists())
        }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("a b c", ExportRunner.safeName("a/b:c"))
        assertEquals("Book", ExportRunner.safeName(" ../.. "))
        assertEquals(80, ExportRunner.safeName("x".repeat(200)).length)
    }

    @Test
    fun chaptersCanBeRenderedTogetherEachWithItsOwnEngineAndTheOrderStaysRight() =
        runTest {
            val made = ArrayList<String>()
            val parallelRunner =
                ExportRunner(
                    newRenderer = {
                        val id = "engine${made.size}"
                        made += id
                        object : ChunkRenderer {
                            override suspend fun render(
                                text: String,
                                file: File,
                            ): Boolean {
                                kotlinx.coroutines.yield()
                                log += "$id:${text.take(3)}"
                                wav(file)
                                return true
                            }

                            override fun reset() = Unit
                        }
                    },
                    encoder = encoder,
                    joiner = joiner,
                    work = File(tmp.root, "work"),
                    parallel = 3,
                    retryDelayMs = 0,
                    closeRenderers = false,
                )
            parallelRunner.run(request(chapter("One", "AAA. "), chapter("Two", "BBB. "), chapter("Three", "CCC. "), opening = false))
            assertEquals(3, made.size)
            assertEquals(
                listOf("01 - One.m4a", "02 - Two.m4a", "03 - Three.m4a")
                    .map {
                        it.replaceFirst("0", "00").takeLast(it.length)
                    }.size,
                joined?.size,
            )
            assertEquals(listOf("00 - One.m4a", "01 - Two.m4a", "02 - Three.m4a"), joined)
            assertEquals(setOf("AAA", "BBB", "CCC"), log.filter { it.startsWith("engine") }.map { it.substringAfter(':') }.toSet())
        }

    @Test
    fun oneFailingChapterStopsTheOthersAndKeepsFinishedFiles() =
        runTest {
            val failing =
                ExportRunner(
                    newRenderer = {
                        object : ChunkRenderer {
                            override suspend fun render(
                                text: String,
                                file: File,
                            ): Boolean {
                                if (text.startsWith("BBB")) return false
                                wav(file)
                                return true
                            }

                            override fun reset() = Unit
                        }
                    },
                    encoder = encoder,
                    joiner = joiner,
                    work = File(tmp.root, "work"),
                    parallel = 2,
                    retryDelayMs = 0,
                    closeRenderers = false,
                )
            try {
                failing.run(request(chapter("One", "AAA. "), chapter("Two", "BBB. "), opening = false, keep = true))
                throw AssertionError("expected ExportException")
            } catch (e: ExportException) {
                assertTrue(e.message.orEmpty().contains("Two"))
            }
            assertEquals(null, joined)
            assertFalse(File(tmp.root, "out/01 - Two.m4a").exists())
            assertFalse(File(tmp.root, "out").listFiles().orEmpty().any { it.name.endsWith(".part") })
        }
}
