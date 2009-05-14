/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

/** 
 * DTMFTwelveKeyDialerView is the view logic that the DTMFDialer uses.
 * This is really a thin wrapper around Linear Layout that intercepts
 * some user interactions to provide the correct UI behaviour for the
 * dialer.
 */
class DTMFTwelveKeyDialerView extends LinearLayout {
    
    private static final String LOG_TAG = "PHONE/DTMFTwelveKeyDialerView";
    private static final boolean DBG = false;

    private DTMFTwelveKeyDialer mDialer;
    
    public DTMFTwelveKeyDialerView (Context context) {
        super(context);
    }
    
    public DTMFTwelveKeyDialerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    void setDialer (DTMFTwelveKeyDialer dialer) {
        mDialer = dialer;
    }
    
    /** 
     * Normally we ignore everything except for the BACK and CALL keys.
     * For those, we pass them to the model (and then the InCallScreen).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DBG) log("dispatchKeyEvent(" + event + ")...");

        int keyCode = event.getKeyCode();
        if (mDialer != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_CALL:
                    return event.isDown() ? mDialer.onKeyDown(keyCode, event) : 
                        mDialer.onKeyUp(keyCode, event); 
            }
        }

        if (DBG) log("==> dispatchKeyEvent: forwarding event to the DTMFDialer");
        return super.dispatchKeyEvent(event);
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        
        int x = (int)event.getX();
        int y = (int)event.getY();
        int closestDeltaX = 0;
        int closestDeltaY = 0;
        
        ArrayList<View> touchables = getTouchables();
        int minDistance = Integer.MAX_VALUE;
        View closest = null;

        final int numTouchables = touchables.size();
        
        Rect touchableBounds = new Rect();
        
        for (int i = 0; i < numTouchables; i++) {
            View touchable = touchables.get(i);

            // get visible bounds of other view in same coordinate system
            touchable.getDrawingRect(touchableBounds);
            
            offsetDescendantRectToMyCoords(touchable, touchableBounds);

            if (touchableBounds.contains(x, y)) {
                return super.dispatchTouchEvent(event);
            } 
            
            int deltaX;
            if (x > touchableBounds.right) {
                deltaX = touchableBounds.right - 1 - x;
            } else if (x < touchableBounds.left) {
                deltaX = touchableBounds.left + 1 - x;
            } else {
                deltaX = 0;
            }
            
            int deltaY;
            if (y > touchableBounds.bottom) {
                deltaY = touchableBounds.bottom - 1 - y;
            } else if (y < touchableBounds.top) {
                deltaY = touchableBounds.top + 1 - y;
            } else {
                deltaY = 0;
            }
            
            final int distanceSquared = (deltaX * deltaX) + (deltaY * deltaY);
            if (distanceSquared < minDistance) {
                minDistance = distanceSquared;
                closest = touchable;
                closestDeltaX = deltaX;
                closestDeltaY = deltaY;
            }
        }
        
        
        if (closest != null) {
            event.offsetLocation(closestDeltaX, closestDeltaY);
            return super.dispatchTouchEvent(event);
        } else {
            return super.dispatchTouchEvent(event);
        }
    }
    
    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
    
}
