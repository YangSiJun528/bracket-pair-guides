package com.sijunyang.bracketpairguides.benchmarks;

import java.util.SplittableRandom;

final class LongArraySamples {
  private static final long REFERENCE_MASK = 0xFFFF_FFFFL;

  private LongArraySamples() {}

  static long[] create(int size, String distribution) {
    switch (distribution) {
      case "pair-events":
        return pairEvents(size);
      case "random":
        return random(size);
      case "ascending":
        return ascending(size);
      case "descending":
        return descending(size);
      default:
        throw new IllegalArgumentException("Unknown distribution: " + distribution);
    }
  }

  private static long[] pairEvents(int size) {
    long[] values = new long[size];
    int pairCount = size / 2;
    for (int pair = 0; pair < pairCount; pair++) {
      int openOffset = pair;
      int closeOffset = pairCount * 2 - pair;
      values[pair * 2] = encode(openOffset, pair, true);
      values[pair * 2 + 1] = encode(closeOffset, pair, false);
    }
    if ((size & 1) != 0) {
      values[size - 1] = encode(pairCount, pairCount, true);
    }
    return values;
  }

  private static long[] random(int size) {
    SplittableRandom random = new SplittableRandom(0xBACC_37A5_0A7L);
    long[] values = new long[size];
    for (int index = 0; index < size; index++) {
      values[index] = random.nextLong();
    }
    return values;
  }

  private static long[] ascending(int size) {
    long[] values = new long[size];
    for (int index = 0; index < size; index++) {
      values[index] = index;
    }
    return values;
  }

  private static long[] descending(int size) {
    long[] values = new long[size];
    for (int index = 0; index < size; index++) {
      values[index] = size - index;
    }
    return values;
  }

  private static long encode(int offset, int pair, boolean start) {
    int reference = (pair << 1) | (start ? 1 : 0);
    return ((long) offset << 32) | ((long) reference & REFERENCE_MASK);
  }
}
