/**
 * This file is part of AudioBoo, an android program for audio blogging.
 * Copyright (C) 2011 BestBefore Media Ltd. All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package fm.audioboo.service;

import android.content.Context;

import android.media.MediaPlayer;

import java.util.TimerTask;
import java.util.Timer;

import java.lang.ref.WeakReference;

import fm.audioboo.application.Boo;

import android.util.Log;

/**
 * Plays Boos. Abstracts out all the differences between streaming MP3s from the
 * web and playing local FLAC files.
 **/
public class BooPlayer extends Thread
{
  /***************************************************************************
   * Private constants
   **/
  // Log ID
  private static final String LTAG              = "BooPlayer";

  // Sleep time, if the thread's not woken.
  private static final int SLEEP_TIME           = 60 * 1000;

  // Interval at which we notify the user of playback progress (msec)
  private static final int TIMER_TASK_INTERVAL  = 500;

  // State machine transitions.
  private static final int T_ERROR              = -3;
  private static final int T_IGNORE             = -2;
  private static final int T_NONE               = -1;
  private static final int T_PREPARE            = 0;
  private static final int T_RESUME             = 1;
  private static final int T_STOP               = 2;
  private static final int T_PAUSE              = 3;
  private static final int T_RESET              = 4;
  private static final int T_START              = 5;

  // Decision matrix - how to get from one state to the other.
  // XXX The indices correspond to values of the first four STATE_ constants,
  //     so don't change the constant values.
  // Read row index (first) as the current state, column index (second) as
  // the desired state.
  private static final int STATE_DECISION_MATRIX[][] = {
    { T_NONE,   T_PREPARE,  T_PREPARE,  T_START,  },
    { T_IGNORE, T_NONE,     T_IGNORE,   T_IGNORE, },
    { T_STOP,   T_RESET,    T_NONE,     T_RESUME, },
    { T_STOP,   T_RESET,    T_PAUSE,    T_NONE,   },
  };


  /***************************************************************************
   * Listener to state changes
   **/
  public static abstract class ProgressListener
  {
    public abstract void onProgress(int state, double progress);
  }



  /***************************************************************************
   * Public data
   **/
  // Flag that keeps the thread running when true.
  public boolean mShouldRun;



  /***************************************************************************
   * Private data
   **/
  // Context in which this object was created
  private WeakReference<Context>  mContext;

  // Lock.
  private Object                mLock         = new Object();

  // Player instance.
  private volatile PlayerBase   mPlayer;

  // Player for MP3 Boos streamed from the Web.
  private volatile MediaPlayer  mMediaPlayer;

  // Listener for state changes
  private ProgressListener      mListener;

  // Tick timer, for tracking progress
  private volatile Timer        mTimer;
  private TimerTask             mTimerTask;

  // Internal player state
  private volatile int          mState        = Constants.STATE_NONE;
  private volatile int          mPendingState = Constants.STATE_NONE;
  private volatile boolean      mResetState;

  // Boo that's currently being played
  private volatile Boo          mBoo;

  // Used for progress tracking
  private double                mPlaybackProgress;
  private long                  mTimestamp;



  /***************************************************************************
   * Implementation
   **/
  public BooPlayer(Context ctx)
  {
    mContext = new WeakReference<Context>(ctx);
  }



  public Context getContext()
  {
    return mContext.get();
  }



  public Object getLock()
  {
    return mLock;
  }



  /**
   * Prepares the internal player with the given Boo. Starts playback
   * immediately.
   **/
  public void play(Boo boo)
  {
    synchronized (mLock)
    {
      if (null != mBoo && mBoo != boo) {
        mResetState = true;
      }
      mBoo = boo;
      mPendingState = Constants.STATE_PLAYING;
    }
    interrupt();
  }



  /**
   * Ends playback. Playback cannot be resumed after this function is called.
   **/
  public void stopPlaying()
  {
    // Log.d(LTAG, "stop from outside");
    synchronized (mLock)
    {
      mPendingState = Constants.STATE_NONE;
    }
    interrupt();
  }



  /**
   * Pauses playback. Playback can be resumed.
   **/
  public void pausePlaying()
  {
    synchronized (mLock)
    {
      mPendingState = Constants.STATE_PAUSED;
    }
    interrupt();
  }



  public void resumePlaying()
  {
    synchronized (mLock)
    {
      mPendingState = Constants.STATE_PLAYING;
    }
    interrupt();
  }



  public int getPlaybackState()
  {
    synchronized (mLock)
    {
      return getPlaybackStateUnlocked();
    }
  }



  public void setPendingState(int state)
  {
    synchronized (mLock)
    {
      setPendingStateUnlocked(state);
    }
  }



  public int getPlaybackStateUnlocked()
  {
    return mState;
  }



  public void setPendingStateUnlocked(int state)
  {
    mPendingState = state;
  }



  /**
   * Thread's run function.
   **/
  public void run()
  {
    mShouldRun = true;
    mResetState = false;

    Boo playingBoo = null;
    while (mShouldRun)
    {
      try {
        // Figure out the action to take from here. This needs to be done under lock
        // so relevant data won't change under our noses.
        Boo currentBoo = null;
        int currentState = Constants.STATE_ERROR;
        int pendingState = Constants.STATE_NONE;
        int action = T_NONE;

        boolean reset = false;

        synchronized (mLock)
        {
          currentBoo = mBoo;
          currentState = mState;
          pendingState = mPendingState;

          // If the current and pending Boo don't match, then we need to reset
          // the state machine. Then we'll go from there.
          reset = mResetState;
          if (mResetState) {
            mResetState = false;
            currentState = Constants.STATE_NONE;
          }

          // If the next state is to be an error state, let's not bother with
          // trying to find out what to do next - we want to stop.
          if (Constants.STATE_ERROR == pendingState) {
            action = T_STOP;
          }
          else {
            action = STATE_DECISION_MATRIX[normalizeState(currentState)][normalizeState(pendingState)];
          }

          // We also set the new state here. This is primarily done because
          // STATE_PREPARING should be set as soon as possible, but it doesn't
          // hurt for the others either.
          // Strictly speaking, we're inviting a race here: once mLock has been
          // released, the thread could be interrupted before the appropriate action
          // to effect the state change can be taken. That, however, does not matter
          // because the next time the decision matrix is consulted, this "lost"
          // state is recovered.
          // By setting the pendingState (in most cases) to be identical to mState,
          // we effectively achieve that the next interrupt() should result in
          // T_NONE.
          switch (action) {
            case T_IGNORE:
            case T_NONE:
              break;

            case T_PREPARE:
            case T_RESET:
              mState = mPendingState = Constants.STATE_PREPARING;
              break;

            case T_RESUME:
              mState = mPendingState = Constants.STATE_PLAYING;
              break;

            case T_STOP:
              mState = mPendingState = Constants.STATE_NONE;
              break;

            case T_PAUSE:
              mState = mPendingState = Constants.STATE_PAUSED;
              break;

            case T_START:
              // For this action only, we set a new pending state. Once
              // preparing has finished, interrupt() will be invoked.
              mState = Constants.STATE_PREPARING;
              mPendingState = Constants.STATE_PLAYING;
              break;

            default:
              Log.e(LTAG, "Unknown action: " + action);
              pendingState = Constants.STATE_ERROR;
              mShouldRun = false;
              continue;
          }
        }
        // Log.d(LTAG, String.format("State change: %d -> %d : %d", currentState, pendingState, action));

        // If we need to reset the state machine, let's do so now.
        if (reset) {
          stopInternal(false);
        }

        // If the pending state is an error state, also send an error state
        // to listeners.
        if (Constants.STATE_ERROR == pendingState) {
          sendStateError();
        }

        // Now perform the appropriate action to attain the new state.
        performAction(action, currentBoo);

        // Sleep until the next interrupt occurs.
        sleep(SLEEP_TIME);
      } catch (InterruptedException ex) {
        // pass
      }
    }

    // Finally we have to transition to an ended state.
    int action = T_NONE;
    synchronized (mLock)
    {
      action = STATE_DECISION_MATRIX[normalizeState(mState)][Constants.STATE_NONE];
      mState = mPendingState = Constants.STATE_NONE;
    }
    performAction(action, null);
  }



  private void performAction(int action, Boo boo)
  {
    switch (action) {
      case T_IGNORE:
      case T_NONE:
        // T_IGNORE and T_NONE are technically different actions: in T_NONE
        // the pending and current state are identical, whereas T_IGNORE
        // simply ignores the state change for now. Either way, we do nothing
        // here.
        break;

      case T_PREPARE:
      case T_START:
        // We need to prepare the player. For that, boo needs to be non-null.
        prepareInternal(boo);
        break;

      case T_RESUME:
        resumeInternal();
        break;

      case T_STOP:
        stopInternal(true);
        break;

      case T_PAUSE:
        pauseInternal();
        break;

      case T_RESET:
        // A reset is identical to stop followed by prepare. The boo needs
        // to be non-null.
        stopInternal(false);
        prepareInternal(boo);
        break;

      default:
        Log.e(LTAG, "Unknown action: " + action);
        break;
    }
  }



  /**
   * Helper function. "Normalizes" a state value insofar as it factors out semi-
   * states.
   **/
  private int normalizeState(int state)
  {
    if (Constants.STATE_BUFFERING == state) {
      return Constants.STATE_PLAYING;
    }
    if (Constants.STATE_ERROR == state) {
      return Constants.STATE_NONE;
    }
    return state;
  }




  private void prepareInternal(Boo boo)
  {
    if (null == boo) {
      Log.e(LTAG, "Prepare without boo!");
      synchronized (mLock)
      {
        mPendingState = Constants.STATE_ERROR;
      }
      interrupt();
      return;
    }

    sendStateBuffering();

    // Local Boos are treated via the FLACPlayerWrapper.
    synchronized (mLock) {
      if (boo.isLocal()) {
        mPlayer = new FLACPlayerWrapper(this);
      }
      else {
        // Examine the Boo's Uri. From that we determine what player to instanciate.
        String path = boo.mData.mHighMP3Url.getPath();
        int ext_sep = path.lastIndexOf(".");
        String ext = path.substring(ext_sep).toLowerCase();

        if (ext.equals(".flac")) {
          // Start FLAC player.
          mPlayer = new FLACPlayerWrapper(this);
        }
        else {
          // Handle everything else via the APIPlayer
          mPlayer = new APIPlayer(this);
        }
      }

      // Now we can use the base API to start playback.
      boolean result = mPlayer.prepare(boo);
      if (result) {
        // Once that returns, we set the current state to PAUSED. While we were in
        // PREPARING state, no other state changes could be effected, so that's safe.
        // We also interrupt() again, to let the thread figure out if there's a
        // pending state after this.
        mState = Constants.STATE_PAUSED;
      }
      else {
        mPendingState = Constants.STATE_ERROR;
      }
    }
    interrupt();
  }



  private void pauseInternal()
  {
    synchronized (mLock) {
      mPlayer.pause();
    }

    sendStateBuffering();
  }



  private void resumeInternal()
  {
    synchronized (mLock) {
      mPlayer.resume();
    }

    if (null == mTimer) {
      startPlaybackState();
    }
    else {
      sendStatePlayback();
    }
  }



  private void stopInternal(boolean sendState)
  {
    synchronized (mLock) {
      if (null != mPlayer) {
        mPlayer.stop();
        mPlayer = null;
      }

      if (null != mTimer) {
        mTimer.cancel();
        mTimer = null;
        mTimerTask = null;
      }

      mBoo = null;
    }

    // Log.d(LTAG, "sending State ended: " + sendState);
    if (sendState) {
      sendStateEnded();
    }
  }



  /**
   * Switches the progress listener to playback state, and starts the timer
   * that'll inform the listener on a regular basis that progress is being made.
   **/
  private void startPlaybackState()
  {
    //Log.d(LTAG, "Starting playback state.");

    if (null == mListener) {
      return;
    }

    // If we made it here, then we'll start a tick timer for sending
    // continuous progress to the users of this class.
    mPlaybackProgress = 0f;
    mTimestamp = System.currentTimeMillis();

    mListener.onProgress(Constants.STATE_PLAYING, mPlaybackProgress);

    try {
      mTimer = new Timer();
      mTimerTask = new TimerTask()
      {
        public void run()
        {
          onTimer();
        }
      };
      mTimer.scheduleAtFixedRate(mTimerTask, 0, TIMER_TASK_INTERVAL);
    } catch (java.lang.IllegalStateException ex) {
      Log.e(LTAG, "Could not start timer: " + ex);
      sendStateError();
    }
  }



  /**
   * Notifies the listener that playback has ended.
   **/
  private void sendStateEnded()
  {
    // Log.d(LTAG, "Send end state");

    if (null == mListener) {
      return;
    }

    mListener.onProgress(Constants.STATE_FINISHED, mPlaybackProgress);
  }



  /**
   * Notifies the listener that the player is in buffering state.
   **/
  private void sendStateBuffering()
  {
    //Log.d(LTAG, "Send buffering state");

    if (null == mListener) {
      return;
    }

    mListener.onProgress(Constants.STATE_BUFFERING, 0f);
  }



  /**
   * Notifies the listener that the player is playing back; this is really
   * only used after a sendStateBuffering() has been sent. Under normal
   * conditions, onTimer() below sends updates.
   **/
  private void sendStatePlayback()
  {
    //Log.d(LTAG, "Send playback state");

    if (null == mListener) {
      return;
    }

    mListener.onProgress(Constants.STATE_PLAYING, mPlaybackProgress);
  }



  /**
   * Sends an error state to the listener.
   **/
  private void sendStateError()
  {
    //Log.d(LTAG, "Send error state");

    if (null == mListener) {
      return;
    }

    mListener.onProgress(Constants.STATE_ERROR, 0f);
  }



  /**
   * Invoked periodically; tracks progress and notifies the listener
   * accordingly.
   **/
  private void onTimer()
  {
    long current = System.currentTimeMillis();
    long diff = current - mTimestamp;
    mTimestamp = current;

    synchronized (mLock)
    {
      if (Constants.STATE_PLAYING != mState) {
        return;
      }
    }

    // Log.d(LTAG, "timestamp: " + mTimestamp);
    // Log.d(LTAG, "diff: " + diff);
    // Log.d(LTAG, "progress: " + mPlaybackProgress);
    mPlaybackProgress += (double) diff / 1000.0;

    sendStatePlayback();
  }

}