/**
 * This file is part of AudioBoo, an android program for audio blogging.
 * Copyright (C) 2009 BestBefore Media Ltd. All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package fm.audioboo.jni;


/**
 * This is *not* a full JNI wrapper for the FLAC codec, but merely exports
 * the minimum of functions necessary for the purposes of the AudioBoo client.
 **/
public class FLACStreamEncoder
{
  /***************************************************************************
   * Interface
   **/

  /**
   * channels must be either 1 (mono) or 2 (stereo)
   * bits_per_sample must be either 8 or 16
   **/
  public FLACStreamEncoder(String outfile, int sample_rate, int channels,
      int bits_per_sample)
  {
    init(outfile, sample_rate, channels, bits_per_sample);
  }



  public void release()
  {
    deinit();
  }



  public void reset(String outfile, int sample_rate, int channels,
      int bits_per_sample)
  {
    deinit();
    init(outfile, sample_rate, channels, bits_per_sample);
  }



  protected void finalize() throws Throwable
  {
    try {
      deinit();
    } finally {
      super.finalize();
    }
  }



  /***************************************************************************
   * JNI Implementation
   **/

  // Pointer to opaque data in C
  private long  mObject;

  /**
   * Constructor equivalent
   **/
  native private void init(String outfile, int sample_rate, int channels,
      int bits_per_sample);

  /**
   * Destructor equivalent, but can be called multiple times.
   **/
  native private void deinit();

  /**
   * Writes data to the encoder. The provided buffer must be at least as long
   * as the provided buffer size.
   * FIXME return value
   **/
  native public int write(byte[] buffer, int bufsize);

  // Load native library
  static {
    System.loadLibrary("audioboo-native");
  }
}