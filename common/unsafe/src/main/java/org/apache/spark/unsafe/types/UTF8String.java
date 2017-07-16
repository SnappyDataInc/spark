/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.unsafe.types;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import org.apache.spark.unsafe.Platform;
import org.apache.spark.unsafe.array.ByteArrayMethods;
import org.apache.spark.unsafe.hash.Murmur3_x86_32;

import static org.apache.spark.unsafe.Platform.*;


/**
 * A UTF-8 String for internal Spark use.
 * <p>
 * A String encoded in UTF-8 as an Array[Byte], which can be used for comparison,
 * search, see http://en.wikipedia.org/wiki/UTF-8 for details.
 * <p>
 * Note: This is not designed for general use cases, should not be used outside SQL.
 */
public final class UTF8String implements Comparable<UTF8String>, Externalizable, KryoSerializable,
  Cloneable {

  // These are only updated by readExternal() or read()
  @Nonnull
  private Object base;
  private long offset;
  private int numBytes;

  private transient int hash;
  private transient boolean isAscii;

  public Object getBaseObject() { return base; }
  public long getBaseOffset() { return offset; }

  private static int[] bytesOfCodePointInUTF8 = {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    4, 4, 4, 4, 4, 4, 4, 4,
    5, 5, 5, 5,
    6, 6};

  private static final boolean isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  private static final UTF8String COMMA_UTF8 = UTF8String.fromString(",");
  public static final UTF8String EMPTY_UTF8 = UTF8String.fromString("");

  /**
   * Creates an UTF8String from byte array, which should be encoded in UTF-8.
   *
   * Note: `bytes` will be hold by returned UTF8String.
   */
  public static UTF8String fromBytes(byte[] bytes) {
    if (bytes != null) {
      return new UTF8String(bytes, BYTE_ARRAY_OFFSET, bytes.length);
    } else {
      return null;
    }
  }

  /**
   * Creates an UTF8String from byte array, which should be encoded in UTF-8.
   *
   * Note: `bytes` will be hold by returned UTF8String.
   */
  public static UTF8String fromBytes(byte[] bytes, int offset, int numBytes) {
    if (bytes != null) {
      return new UTF8String(bytes, BYTE_ARRAY_OFFSET + offset, numBytes);
    } else {
      return null;
    }
  }

  /**
   * Creates an UTF8String from given address (base and offset) and length.
   */
  public static UTF8String fromAddress(Object base, long offset, int numBytes) {
    return new UTF8String(base, offset, numBytes);
  }

  /**
   * Creates an UTF8String from String.
   */
  public static UTF8String fromString(String str) {
    return str == null ? null : fromBytes(str.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Creates an UTF8String that contains `length` spaces.
   */
  public static UTF8String blankString(int length) {
    byte[] spaces = new byte[length];
    Arrays.fill(spaces, (byte) ' ');
    return fromBytes(spaces);
  }

  protected UTF8String(Object base, long offset, int numBytes) {
    this.base = base;
    this.offset = offset;
    this.numBytes = numBytes;
  }

  // for serialization
  public UTF8String() {
    this(null, 0, 0);
  }

  /**
   * Writes the content of this string into a memory address, identified by an object and an offset.
   * The target memory address must already been allocated, and have enough space to hold all the
   * bytes in this string.
   */
  public void writeToMemory(Object target, long targetOffset) {
    Platform.copyMemory(base, offset, target, targetOffset, numBytes);
  }

  public void writeTo(ByteBuffer buffer) {
    assert(buffer.hasArray());
    byte[] target = buffer.array();
    int offset = buffer.arrayOffset();
    int pos = buffer.position();
    writeToMemory(target, Platform.BYTE_ARRAY_OFFSET + offset + pos);
    buffer.position(pos + numBytes);
  }

  /**
   * Returns the number of bytes for a code point with the first byte as `b`
   * @param b The first byte of a code point
   */
  private static int numBytesForFirstByte(final byte b) {
    if (b >= 0) return 1;
    final int offset = (b & 0xFF) - 192;
    return (offset >= 0) ? bytesOfCodePointInUTF8[offset] : 1;
  }

  /**
   * Returns the number of bytes
   */
  public int numBytes() {
    return numBytes;
  }

  /**
   * Returns the number of code points in it.
   */
  public int numChars() {
    if (isAscii) return numBytes;
    final long endOffset = offset + numBytes;
    int len = 0;
    for (long offset = this.offset; offset < endOffset;
         offset += numBytesForFirstByte(Platform.getByte(base, offset))) {
      len++;
    }
    if (len == numBytes) isAscii = true;
    return len;
  }

  /**
   * Returns a 64-bit integer that can be used as the prefix used in sorting.
   */
  public long getPrefix() {
    // Since JVMs are either 4-byte aligned or 8-byte aligned, we check the size of the string.
    // If size is 0, just return 0.
    // If size is between 0 and 4 (inclusive), assume data is 4-byte aligned under the hood and
    // use a getInt to fetch the prefix.
    // If size is greater than 4, assume we have at least 8 bytes of data to fetch.
    // After getting the data, we use a mask to mask out data that is not part of the string.
    long p;
    long mask = 0;
    if (isLittleEndian) {
      if (numBytes >= 8) {
        p = Platform.getLong(base, offset);
      } else if (numBytes > 4) {
        p = Platform.getLong(base, offset);
        mask = (1L << (8 - numBytes) * 8) - 1;
      } else if (numBytes > 0) {
        p = (long) Platform.getInt(base, offset);
        mask = (1L << (8 - numBytes) * 8) - 1;
      } else {
        p = 0;
      }
      p = java.lang.Long.reverseBytes(p);
    } else {
      // byteOrder == ByteOrder.BIG_ENDIAN
      if (numBytes >= 8) {
        p = Platform.getLong(base, offset);
      } else if (numBytes > 4) {
        p = Platform.getLong(base, offset);
        mask = (1L << (8 - numBytes) * 8) - 1;
      } else if (numBytes > 0) {
        p = ((long) Platform.getInt(base, offset)) << 32;
        mask = (1L << (8 - numBytes) * 8) - 1;
      } else {
        p = 0;
      }
    }
    p &= ~mask;
    return p;
  }

  /**
   * Returns the underline bytes, will be a copy of it if it's part of another array.
   */
  public byte[] getBytes() {
    // avoid copy if `base` is `byte[]`
    if (offset == BYTE_ARRAY_OFFSET && base instanceof byte[]
      && ((byte[]) base).length == numBytes) {
      return (byte[]) base;
    } else {
      byte[] bytes = new byte[numBytes];
      copyMemory(base, offset, bytes, BYTE_ARRAY_OFFSET, numBytes);
      return bytes;
    }
  }

  /**
   * Returns a substring of this.
   * @param start the position of first code point
   * @param until the position after last code point, exclusive.
   */
  public UTF8String substring(final int start, final int until) {
    if (until <= start || start >= numBytes) {
      return EMPTY_UTF8;
    }

    int i = 0;
    int c = 0;
    while (i < numBytes && c < start) {
      i += numBytesForFirstByte(getByte(i));
      c += 1;
    }

    int j = i;
    while (i < numBytes && c < until) {
      i += numBytesForFirstByte(getByte(i));
      c += 1;
    }

    if (i > j) {
      byte[] bytes = new byte[i - j];
      copyMemory(base, offset + j, bytes, BYTE_ARRAY_OFFSET, i - j);
      return fromBytes(bytes);
    } else {
      return EMPTY_UTF8;
    }
  }

  public UTF8String substringSQL(int pos, int length) {
    // Information regarding the pos calculation:
    // Hive and SQL use one-based indexing for SUBSTR arguments but also accept zero and
    // negative indices for start positions. If a start index i is greater than 0, it
    // refers to element i-1 in the sequence. If a start index i is less than 0, it refers
    // to the -ith element before the end of the sequence. If a start index i is 0, it
    // refers to the first element.
    int len = numChars();
    int start = (pos > 0) ? pos -1 : ((pos < 0) ? len + pos : 0);
    int end = (length == Integer.MAX_VALUE) ? len : start + length;
    return substring(start, end);
  }

  /**
   * Returns whether this contains `substring` or not.
   */
  public boolean contains(final UTF8String substring) {
    if (substring.numBytes == 0) {
      return true;
    }

    byte first = substring.getByte(0);
    for (int i = 0; i <= numBytes - substring.numBytes; i++) {
      if (getByte(i) == first && matchAt(substring, i)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the byte at position `i`.
   */
  public byte getByte(int i) {
    return Platform.getByte(base, offset + i);
  }

  private boolean matchAt(final UTF8String s, int pos) {
    if (s.numBytes + pos > numBytes || pos < 0) {
      return false;
    }
    return ByteArrayMethods.arrayEquals(base, offset + pos, s.base, s.offset, s.numBytes);
  }

  public boolean startsWith(final UTF8String prefix) {
    return matchAt(prefix, 0);
  }

  public boolean endsWith(final UTF8String suffix) {
    return matchAt(suffix, numBytes - suffix.numBytes);
  }

  /**
   * Returns the upper case of this string
   */
  public UTF8String toUpperCase() {
    if (numBytes == 0) {
      return EMPTY_UTF8;
    }

    byte[] bytes = new byte[numBytes];
    bytes[0] = (byte) Character.toTitleCase(getByte(0));
    for (int i = 0; i < numBytes; i++) {
      byte b = getByte(i);
      if (numBytesForFirstByte(b) != 1) {
        // fallback
        return toUpperCaseSlow();
      }
      int upper = Character.toUpperCase((int) b);
      if (upper > 127) {
        // fallback
        return toUpperCaseSlow();
      }
      bytes[i] = (byte) upper;
    }
    return fromBytes(bytes);
  }

  private UTF8String toUpperCaseSlow() {
    return fromString(toString().toUpperCase());
  }

  /**
   * Returns the lower case of this string
   */
  public UTF8String toLowerCase() {
    if (numBytes == 0) {
      return EMPTY_UTF8;
    }

    byte[] bytes = new byte[numBytes];
    bytes[0] = (byte) Character.toTitleCase(getByte(0));
    for (int i = 0; i < numBytes; i++) {
      byte b = getByte(i);
      if (numBytesForFirstByte(b) != 1) {
        // fallback
        return toLowerCaseSlow();
      }
      int lower = Character.toLowerCase((int) b);
      if (lower > 127) {
        // fallback
        return toLowerCaseSlow();
      }
      bytes[i] = (byte) lower;
    }
    return fromBytes(bytes);
  }

  private UTF8String toLowerCaseSlow() {
    return fromString(toString().toLowerCase());
  }

  /**
   * Returns the title case of this string, that could be used as title.
   */
  public UTF8String toTitleCase() {
    if (numBytes == 0) {
      return EMPTY_UTF8;
    }

    byte[] bytes = new byte[numBytes];
    for (int i = 0; i < numBytes; i++) {
      byte b = getByte(i);
      if (i == 0 || getByte(i - 1) == ' ') {
        if (numBytesForFirstByte(b) != 1) {
          // fallback
          return toTitleCaseSlow();
        }
        int upper = Character.toTitleCase(b);
        if (upper > 127) {
          // fallback
          return toTitleCaseSlow();
        }
        bytes[i] = (byte) upper;
      } else {
        bytes[i] = b;
      }
    }
    return fromBytes(bytes);
  }

  private UTF8String toTitleCaseSlow() {
    StringBuffer sb = new StringBuffer();
    String s = toString();
    sb.append(s);
    sb.setCharAt(0, Character.toTitleCase(sb.charAt(0)));
    for (int i = 1; i < s.length(); i++) {
      if (sb.charAt(i - 1) == ' ') {
        sb.setCharAt(i, Character.toTitleCase(sb.charAt(i)));
      }
    }
    return fromString(sb.toString());
  }

  /*
   * Returns the index of the string `match` in this String. This string has to be a comma separated
   * list. If `match` contains a comma 0 will be returned. If the `match` isn't part of this String,
   * 0 will be returned, else the index of match (1-based index)
   */
  public int findInSet(UTF8String match) {
    if (match.contains(COMMA_UTF8)) {
      return 0;
    }

    int n = 1, lastComma = -1;
    for (int i = 0; i < numBytes; i++) {
      if (getByte(i) == (byte) ',') {
        if (i - (lastComma + 1) == match.numBytes &&
          ByteArrayMethods.arrayEquals(base, offset + (lastComma + 1), match.base, match.offset,
            match.numBytes)) {
          return n;
        }
        lastComma = i;
        n++;
      }
    }
    if (numBytes - (lastComma + 1) == match.numBytes &&
      ByteArrayMethods.arrayEquals(base, offset + (lastComma + 1), match.base, match.offset,
        match.numBytes)) {
      return n;
    }
    return 0;
  }

  /**
   * Copy the bytes from the current UTF8String, and make a new UTF8String.
   * @param start the start position of the current UTF8String in bytes.
   * @param end the end position of the current UTF8String in bytes.
   * @return a new UTF8String in the position of [start, end] of current UTF8String bytes.
   */
  private UTF8String copyUTF8String(int start, int end) {
    int len = end - start + 1;
    byte[] newBytes = new byte[len];
    copyMemory(base, offset + start, newBytes, BYTE_ARRAY_OFFSET, len);
    return UTF8String.fromBytes(newBytes);
  }

  public UTF8String trim() {
    int s = 0;
    int e = this.numBytes - 1;
    // skip all of the space (0x20) in the left side
    while (s < this.numBytes && getByte(s) == 0x20) s++;
    // skip all of the space (0x20) in the right side
    while (e >= 0 && getByte(e) == 0x20) e--;
    if (s > e) {
      // empty string
      return EMPTY_UTF8;
    } else {
      return copyUTF8String(s, e);
    }
  }

  public UTF8String trimLeft() {
    int s = 0;
    // skip all of the space (0x20) in the left side
    while (s < this.numBytes && getByte(s) == 0x20) s++;
    if (s == this.numBytes) {
      // empty string
      return EMPTY_UTF8;
    } else {
      return copyUTF8String(s, this.numBytes - 1);
    }
  }

  public UTF8String trimRight() {
    int e = numBytes - 1;
    // skip all of the space (0x20) in the right side
    while (e >= 0 && getByte(e) == 0x20) e--;

    if (e < 0) {
      // empty string
      return EMPTY_UTF8;
    } else {
      return copyUTF8String(0, e);
    }
  }

  public UTF8String reverse() {
    byte[] result = new byte[this.numBytes];

    int i = 0; // position in byte
    while (i < numBytes) {
      int len = numBytesForFirstByte(getByte(i));
      copyMemory(this.base, this.offset + i, result,
        BYTE_ARRAY_OFFSET + result.length - i - len, len);

      i += len;
    }

    return UTF8String.fromBytes(result);
  }

  public UTF8String repeat(int times) {
    if (times <= 0) {
      return EMPTY_UTF8;
    }

    byte[] newBytes = new byte[numBytes * times];
    copyMemory(this.base, this.offset, newBytes, BYTE_ARRAY_OFFSET, numBytes);

    int copied = 1;
    while (copied < times) {
      int toCopy = Math.min(copied, times - copied);
      System.arraycopy(newBytes, 0, newBytes, copied * numBytes, numBytes * toCopy);
      copied += toCopy;
    }

    return UTF8String.fromBytes(newBytes);
  }

  /**
   * Returns the position of the first occurrence of substr in
   * current string from the specified position (0-based index).
   *
   * @param v the string to be searched
   * @param start the start position of the current string for searching
   * @return the position of the first occurrence of substr, if not found, -1 returned.
   */
  public int indexOf(UTF8String v, int start) {
    if (v.numBytes() == 0) {
      return 0;
    }

    // locate to the start position.
    int i = 0; // position in byte
    int c = 0; // position in character
    while (i < numBytes && c < start) {
      i += numBytesForFirstByte(getByte(i));
      c += 1;
    }

    do {
      if (i + v.numBytes > numBytes) {
        return -1;
      }
      if (ByteArrayMethods.arrayEquals(base, offset + i, v.base, v.offset, v.numBytes)) {
        return c;
      }
      i += numBytesForFirstByte(getByte(i));
      c += 1;
    } while (i < numBytes);

    return -1;
  }

  /**
   * Find the `str` from left to right.
   */
  private int find(UTF8String str, int start) {
    assert (str.numBytes > 0);
    while (start <= numBytes - str.numBytes) {
      if (ByteArrayMethods.arrayEquals(base, offset + start, str.base, str.offset, str.numBytes)) {
        return start;
      }
      start += 1;
    }
    return -1;
  }

  /**
   * Find the `str` from right to left.
   */
  private int rfind(UTF8String str, int start) {
    assert (str.numBytes > 0);
    while (start >= 0) {
      if (ByteArrayMethods.arrayEquals(base, offset + start, str.base, str.offset, str.numBytes)) {
        return start;
      }
      start -= 1;
    }
    return -1;
  }

  /**
   * Returns the substring from string str before count occurrences of the delimiter delim.
   * If count is positive, everything the left of the final delimiter (counting from left) is
   * returned. If count is negative, every to the right of the final delimiter (counting from the
   * right) is returned. subStringIndex performs a case-sensitive match when searching for delim.
   */
  public UTF8String subStringIndex(UTF8String delim, int count) {
    if (delim.numBytes == 0 || count == 0) {
      return EMPTY_UTF8;
    }
    if (count > 0) {
      int idx = -1;
      while (count > 0) {
        idx = find(delim, idx + 1);
        if (idx >= 0) {
          count --;
        } else {
          // can not find enough delim
          return this;
        }
      }
      if (idx == 0) {
        return EMPTY_UTF8;
      }
      byte[] bytes = new byte[idx];
      copyMemory(base, offset, bytes, BYTE_ARRAY_OFFSET, idx);
      return fromBytes(bytes);

    } else {
      int idx = numBytes - delim.numBytes + 1;
      count = -count;
      while (count > 0) {
        idx = rfind(delim, idx - 1);
        if (idx >= 0) {
          count --;
        } else {
          // can not find enough delim
          return this;
        }
      }
      if (idx + delim.numBytes == numBytes) {
        return EMPTY_UTF8;
      }
      int size = numBytes - delim.numBytes - idx;
      byte[] bytes = new byte[size];
      copyMemory(base, offset + idx + delim.numBytes, bytes, BYTE_ARRAY_OFFSET, size);
      return fromBytes(bytes);
    }
  }

  /**
   * Returns str, right-padded with pad to a length of len
   * For example:
   *   ('hi', 5, '??') =&gt; 'hi???'
   *   ('hi', 1, '??') =&gt; 'h'
   */
  public UTF8String rpad(int len, UTF8String pad) {
    int spaces = len - this.numChars(); // number of char need to pad
    if (spaces <= 0 || pad.numBytes() == 0) {
      // no padding at all, return the substring of the current string
      return substring(0, len);
    } else {
      int padChars = pad.numChars();
      int count = spaces / padChars; // how many padding string needed
      // the partial string of the padding
      UTF8String remain = pad.substring(0, spaces - padChars * count);

      byte[] data = new byte[this.numBytes + pad.numBytes * count + remain.numBytes];
      copyMemory(this.base, this.offset, data, BYTE_ARRAY_OFFSET, this.numBytes);
      int offset = this.numBytes;
      int idx = 0;
      while (idx < count) {
        copyMemory(pad.base, pad.offset, data, BYTE_ARRAY_OFFSET + offset, pad.numBytes);
        ++ idx;
        offset += pad.numBytes;
      }
      copyMemory(remain.base, remain.offset, data, BYTE_ARRAY_OFFSET + offset, remain.numBytes);

      return UTF8String.fromBytes(data);
    }
  }

  /**
   * Returns str, left-padded with pad to a length of len.
   * For example:
   *   ('hi', 5, '??') =&gt; '???hi'
   *   ('hi', 1, '??') =&gt; 'h'
   */
  public UTF8String lpad(int len, UTF8String pad) {
    int spaces = len - this.numChars(); // number of char need to pad
    if (spaces <= 0 || pad.numBytes() == 0) {
      // no padding at all, return the substring of the current string
      return substring(0, len);
    } else {
      int padChars = pad.numChars();
      int count = spaces / padChars; // how many padding string needed
      // the partial string of the padding
      UTF8String remain = pad.substring(0, spaces - padChars * count);

      byte[] data = new byte[this.numBytes + pad.numBytes * count + remain.numBytes];

      int offset = 0;
      int idx = 0;
      while (idx < count) {
        copyMemory(pad.base, pad.offset, data, BYTE_ARRAY_OFFSET + offset, pad.numBytes);
        ++ idx;
        offset += pad.numBytes;
      }
      copyMemory(remain.base, remain.offset, data, BYTE_ARRAY_OFFSET + offset, remain.numBytes);
      offset += remain.numBytes;
      copyMemory(this.base, this.offset, data, BYTE_ARRAY_OFFSET + offset, numBytes());

      return UTF8String.fromBytes(data);
    }
  }

  /**
   * Concatenates input strings together into a single string. Returns null if any input is null.
   */
  public static UTF8String concat(UTF8String... inputs) {
    // Compute the total length of the result.
    int totalLength = 0;
    for (int i = 0; i < inputs.length; i++) {
      if (inputs[i] != null) {
        totalLength += inputs[i].numBytes;
      } else {
        return null;
      }
    }

    // Allocate a new byte array, and copy the inputs one by one into it.
    final byte[] result = new byte[totalLength];
    int offset = 0;
    for (int i = 0; i < inputs.length; i++) {
      int len = inputs[i].numBytes;
      copyMemory(
        inputs[i].base, inputs[i].offset,
        result, BYTE_ARRAY_OFFSET + offset,
        len);
      offset += len;
    }
    return fromBytes(result);
  }

  /**
   * Concatenates input strings together into a single string using the separator.
   * A null input is skipped. For example, concat(",", "a", null, "c") would yield "a,c".
   */
  public static UTF8String concatWs(UTF8String separator, UTF8String... inputs) {
    if (separator == null) {
      return null;
    }

    int numInputBytes = 0;  // total number of bytes from the inputs
    int numInputs = 0;      // number of non-null inputs
    for (int i = 0; i < inputs.length; i++) {
      if (inputs[i] != null) {
        numInputBytes += inputs[i].numBytes;
        numInputs++;
      }
    }

    if (numInputs == 0) {
      // Return an empty string if there is no input, or all the inputs are null.
      return EMPTY_UTF8;
    }

    // Allocate a new byte array, and copy the inputs one by one into it.
    // The size of the new array is the size of all inputs, plus the separators.
    final byte[] result = new byte[numInputBytes + (numInputs - 1) * separator.numBytes];
    int offset = 0;

    for (int i = 0, j = 0; i < inputs.length; i++) {
      if (inputs[i] != null) {
        int len = inputs[i].numBytes;
        copyMemory(
          inputs[i].base, inputs[i].offset,
          result, BYTE_ARRAY_OFFSET + offset,
          len);
        offset += len;

        j++;
        // Add separator if this is not the last input.
        if (j < numInputs) {
          copyMemory(
            separator.base, separator.offset,
            result, BYTE_ARRAY_OFFSET + offset,
            separator.numBytes);
          offset += separator.numBytes;
        }
      }
    }
    return fromBytes(result);
  }

  public UTF8String[] split(UTF8String pattern, int limit) {
    String[] splits = toString().split(pattern.toString(), limit);
    UTF8String[] res = new UTF8String[splits.length];
    for (int i = 0; i < res.length; i++) {
      res[i] = fromString(splits[i]);
    }
    return res;
  }

  // TODO: Need to use `Code Point` here instead of Char in case the character longer than 2 bytes
  public UTF8String translate(Map<Character, Character> dict) {
    String srcStr = this.toString();

    StringBuilder sb = new StringBuilder();
    for(int k = 0; k< srcStr.length(); k++) {
      if (null == dict.get(srcStr.charAt(k))) {
        sb.append(srcStr.charAt(k));
      } else if ('\0' != dict.get(srcStr.charAt(k))){
        sb.append(dict.get(srcStr.charAt(k)));
      }
    }
    return fromString(sb.toString());
  }

  private int getDigit(byte b) {
    if (b >= '0' && b <= '9') {
      return b - '0';
    }
    throw new NumberFormatException(toString());
  }

  /**
   * Parses this UTF8String to long.
   *
   * Note that, in this method we accumulate the result in negative format, and convert it to
   * positive format at the end, if this string is not started with '-'. This is because min value
   * is bigger than max value in digits, e.g. Integer.MAX_VALUE is '2147483647' and
   * Integer.MIN_VALUE is '-2147483648'.
   *
   * This code is mostly copied from LazyLong.parseLong in Hive.
   */
  public long toLong() {
    if (numBytes == 0) {
      throw new NumberFormatException("Empty string");
    }

    byte b = getByte(0);
    final boolean negative = b == '-';
    int offset = 0;
    if (negative || b == '+') {
      offset++;
      if (numBytes == 1) {
        throw new NumberFormatException(toString());
      }
    }

    final byte separator = '.';
    final int radix = 10;
    final long stopValue = Long.MIN_VALUE / radix;
    long result = 0;

    while (offset < numBytes) {
      b = getByte(offset);
      offset++;
      if (b == separator) {
        // We allow decimals and will return a truncated integral in that case.
        // Therefore we won't throw an exception here (checking the fractional
        // part happens below.)
        break;
      }

      int digit = getDigit(b);
      // We are going to process the new digit and accumulate the result. However, before doing
      // this, if the result is already smaller than the stopValue(Long.MIN_VALUE / radix), then
      // result * 10 will definitely be smaller than minValue, and we can stop and throw exception.
      if (result < stopValue) {
        throw new NumberFormatException(toString());
      }

      result = result * radix - digit;
      // Since the previous result is less than or equal to stopValue(Long.MIN_VALUE / radix), we
      // can just use `result > 0` to check overflow. If result overflows, we should stop and throw
      // exception.
      if (result > 0) {
        throw new NumberFormatException(toString());
      }
    }

    // This is the case when we've encountered a decimal separator. The fractional
    // part will not change the number, but we will verify that the fractional part
    // is well formed.
    while (offset < numBytes) {
      if (getDigit(getByte(offset)) == -1) {
        throw new NumberFormatException(toString());
      }
      offset++;
    }

    if (!negative) {
      result = -result;
      if (result < 0) {
        throw new NumberFormatException(toString());
      }
    }

    return result;
  }

  /**
   * Parses this UTF8String to int.
   *
   * Note that, in this method we accumulate the result in negative format, and convert it to
   * positive format at the end, if this string is not started with '-'. This is because min value
   * is bigger than max value in digits, e.g. Integer.MAX_VALUE is '2147483647' and
   * Integer.MIN_VALUE is '-2147483648'.
   *
   * This code is mostly copied from LazyInt.parseInt in Hive.
   *
   * Note that, this method is almost same as `toLong`, but we leave it duplicated for performance
   * reasons, like Hive does.
   */
  public int toInt() {
    if (numBytes == 0) {
      throw new NumberFormatException("Empty string");
    }

    byte b = getByte(0);
    final boolean negative = b == '-';
    int offset = 0;
    if (negative || b == '+') {
      offset++;
      if (numBytes == 1) {
        throw new NumberFormatException(toString());
      }
    }

    final byte separator = '.';
    final int radix = 10;
    final int stopValue = Integer.MIN_VALUE / radix;
    int result = 0;

    while (offset < numBytes) {
      b = getByte(offset);
      offset++;
      if (b == separator) {
        // We allow decimals and will return a truncated integral in that case.
        // Therefore we won't throw an exception here (checking the fractional
        // part happens below.)
        break;
      }

      int digit = getDigit(b);
      // We are going to process the new digit and accumulate the result. However, before doing
      // this, if the result is already smaller than the stopValue(Integer.MIN_VALUE / radix), then
      // result * 10 will definitely be smaller than minValue, and we can stop and throw exception.
      if (result < stopValue) {
        throw new NumberFormatException(toString());
      }

      result = result * radix - digit;
      // Since the previous result is less than or equal to stopValue(Integer.MIN_VALUE / radix),
      // we can just use `result > 0` to check overflow. If result overflows, we should stop and
      // throw exception.
      if (result > 0) {
        throw new NumberFormatException(toString());
      }
    }

    // This is the case when we've encountered a decimal separator. The fractional
    // part will not change the number, but we will verify that the fractional part
    // is well formed.
    while (offset < numBytes) {
      if (getDigit(getByte(offset)) == -1) {
        throw new NumberFormatException(toString());
      }
      offset++;
    }

    if (!negative) {
      result = -result;
      if (result < 0) {
        throw new NumberFormatException(toString());
      }
    }

    return result;
  }

  public short toShort() {
    int intValue = toInt();
    short result = (short) intValue;
    if (result != intValue) {
      throw new NumberFormatException(toString());
    }

    return result;
  }

  public byte toByte() {
    int intValue = toInt();
    byte result = (byte) intValue;
    if (result != intValue) {
      throw new NumberFormatException(toString());
    }

    return result;
  }

  @Override
  public String toString() {
    return new String(getBytes(), StandardCharsets.UTF_8);
  }

  @Override
  public UTF8String clone() {
    UTF8String newString = fromBytes(getBytes());
    if (isAscii) {
      newString.isAscii = true;
    }
    return newString;
  }

  public UTF8String cloneIfRequired() {
    if (offset == BYTE_ARRAY_OFFSET &&
        ((byte[])base).length == numBytes) {
      return this;
    } else {
      final int numBytes = this.numBytes;
      final byte[] bytes = new byte[numBytes];
      copyMemory(base, offset, bytes, BYTE_ARRAY_OFFSET, numBytes);
      UTF8String newString = fromAddress(bytes, BYTE_ARRAY_OFFSET, numBytes);
      if (isAscii) {
        newString.isAscii = true;
      }
      return newString;
    }
  }

  @Override
  public int compareTo(@Nonnull final UTF8String other) {
    return compare(other);
  }

  /** Read integer in big-endian format */
  static int getIntBigEndian(final Object base, final long offset) {
    return isLittleEndian ? Integer.reverseBytes(Platform.getInt(base, offset))
        : Platform.getInt(base, offset);
  }

  /** Read long in big-endian format */
  static long getLongBigEndian(final Object base, final long offset) {
    return isLittleEndian ? Long.reverseBytes(Platform.getLong(base, offset))
        : Platform.getLong(base, offset);
  }

  public final int compare(final UTF8String other) {
    final Object rightBase = other.getBaseObject();
    long rightOffset = other.getBaseOffset();
    final Object leftBase = base;
    long leftOffset = offset;

    final int len = Math.min(numBytes, other.numBytes);
    long endOffset = leftOffset + len;
    // for architectures that support unaligned accesses, read 8 bytes at a time
    if (Platform.unaligned() || (((leftOffset & 0x7) == 0) && ((rightOffset & 0x7) == 0))) {
      endOffset -= 8;
      while (leftOffset <= endOffset) {
        // In UTF-8, the byte should be unsigned, so we should compare them as unsigned long.
        final long ll = getLongBigEndian(leftBase, leftOffset);
        final long rl = getLongBigEndian(rightBase, rightOffset);
        final long res = ll - rl;
        // If the sign of both values is same then "res" is with correct sign.
        // If the sign of values is different then "res" has opposite sign.
        // The XOR operations will revert the sign bit of res if sign of values is different.
        // After that converting to signum is "(1 + ((v >> 63) << 1))"
        //   where (v >> 63) will flow the sign to give -1 or 0, and (1 + 2 times)
        //   of that will give -1 or 1 respectively.
        if (res != 0) return (int)(1 + (((ll ^ rl ^ res) >> 63) << 1));
        leftOffset += 8;
        rightOffset += 8;
      }
      endOffset += 4;
      if (leftOffset <= endOffset) {
        // In UTF-8, the byte should be unsigned, so we should compare them as unsigned int
        // which is done by converting to unsigned longs.
        // After that conversion to signed integer is "(1 + ((v >> 63) << 1))" as above.
        final long res = (getIntBigEndian(leftBase, leftOffset) & 0xffffffffL) -
            (getIntBigEndian(rightBase, rightOffset) & 0xffffffffL);
        if (res != 0) return (int)(1 + ((res >> 63) << 1));
        leftOffset += 4;
        rightOffset += 4;
      }
      endOffset += 4;
    }
    // finish the remaining bytes
    while (leftOffset < endOffset) {
      // In UTF-8, the byte should be unsigned, so we should compare them as unsigned int.
      final int res = (Platform.getByte(leftBase, leftOffset) & 0xff) -
          (Platform.getByte(rightBase, rightOffset) & 0xff);
      if (res != 0) return res;
      leftOffset++;
      rightOffset++;
    }
    return numBytes - other.numBytes;
  }

  @Override
  public boolean equals(final Object other) {
    if (other instanceof UTF8String) {
      UTF8String o = (UTF8String) other;
      if (numBytes != o.numBytes) {
        return false;
      }
      return ByteArrayMethods.arrayEquals(base, offset, o.base, o.offset, numBytes);
    } else {
      return false;
    }
  }

  public boolean equals(final UTF8String o) {
    final int numBytes = this.numBytes;
    return o != null && numBytes == o.numBytes && ByteArrayMethods.arrayEquals(
        base, offset, o.base, o.offset, numBytes);
  }

  /**
   * Levenshtein distance is a metric for measuring the distance of two strings. The distance is
   * defined by the minimum number of single-character edits (i.e. insertions, deletions or
   * substitutions) that are required to change one of the strings into the other.
   */
  public int levenshteinDistance(UTF8String other) {
    // Implementation adopted from org.apache.common.lang3.StringUtils.getLevenshteinDistance

    int n = numChars();
    int m = other.numChars();

    if (n == 0) {
      return m;
    } else if (m == 0) {
      return n;
    }

    UTF8String s, t;

    if (n <= m) {
      s = this;
      t = other;
    } else {
      s = other;
      t = this;
      int swap;
      swap = n;
      n = m;
      m = swap;
    }

    int[] p = new int[n + 1];
    int[] d = new int[n + 1];
    int[] swap;

    int i, i_bytes, j, j_bytes, num_bytes_j, cost;

    for (i = 0; i <= n; i++) {
      p[i] = i;
    }

    for (j = 0, j_bytes = 0; j < m; j_bytes += num_bytes_j, j++) {
      num_bytes_j = numBytesForFirstByte(t.getByte(j_bytes));
      d[0] = j + 1;

      for (i = 0, i_bytes = 0; i < n; i_bytes += numBytesForFirstByte(s.getByte(i_bytes)), i++) {
        if (s.getByte(i_bytes) != t.getByte(j_bytes) ||
              num_bytes_j != numBytesForFirstByte(s.getByte(i_bytes))) {
          cost = 1;
        } else {
          cost = (ByteArrayMethods.arrayEquals(t.base, t.offset + j_bytes, s.base,
              s.offset + i_bytes, num_bytes_j)) ? 0 : 1;
        }
        d[i + 1] = Math.min(Math.min(d[i] + 1, p[i + 1] + 1), p[i] + cost);
      }

      swap = p;
      p = d;
      d = swap;
    }

    return p[n];
  }

  @Override
  public int hashCode() {
    final int h = this.hash;
    if (h != 0) return h;
    return (this.hash = Murmur3_x86_32.hashUnsafeBytes(
        base, offset, numBytes, 42));
  }

  /**
   * Soundex mapping table
   */
  private static final byte[] US_ENGLISH_MAPPING = {'0', '1', '2', '3', '0', '1', '2', '7',
    '0', '2', '2', '4', '5', '5', '0', '1', '2', '6', '2', '3', '0', '1', '7', '2', '0', '2'};

  /**
   * Encodes a string into a Soundex value. Soundex is an encoding used to relate similar names,
   * but can also be used as a general purpose scheme to find word with similar phonemes.
   * https://en.wikipedia.org/wiki/Soundex
   */
  public UTF8String soundex() {
    if (numBytes == 0) {
      return EMPTY_UTF8;
    }

    byte b = getByte(0);
    if ('a' <= b && b <= 'z') {
      b -= 32;
    } else if (b < 'A' || 'Z' < b) {
      // first character must be a letter
      return this;
    }
    byte[] sx = {'0', '0', '0', '0'};
    sx[0] = b;
    int sxi = 1;
    int idx = b - 'A';
    byte lastCode = US_ENGLISH_MAPPING[idx];

    for (int i = 1; i < numBytes; i++) {
      b = getByte(i);
      if ('a' <= b && b <= 'z') {
        b -= 32;
      } else if (b < 'A' || 'Z' < b) {
        // not a letter, skip it
        lastCode = '0';
        continue;
      }
      idx = b - 'A';
      byte code = US_ENGLISH_MAPPING[idx];
      if (code == '7') {
        // ignore it
      } else {
        if (code != '0' && code != lastCode) {
          sx[sxi++] = code;
          if (sxi > 3) break;
        }
        lastCode = code;
      }
    }
    return UTF8String.fromBytes(sx);
  }

  public void writeExternal(ObjectOutput out) throws IOException {
    byte[] bytes = getBytes();
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    offset = BYTE_ARRAY_OFFSET;
    numBytes = in.readInt();
    base = new byte[numBytes];
    in.readFully((byte[]) base);
  }

  @Override
  public void write(Kryo kryo, Output out) {
    byte[] bytes = getBytes();
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  @Override
  public void read(Kryo kryo, Input in) {
    this.offset = BYTE_ARRAY_OFFSET;
    this.numBytes = in.readInt();
    this.base = new byte[numBytes];
    in.read((byte[]) base);
  }

}
