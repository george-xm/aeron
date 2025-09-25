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
package io.aeron.archive;

import io.aeron.Aeron;
import io.aeron.AeronCounters;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.status.CountersReader;

import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.concurrent.status.CountersReader.KEY_OFFSET;
import static org.agrona.concurrent.status.CountersReader.NULL_COUNTER_ID;
import static org.agrona.concurrent.status.CountersReader.RECORD_ALLOCATED;
import static org.agrona.concurrent.status.CountersReader.RECORD_UNUSED;
import static org.agrona.concurrent.status.CountersReader.metaDataOffset;

/**
 * A counter that represents client connected to the Archive. It stores control session id as a value and client
 * details in the label.
 *
 * @since 1.49.0
 */
public final class ControlSessionCounter
{
    /**
     * Type id of the counter.
     */
    public static final int TYPE_ID = AeronCounters.ARCHIVE_CONTROL_SESSION_TYPE_ID;

    /**
     * Offset withing {@code key} buffer where archive id is stored.
     */
    public static final int ARCHIVE_ID_KEY_OFFSET = 0;

    /**
     * Offset withing {@code key} buffer where control session id is stored.
     */
    public static final int CONTROL_SESSION_ID_KEY_OFFSET = ARCHIVE_ID_KEY_OFFSET + SIZE_OF_LONG;

    /**
     * Counter name, i.e. the first part of the counter label.
     */
    public static final String NAME = "control-session";

    private ControlSessionCounter()
    {
    }

    static long allocate(
        final Aeron aeron,
        final MutableDirectBuffer tempBuffer,
        final long archiveId,
        final long controlSessionId,
        final String clientInfo)
    {
        tempBuffer.putLong(ARCHIVE_ID_KEY_OFFSET, archiveId);
        tempBuffer.putLong(CONTROL_SESSION_ID_KEY_OFFSET, controlSessionId);
        final int keyLength = 2 * SIZE_OF_LONG;

        int labelLength = 0;
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, NAME);
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, ": ");
        labelLength += tempBuffer.putStringWithoutLengthAscii(keyLength + labelLength, clientInfo);
        labelLength += ArchiveCounters.appendArchiveIdLabel(tempBuffer, keyLength + labelLength, archiveId);

        return aeron.asyncAddCounter(TYPE_ID, tempBuffer, 0, keyLength, tempBuffer, keyLength, labelLength);
    }

    /**
     * Find the active counter id based on the provided archive and control session ids.
     *
     * @param countersReader   to search within.
     * @param archiveId        to target specific Archive.
     * @param controlSessionId for which counter was created.
     * @return the counter id if found, otherwise {@link CountersReader#NULL_COUNTER_ID}.
     */
    public static int findByControlSessionId(
        final CountersReader countersReader, final long archiveId, final long controlSessionId)
    {
        final DirectBuffer buffer = countersReader.metaDataBuffer();

        for (int counterId = 0, maxId = countersReader.maxCounterId(); counterId <= maxId; counterId++)
        {
            final int counterState = countersReader.getCounterState(counterId);
            if (RECORD_ALLOCATED == counterState)
            {
                if (countersReader.getCounterTypeId(counterId) == TYPE_ID)
                {
                    final int keyOffset = metaDataOffset(counterId) + KEY_OFFSET;
                    if (buffer.getLong(keyOffset + ARCHIVE_ID_KEY_OFFSET) == archiveId &&
                        buffer.getLong(keyOffset + CONTROL_SESSION_ID_KEY_OFFSET) == controlSessionId)
                    {
                        return counterId;
                    }
                }
            }
            else if (RECORD_UNUSED == counterState)
            {
                break;
            }
        }

        return NULL_COUNTER_ID;
    }
}
