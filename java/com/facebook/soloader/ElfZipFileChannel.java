/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.soloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

public class ElfZipFileChannel implements ElfByteChannel {

  // MinElf reads ELF fields a few bytes at a time and moves back and forth between the headers,
  // the dynamic section and the string table. Serving those reads from a fixed-size buffer turns
  // the hundreds of tiny stream reads it makes per library into a few buffer refills.
  private static final int BUFFER_SIZE = 8192;

  private @Nullable InputStream mIs;
  private final ZipEntry mZipEntry;
  private final ZipFile mZipFile;
  private final long mLength;
  private boolean mOpened;
  private long mPos;
  // Position of mIs within the entry.
  private long mStreamPos;
  private final byte[] mBuffer = new byte[BUFFER_SIZE];
  // Entry position of mBuffer[0], and the number of valid bytes in mBuffer.
  private long mBufferStart;
  private int mBufferLength;

  public ElfZipFileChannel(ZipFile zipFile, ZipEntry zipEntry) throws IOException {
    mZipFile = zipFile;
    mZipEntry = zipEntry;

    mOpened = true;
    mPos = 0;
    mStreamPos = 0;
    mLength = mZipEntry.getSize();
    mIs = mZipFile.getInputStream(mZipEntry);
    if (mIs == null) {
      throw new IOException(mZipEntry.getName() + "'s InputStream is null");
    }
  }

  @Override
  public long position() throws IOException {
    return mPos;
  }

  @Override
  public ElfByteChannel position(long newPosition) throws IOException {
    if (mIs == null) {
      throw new IOException(mZipEntry.getName() + "'s InputStream is null");
    }

    if (newPosition > mLength) {
      newPosition = mLength;
    }
    // The stream itself is only moved when the buffer needs refilling.
    mPos = newPosition;
    return this;
  }

  /**
   * Reads a sequence of bytes from this channel into the given buffer. Bytes are read starting at
   * this channel's current file position, and then the file position is updated with the number of
   * bytes actually read.
   *
   * @return The number of bytes read, possibly zero, or -1 if the channel has reached end-of-stream
   */
  @Override
  public int read(ByteBuffer dst) throws IOException {
    return read(dst, mPos);
  }

  /**
   * Reads a sequence of bytes from this channel into the given buffer, starting at the given file
   * position.
   *
   * <p>N.B. The file position is updated with the number of bytes actually read. It's different
   * from <a
   * href="https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html#read(java.nio.ByteBuffer,%20long)">FileChannel.html#read(java.nio.ByteBuffer,
   * long)</a>.
   *
   * @return The number of bytes read, possibly zero, or -1 if the given position is greater than or
   *     equal to the file's current size
   */
  @Override
  public int read(ByteBuffer dst, long position) throws IOException {
    if (mIs == null) {
      throw new IOException("InputStream is null");
    }
    if (position < 0) {
      throw new IllegalArgumentException("Negative position: " + position);
    }

    int wanted = dst.remaining();
    long possible = mLength - position;
    if (possible <= 0) {
      return -1;
    }
    if (wanted > (int) possible) {
      wanted = (int) possible;
    }
    if (wanted == 0) {
      return 0;
    }
    int copied = 0;
    while (copied < wanted) {
      final long pos = position + copied;
      if (pos < mBufferStart || pos >= mBufferStart + mBufferLength) {
        fillBuffer(pos - (pos % BUFFER_SIZE));
        if (pos >= mBufferStart + mBufferLength) {
          break; // The entry ended earlier than its declared size
        }
      }
      final int offset = (int) (pos - mBufferStart);
      final int count = Math.min(wanted - copied, mBufferLength - offset);
      dst.put(mBuffer, offset, count);
      copied += count;
    }
    mPos = position + copied;
    return copied == 0 ? -1 : copied;
  }

  /** Fills mBuffer with the entry's bytes starting at {@code start}. */
  private void fillBuffer(long start) throws IOException {
    mBufferStart = start;
    mBufferLength = 0;
    if (!seekStream(start)) {
      return; // The entry ended before start
    }
    final int length = (int) Math.min(BUFFER_SIZE, mLength - start);
    while (mBufferLength < length) {
      final int count = mIs.read(mBuffer, mBufferLength, length - mBufferLength);
      if (count < 0) {
        break;
      }
      mBufferLength += count;
      mStreamPos += count;
    }
  }

  /** Moves mIs to {@code target}. Returns false if the entry ends before it. */
  private boolean seekStream(long target) throws IOException {
    if (target < mStreamPos) {
      mIs.close();
      mIs = mZipFile.getInputStream(mZipEntry);
      if (mIs == null) {
        throw new IOException(mZipEntry.getName() + "'s InputStream is null");
      }
      mStreamPos = 0;
    }
    while (mStreamPos < target) {
      final long skipped = mIs.skip(target - mStreamPos);
      if (skipped > 0) {
        mStreamPos += skipped;
        continue;
      }
      // skip() may return 0 before the end of the stream, so read and discard instead. Callers
      // refill mBuffer right after this.
      final int count = mIs.read(mBuffer, 0, (int) Math.min(BUFFER_SIZE, target - mStreamPos));
      if (count < 0) {
        return false;
      }
      mStreamPos += count;
    }
    return true;
  }

  @Override
  public long size() throws IOException {
    return mLength;
  }

  @Override
  public ElfByteChannel truncate(long size) throws IOException {
    throw new UnsupportedOperationException("ElfZipFileChannel doesn't support truncate");
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    // Readonly byte channel
    throw new UnsupportedOperationException("ElfZipFileChannel doesn't support write");
  }

  @Override
  public boolean isOpen() {
    return mOpened;
  }

  @Override
  public void close() throws IOException {
    if (mIs != null) {
      mIs.close();
      mOpened = false;
    }
  }
}
