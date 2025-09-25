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
package io.aeron.driver;

import io.aeron.Aeron;
import io.aeron.CncFileDescriptor;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver.Context;
import io.aeron.driver.media.ControlTransportPoller;
import io.aeron.driver.media.DataTransportPoller;
import io.aeron.driver.media.UdpTransportPoller;
import io.aeron.driver.status.SystemCounterDescriptor;
import io.aeron.exceptions.ConfigurationException;
import org.agrona.ErrorHandler;
import org.agrona.collections.ObjectHashSet;
import org.agrona.concurrent.CountedErrorHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.aeron.driver.Configuration.CALLER_RUNS_TASK_EXECUTOR;
import static io.aeron.driver.Configuration.COUNTERS_VALUES_BUFFER_LENGTH_MAX;
import static io.aeron.driver.Configuration.COUNTERS_VALUES_BUFFER_LENGTH_MIN;
import static io.aeron.driver.Configuration.ERROR_BUFFER_LENGTH_DEFAULT;
import static io.aeron.driver.Configuration.LOSS_REPORT_BUFFER_LENGTH_DEFAULT;
import static io.aeron.driver.Configuration.NAK_MAX_BACKOFF_DEFAULT_NS;
import static io.aeron.driver.Configuration.NAK_MULTICAST_MAX_BACKOFF_PROP_NAME;
import static io.aeron.driver.Configuration.NAK_UNICAST_DELAY_MIN_VALUE_NS;
import static io.aeron.driver.Configuration.UNTETHERED_LINGER_TIMEOUT_PROP_NAME;
import static io.aeron.driver.Configuration.UNTETHERED_WINDOW_LIMIT_TIMEOUT_DEFAULT_NS;
import static io.aeron.driver.Configuration.UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME;
import static io.aeron.logbuffer.LogBufferDescriptor.TERM_MAX_LENGTH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class MediaDriverContextTest
{
    private final Context context = new Context();

    @AfterEach
    void afterEach()
    {
        context.close();
    }

    @Test
    void nakMulticastMaxBackoffNsDefaultValue()
    {
        assertEquals(NAK_MAX_BACKOFF_DEFAULT_NS, context.nakMulticastMaxBackoffNs());
    }

    @Test
    void nakMulticastMaxBackoffNsValueFromSystemProperty()
    {
        System.setProperty(NAK_MULTICAST_MAX_BACKOFF_PROP_NAME, "333");
        try
        {
            final Context context = new Context();
            assertEquals(333, context.nakMulticastMaxBackoffNs());
        }
        finally
        {
            System.clearProperty(NAK_MULTICAST_MAX_BACKOFF_PROP_NAME);
        }
    }

    @Test
    void nakMulticastMaxBackoffNsExplicitValue()
    {
        context.nakMulticastMaxBackoffNs(Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, context.nakMulticastMaxBackoffNs());
    }

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -5, 0, 1024 * 1024, 1024 * 1024 + 64 * 12 - 1 })
    void conductorBufferLengthMustBeWithinRange(final int length)
    {
        context.conductorBufferLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertTrue(exception.getMessage().contains("conductorBufferLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -5, 0, 1024 * 1024, 1024 * 1024 + 64 * 2 - 1 })
    void toClientsBufferLengthMustBeWithinRange(final int length)
    {
        context.toClientsBufferLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertTrue(exception.getMessage().contains("toClientsBufferLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -76, 0, COUNTERS_VALUES_BUFFER_LENGTH_MIN - 1, COUNTERS_VALUES_BUFFER_LENGTH_MAX + 1 })
    void counterValuesBufferLengthMustBeWithinRange(final int length)
    {
        context.counterValuesBufferLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertTrue(exception.getMessage().contains("counterValuesBufferLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -76, 0, ERROR_BUFFER_LENGTH_DEFAULT - 1 })
    void errorBufferLengthMustBeWithinRange(final int length)
    {
        context.errorBufferLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertTrue(exception.getMessage().contains("errorBufferLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -6, 0, 5, LOSS_REPORT_BUFFER_LENGTH_DEFAULT - 4096 })
    void lossReportBufferLengthMustBeWithinRange(final int length, final @TempDir Path temp) throws IOException
    {
        final Path aeronDir = temp.resolve("aeron");
        Files.createDirectories(aeronDir);

        context.aeronDirectoryName(aeronDir.toString());
        context.lossReportBufferLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertThat(exception.getMessage(), containsString("lossReportBufferLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -76, -3, TERM_MAX_LENGTH + 1 })
    void publicationTermWindowLengthMustBeWithinRange(final int length)
    {
        context.publicationTermWindowLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertThat(exception.getMessage(), containsString("publicationTermWindowLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -76, -3, TERM_MAX_LENGTH + 1 })
    void ipcPublicationTermWindowLengthMustBeWithinRange(final int length)
    {
        context.ipcPublicationTermWindowLength(length);

        final ConfigurationException exception = assertThrows(ConfigurationException.class, context::conclude);
        assertThat(exception.getMessage(), containsString("ipcPublicationTermWindowLength"));
    }

    @ParameterizedTest
    @ValueSource(ints = { -100, 42, Integer.MAX_VALUE })
    void asyncTaskExecutorThreadCount(final int threadCount)
    {
        assertEquals(1, context.asyncTaskExecutorThreads());

        context.asyncTaskExecutorThreads(threadCount);
        assertEquals(threadCount, context.asyncTaskExecutorThreads());
    }

    @ParameterizedTest
    @ValueSource(ints = { -5, 0 })
    void shouldDisableAsyncExecutionIfThreadsAreNotConfigured(final int asyncExecutorThreadCount)
    {
        context.asyncTaskExecutorThreads(asyncExecutorThreadCount);
        assertNull(context.asyncTaskExecutor());

        context.concludeNullProperties();

        final Executor asyncTaskExecutor = context.asyncTaskExecutor();
        assertNotNull(asyncTaskExecutor);
        assertSame(CALLER_RUNS_TASK_EXECUTOR, asyncTaskExecutor);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 4 })
    void shouldCreateFixedThreadPoolExecutor(final int asyncExecutorThreadCount) throws Exception
    {
        assertNull(context.asyncTaskExecutor());
        context.asyncTaskExecutorThreads(asyncExecutorThreadCount);

        context.concludeNullProperties();

        final Executor asyncTaskExecutor = context.asyncTaskExecutor();
        assertNotNull(asyncTaskExecutor);
        assertInstanceOf(ThreadPoolExecutor.class, asyncTaskExecutor);

        final ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor)asyncTaskExecutor;
        assertEquals(asyncExecutorThreadCount, threadPoolExecutor.getCorePoolSize());
        assertEquals(asyncExecutorThreadCount, threadPoolExecutor.getPoolSize());

        final CyclicBarrier barrier = new CyclicBarrier(asyncExecutorThreadCount + 1);
        final CopyOnWriteArraySet<Thread> threads = new CopyOnWriteArraySet<>();
        final Callable<Void> task = () ->
        {
            threads.add(Thread.currentThread());
            barrier.await();
            return null;
        };
        for (int i = 0; i < asyncExecutorThreadCount; i++)
        {
            threadPoolExecutor.submit(task);
        }

        barrier.await(10, TimeUnit.SECONDS);

        assertEquals(asyncExecutorThreadCount, threads.size());
        assertEquals(asyncExecutorThreadCount, threadPoolExecutor.getPoolSize());

        final ObjectHashSet<String> uniqueNames = new ObjectHashSet<>(asyncExecutorThreadCount);
        for (final Thread t : threads)
        {
            assertThat(t.getName(), CoreMatchers.startsWith("async-executor"));
            assertTrue(t.isDaemon());
            assertTrue(uniqueNames.add(t.getName()));
        }
    }

    @Test
    void shouldAllowSettingTheAsyncTaskExecutor()
    {
        final Executor asynTaskExecutor = mock(Executor.class);
        context.asyncTaskExecutor(asynTaskExecutor);
        assertSame(asynTaskExecutor, context.asyncTaskExecutor());

        context.concludeNullProperties();

        assertSame(asynTaskExecutor, context.asyncTaskExecutor());
    }

    @Test
    void shouldNotCloseAsyncTaskExecutor()
    {
        final ExecutorService asyncTaskExecutor = mock(ExecutorService.class);
        context.asyncTaskExecutor(asyncTaskExecutor);

        context.close();

        verify(asyncTaskExecutor, never()).shutdownNow();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void resolverNameIsRequiredWhenDriverNameResolutionIsUsed(final String noName)
    {
        context.resolverName(noName)
            .resolverInterface("127.0.0.1");

        final ConfigurationException exception = assertThrowsExactly(ConfigurationException.class, context::conclude);
        assertEquals("ERROR - `resolverName` is required when `resolverInterface` is set", exception.getMessage());
    }

    @Test
    void shouldAssignCountedErrorHandler(@TempDir final Path tempDir)
    {
        context.aeronDirectoryName(tempDir.toString());
        assertNull(context.countedErrorHandler());
        final CountedErrorHandler countedErrorHandler = mock(CountedErrorHandler.class);
        context.countedErrorHandler(countedErrorHandler);
        assertSame(countedErrorHandler, context.countedErrorHandler());

        context.conclude();
        assertSame(countedErrorHandler, context.countedErrorHandler());
    }

    @Test
    void shouldWrapErrorHandler(@TempDir final Path tempDir)
    {
        context.aeronDirectoryName(tempDir.toString());
        final ErrorHandler customHandler = mock(ErrorHandler.class);
        context.errorHandler(customHandler);
        assertNull(context.countedErrorHandler());

        context.conclude();

        final ErrorHandler errorHandler = context.errorHandler();
        assertNotNull(errorHandler);
        assertNotSame(customHandler, errorHandler);
        final CountedErrorHandler countedErrorHandler = context.countedErrorHandler();
        assertNotNull(countedErrorHandler);

        final RuntimeException exception = new RuntimeException("test");
        countedErrorHandler.onError(exception);

        verify(customHandler).onError(exception);
        verifyNoMoreInteractions(customHandler);
        final AtomicCounter errorCounter = context.systemCounters().get(SystemCounterDescriptor.ERRORS);
        assertEquals(1, errorCounter.get());
    }

    @Test
    void shouldCreateControlTransportPollerWithCountedErrorHandler(@TempDir final Path tempDir) throws Exception
    {
        context.aeronDirectoryName(tempDir.toString());
        context.controlTransportPoller(null);

        context.conclude();

        final ControlTransportPoller controlTransportPoller = context.controlTransportPoller();
        assertNotNull(controlTransportPoller);

        assertSame(context.countedErrorHandler(), getErrorHandler(controlTransportPoller));
    }

    @Test
    void shouldCreateDataTransportPollerWithCountedErrorHandler(@TempDir final Path tempDir) throws Exception
    {
        context.aeronDirectoryName(tempDir.toString());
        context.dataTransportPoller(null);

        context.conclude();

        final DataTransportPoller dataTransportPoller = context.dataTransportPoller();
        assertNotNull(dataTransportPoller);

        assertSame(context.countedErrorHandler(), getErrorHandler(dataTransportPoller));
    }

    @ParameterizedTest
    @ValueSource(ints = { 4096, 2 * 1024 * 1024 })
    void shouldAddFilePageSizeToTheCncFile(final int filePageSize) throws IOException
    {
        final Path dir = Paths.get(CommonContext.generateRandomDirName());
        Files.createDirectories(dir);
        context
            .aeronDirectoryName(dir.toString())
            .dirDeleteOnShutdown(true)
            .filePageSize(filePageSize);

        context.conclude();

        final UnsafeBuffer metaDataBuffer = CncFileDescriptor.createMetaDataBuffer(context.cncByteBuffer());
        assertEquals(filePageSize, CncFileDescriptor.filePageSize(metaDataBuffer));
    }

    private static ErrorHandler getErrorHandler(final UdpTransportPoller transportPoller) throws Exception
    {
        final Field field = UdpTransportPoller.class.getDeclaredField("errorHandler");
        field.setAccessible(true);
        return (ErrorHandler)field.get(transportPoller);
    }

    @Test
    void shouldTestDefaultUntetheredWindowLimitTimeout()
    {
        assertEquals(UNTETHERED_WINDOW_LIMIT_TIMEOUT_DEFAULT_NS, context.untetheredWindowLimitTimeoutNs());
    }

    @Test
    void shouldTestDefaultUntetheredLingerTimeout()
    {
        assertEquals(Aeron.NULL_VALUE, context.untetheredLingerTimeoutNs());
    }

    @Test
    void shouldHonorSystemPropertyUntetheredLingerTimeout()
    {
        System.setProperty(UNTETHERED_LINGER_TIMEOUT_PROP_NAME, "222ms");
        try
        {
            final Context ctx = new Context();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(222), ctx.untetheredLingerTimeoutNs());
        }
        finally
        {
            System.clearProperty(UNTETHERED_LINGER_TIMEOUT_PROP_NAME);
        }
    }

    @Test
    void shouldHonorSystemPropertyWindowLimitTimeout()
    {
        System.setProperty(UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME, "444ms");
        try
        {
            final Context ctx = new Context();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(444), ctx.untetheredWindowLimitTimeoutNs());
        }
        finally
        {
            System.clearProperty(UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME);
        }
    }

    @Test
    void shouldHonorSystemPropertyOverrideUntetheredLingerTimeoutAndWindowTimeout()
    {
        System.setProperty(UNTETHERED_LINGER_TIMEOUT_PROP_NAME, "222ms");
        System.setProperty(UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME, "444ms");
        try
        {
            final Context ctx = new Context();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(222), ctx.untetheredLingerTimeoutNs());
            assertEquals(TimeUnit.MILLISECONDS.toNanos(444), ctx.untetheredWindowLimitTimeoutNs());
        }
        finally
        {
            System.clearProperty(UNTETHERED_LINGER_TIMEOUT_PROP_NAME);
            System.clearProperty(UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME);
        }
    }

    @Test
    void shouldUseNullForUntetheredLingerTimeoutEvenIfWindowIsSet()
    {
        final Context ctx = new Context().untetheredWindowLimitTimeoutNs(35326745);
        assertEquals(Aeron.NULL_VALUE, ctx.untetheredLingerTimeoutNs());
    }

    @ParameterizedTest
    @ValueSource(longs = { Long.MIN_VALUE, -1, 0, NAK_UNICAST_DELAY_MIN_VALUE_NS - 1 })
    void shouldRejectInvalidNakUnicastDelay(final long nakUnicastDelayNs)
    {
        context.nakUnicastDelayNs(nakUnicastDelayNs);

        final ConfigurationException exception =
            assertThrowsExactly(ConfigurationException.class, context::conclude);
        assertEquals(
            "ERROR - nakUnicastDelayNs less than min size of " + NAK_UNICAST_DELAY_MIN_VALUE_NS + ": " +
                nakUnicastDelayNs,
            exception.getMessage());
    }

    @Test
    void shouldAcceptMinNakUnicastDelay(final @TempDir Path tempDir)
    {
        context
            .aeronDirectoryName(tempDir.toString())
            .nakUnicastDelayNs(NAK_UNICAST_DELAY_MIN_VALUE_NS);

        context.conclude();

        assertEquals(NAK_UNICAST_DELAY_MIN_VALUE_NS, context.nakUnicastDelayNs());
    }

    @ParameterizedTest
    @ValueSource(longs = { Long.MIN_VALUE, -1, 0 })
    void shouldRejectInvalidNakUnicastRetryDelayRatio(final long nakUnicastRetryDelayRatio)
    {
        context.nakUnicastRetryDelayRatio(nakUnicastRetryDelayRatio);

        final ConfigurationException exception =
            assertThrowsExactly(ConfigurationException.class, context::conclude);
        assertEquals(
            "ERROR - nakUnicastRetryDelayRatio less than min size of 1: " +
                nakUnicastRetryDelayRatio,
            exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"5000000000,1000000000000"})
    void shouldRejectInvalidNakUnicastDelayRetryCombination(
        final long nakUnicastDelayNs, final long nakUnicastRetryDelayRatio)
    {
        context
            .nakUnicastDelayNs(nakUnicastDelayNs)
            .nakUnicastRetryDelayRatio(nakUnicastRetryDelayRatio);

        final ArithmeticException exception =
            assertThrowsExactly(ArithmeticException.class, context::conclude);
        assertEquals("long overflow", exception.getMessage());
    }
}
