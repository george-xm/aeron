/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.test.EventLogExtension;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.SystemUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.aeron.CommonContext.UDP_MEDIA;
import static io.aeron.CommonContext.UNTETHERED_LINGER_TIMEOUT_PARAM_NAME;
import static io.aeron.CommonContext.UNTETHERED_RESTING_TIMEOUT_PARAM_NAME;
import static java.util.Arrays.asList;
import static org.agrona.concurrent.status.CountersReader.RECORD_ALLOCATED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({ EventLogExtension.class, InterruptingTestCallback.class })
class UntetheredSubscriptionTest
{
    private static List<String> channels()
    {
        return asList(
            "aeron:ipc?term-length=64k",
            "aeron:udp?endpoint=localhost:24325|term-length=64k",
            "aeron-spy:aeron:udp?endpoint=localhost:24325|term-length=64k",
            """
                aeron:ipc?term-length=64k|untethered-window-limit-timeout=50ms|\
                untethered-resting-timeout=50ms|untethered-linger-timeout=25ms""",
            """
                aeron:udp?endpoint=localhost:24325|term-length=64k|\
                untethered-window-limit-timeout=50ms|untethered-resting-timeout=50ms|\
                untethered-linger-timeout=25ms""",
            """
                aeron-spy:aeron:udp?endpoint=localhost:24325|term-length=64k|\
                untethered-window-limit-timeout=50ms|untethered-resting-timeout=50ms|\
                untethered-linger-timeout=25ms"""
        );
    }

    private static final int STREAM_ID = 1001;
    private static final int FRAGMENT_COUNT_LIMIT = 10;
    private static final int MESSAGE_LENGTH = 512 - DataHeaderFlyweight.HEADER_LENGTH;

    @RegisterExtension
    final SystemTestWatcher testWatcher = new SystemTestWatcher();

    private TestMediaDriver driver;

    private Aeron aeron;

    @AfterEach
    void after()
    {
        CloseHelper.closeAll(aeron, driver);
    }

    private void launch(final String channel)
    {
        final MediaDriver.Context context = new MediaDriver.Context()
            .aeronDirectoryName(CommonContext.generateRandomDirName())
            .errorHandler(Tests::onError)
            .spiesSimulateConnection(true)
            .dirDeleteOnStart(true)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(3))
            .statusMessageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(50))
            .threadingMode(ThreadingMode.SHARED);

        final ChannelUri channelUri = ChannelUri.parse(channel);
        if (!channelUri.containsKey(CommonContext.UNTETHERED_WINDOW_LIMIT_TIMEOUT_PARAM_NAME))
        {
            context.untetheredWindowLimitTimeoutNs(TimeUnit.MILLISECONDS.toNanos(59));
        }

        if (!channelUri.containsKey(CommonContext.UNTETHERED_LINGER_TIMEOUT_PARAM_NAME))
        {
            context.untetheredLingerTimeoutNs(TimeUnit.MILLISECONDS.toNanos(25));
        }

        if (!channelUri.containsKey(UNTETHERED_RESTING_TIMEOUT_PARAM_NAME))
        {
            context.untetheredRestingTimeoutNs(TimeUnit.MILLISECONDS.toNanos(111));
        }

        driver = TestMediaDriver.launch(context, testWatcher);
        testWatcher.dataCollector().add(driver.context().aeronDirectory());
        aeron = Aeron.connect(new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .useConductorAgentInvoker(true));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldBecomeUnavailableWhenNotKeepingUp(final String channel)
    {
        launch(channel);

        final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {};
        final AtomicBoolean unavailableCalled = new AtomicBoolean();
        final UnavailableImageHandler handler = (image) -> unavailableCalled.set(true);

        final UnsafeBuffer srcBuffer = new UnsafeBuffer(ByteBuffer.allocate(MESSAGE_LENGTH));
        final String untetheredChannel = channel + "|tether=false";
        final String publicationChannel = channel.startsWith("aeron-spy") ? channel.substring(10) : channel;
        boolean pollingUntethered = true;

        try (Subscription tetheredSub = aeron.addSubscription(channel, STREAM_ID);
            Subscription untetheredSub = aeron.addSubscription(untetheredChannel, STREAM_ID, null, handler);
            Publication publication = aeron.addPublication(publicationChannel, STREAM_ID))
        {
            while (!tetheredSub.isConnected() || !untetheredSub.isConnected())
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            while (true)
            {
                if (publication.offer(srcBuffer) < 0)
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }

                if (pollingUntethered && untetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT) > 0)
                {
                    pollingUntethered = false;
                }

                tetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);

                if (unavailableCalled.get())
                {
                    assertTrue(tetheredSub.isConnected());
                    assertFalse(untetheredSub.isConnected());

                    while (publication.offer(srcBuffer) < 0)
                    {
                        Tests.yield();
                        aeron.conductorAgentInvoker().invoke();
                    }

                    return;
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldRejoinAfterResting(final String channel)
    {
        launch(channel);

        final AtomicInteger unavailableImageCount = new AtomicInteger();
        final AtomicInteger availableImageCount = new AtomicInteger();
        final UnavailableImageHandler unavailableHandler = (image) -> unavailableImageCount.incrementAndGet();
        final AvailableImageHandler availableHandler = (image) -> availableImageCount.incrementAndGet();
        final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {};

        final UnsafeBuffer srcBuffer = new UnsafeBuffer(ByteBuffer.allocate(MESSAGE_LENGTH));
        srcBuffer.setMemory(0, MESSAGE_LENGTH, (byte)-1);
        final String untetheredChannel = channel + "|tether=false";
        final String publicationChannel = channel.startsWith("aeron-spy") ? channel.substring(10) : channel;
        boolean pollingUntethered = true;

        try (Subscription tetheredSub = aeron.addSubscription(channel, STREAM_ID);
            Subscription untetheredSub = aeron.addSubscription(
                untetheredChannel, STREAM_ID, availableHandler, unavailableHandler);
            Publication publication = aeron.addPublication(publicationChannel, STREAM_ID))
        {
            while (!tetheredSub.isConnected() || !untetheredSub.isConnected())
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            while (0 == unavailableImageCount.get())
            {
                if (publication.offer(srcBuffer, 0, ThreadLocalRandom.current().nextInt(1, MESSAGE_LENGTH)) < 0)
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }

                if (pollingUntethered && untetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT) > 0)
                {
                    pollingUntethered = false;
                }

                tetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);
            }

            while (availableImageCount.get() < 2)
            {
                publication.offer(srcBuffer, 0, ThreadLocalRandom.current().nextInt(1, MESSAGE_LENGTH));
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            final Image tetheredImage = tetheredSub.imageAtIndex(0);
            final Image untetheredImage = untetheredSub.imageAtIndex(0);
            while (untetheredImage.position() < publication.position() ||
                tetheredImage.position() < publication.position())
            {
                int fragments = 0;
                fragments += tetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);
                fragments += untetheredSub.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);
                if (0 == fragments)
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }
            }
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldStoreUntetheredTimeoutsInLogBufferMetadata()
    {
        final String channel = "aeron:udp?term-length=64k|endpoint=localhost:5555";
        launch("aeron:ipc");

        final int streamId = 1142;
        final ChannelUriStringBuilder publicationBuilder = new ChannelUriStringBuilder(channel)
            .untetheredWindowLimitTimeoutNs(TimeUnit.MILLISECONDS.toNanos(150))
            .untetheredLingerTimeoutNs(TimeUnit.MILLISECONDS.toNanos(68))
            .untetheredRestingTimeoutNs(TimeUnit.MILLISECONDS.toNanos(140));
        final ExclusivePublication publication = aeron.addExclusivePublication(publicationBuilder.build(), streamId);

        final ChannelUriStringBuilder subscriptionBuilder = new ChannelUriStringBuilder(channel)
            .untetheredWindowLimitTimeoutNs(TimeUnit.SECONDS.toNanos(200))
            .untetheredLingerTimeoutNs(TimeUnit.SECONDS.toNanos(300))
            .untetheredRestingTimeoutNs(TimeUnit.SECONDS.toNanos(444));
        final Subscription subscription = aeron.addSubscription(subscriptionBuilder.build(), streamId);
        while (!subscription.isConnected())
        {
            aeron.conductorAgentInvoker().invoke();
        }

        assertUntetheredParametersInLogBufferMetadata(
            "publications",
            publication.registrationId(),
            publicationBuilder.untetheredWindowLimitTimeoutNs(),
            publicationBuilder.untetheredLingerTimeoutNs(),
            publicationBuilder.untetheredRestingTimeoutNs());

        assertUntetheredParametersInLogBufferMetadata(
            "images",
            subscription.imageAtIndex(0).correlationId(),
            subscriptionBuilder.untetheredWindowLimitTimeoutNs(),
            subscriptionBuilder.untetheredLingerTimeoutNs(),
            subscriptionBuilder.untetheredRestingTimeoutNs());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @SuppressWarnings("try")
    @InterruptAfter(10)
    void shouldSetConnectedStatusCorrectlyWhenUntetheredSpyReconnectsAfterResting(final boolean spiesSimulateConnection)
    {
        final String channel =
            "aeron:udp?endpoint=localhost:5596|term-length=64k|ssc=" + spiesSimulateConnection;
        launch(channel);

        final AtomicLong spyUnavailableImageCount = new AtomicLong();
        final AtomicLong spyAvailableImageCount = new AtomicLong();
        try (Publication publication = aeron.addExclusivePublication(channel, STREAM_ID);
            Subscription subscription = aeron.addSubscription(channel + "|tether=true", STREAM_ID);
            Subscription spy = aeron.addSubscription(
                CommonContext.SPY_PREFIX + channel + "|tether=false",
                STREAM_ID,
                (image) -> spyAvailableImageCount.incrementAndGet(),
                (image) -> spyUnavailableImageCount.incrementAndGet()))
        {
            while (!publication.isConnected() || !subscription.isConnected() || !spy.isConnected())
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }
            assertEquals(1, spyAvailableImageCount.get());

            final UnsafeBuffer data = new UnsafeBuffer(new byte[1024]);
            ThreadLocalRandom.current().nextBytes(data.byteArray());
            final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {};
            while (0 == spyUnavailableImageCount.get())
            {
                if (publication.offer(data) > 0)
                {
                    while (0 == subscription.poll(fragmentHandler, 1))
                    {
                        Tests.yield();
                    }
                }

                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            final int subPosCounterId = subscription.imageBySessionId(publication.sessionId()).subscriberPositionId();
            subscription.close();
            subscription.close();
            awaitCounterClosed(subPosCounterId); // await subscription close

            while (2 != spyAvailableImageCount.get()) // wait for spy to re-connect
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            assertEquals(1, spyUnavailableImageCount.get());
            assertEquals(publication.position(), spy.imageAtIndex(0).position());
            assertTrue(spy.isConnected());

            if (spiesSimulateConnection)
            {
                while (!publication.isConnected())
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }
            }
            else
            {
                final long startNs = System.nanoTime();
                final long endNs = startNs + 5 * resolveTimeoutNs(
                    publication, UNTETHERED_RESTING_TIMEOUT_PARAM_NAME, driver.context().untetheredRestingTimeoutNs());
                do
                {
                    assertFalse(publication.isConnected());
                    assertFalse(publication.isClosed());
                    assertThat(publication.availableWindow(), lessThanOrEqualTo(0L));
                }
                while (System.nanoTime() < endNs);
            }
        }
    }

    @Test
    @SuppressWarnings("try")
    @InterruptAfter(10)
    void shouldSetConnectedStatusCorrectlyWhenUntetheredIpcSubscriptionReconnectsAfterResting()
    {
        final String channel =
            "aeron:ipc?endpoint=localhost:5596|term-length=64k|untethered-resting-timeout=300ms";
        launch(channel);

        final AtomicLong unavailableImageCount = new AtomicLong();
        final AtomicLong availableImageCount = new AtomicLong();
        try (Publication publication = aeron.addExclusivePublication(channel, STREAM_ID);
            Subscription subscription = aeron.addSubscription(channel, STREAM_ID);
            Subscription untetheredSubscription = aeron.addSubscription(
                channel + "|tether=false",
                STREAM_ID,
                (image) -> availableImageCount.incrementAndGet(),
                (image) -> unavailableImageCount.incrementAndGet()))
        {
            while (!publication.isConnected() || !subscription.isConnected() || !untetheredSubscription.isConnected())
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }
            assertEquals(1, availableImageCount.get());

            final UnsafeBuffer data = new UnsafeBuffer(new byte[1024]);
            ThreadLocalRandom.current().nextBytes(data.byteArray());
            final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {};
            while (0 == unavailableImageCount.get())
            {
                if (publication.offer(data) > 0)
                {
                    while (0 == subscription.poll(fragmentHandler, 1))
                    {
                        Tests.yield();
                    }
                }

                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }

            final int subPosCounterId = subscription.imageBySessionId(publication.sessionId()).subscriberPositionId();
            subscription.close();
            awaitCounterClosed(subPosCounterId); // await subscription close

            boolean publicationWasDisconnected = !publication.isConnected();
            while (2 != availableImageCount.get()) // wait for re-connect after resting
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
                if (!publication.isConnected())
                {
                    publicationWasDisconnected = true;
                }
            }

            assertTrue(publicationWasDisconnected);
            Tests.awaitConnected(publication);

            assertEquals(publication.position(), untetheredSubscription.imageAtIndex(0).position());
        }
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldNotRejoinAfterRestingIfRejoinIsFalse(final String channel)
    {
        TestMediaDriver.notSupportedOnCMediaDriver("not implemented");
        launch(channel);

        final AtomicInteger unavailableImageCount = new AtomicInteger();
        final AtomicInteger availableImageCount = new AtomicInteger();
        final UnavailableImageHandler unavailableHandler = (image) -> unavailableImageCount.incrementAndGet();
        final AvailableImageHandler availableHandler = (image) -> availableImageCount.incrementAndGet();
        final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> {};

        final UnsafeBuffer srcBuffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
        ThreadLocalRandom.current().nextBytes(srcBuffer.byteArray());
        final String untetheredChannel = channel + "|tether=false|rejoin=false";
        final String publicationChannel = channel.startsWith("aeron-spy") ? channel.substring(10) : channel;
        final boolean isUdp = UDP_MEDIA.equals(ChannelUri.parse(channel).media());
        boolean pollingUntethered = true;

        try (Subscription tetheredSub = aeron.addSubscription(isUdp ? channel + "|rejoin=false" : channel, STREAM_ID);
            Subscription untetheredSub = aeron.addSubscription(
                untetheredChannel, STREAM_ID, availableHandler, unavailableHandler);
            Publication publication = aeron.addPublication(publicationChannel, STREAM_ID))
        {
            while (!tetheredSub.isConnected() || !untetheredSub.isConnected())
            {
                Tests.yield();
                aeron.conductorAgentInvoker().invoke();
            }
            final Image tetheredImage = tetheredSub.imageBySessionId(publication.sessionId());
            final Image untetheredImage = untetheredSub.imageBySessionId(publication.sessionId());
            final int untetheredSubPosCounterId = untetheredImage.subscriberPositionId();

            while (0 == unavailableImageCount.get())
            {
                if (publication.offer(srcBuffer, 0, ThreadLocalRandom.current().nextInt(1, MESSAGE_LENGTH)) < 0)
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }

                if (pollingUntethered && untetheredImage.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT) > 0)
                {
                    pollingUntethered = false;
                }

                tetheredImage.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT);
            }

            final long startNs = System.nanoTime();
            final long endNs = startNs + 5 * resolveTimeoutNs(
                publication, UNTETHERED_LINGER_TIMEOUT_PARAM_NAME, driver.context().untetheredLingerTimeoutNs());
            do
            {
                assertEquals(1, unavailableImageCount.get());
                assertEquals(1, availableImageCount.get());
                assertFalse(untetheredSub.isConnected());
                assertTrue(tetheredSub.isConnected());

                if (publication.offer(srcBuffer, 0, ThreadLocalRandom.current().nextInt(1, MESSAGE_LENGTH)) < 0)
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }
            }
            while (System.nanoTime() < endNs);

            while (tetheredImage.position() < publication.position())
            {
                if (0 == tetheredImage.poll(fragmentHandler, FRAGMENT_COUNT_LIMIT))
                {
                    Tests.yield();
                    aeron.conductorAgentInvoker().invoke();
                }
            }

            awaitCounterClosed(untetheredSubPosCounterId);
        }
    }

    private void awaitCounterClosed(final int counterId)
    {
        while (RECORD_ALLOCATED == aeron.countersReader().getCounterState(counterId))
        {
            Tests.yield();
            aeron.conductorAgentInvoker().invoke();
        }
    }

    private static long resolveTimeoutNs(
        final Publication publication, final String paramName, final long defaultValue)
    {
        final ChannelUri channelUri = ChannelUri.parse(publication.channel());
        final String timeout = channelUri.get(paramName);
        if (null != timeout)
        {
            return SystemUtil.parseDuration(paramName, timeout);
        }
        return defaultValue;
    }

    private void assertUntetheredParametersInLogBufferMetadata(
        final String directory,
        final long registrationId,
        final long expectedWindowLimitTimeoutNs,
        final long expectedLingerTimeoutNs,
        final long expectedRestingTmeoutNs)
    {
        try (LogBuffers logBuffers = new LogBuffers(
            driver.aeronDirectoryName() + "/" + directory + "/" + registrationId + ".logbuffer"))
        {
            assertEquals(
                expectedWindowLimitTimeoutNs,
                LogBufferDescriptor.untetheredWindowLimitTimeoutNs(logBuffers.metaDataBuffer()));
            assertEquals(
                expectedLingerTimeoutNs,
                LogBufferDescriptor.untetheredLingerTimeoutNs(logBuffers.metaDataBuffer()));
            assertEquals(
                expectedRestingTmeoutNs,
                LogBufferDescriptor.untetheredRestingTimeoutNs(logBuffers.metaDataBuffer()));
        }
    }
}
