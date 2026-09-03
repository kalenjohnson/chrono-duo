package com.kalenjohnson.chronoduo.dsimport;

/**
 * A minimal seekable byte source abstraction so {@link NitroRom} can be fed
 * from anything that can do positioned reads: a {@code RandomAccessFile}, a
 * {@code FileChannel}, or (on Android) a {@code ParcelFileDescriptor}
 * wrapped in a small adapter. Deliberately Android-free.
 */
public interface SeekableSource {
    /**
     * Reads up to {@code len} bytes starting at absolute position
     * {@code pos} into {@code dst} starting at {@code off}. Implementations
     * may perform short reads (return less than {@code len}) as long as
     * more data is actually available at that position; callers must loop.
     *
     * @return the number of bytes actually read, or -1 at end of stream.
     */
    int read(long pos, byte[] dst, int off, int len);

    /** Total length in bytes of the underlying source. */
    long length();
}
