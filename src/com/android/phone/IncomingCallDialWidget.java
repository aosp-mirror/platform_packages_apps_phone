/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;


/**
 * Custom View for the "dial-to-answer" widget, which allows the user to
 * answer an incoming call using the touchscreen.
 */
public class IncomingCallDialWidget extends View {
    private static final String LOG_TAG = "IncomingCallDialWidget";
    private static final boolean DBG = false;

    /**
     * Reference to the InCallTouchUi widget that owns us.
     */
    private InCallTouchUi mInCallTouchUi;

    private float mDensity;

    private Paint mTmpPaint;

    // UI elements
    private Drawable mBackground;
    private Drawable mDimple;

    // TODO: fix uses of "answer icon" and "reject icon" to just be
    // "left" and "right", to make the widget more generic.
    private Drawable mAnswerIcon;
    private Drawable mRejectIcon;

    private Drawable mArrowShortLeftAndRight;
    private Drawable mArrowLongLeftGreen;
    private Drawable mArrowLongRightRed;

    // Misc positions / dimensions (see onSizeChanged())
    private int mKnobX;
    private int mKnobY;
    private int mKnobRadius;

    // Angular distance of the answer / reject icons, relative to the
    // top of the dial.  (This is also the spacing between "dimples".)
    private float mIconOffsetRadians;

    // TODO: fix uses of "green handle" and "red handle" to just be
    // "left" and "right", to make the widget more generic.
    private int mGreenHandleX;
    private int mGreenHandleY;
    private int mRedHandleX;
    private int mRedHandleY;

    // Bounds of the green and red handles, as of the most recent onDraw()
    // call.  Used for hit detection with DOWN events.
    private final Rect mGreenHandleBounds = new Rect();
    private final Rect mRedHandleBounds = new Rect();

    // Temp rect used by compareWithSlop()
    private final Rect mTempRect = new Rect();

    private int mHandleTrackRadius;

    // State of the current gesture
    private float mDialTheta;  // radians (positive = counterclockwise rotation)
    private int mDownX;
    private int mDownY;
    private int mDeltaX;
    private int mDeltaY;

    private int mGrabbedState = NOTHING_GRABBED;
    private static final int NOTHING_GRABBED = 0;
    private static final int GREEN_HANDLE_GRABBED = 1;
    private static final int RED_HANDLE_GRABBED = 2;

    // Vibration (haptic feedback)
    private Vibrator mVibrator;
    private static final long VIBRATE_SHORT = 60;  // msec
    private static final long VIBRATE_LONG = 100;  // msec

    // Various tweakable layout or behavior parameters:

    // How close to the edge of the screen, we let the handle get before
    // triggering an action:
    private static final int EDGE_THRESHOLD_DIP = 70;

    // How far above the bottom of the screen we draw the background asset.
    private static final int BACKGROUND_BOTTOM_PADDING_DIP = 0;

    // Extra amount added to the Y-position of the "arrows" asset; zero
    // means align the bottom of the arrows bitmap with the top of the
    // background bitmap.  A positive value here adjusts the arrow image
    // downward.
    private static final int ARROW_YPOS_ADJUST_DIP = 30;


    /**
     * Constructor used when this widget is created from a layout file.
     */
    public IncomingCallDialWidget(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DBG) log("IncomingCallDialWidget constructor...");

        Resources r = getResources();
        mDensity = r.getDisplayMetrics().density;
        if (DBG) log("- Density: " + mDensity);
        // Density is 1.0 on HVGA (like Dream), and 1.5 on WVGA.
        // Usage: raw_pixel_value = (int) (dpi_value * mDensity + 0.5f)

        // Assets (all are BitmapDrawables).

        mBackground = r.getDrawable(R.drawable.jog_dial_bg);
        mDimple = r.getDrawable(R.drawable.jog_dial_dimple);

        // TODO: provide public API to pass in resource IDs for these,
        // to make the widget more generic.  These should also be
        // specifyable via XML.
        mAnswerIcon = r.getDrawable(R.drawable.ic_jog_dial_answer);
        mRejectIcon = r.getDrawable(R.drawable.ic_jog_dial_decline);

        mArrowLongLeftGreen = r.getDrawable(R.drawable.jog_dial_arrow_long_left_green);
        mArrowLongRightRed = r.getDrawable(R.drawable.jog_dial_arrow_long_right_red);
        mArrowShortLeftAndRight = r.getDrawable(R.drawable.jog_dial_arrow_short_left_and_right);

        // TODO: Paint used for layout debugging; remove before ship.
        mTmpPaint = new Paint();
        mTmpPaint.setColor(0XFFDDDDDD);
        mTmpPaint.setStyle(Paint.Style.STROKE);
        mTmpPaint.setStrokeWidth((int) (2 * mDensity + 0.5f));
        mTmpPaint.setAntiAlias(true);
    }

    public void setInCallTouchUiInstance(InCallTouchUi inCallTouchUi) {
        mInCallTouchUi = inCallTouchUi;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DBG) log("onSizeChanged()...  new = " + w + " x " + h + ", old = " + oldw + " x " + oldh);
        // HVGA device: size here is 320 x 455
        // WVGA800 device: size here is 480 x 762
        // WVGA854 device: size here is 480 x 816

        // Compute the sizes and layout of our various internal elements.
        // (Don't use any hardcoded dimensions here; everything should be
        // relative to our overall size or scaled by mDensity.)

        // Location of the dial center, hand-tweaked to match the artwork.
        final float KNOB_CENTER_Y = 1.45f;  // in "screen-heights"
        //
        // TODO: use scaled pixels for this instead of "screen-heights".
        // i.e. have a DIP constant for "Location of the (offscreen) center of
        // the dial.  (The dial center is located this amount of dip below
        // the bottom of the screen.)"
        //
        // Also, what the dial radius should be, based on the asset itself:
        // > Not sure if this helps but according to Adobe Illustrator, the center
        // > radius for the wheel is 355.878 pt (HVGA dimension)

        // Ugly hack alert: for now we manually tweak some layout params
        // based on the screen height (or really, aspect ratio.)
        // TODO: refactor the layout math so this isn't necessary.
        // (We can probably do this by making all layout relative to the
        // *bottom* of the screen.)
        int iconOffsetDegrees;
        int knobYAdjust;
        if (h > 800) {
            if (DBG) log("- layout: WVGA854");
            iconOffsetDegrees = 17;
            knobYAdjust = 60;
        } else if (h > 500) {
            if (DBG) log("- layout: WVGA800");
            iconOffsetDegrees = 18;
            knobYAdjust = 40;
        } else {
            if (DBG) log("- layout: HVGA");
            iconOffsetDegrees = 20;
            knobYAdjust = 0;
        }
        mIconOffsetRadians = (float) Math.toRadians(iconOffsetDegrees);

        mKnobX = w / 2;
        mKnobY = (int) (h * KNOB_CENTER_Y) + knobYAdjust;
        mKnobRadius = (int) (h * (KNOB_CENTER_Y - 0.6));

        final int HANDLE_TRACK_OFFSET_DIP = 40;
        final int HANDLE_TRACK_OFFSET = (int) (HANDLE_TRACK_OFFSET_DIP * mDensity + 0.5f);
        mHandleTrackRadius = (int) (mKnobRadius - HANDLE_TRACK_OFFSET);

        reset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DBG) log("onDraw()...");

        final int width = getWidth();
        final int height = getHeight();
        if (DBG) log("- Dimensions: " + width + " x " + height);

        // Background:
        final int backgroundW = mBackground.getIntrinsicWidth();
        final int backgroundH = mBackground.getIntrinsicHeight();
        final int backgroundBottomPadding = (int) (BACKGROUND_BOTTOM_PADDING_DIP
                                                   * mDensity + 0.5f);
        final int backgroundY = height - backgroundH - backgroundBottomPadding;
        if (DBG) log("- Background INTRINSIC: " + backgroundW + " x " + backgroundH);
        mBackground.setBounds(0, backgroundY,
                              backgroundW, backgroundY + backgroundH);
        if (DBG) log("  Background BOUNDS: " + mBackground.getBounds());
        mBackground.draw(canvas);

        // Arrows:
        // All arrow assets are the same size (they're the full width of
        // the screen) regardless of which arrows are actually visible.
        int arrowW = mArrowShortLeftAndRight.getIntrinsicWidth();
        int arrowH = mArrowShortLeftAndRight.getIntrinsicHeight();
        final int ARROW_YPOS_ADJUST = (int) (ARROW_YPOS_ADJUST_DIP * mDensity + 0.5f);
        final int arrowY = (height - backgroundBottomPadding - backgroundH - arrowH)
                + ARROW_YPOS_ADJUST;

        // Draw the correct arrow(s) depending on the current state:
        Drawable mCurrentArrow;
        switch (mGrabbedState) {
            case NOTHING_GRABBED:
                mCurrentArrow  = mArrowShortLeftAndRight;
                break;
            case GREEN_HANDLE_GRABBED:
                mCurrentArrow = mArrowLongLeftGreen;
                break;
            case RED_HANDLE_GRABBED:
                mCurrentArrow = mArrowLongRightRed;
                break;
            default:
                throw new IllegalStateException("invalid mGrabbedState: " + mGrabbedState);
        }
        mCurrentArrow.setBounds(0, arrowY,
                                arrowW, arrowY + arrowH);
        mCurrentArrow.draw(canvas);

        // Debugging: uncomment this to draw an arc showing whether or not
        // the moving "dial" is aligned correctly with the background
        // artwork.
        // canvas.drawCircle(mKnobX, mKnobY, mKnobRadius, mTmpPaint);

        // Update m{Green,Red}Handle{X,Y}
        computeHandlePositions();

        // Draw the various handle icons (depending on the current grabbed state.)
        // We draw the dimples first, then the icons centered on top.

        // We also stash away a copy of each handle's last known bounds
        // (to be used for hit detection; see the DOWN event handling.)

        drawCentered(mDimple, canvas, mGreenHandleX, mGreenHandleY);
        if (mGrabbedState != RED_HANDLE_GRABBED) {
            drawCentered(mAnswerIcon, canvas, mGreenHandleX, mGreenHandleY);
            mAnswerIcon.copyBounds(mGreenHandleBounds);
        } else {
            // Green handle is gone; don't
            mGreenHandleBounds.setEmpty();
        }

        drawCentered(mDimple, canvas, mRedHandleX, mRedHandleY);
        if (mGrabbedState != GREEN_HANDLE_GRABBED) {
            drawCentered(mRejectIcon, canvas, mRedHandleX, mRedHandleY);
            mRejectIcon.copyBounds(mRedHandleBounds);
        } else {
            mRedHandleBounds.setEmpty();
        }

        // One blank dimple in the center:
        drawDimple(0, canvas);
        // ... and more dimples beyond the two handles:
        drawDimple(2 * mIconOffsetRadians, canvas);
        drawDimple(-2 * mIconOffsetRadians, canvas);
        drawDimple(3 * mIconOffsetRadians, canvas);
        drawDimple(-3 * mIconOffsetRadians, canvas);

        // TODO: time/profile this method, optimize it to make sure we can
        // get a good frame rate while dragging/rotating.
    }

    /**
     * Handle touch screen events.
     *
     * @param event The motion event.
     * @return True if the event was handled, false otherwise.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (DBG) log("onTouchEvent: " + event);

        int eventX = (int) event.getX();
        int eventY = (int) event.getY();

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            onDownEvent(eventX, eventY);
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            onMoveEvent(eventX, eventY);
        } else if ((event.getAction() == MotionEvent.ACTION_UP)
                   || (event.getAction() == MotionEvent.ACTION_CANCEL)) {
            onUpEvent(eventX, eventY);
        }

        // TODO: initial attempt to throttle touch events
        //
        // Crude but effective solution to the problem that the framework
        // sends us touch events as fast as we can accept them: simply
        // sleep in the UI thread when receiving a touch event.
        //
        // Unfortunately this doesn't help.  Sleeping for ~50 msec
        // makes the frame rate noticeably worse.  Sleeping for 10 msec
        // has no noticeable effect at all.
        //
        // Our problem actually isn't frame rate anyway; it's the *latency*
        // you see when you move your finger and the onscreen widget lags
        // behind.  If you're dragging quickly across the screen, I think
        // the problem is simply that one draw() cycle takes long enough
        // that your finger may have already moved a noticeable amount by
        // the time the screen gets updated.
        //
        // if (event.getAction() == MotionEvent.ACTION_MOVE) {
        //     log("THROTTLE!");
        //     // 50 msec: way too long
        //     // 10 msec: hard to notice any change
        //     SystemClock.sleep(10);
        // }

        return true;
    }

    /**
     * Handle a DOWN touch event.
     */
    private void onDownEvent(int eventX, int eventY) {
        if (DBG) log("DOWN...");

        mDownX = eventX;
        mDownY = eventY;
        mDeltaX = mDeltaY = 0;

        // if (DBG) log("* Hit detection:");
        // if (DBG) log("*   - event: " + eventX + " , " + eventY);
        // if (DBG) log("*   - Green handle bounds: " + mGreenHandleBounds);
        // if (DBG) log("*   - Green handle x/y: " + mGreenHandleX + " , " + mGreenHandleY);
        // if (DBG) log("*   - Red handle bounds: " + mRedHandleBounds);
        // if (DBG) log("*   - Red handle x/y: " + mRedHandleX + " , " + mRedHandleY);

        // Check for a hit on either handle.

        // TODO: rather than a straight contains() call, we should
        // probably allow some slop, like maybe some percentage of the
        // height or width.
        // Implementation notes for "compareWithSlop()":
        //   use a temp working rect that only gets created once
        //   set() that rect based on the passed-in rect
        //   inset() that rect by some negative amount
        //      e.g. mTempRect.inset(-width / 4, -height / 4);
        //   Then use mTempRect.contains() to test for a hit

        if (mGreenHandleBounds.contains(eventX, eventY)) {
            log("- HIT! on green handle...");
            mGrabbedState = GREEN_HANDLE_GRABBED;
            vibrate(VIBRATE_SHORT);
        } else if (mRedHandleBounds.contains(eventX, eventY)) {
            log("- HIT! on red handle...");
            mGrabbedState = RED_HANDLE_GRABBED;
            vibrate(VIBRATE_SHORT);
        } else {
            if (DBG) log("- Missed!");
            mGrabbedState = NOTHING_GRABBED;
        }

        invalidate();
    }

    /**
     * Handle a MOVE touch event.
     */
    private void onMoveEvent(int eventX, int eventY) {
        if (DBG) log("MOVE...");

        // TODO: check if the user hasn't moved very much (i.e. < 3
        // pixels?)  since last time, and if so *discard* this event and
        // don't invalidate().

        if (mGrabbedState != NOTHING_GRABBED) {
            mDeltaX = eventX - mDownX;
            mDeltaY = eventY - mDownY;
            if (DBG) log("- delta: " + mDeltaX + " , " + mDeltaY
                         + "  (mHandleTrackRadius = " + mHandleTrackRadius + ")");

            // Compute the amount of handle rotation based on the
            // user's drag.
            // Basically we convert the current drag location into polar
            // coordinates, and update mDialTheta to be the angle of the
            // current drag location.

            float normalizedDeltaX = (float) mDeltaX / (float) mHandleTrackRadius;
            if (DBG) log("- normalizedDeltaX: " + normalizedDeltaX);
            normalizedDeltaX = MathUtils.constrain(normalizedDeltaX, -1.0f, 1.0f);
            if (DBG) log("- Clamped normalizedDeltaX: " + normalizedDeltaX);

            float normalizedDeltaY = 1.0f - ((float) (mDeltaY) / (float) mHandleTrackRadius);
            if (DBG) log("- normalizedDeltaY: " + normalizedDeltaY);
            normalizedDeltaY = MathUtils.constrain(normalizedDeltaY, 0.0f, 1.0f);
            if (DBG) log("- Clamped normalizedDeltaY: " + normalizedDeltaY);

            // We don't actually need the radius (for now at least).
            //   float r = MathUtils.mag(normalizedDeltaX, normalizedDeltaY);
            //   if (DBG) log("- r = " + r);
            // But TODO: consider checking if the user has moved too far
            // off of the correct "track", and if so don't move the
            // handle, and passibly abort the drag.

            float newTheta = (float) (Math.atan2(normalizedDeltaY, normalizedDeltaX)
                                      - Math.PI / 2.0);
            if (DBG) log("- newTheta = " + newTheta + " radians = "
                         + Math.toDegrees(newTheta) + " degrees)");
            mDialTheta = newTheta;

            // TODO: maybe do a very short vibration for every 20 or 30
            // degrees of rotation?

            // If they've rotated far enough, do the action!

            // Rather than looking at mDialTheta, we trigger simply by
            // looking at the X position of the jog handle.  (We trigger
            // an action when the handle gets close enough to the edge of
            // the screen.)

            computeHandlePositions();

            final int width = getWidth();
            final int EDGE_THRESHOLD = (int) (EDGE_THRESHOLD_DIP * mDensity + 0.5f);
            if (DBG) log("    (width = " + width + ", EDGE_THRESHOLD = " + EDGE_THRESHOLD + ")");

            if ((mGrabbedState == GREEN_HANDLE_GRABBED)
                && (mGreenHandleX > width - EDGE_THRESHOLD)) {
                if (DBG) log("- TRIGGER: positive!");
                vibrate(VIBRATE_LONG);
                mGrabbedState = NOTHING_GRABBED;  // Don't process any further MOVE events
                answer();
            } else if ((mGrabbedState == RED_HANDLE_GRABBED)
                       && (mRedHandleX < EDGE_THRESHOLD)) {
                if (DBG) log("- TRIGGER: negative!");
                vibrate(VIBRATE_LONG);
                mGrabbedState = NOTHING_GRABBED;  // Don't process any further MOVE events
                reject();
            }
            invalidate();
        } else {
            if (DBG) log("- nothing grabbed, ignoring move");
        }
    }

    /**
     * Handle an UP or CANCEL touch event.
     */
    private void onUpEvent(int eventX, int eventY) {
        if (DBG) log("UP/CANCEL...");

        // TODO: animate knob back to center

        reset();
        invalidate();
    }

    /**
     * Resets the rotating knob to its initial state.
     */
    private void reset() {
        mDialTheta = 0;
        mGrabbedState = NOTHING_GRABBED;
        mDeltaX = mDeltaY = 0;
    }

    /**
     * Updates the handle X/Y positions based on mDialTheta.
     */
    private void computeHandlePositions() {
        if (DBG) log("- computeHandlePositions()...");

        // Here's the position of the handle at the very top of its arc:
        final int handleApogeeX = getWidth() / 2;
        final int handleApogeeY = mKnobY - mHandleTrackRadius;

        // Set the handle X/Y positions based on the current rotation amount.
        // Note mDialTheta=0 means the dial is at the very top, so that's why
        // sin(theta) affects the X axis and cos(theta) affects Y.

        // Green handle:

        float greenHandleTheta = mDialTheta + mIconOffsetRadians;
        float xComponent = (float) -Math.sin(greenHandleTheta);
        float yComponent = 1.0f - (float) Math.cos(greenHandleTheta);
        // if (DBG) log("    components: " + xComponent + " , " + yComponent);

        xComponent *= mHandleTrackRadius;
        yComponent *= mHandleTrackRadius;
        // if (DBG) log("        scaled: " + xComponent + " , " + yComponent);

        mGreenHandleX = handleApogeeX + (int) xComponent;
        mGreenHandleY = handleApogeeY + (int) yComponent;

        if (DBG) log("==> GREEN: mDialTheta = "
                     + Math.toDegrees(mDialTheta) + " degrees; "
                     + "greenHandleTheta = "
                     + Math.toDegrees(greenHandleTheta) + " degrees; "
                     + "handle pos = " + mGreenHandleX + " , " + mGreenHandleY);

        // Red handle:

        float redHandleTheta = mDialTheta - mIconOffsetRadians;
        xComponent = (float) -Math.sin(redHandleTheta);
        yComponent = 1.0f - (float) Math.cos(redHandleTheta);
        // if (DBG) log("    components: " + xComponent + " , " + yComponent);

        xComponent *= mHandleTrackRadius;
        yComponent *= mHandleTrackRadius;
        // if (DBG) log("        scaled: " + xComponent + " , " + yComponent);

        mRedHandleX = handleApogeeX + (int) xComponent;
        mRedHandleY = handleApogeeY + (int) yComponent;

        if (DBG) log("==> RED: mDialTheta = "
                     + Math.toDegrees(mDialTheta) + " degrees; "
                     + "redHandleTheta = "
                     + Math.toDegrees(redHandleTheta) + " degrees; "
                     + "handle pos = " + mRedHandleX + " , " + mRedHandleY);
    }

    /*
     * Draws a dimple, offset by the angle offsetTheta, relative to the
     * current dial position mDialTheta.
     */
    private void drawDimple(float offsetTheta, Canvas canvas) {
        // Here's the position of the handle at the very top of its arc:
        final int handleApogeeX = getWidth() / 2;
        final int handleApogeeY = mKnobY - mHandleTrackRadius;

        float dimpleTheta = mDialTheta + offsetTheta;

        float xComponent = (float) -Math.sin(dimpleTheta);
        float yComponent = 1.0f - (float) Math.cos(dimpleTheta);
        // if (DBG) log("    components: " + xComponent + " , " + yComponent);

        xComponent *= mHandleTrackRadius;
        yComponent *= mHandleTrackRadius;
        // if (DBG) log("        scaled: " + xComponent + " , " + yComponent);

        int dimpleX = handleApogeeX + (int) xComponent;
        int dimpleY = handleApogeeY + (int) yComponent;

        // if (DBG) log("==> drawDimple: mDialTheta = "
        //             + Math.toDegrees(mDialTheta) + " degrees; "
        //             + "dimpleTheta = "
        //             + Math.toDegrees(dimpleTheta) + " degrees; "
        //             + "dimple pos = " + dimpleX + " , " + dimpleY);

        drawCentered(mDimple, canvas, dimpleX, dimpleY);
    }

    //
    // "Answer" and "Reject" actions.
    //
    // For now, just call the corresponding InCallTouchUi method.
    // TODO: use a more general callback mechanism here to make this
    // widget reusable.
    //

    private void answer() {
        if (mInCallTouchUi != null) {
            mInCallTouchUi.answerIncomingCall();
        } else {
            Log.e(LOG_TAG, "Got 'answer' gesture but mInCallTouchUi is null");
        }
    }

    private void reject() {
        if (mInCallTouchUi != null) {
            mInCallTouchUi.rejectIncomingCall();
        } else {
            Log.e(LOG_TAG, "Got 'reject' gesture but mInCallTouchUi is null");
        }
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        if (mVibrator == null) {
            mVibrator = new Vibrator();
        }
        mVibrator.vibrate(duration);
    }

    /**
     * Sets the bounds of the specified Drawable so that it's centered
     * on the point (x,y), then draws it onto the specified canvas.
     * TODO: is there already a utility method somewhere for this?
     */
    private void drawCentered(Drawable d, Canvas c, int x, int y) {
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();

        // if (DBG) log("--> drawCentered: " + x + " , " + y + "; intrinsic " + w + " x " + h);
        d.setBounds(x - (w / 2), y - (h / 2),
                    x + (w / 2), y + (h / 2));
        d.draw(c);
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
