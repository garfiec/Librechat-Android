package com.garfiec.librechat.feature.chat.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/** Concurrency contract for [collectMarkdownSegments]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownSegmentStreamTest {

    private fun text(segments: List<MarkdownSegment>): String =
        (segments.single() as MarkdownSegment.TextBlock).text

    @Test
    fun publishesEachDistinctTextInOrder() = runTest {
        val source = Channel<String>(Channel.UNLIMITED)
        val published = mutableListOf<String>()
        val job = launch {
            collectMarkdownSegments(
                texts = source.receiveAsFlow(),
                alreadyParsed = "",
                parse = { listOf(MarkdownSegment.TextBlock(it)) },
                parseContext = StandardTestDispatcher(testScheduler),
            ) { published += text(it) }
        }
        runCurrent()

        source.trySend("a"); advanceUntilIdle()
        source.trySend("ab"); advanceUntilIdle()
        source.trySend("abc"); advanceUntilIdle()

        assertEquals(listOf("a", "ab", "abc"), published)
        job.cancel()
    }

    @Test
    fun conflationSkipsIntermediateTexts() = runTest {
        val source = Channel<String>(Channel.UNLIMITED)
        val parsed = mutableListOf<String>()
        val published = mutableListOf<String>()
        val job = launch {
            collectMarkdownSegments(
                texts = source.receiveAsFlow(),
                alreadyParsed = "",
                parse = { parsed += it; listOf(MarkdownSegment.TextBlock(it)) },
                parseContext = EmptyCoroutineContext,
            ) { published += text(it) }
        }
        runCurrent()

        source.trySend("a")
        source.trySend("ab")
        source.trySend("abc")
        runCurrent()

        assertEquals(listOf("a", "abc"), parsed)
        assertEquals(listOf("a", "abc"), published)
        job.cancel()
    }

    @Test
    fun skipsEmissionEqualToAlreadyParsed() = runTest {
        val source = Channel<String>(Channel.UNLIMITED)
        val parsed = mutableListOf<String>()
        val published = mutableListOf<String>()
        val job = launch {
            collectMarkdownSegments(
                texts = source.receiveAsFlow(),
                alreadyParsed = "hello",
                parse = { parsed += it; listOf(MarkdownSegment.TextBlock(it)) },
                parseContext = StandardTestDispatcher(testScheduler),
            ) { published += text(it) }
        }
        runCurrent()

        source.trySend("hello"); advanceUntilIdle()
        assertTrue(parsed.isEmpty())
        assertTrue(published.isEmpty())

        source.trySend("hello world"); advanceUntilIdle()
        assertEquals(listOf("hello world"), parsed)
        job.cancel()
    }

    @Test
    @Suppress("InjectDispatcher") // needs two genuinely distinct real dispatchers; see below
    fun cancellationDiscardsInFlightParse() = runTest {
        val parseEntered = CountDownLatch(1)
        val releaseParse = CountDownLatch(1)
        val published = CopyOnWriteArrayList<String>()
        val source = Channel<String>(Channel.UNLIMITED)

        // Distinct real dispatchers: withContext dispatches the resume, where a cancelled job
        // discards the completed value rather than publishing it. Same-dispatcher would not.
        val job = launch(Dispatchers.Default) {
            collectMarkdownSegments(
                texts = source.receiveAsFlow(),
                alreadyParsed = "",
                parse = {
                    parseEntered.countDown()
                    releaseParse.await()
                    listOf(MarkdownSegment.TextBlock(it))
                },
                parseContext = Dispatchers.IO,
            ) { published += text(it) }
        }

        source.trySend("a")
        assertTrue(parseEntered.await(5, TimeUnit.SECONDS))

        job.cancel()
        releaseParse.countDown()
        job.join()

        assertTrue(published.isEmpty())
    }

    @Test
    fun realParserShowsPlainTextUntilFenceCloses() = runTest {
        val source = Channel<String>(Channel.UNLIMITED)
        val published = mutableListOf<List<MarkdownSegment>>()
        val job = launch {
            collectMarkdownSegments(
                texts = source.receiveAsFlow(),
                alreadyParsed = "",
                parseContext = StandardTestDispatcher(testScheduler),
            ) { published += it }
        }
        runCurrent()

        source.trySend("```kotlin\nval x = 1"); advanceUntilIdle()
        source.trySend("```kotlin\nval x = 1\n```"); advanceUntilIdle()

        assertTrue(published.first().none { it is MarkdownSegment.CodeBlock })
        val code = published.last().filterIsInstance<MarkdownSegment.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        job.cancel()
    }
}
