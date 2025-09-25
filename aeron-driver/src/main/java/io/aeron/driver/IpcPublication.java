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
import io.aeron.CommonContext;
import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.LogBufferUnblocker;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;

import static io.aeron.ErrorCode.IMAGE_REJECTED;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * Encapsulation of a stream used directly between publishers and subscribers for IPC over shared memory.
 */
public final class IpcPublication implements DriverManagedResource, Subscribable
{
    @SuppressWarnings("JavadocVariable")
    enum State
    {
        ACTIVE, DRAINING, LINGER, DONE
    }

    private static final ReadablePosition[] EMPTY_POSITIONS = new ReadablePosition[0];
    private static final InetSocketAddress IPC_SRC_ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final long registrationId;
    private final long tag;
    private final long unblockTimeoutNs;
    private final long untetheredWindowLimitTimeoutNs;
    private final long untetheredLingerTimeoutNs;
    private final long untetheredRestingTimeoutNs;
    private final long imageLivenessTimeoutNs;
    private final long responseCorrelationId;
    private final String channel;
    private final int sessionId;
    private final int streamId;
    private final int startingTermId;
    private final int startingTermOffset;
    private final int positionBitsToShift;
    private final int termBufferLength;
    private final int mtuLength;
    private final int termWindowLength;
    private final int initialTermId;
    private final int tripGain;
    private long tripLimit;
    private long consumerPosition;
    private long lastConsumerPosition;
    private long timeOfLastConsumerPositionUpdateNs;
    private long cleanPosition;
    private int refCount = 0;
    private boolean reachedEndOfLife = false;
    private boolean inCoolDown = false;
    private long coolDownExpireTimeNs = 0;
    private final boolean isExclusive;
    private State state = State.ACTIVE;
    private final UnsafeBuffer[] termBuffers;
    private final Position publisherPos;
    private final Position publisherLimit;
    private final UnsafeBuffer metaDataBuffer;
    private ReadablePosition[] subscriberPositions = EMPTY_POSITIONS;
    private final ArrayList<UntetheredSubscription> untetheredSubscriptions = new ArrayList<>();
    private final RawLog rawLog;
    private final AtomicCounter unblockedPublications;
    private final AtomicCounter publicationsRevoked;
    private final ErrorHandler errorHandler;

    IpcPublication(
        final long registrationId,
        final String channel,
        final MediaDriver.Context ctx,
        final long tag,
        final int sessionId,
        final int streamId,
        final Position publisherPos,
        final Position publisherLimit,
        final RawLog rawLog,
        final boolean isExclusive,
        final PublicationParams params)
    {
        this.registrationId = registrationId;
        this.channel = channel;
        this.tag = tag;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.isExclusive = isExclusive;
        this.termBuffers = rawLog.termBuffers();
        this.initialTermId = LogBufferDescriptor.initialTermId(rawLog.metaData());
        this.startingTermId = params.termId;
        this.startingTermOffset = params.termOffset;
        this.errorHandler = ctx.errorHandler();

        final int termLength = params.termLength;
        this.termBufferLength = termLength;
        this.mtuLength = params.mtuLength;
        this.positionBitsToShift = LogBufferDescriptor.positionBitsToShift(termLength);
        this.termWindowLength = params.publicationWindowLength;
        this.tripGain = termWindowLength >> 3;
        this.publisherPos = publisherPos;
        this.publisherLimit = publisherLimit;
        this.rawLog = rawLog;
        this.unblockTimeoutNs = ctx.publicationUnblockTimeoutNs();
        untetheredWindowLimitTimeoutNs = params.untetheredWindowLimitTimeoutNs;
        untetheredLingerTimeoutNs = params.untetheredLingerTimeoutNs;
        untetheredRestingTimeoutNs = params.untetheredRestingTimeoutNs;
        this.imageLivenessTimeoutNs = ctx.imageLivenessTimeoutNs();
        this.responseCorrelationId = params.responseCorrelationId;

        final SystemCounters systemCounters = ctx.systemCounters();
        this.unblockedPublications = systemCounters.get(UNBLOCKED_PUBLICATIONS);
        this.publicationsRevoked = systemCounters.get(PUBLICATIONS_REVOKED);

        this.metaDataBuffer = rawLog.metaData();

        consumerPosition = producerPosition();
        lastConsumerPosition = consumerPosition;
        cleanPosition = consumerPosition;
        timeOfLastConsumerPositionUpdateNs = ctx.cachedNanoClock().nanoTime();
    }

    /**
     * Channel URI string for this publication.
     *
     * @return channel URI string for this publication.
     */
    public String channel()
    {
        return channel;
    }

    /**
     * Session id allocated to this stream.
     *
     * @return session id allocated to this stream.
     */
    public int sessionId()
    {
        return sessionId;
    }

    /**
     * Stream id within the channel.
     *
     * @return stream id within the channel.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * {@inheritDoc}
     */
    public long subscribableRegistrationId()
    {
        return registrationId;
    }

    long registrationId()
    {
        return registrationId;
    }

    long tag()
    {
        return tag;
    }

    boolean isExclusive()
    {
        return isExclusive;
    }

    int initialTermId()
    {
        return initialTermId;
    }

    int startingTermId()
    {
        return startingTermId;
    }

    int startingTermOffset()
    {
        return startingTermOffset;
    }

    RawLog rawLog()
    {
        return rawLog;
    }

    int publisherLimitId()
    {
        return publisherLimit.id();
    }

    int termBufferLength()
    {
        return termBufferLength;
    }

    int mtuLength()
    {
        return mtuLength;
    }

    /**
     * {@inheritDoc}
     */
    public boolean free()
    {
        return rawLog.free();
    }

    /**
     * {@inheritDoc}
     */
    public void close()
    {
        CloseHelper.close(errorHandler, publisherPos);
        CloseHelper.close(errorHandler, publisherLimit);
        CloseHelper.closeAll(errorHandler, subscriberPositions);

        for (int i = 0, size = untetheredSubscriptions.size(); i < size; i++)
        {
            final UntetheredSubscription untetheredSubscription = untetheredSubscriptions.get(i);
            if (UntetheredSubscription.State.RESTING == untetheredSubscription.state)
            {
                CloseHelper.close(errorHandler, untetheredSubscription.position);
            }
        }
    }

    void reject(final long position, final String reason, final DriverConductor conductor, final long nowNs)
    {
        conductor.onPublicationError(
            registrationId,
            Aeron.NULL_VALUE,
            sessionId(),
            streamId(),
            Aeron.NULL_VALUE,
            Aeron.NULL_VALUE,
            IPC_SRC_ADDRESS,
            IMAGE_REJECTED.value(),
            reason);

        if (!inCoolDown)
        {
            updateConnectedStatus(false);

            conductor.unlinkIpcSubscriptions(this);

            CloseHelper.closeAll(errorHandler, subscriberPositions);
            subscriberPositions = EMPTY_POSITIONS;

            untetheredSubscriptions.clear();

            inCoolDown = true;
        }

        coolDownExpireTimeNs = nowNs + imageLivenessTimeoutNs;
    }

    /**
     * {@inheritDoc}
     */
    public void addSubscriber(
        final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition, final long nowNs)
    {
        subscriberPositions = ArrayUtil.add(subscriberPositions, subscriberPosition);
        if (!subscriptionLink.isTether())
        {
            untetheredSubscriptions.add(new UntetheredSubscription(subscriptionLink, subscriberPosition, nowNs));
        }

        updateConnectedStatus(true);
    }

    /**
     * {@inheritDoc}
     */
    public void removeSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.remove(subscriberPositions, subscriberPosition);
        subscriberPosition.close();

        if (!subscriptionLink.isTether())
        {
            for (int lastIndex = untetheredSubscriptions.size() - 1, i = lastIndex; i >= 0; i--)
            {
                if (untetheredSubscriptions.get(i).subscriptionLink == subscriptionLink)
                {
                    ArrayListUtil.fastUnorderedRemove(untetheredSubscriptions, i, lastIndex);
                    break;
                }
            }
        }

        if (0 == subscriberPositions.length)
        {
            updateConnectedStatus(false);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void onTimeEvent(final long timeNs, final long timeMs, final DriverConductor conductor)
    {
        switch (state)
        {
            case ACTIVE:
            {
                if (isPublicationRevoked(metaDataBuffer))
                {
                    final long revokedPos = producerPosition();
                    publisherLimit.setRelease(revokedPos);
                    LogBufferDescriptor.endOfStreamPosition(metaDataBuffer, revokedPos);
                    updateConnectedStatus(false);

                    conductor.transitionToLinger(this);

                    state = State.LINGER;

                    logRevoke(revokedPos, sessionId(), streamId(), channel());
                    publicationsRevoked.increment();
                }
                else
                {
                    checkUntetheredSubscriptions(timeNs, conductor);
                    updateConnectedStatus(0 != subscriberPositions.length);
                    final long producerPosition = producerPosition();
                    publisherPos.setRelease(producerPosition);
                    if (!isExclusive)
                    {
                        checkForBlockedPublisher(producerPosition, timeNs);
                    }
                    checkCoolDownStatus(timeNs, conductor);
                }
                break;
            }

            case DRAINING:
            {
                final long producerPosition = producerPosition();
                publisherPos.setRelease(producerPosition);
                if (isDrained(producerPosition))
                {
                    conductor.transitionToLinger(this);
                    state = State.LINGER;
                }
                else if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, consumerPosition, termBufferLength))
                {
                    unblockedPublications.incrementRelease();
                }
                break;
            }

            case LINGER:
                if (0 == refCount)
                {
                    conductor.cleanupIpcPublication(this);
                    reachedEndOfLife = true;
                    state = State.DONE;
                }
                break;

            case DONE:
                break;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    void revoke()
    {
        LogBufferDescriptor.isPublicationRevoked(metaDataBuffer, true);
    }

    void incRef()
    {
        ++refCount;
    }

    void decRef()
    {
        if (0 == --refCount)
        {
            final long producerPosition = producerPosition();
            publisherLimit.setRelease(producerPosition);
            LogBufferDescriptor.endOfStreamPosition(metaDataBuffer, producerPosition);

            if (!LogBufferDescriptor.isPublicationRevoked(metaDataBuffer))
            {
                state = State.DRAINING;
            }
        }
    }

    int updatePublisherPositionAndLimit()
    {
        int workCount = 0;

        if (State.ACTIVE == state)
        {
            final long producerPosition = producerPosition();
            publisherPos.setRelease(producerPosition);

            if (subscriberPositions.length > 0)
            {
                long minSubscriberPosition = Long.MAX_VALUE;
                long maxSubscriberPosition = consumerPosition;

                for (final ReadablePosition subscriberPosition : subscriberPositions)
                {
                    final long position = subscriberPosition.getVolatile();
                    minSubscriberPosition = Math.min(minSubscriberPosition, position);
                    maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
                }

                if (maxSubscriberPosition > consumerPosition)
                {
                    consumerPosition = maxSubscriberPosition;
                }

                final long newLimitPosition = minSubscriberPosition + termWindowLength;
                if (newLimitPosition >= tripLimit)
                {
                    cleanBufferTo(minSubscriberPosition);
                    publisherLimit.setRelease(newLimitPosition);
                    tripLimit = newLimitPosition + tripGain;
                    workCount = 1;
                }
            }
            else if (publisherLimit.get() > consumerPosition)
            {
                tripLimit = consumerPosition;
                publisherLimit.setRelease(consumerPosition);
                cleanBufferTo(consumerPosition);
                workCount = 1;
            }
        }

        return workCount;
    }

    long joinPosition()
    {
        long position = consumerPosition;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            position = Math.min(subscriberPosition.getVolatile(), position);
        }

        return position;
    }

    long producerPosition()
    {
        final long rawTail = rawTailVolatile(metaDataBuffer);
        final int termOffset = termOffset(rawTail, termBufferLength);

        return computePosition(termId(rawTail), termOffset, positionBitsToShift, initialTermId);
    }

    long consumerPosition()
    {
        return consumerPosition;
    }

    State state()
    {
        return state;
    }

    boolean isAcceptingSubscriptions()
    {
        return !inCoolDown && (State.ACTIVE == state || (State.DRAINING == state && !isDrained(producerPosition())));
    }

    long responseCorrelationId()
    {
        return responseCorrelationId;
    }

    private void checkUntetheredSubscriptions(final long nowNs, final DriverConductor conductor)
    {
        final long untetheredWindowLimit = (consumerPosition - termWindowLength) + (termWindowLength >> 2);

        for (int lastIndex = untetheredSubscriptions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final UntetheredSubscription untethered = untetheredSubscriptions.get(i);
            if (UntetheredSubscription.State.ACTIVE == untethered.state)
            {
                if (untethered.position.getVolatile() > untetheredWindowLimit)
                {
                    untethered.timeOfLastUpdateNs = nowNs;
                }
                else if ((untethered.timeOfLastUpdateNs + untetheredWindowLimitTimeoutNs) - nowNs <= 0)
                {
                    conductor.notifyUnavailableImageLink(registrationId, untethered.subscriptionLink);
                    untethered.state(UntetheredSubscription.State.LINGER, nowNs, streamId, sessionId);
                }
            }
            else if (UntetheredSubscription.State.LINGER == untethered.state)
            {
                if ((untethered.timeOfLastUpdateNs + untetheredLingerTimeoutNs) - nowNs <= 0)
                {
                    subscriberPositions = ArrayUtil.remove(subscriberPositions, untethered.position);
                    if (untethered.subscriptionLink.isRejoin())
                    {
                        untethered.state(UntetheredSubscription.State.RESTING, nowNs, streamId, sessionId);
                    }
                    else
                    {
                        ArrayListUtil.fastUnorderedRemove(untetheredSubscriptions, i, lastIndex--);
                        untethered.position.close();
                    }
                }
            }
            else if (UntetheredSubscription.State.RESTING == untethered.state)
            {
                if ((untethered.timeOfLastUpdateNs + untetheredRestingTimeoutNs) - nowNs <= 0)
                {
                    final long joinPosition = joinPosition();
                    subscriberPositions = ArrayUtil.add(subscriberPositions, untethered.position);
                    conductor.notifyAvailableImageLink(
                        registrationId,
                        sessionId,
                        untethered.subscriptionLink,
                        untethered.position.id(),
                        joinPosition,
                        rawLog.fileName(),
                        CommonContext.IPC_CHANNEL);
                    untethered.state(UntetheredSubscription.State.ACTIVE, nowNs, streamId, sessionId);
                }
            }
        }
    }

    private void updateConnectedStatus(final boolean newStatus)
    {
        if (LogBufferDescriptor.isConnected(metaDataBuffer) != newStatus)
        {
            LogBufferDescriptor.isConnected(metaDataBuffer, newStatus);
        }
    }

    private void checkCoolDownStatus(final long timeNs, final DriverConductor conductor)
    {
        if (inCoolDown && coolDownExpireTimeNs < timeNs)
        {
            inCoolDown = false;

            conductor.linkIpcSubscriptions(this);

            coolDownExpireTimeNs = 0;
        }
    }

    private boolean isDrained(final long producerPosition)
    {
        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            if (subscriberPosition.getVolatile() < producerPosition)
            {
                return false;
            }
        }

        return true;
    }

    private void checkForBlockedPublisher(final long producerPosition, final long timeNs)
    {
        final long consumerPosition = this.consumerPosition;

        if (consumerPosition == lastConsumerPosition && isPossiblyBlocked(producerPosition, consumerPosition))
        {
            if ((timeOfLastConsumerPositionUpdateNs + unblockTimeoutNs) - timeNs < 0)
            {
                if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, consumerPosition, termBufferLength))
                {
                    unblockedPublications.incrementRelease();
                }
            }
        }
        else
        {
            timeOfLastConsumerPositionUpdateNs = timeNs;
            lastConsumerPosition = consumerPosition;
        }
    }

    private boolean isPossiblyBlocked(final long producerPosition, final long consumerPosition)
    {
        final int producerTermCount = activeTermCount(metaDataBuffer);
        final int expectedTermCount = (int)(consumerPosition >> positionBitsToShift);

        if (producerTermCount != expectedTermCount)
        {
            return true;
        }

        return producerPosition > consumerPosition;
    }

    private void cleanBufferTo(final long position)
    {
        final long cleanPosition = this.cleanPosition;
        if (position > cleanPosition)
        {
            final UnsafeBuffer dirtyTermBuffer = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
            final int bytesForCleaning = (int)(position - cleanPosition);
            final int bufferCapacity = termBufferLength;
            final int termOffset = (int)cleanPosition & (bufferCapacity - 1);
            final int length = Math.min(bytesForCleaning, bufferCapacity - termOffset);

            dirtyTermBuffer.setMemory(termOffset + SIZE_OF_LONG, length - SIZE_OF_LONG, (byte)0);
            dirtyTermBuffer.putLongRelease(termOffset, 0);
            this.cleanPosition = cleanPosition + length;
        }
    }

    private static void logRevoke(
        final long revokedPos,
        final int sessionId,
        final int streamId,
        final String channel)
    {
    }
}
