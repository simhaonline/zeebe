/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl.log;

import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.log.LogStreamReader;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.logstreams.spi.LogStorageReader;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.function.LongUnaryOperator;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class LogStreamReaderImpl implements LogStreamReader {

  static final String ERROR_CLOSED = "Iterator is closed";
  private static final long FIRST_POSITION = Long.MIN_VALUE;
  private static final int UNINITIALIZED = -1;

  private LogStorageReader storageReader;

  // event returned to caller (important: has to be preserved even after compact/buffer resize)
  private final LoggedEventImpl returnedEvent = new LoggedEventImpl();
  private final DirectBuffer returnedEventBuffer = new UnsafeBuffer(0, 0);

  private final LoggedEventImpl nextEvent = new LoggedEventImpl();
  private final DirectBuffer nextEventBuffer = new UnsafeBuffer(0, 0);

  // state
  private IteratorState state;
  private long nextLogStorageReadAddress;
  private long lastReadAddress;
  private int bufferOffset;

  public LogStreamReaderImpl(
      final ConcurrentNavigableMap<Long, Long> positionToIndexMapping,
      final LogStorage logStorage) {
    this.storageReader = logStorage.newReader(positionToIndexMapping);
    invalidateBufferAndOffsets();
    seek(FIRST_POSITION);
  }

  @Override
  public boolean seekToNextEvent(final long position) {

    if (position <= -1) {
      seekToFirstEvent();
      return true;
    }

    final boolean found = seek(position);
    if (found && hasNext()) {
      next();
      return true;
    }

    return false;
  }

  @Override
  public boolean seek(final long position) {
    if (state == IteratorState.CLOSED) {
      throw new IllegalStateException(ERROR_CLOSED);
    }

    final long seekAddress = storageReader.lookUpApproximateAddress(position);

    final long startTime = System.currentTimeMillis();
    invalidateBufferAndOffsets();
    final long endTime = System.currentTimeMillis();
    Loggers.LOGSTREAMS_LOGGER.info("Invalidate buffer took: {} ms", endTime - startTime);

    final long startSeek = System.currentTimeMillis();
    final var found = seekFrom(seekAddress, position);
    final long endSeek = System.currentTimeMillis();
    Loggers.LOGSTREAMS_LOGGER.info(
        "Seek from {} to pos {} took: {} ms", seekAddress, position, endSeek - startSeek);
    return found;
  }

  //  private long findAddress(long position) {
  //    //    storageReader.lookUpApproximateAddress()
  //    //    if (position < 0) {
  //    //      return position;
  //    //    }
  //
  //    var index = positionToIndexMapping.getOrDefault(position, -1L);
  //
  //    if (index == -1) {
  //      final var lowerEntry = positionToIndexMapping.lowerEntry(position);
  //      if (lowerEntry != null) {
  //        index = lowerEntry.getValue();
  //      }
  //    }
  //
  //    long result;
  //    if (index == -1) {
  //      result = LogStorage.OP_RESULT_NO_DATA;
  //    }
  //    result = index;
  //
  //    final var lookUpResult = storageReader.lookUpApproximateAddress(position);
  //    assert result == lookUpResult
  //        : "Result is not equal! Result: "
  //            + result
  //            + " look up: "
  //            + lookUpResult
  //            + " for position "
  //            + position;

  //    return result;
  //  }

  @Override
  public void seekToFirstEvent() {
    seek(FIRST_POSITION);
  }

  @Override
  public long seekToEnd() {
    // invalidate events first as the buffer content may change
    invalidateBufferAndOffsets();

    if (storageReader.isEmpty()) {
      state = IteratorState.EMPTY_LOG_STREAM;
      return UNINITIALIZED;
    } else {
      if (readLastBlockIntoBuffer()) {
        do {
          nextEvent.wrap(nextEventBuffer, bufferOffset);
          bufferOffset += nextEvent.getLength();
        } while (bufferOffset < nextEventBuffer.capacity());

        return nextEvent.getPosition();
      }

      // if the log is not empty this should not happen however
      Loggers.LOGSTREAMS_LOGGER.warn("Unexpected non-empty log failed to read the last block");
      return UNINITIALIZED;
    }
  }

  @Override
  public long getPosition() {
    // if an event was already returned use it's position otherwise use position of next event if
    // available, kind of strange but seemed to be the old API
    if (isReturnedEventInitialized()) {
      return returnedEvent.getPosition();
    }

    if (state == IteratorState.EVENT_AVAILABLE) {
      return nextEvent.getPosition();
    }

    return UNINITIALIZED;
  }

  @Override
  public long lastReadAddress() {
    return lastReadAddress;
  }

  private boolean seekFrom(final long blockAddress, final long position) {
    if (blockAddress < 0) {
      // no block found => empty log
      state = IteratorState.EMPTY_LOG_STREAM;
      return false;
    } else {
      readBlockIntoBuffer(blockAddress);
      readNextEvent();
      return searchPositionInBuffer(position);
    }
  }

  @Override
  public void close() {
    nextEventBuffer.wrap(0, 0);
    bufferOffset = 0;
    state = IteratorState.CLOSED;

    if (storageReader != null) {
      storageReader.close();
      storageReader = null;
    }
  }

  @Override
  public boolean hasNext() {
    switch (state) {
      case EVENT_AVAILABLE:
        return true;
      case EMPTY_LOG_STREAM:
        seekToFirstEvent();
        break;
      case NOT_ENOUGH_DATA:
        readNextAddress();
        break;
      case CLOSED:
        throw new IllegalStateException(ERROR_CLOSED);
      default:
        throw new IllegalStateException("Unknown reader state " + state.name());
    }

    return state == IteratorState.EVENT_AVAILABLE;
  }

  @Override
  public LoggedEvent next() {
    switch (state) {
      case EVENT_AVAILABLE:
        // wrap event for returning
        returnedEventBuffer.wrap(
            nextEventBuffer, nextEvent.getFragmentOffset(), nextEvent.getFragmentLength());

        // find next event in log
        readNextEvent();
        return returnedEvent;
      case CLOSED:
        throw new IllegalStateException(ERROR_CLOSED);
      default:
        throw new NoSuchElementException(
            "Api protocol violation: No next log entry available; You need to probe with hasNext() first.");
    }
  }

  private boolean readLastBlockIntoBuffer() {
    return executeReadMethod(
        Long.MAX_VALUE, readAddress -> storageReader.readLastBlock(nextEventBuffer));
  }

  private boolean readBlockIntoBuffer(final long blockAddress) {
    return executeReadMethod(
        blockAddress, readAddress -> storageReader.read(nextEventBuffer, readAddress));
  }

  private boolean executeReadMethod(final long blockAddress, final LongUnaryOperator readMethod) {
    final long result = readMethod.applyAsLong(blockAddress);
    bufferOffset = 0;

    if (result == LogStorage.OP_RESULT_INVALID_ADDR) {
      throw new IllegalStateException("Invalid address to read from " + blockAddress);
    } else if (result == LogStorage.OP_RESULT_NO_DATA) {
      state = IteratorState.NOT_ENOUGH_DATA;
      return false;
    } else {
      this.lastReadAddress = blockAddress;
      this.nextLogStorageReadAddress = result;
      return true;
    }
  }

  private boolean searchPositionInBuffer(final long position) {
    while (state == IteratorState.EVENT_AVAILABLE && nextEvent.getPosition() < position) {
      readNextEvent();
    }

    if (nextEvent.getPosition() < position) {
      // not in buffered block, read next block and continue the search
      return readNextAddress() && searchPositionInBuffer(position);
    }

    return nextEvent.getPosition() == position;
  }

  private boolean readNextAddress() {
    final boolean blockFound = readBlockIntoBuffer(nextLogStorageReadAddress);

    if (blockFound) {
      readNextEvent();
    }

    return blockFound;
  }

  private void readNextEvent() {
    // initially we assume there is not enough data
    state = IteratorState.NOT_ENOUGH_DATA;

    if (bufferOffset < nextEventBuffer.capacity()) {
      state = IteratorState.EVENT_AVAILABLE;
      nextEvent.wrap(nextEventBuffer, bufferOffset);
      bufferOffset += nextEvent.getLength();
    } else {
      readNextAddress();
    }
  }

  private boolean isReturnedEventInitialized() {
    return returnedEventBuffer.addressOffset() != 0;
  }

  private void invalidateBufferAndOffsets() {
    state = IteratorState.NOT_ENOUGH_DATA;
    bufferOffset = 0;
    nextEventBuffer.wrap(0, 0);
    nextEvent.wrap(nextEventBuffer, 0);
    returnedEventBuffer.wrap(0, 0);
    returnedEvent.wrap(returnedEventBuffer, 0);
  }

  enum IteratorState {
    CLOSED,
    EMPTY_LOG_STREAM,
    EVENT_AVAILABLE,
    NOT_ENOUGH_DATA,
  }
}
