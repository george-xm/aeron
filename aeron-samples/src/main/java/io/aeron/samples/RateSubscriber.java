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
package io.aeron.samples;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.samples.SamplesUtil.rateReporterHandler;

/**
 * Example that displays current throughput rate while receiving data.
 */
public class RateSubscriber
{
    private static final int STREAM_ID = SampleConfiguration.STREAM_ID;
    private static final int FRAGMENT_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final boolean EMBEDDED_MEDIA_DRIVER = SampleConfiguration.EMBEDDED_MEDIA_DRIVER;
    private static final String CHANNEL = SampleConfiguration.CHANNEL;

    /**
     * Main method for launching the process.
     *
     * @param args passed to the process.
     * @throws InterruptedException if the task is interrupted
     * @throws ExecutionException   if the {@link Future} has an error.
     */
    public static void main(final String[] args) throws InterruptedException, ExecutionException
    {
        System.out.println("Subscribing to " + CHANNEL + " on stream id " + STREAM_ID);

        final AtomicBoolean running = new AtomicBoolean(true);
        try (ShutdownSignalBarrier barrier = new ShutdownSignalBarrier(() -> running.set(false));
            MediaDriver driver = EMBEDDED_MEDIA_DRIVER ?
                MediaDriver.launchEmbedded(new MediaDriver.Context().terminationHook(barrier::signalAll)) : null)
        {
            final ExecutorService executor = Executors.newFixedThreadPool(2);
            final Aeron.Context ctx = new Aeron.Context()
                .availableImageHandler(SamplesUtil::printAvailableImage)
                .unavailableImageHandler(SamplesUtil::printUnavailableImage);

            if (EMBEDDED_MEDIA_DRIVER)
            {
                ctx.aeronDirectoryName(driver.aeronDirectoryName());
            }

            final RateReporter reporter = new RateReporter(TimeUnit.SECONDS.toNanos(1), SamplesUtil::printRate);

            try (Aeron aeron = Aeron.connect(ctx);
                Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID))
            {
                executor.submit(() -> SamplesUtil.subscriberLoop(
                    rateReporterHandler(reporter), FRAGMENT_COUNT_LIMIT, running).accept(subscription));
                executor.submit(reporter);

                barrier.await();

                System.out.println("Shutting down...");

                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                {
                    System.out.println("Warning: not all tasks completed promptly");
                }
            }
        }
    }
}
