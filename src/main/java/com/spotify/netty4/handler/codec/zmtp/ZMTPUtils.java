/*
 * Copyright (c) 2012-2013 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.spotify.netty4.handler.codec.zmtp;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Utilities for working with ZMTP
 */
public class ZMTPUtils {

  public static final byte FINAL_FLAG = 0x0;
  public static final byte LONG_FLAG = 0x02;
  public static final byte MORE_FLAG = 0x1;

  public static final ZMTPFrame DELIMITER = ZMTPFrame.EMPTY_FRAME;

  /**
   * Decode a ZMTP/1.0 length field
   *
   * @return Decoded length or -1 if there was not enough bytes in the buffer.
   */
  public static long decodeZMTP1Length(final ByteBuf in) {
    if (in.readableBytes() < 1) {
      return -1;
    }
    long size = in.readByte() & 0xFF;
    if (size == 0xFF) {
      if (in.readableBytes() < 8) {
        return -1;
      }
      size = in.readLong();
    }

    return size;
  }

  /**
   * Encode a ZMTP/1.0 frame length field.
   *
   * @param length The length.
   * @param out    Target buffer.
   */
  public static void encodeZMTP1Length(final long length, final ByteBuf out) {
    encodeZMTP1Length(length, length, out, false);
  }

  /**
   * Encode a ZMTP/1.0 frame length field.
   *
   * @param length    The length.
   * @param out       Target buffer.
   * @param forceLong true to force writing length as a 64 bit unsigned integer.
   */
  public static void encodeZMTP1Length(final long length, final ByteBuf out,
                                       final boolean forceLong) {
    encodeZMTP1Length(length, length, out, forceLong);
  }

  /**
   * Encode a ZMTP/1.0 frame length field.
   *
   * @param maxLength The maximum length of the field.
   * @param length    The length.
   * @param out       Target buffer.
   * @param forceLong true to force writing length as a 64 bit unsigned integer.
   */
  public static void encodeZMTP1Length(final long maxLength, final long length, final ByteBuf out,
                                       final boolean forceLong) {
    if (maxLength < 255 && !forceLong) {
      out.writeByte((byte) length);
    } else {
      out.writeByte(0xFF);
      out.writeLong(length);
    }
  }

  /**
   * Encode a ZMTP/1.0 frame header.
   *
   * @param maxLength The maximum length of the field.
   * @param length    The length.
   * @param more      true if more frames will follow, false if this is the final frame.
   * @param out       Target buffer.
   */
  private static void encodeZMTP1FrameHeader(final ByteBuf out, final int maxLength,
                                             final int length, final boolean more) {
    encodeZMTP1Length(maxLength + 1, length + 1, out, false);
    out.writeByte(more ? MORE_FLAG : FINAL_FLAG);
  }


  /**
   * Encode a ZMTP/2.0 frame header.
   *
   * @param maxLength The maximum length of the field.
   * @param length    The length.
   * @param more      true if more frames will follow, false if this is the final frame.
   * @param out       Target buffer.
   */
  public static void encodeZMTP2FrameHeader(final long maxLength, final long length,
                                            final boolean more, final ByteBuf out) {
    final byte flags = more ? MORE_FLAG : FINAL_FLAG;
    if (maxLength < 256) {
      out.writeByte(flags);
      out.writeByte((byte) length);
    } else {
      out.writeByte(flags | LONG_FLAG);
      out.writeLong(length);
    }
  }

  /**
   * Returns a byte array from a UUID
   *
   * @return byte array format (big endian)
   */
  public static byte[] encodeUUID(final UUID uuid) {
    final long most = uuid.getMostSignificantBits();
    final long least = uuid.getLeastSignificantBits();

    return new byte[]{
        (byte) (most >>> 56), (byte) (most >>> 48), (byte) (most >>> 40),
        (byte) (most >>> 32), (byte) (most >>> 24), (byte) (most >>> 16),
        (byte) (most >>> 8), (byte) most,
        (byte) (least >>> 56), (byte) (least >>> 48), (byte) (least >>> 40),
        (byte) (least >>> 32), (byte) (least >>> 24), (byte) (least >>> 16),
        (byte) (least >>> 8), (byte) least};
  }

  /**
   * Write a ZMTP frame to a buffer.
   *
   * @param frame  The frame to write.
   * @param buffer The target buffer.
   * @param more   True to write a more flag, false to write a final flag.
   */
  public static void writeFrame(final ZMTPFrame frame, final ByteBuf buffer,
                                final boolean more, final int version) {
    writeFrameHeader(buffer, frame.size(), frame.size(), more, version);
    if (frame.hasContent()) {
      final ByteBuf source = frame.content();
      buffer.ensureWritable(source.readableBytes());
      source.getBytes(source.readerIndex(), buffer, source.readableBytes());
    }
  }

  /**
   * Write a ZMTP frame header to a buffer.
   *
   * @param buffer The target buffer.
   * @param more   True to write a more flag, false to write a final flag.
   */
  public static void writeFrameHeader(final ByteBuf buffer, final int maxLength, final int size,
                                      final boolean more, final int version) {
    if (version == 1) {
      encodeZMTP1FrameHeader(buffer, maxLength, size, more);
    } else { // version == 2
      encodeZMTP2FrameHeader(maxLength, size, more, buffer);
    }
  }

  /**
   * Write a ZMTP message to a buffer.
   *
   * @param message   The message to write.
   * @param buffer    The target buffer.
   * @param enveloped Whether the envelope and delimiter should be written.
   */
  @SuppressWarnings("ForLoopReplaceableByForEach")
  public static void writeMessage(final ZMTPMessage message, final ByteBuf buffer,
                                  final boolean enveloped, int version) {
    // Write envelope
    if (enveloped) {
      // Sanity check
      if (message.content().isEmpty()) {
        throw new IllegalArgumentException("Cannot write enveloped message with no content");
      }

      final List<ZMTPFrame> envelope = message.envelope();
      for (int i = 0; i < envelope.size(); i++) {
        writeFrame(envelope.get(i), buffer, true, version);
      }

      // Write the delimiter
      writeFrame(DELIMITER, buffer, true, version);
    }

    final List<ZMTPFrame> content = message.content();
    final int n = content.size();
    final int lastFrame = n - 1;
    for (int i = 0; i < n; i++) {
      writeFrame(content.get(i), buffer, i < lastFrame, version);
    }
  }

  /**
   * Calculate bytes needed to serialize a ZMTP frame.
   *
   * @param frame The frame.
   * @return Bytes needed.
   */
  public static int frameSize(final ZMTPFrame frame, int version) {
    return frameSize(frame.size(), version);
  }

  /**
   * Calculate bytes needed to serialize a ZMTP frame.
   *
   * @param payloadSize Size of the frame payload.
   * @param version     ZMTP version.
   * @return Bytes needed.
   */
  public static int frameSize(final int payloadSize, final int version) {
    if (version == 1) {
      if (payloadSize + 1 < 255) {
        return 1 + 1 + payloadSize;
      } else {
        return 1 + 8 + 1 + payloadSize;
      }
    } else { // version 2
      if (payloadSize < 256) {
        return 1 + 1 + payloadSize;
      } else {
        return 1 + 8 + payloadSize;
      }
    }
  }

  /**
   * Calculate bytes needed to serialize a ZMTP message.
   *
   * @param message   The message.
   * @param enveloped Whether an envelope will be written.
   * @param version   ZMTP version.
   * @return The number of bytes needed.
   */
  public static int messageSize(final ZMTPMessage message, final boolean enveloped,
                                final int version) {
    final int contentSize = framesSize(message.content(), version);
    if (!enveloped) {
      return contentSize;
    }
    final int
        envelopeSize =
        framesSize(message.envelope(), version) + frameSize(DELIMITER, version);
    return envelopeSize + contentSize;
  }

  /**
   * Calculate bytes needed to serialize a list of ZMTP frames.
   *
   * @param frames  The ZMTP frames.
   * @param version ZMTP version.
   */
  @SuppressWarnings("ForLoopReplaceableByForEach")
  public static int framesSize(final List<ZMTPFrame> frames, final int version) {
    int size = 0;
    final int n = frames.size();
    for (int i = 0; i < n; i++) {
      size += frameSize(frames.get(i), version);
    }
    return size;
  }

  /**
   * Create a human readable string representation of binary data, keeping printable ascii and hex
   * encoding everything else.
   *
   * @param data The data
   * @return A human readable string representation of the data.
   */
  public static String toString(final byte[] data) {
    if (data == null) {
      return null;
    }
    return toString(Unpooled.wrappedBuffer(data));
  }

  /**
   * Create a human readable string representation of binary data, keeping printable ascii and hex
   * encoding everything else.
   *
   * @param data The data
   * @return A human readable string representation of the data.
   */
  public static String toString(final ByteBuf data) {
    if (data == null) {
      return null;
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = data.readerIndex(); i < data.writerIndex(); i++) {
      final byte b = data.getByte(i);
      if (b > 31 && b < 127) {
        if (b == '%') {
          sb.append('%');
        }
        sb.append((char) b);
      } else {
        sb.append('%');
        sb.append(String.format("%02X", b));
      }
    }
    return sb.toString();
  }

  /**
   * Create a human readable string representation of a list of ZMTP frames, keeping printable
   * ascii
   * and hex encoding everything else.
   *
   * @param frames The ZMTP frames.
   * @return A human readable string representation of the frames.
   */
  public static String toString(final List<ZMTPFrame> frames) {
    final StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < frames.size(); i++) {
      final ZMTPFrame frame = frames.get(i);
      builder.append('"');
      builder.append(toString(frame.content()));
      builder.append('"');
      if (i < frames.size() - 1) {
        builder.append(',');
      }
    }
    builder.append(']');
    return builder.toString();
  }
}