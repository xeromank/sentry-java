package io.sentry.android.core

import io.sentry.IHub
import io.sentry.ILogger
import io.sentry.SendCachedEnvelopeFireAndForgetIntegration.SendFireAndForget
import io.sentry.SendCachedEnvelopeFireAndForgetIntegration.SendFireAndForgetFactory
import io.sentry.SentryLevel.DEBUG
import io.sentry.util.LazyEvaluator
import org.awaitility.kotlin.await
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

class SendCachedEnvelopeIntegrationTest {
    private class Fixture {
        val hub: IHub = mock()
        val options = SentryAndroidOptions()
        val logger = mock<ILogger>()
        val factory = mock<SendFireAndForgetFactory>()
        val flag = AtomicBoolean(true)
        val sender = mock<SendFireAndForget>()

        fun getSut(
            cacheDirPath: String? = "abc",
            hasStartupCrashMarker: Boolean = false,
            hasSender: Boolean = true,
            delaySend: Long = 0L,
            taskFails: Boolean = false
        ): SendCachedEnvelopeIntegration {
            options.cacheDirPath = cacheDirPath
            options.setLogger(logger)
            options.isDebug = true

            whenever(sender.send()).then {
                Thread.sleep(delaySend)
                if (taskFails) {
                    throw ExecutionException(RuntimeException("Something went wrong"))
                }
                flag.set(false)
            }
            whenever(factory.hasValidPath(any(), any())).thenCallRealMethod()
            whenever(factory.create(any(), any())).thenReturn(
                if (hasSender) {
                    sender
                } else {
                    null
                }
            )

            return SendCachedEnvelopeIntegration(factory, LazyEvaluator { hasStartupCrashMarker })
        }
    }

    private val fixture = Fixture()

    @Test
    fun `when cacheDirPath is not set, does nothing`() {
        val sut = fixture.getSut(cacheDirPath = null)

        sut.register(fixture.hub, fixture.options)

        verify(fixture.factory, never()).create(any(), any())
    }

    @Test
    fun `when factory returns null, does nothing`() {
        val sut = fixture.getSut(hasSender = false)

        sut.register(fixture.hub, fixture.options)

        verify(fixture.factory).create(any(), any())
        verify(fixture.sender, never()).send()
    }

    @Test
    fun `when has factory and cacheDirPath set, submits task into queue`() {
        val sut = fixture.getSut()

        sut.register(fixture.hub, fixture.options)

        await.untilFalse(fixture.flag)
        verify(fixture.sender).send()
    }

    @Test
    fun `when has startup crash marker, awaits the task on the calling thread`() {
        val sut = fixture.getSut(hasStartupCrashMarker = true)

        sut.register(fixture.hub, fixture.options)

        // we do not need to await here, because it's executed synchronously
        verify(fixture.sender).send()
    }

    @Test
    fun `when synchronous send times out, continues the task on a background thread`() {
        val sut = fixture.getSut(hasStartupCrashMarker = true, delaySend = 1000)
        fixture.options.startupCrashFlushTimeoutMillis = 100

        sut.register(fixture.hub, fixture.options)

        // first wait until synchronous send times out and check that the logger was hit in the catch block
        await.atLeast(500, MILLISECONDS)
        verify(fixture.logger).log(
            eq(DEBUG),
            eq("Synchronous send timed out, continuing in the background.")
        )

        // then wait until the async send finishes in background
        await.untilFalse(fixture.flag)
        verify(fixture.sender).send()
    }
}
