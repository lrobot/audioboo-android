/**
 * This file is part of AudioBoo, an android program for audio blogging.
 * Copyright (C) 2009 BestBefore Media Ltd. All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package fm.audioboo.widget;

import android.view.View;

import android.content.Context;
import android.util.AttributeSet;
import android.content.res.TypedArray;
import android.content.res.ColorStateList;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Paint.Style;
import android.graphics.Color;

import fm.audioboo.app.R;

import android.util.Log;

/**
 * Similar to a ProgressBar, but simply draws a colored pie.
 **/
public class PieProgressView extends View
{
  /***************************************************************************
   * Private constants
   **/
  // Log ID
  private static final String LTAG  = "PieProgressView";


  /***************************************************************************
   * Data members
   **/
  // Minimum is always 0. Set maximum and progress to determine the angle
  private int             mPieMax = 100;
  private int             mPieProgress = 0;

  // Pie color
  private ColorStateList  mPieColor;

  // Context
  private Context         mContext;

  // Pie paint
  private Paint           mPiePaint;


  /***************************************************************************
   * Implementation
   **/

  public PieProgressView(Context context)
  {
    super(context);
    mContext = context;
  }



  public PieProgressView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    mContext = context;
    initWithAttrs(attrs);
  }



  public PieProgressView(Context context, AttributeSet attrs, int defStyle)
  {
    super(context, attrs, defStyle);
    mContext = context;
    initWithAttrs(attrs);
  }



  public void setProgress(int progress)
  {
    mPieProgress = progress;
  }



  public void setMax(int max)
  {
    mPieMax = max;
  }



  private void initWithAttrs(AttributeSet attrs)
  {
    TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.PieProgressView);
    mPieColor = a.getColorStateList(R.styleable.PieProgressView_pieColor);
    a.recycle();
  }




  @Override
  protected void onDraw(Canvas canvas)
  {
    if (null == mPiePaint) {
      mPiePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    // Get current color depending on view state
    int currentColor = Color.WHITE;
    if (null != mPieColor) {
      currentColor = mPieColor.getColorForState(getDrawableState(), Color.WHITE);
    }
    mPiePaint.setColor(currentColor);

    // Filled pie, no stroke width.
    int angle = (int) (((1.0 * mPieProgress) / mPieMax) * 360);
    RectF arcRect = new RectF(0, 0, this.getWidth(), this.getHeight());
    canvas.drawArc(arcRect, -90, angle, true, mPiePaint);
  }
}