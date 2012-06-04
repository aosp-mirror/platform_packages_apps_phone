/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.view.MotionEvent;
import android.view.View;

/**
 * OnTouchListener used to shrink the "hit target" of some onscreen buttons.
 *
 * We do this for a few specific buttons which are vulnerable to
 * "false touches" because either (1) they're near the edge of the
 * screen and might be unintentionally touched while holding the
 * device in your hand, or (2) they're in the upper corners and might
 * be touched by the user's ear before the prox sensor has a chance to
 * kick in.
 *
 * TODO (new ICS layout): not sure which buttons need this yet.
 * For now, use it only with the "End call" button (which extends all
 * the way to the edges of the screen).  But we can consider doing
 * this for "Dialpad" and/or "Add call" if those turn out to be a
 * problem too.
 */
public class SmallerHitTargetTouchListener implements View.OnTouchListener {
    /**
     * Width of the allowable "hit target" as a percentage of
     * the total width of this button.
     */
    private static final int HIT_TARGET_PERCENT_X = 50;

    /**
     * Height of the allowable "hit target" as a percentage of
     * the total height of this button.
     *
     * This is larger than HIT_TARGET_PERCENT_X because some of
     * the onscreen buttons are wide but not very tall and we don't
     * want to make the vertical hit target *too* small.
     */
    private static final int HIT_TARGET_PERCENT_Y = 80;

    // Size (percentage-wise) of the "edge" area that's *not* touch-sensitive.
    private static final int X_EDGE = (100 - HIT_TARGET_PERCENT_X) / 2;
    private static final int Y_EDGE = (100 - HIT_TARGET_PERCENT_Y) / 2;
    // Min/max values (percentage-wise) of the touch-sensitive hit target.
    private static final int X_HIT_MIN = X_EDGE;
    private static final int X_HIT_MAX = 100 - X_EDGE;
    private static final int Y_HIT_MIN = Y_EDGE;
    private static final int Y_HIT_MAX = 100 - Y_EDGE;

    // True if the most recent DOWN event was a "hit".
    boolean mDownEventHit;

    /**
     * Called when a touch event is dispatched to a view. This allows listeners to
     * get a chance to respond before the target view.
     *
     * @return True if the listener has consumed the event, false otherwise.
     *         (In other words, we return true when the touch is *outside*
     *         the "smaller hit target", which will prevent the actual
     *         button from handling these events.)
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // if (DBG) log("SmallerHitTargetTouchListener: " + v + ", event " + event);

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Note that event.getX() and event.getY() are already
            // translated into the View's coordinates.  (In other words,
            // "0,0" is a touch on the upper-left-most corner of the view.)
            int touchX = (int) event.getX();
            int touchY = (int) event.getY();

            int viewWidth = v.getWidth();
            int viewHeight = v.getHeight();

            // Touch location as a percentage of the total button width or height.
            int touchXPercent = (int) ((float) (touchX * 100) / (float) viewWidth);
            int touchYPercent = (int) ((float) (touchY * 100) / (float) viewHeight);
            // if (DBG) log("- percentage:  x = " + touchXPercent + ",  y = " + touchYPercent);

            // TODO: user research: add event logging here of the actual
            // hit location (and button ID), and enable it for dogfooders
            // for a few days.  That'll give us a good idea of how close
            // to the center of the button(s) most touch events are, to
            // help us fine-tune the HIT_TARGET_PERCENT_* constants.

            if (touchXPercent < X_HIT_MIN || touchXPercent > X_HIT_MAX
                    || touchYPercent < Y_HIT_MIN || touchYPercent > Y_HIT_MAX) {
                // Missed!
                // if (DBG) log("  -> MISSED!");
                mDownEventHit = false;
                return true;  // Consume this event; don't let the button see it
            } else {
                // Hit!
                // if (DBG) log("  -> HIT!");
                mDownEventHit = true;
                return false;  // Let this event through to the actual button
            }
        } else {
            // This is a MOVE, UP or CANCEL event.
            //
            // We only do the "smaller hit target" check on DOWN events.
            // For the subsequent MOVE/UP/CANCEL events, we let them
            // through to the actual button IFF the previous DOWN event
            // got through to the actual button (i.e. it was a "hit".)
            return !mDownEventHit;
        }
    }
}