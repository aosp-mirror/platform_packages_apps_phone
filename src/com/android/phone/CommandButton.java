/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.util.AttributeSet;
import android.util.Config;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * Special style of Button used in the Phone app, with an icon
 * and a text label underneath.
 * Watch out: this inherits from LinearLayout, *not* Button or
 * TextView.
 *
 * TODO: find a better name for this class.  Something like
 * IconicButton (for example) would be more clear.
 *
 * TODO: It might be good to move this widget into the framework
 * eventually, since an "iconic button with text underneath" sounds
 * like it would be useful to lots of developers.  (Or, it might be
 * better to just extend ImageButton to have an optional label that
 * would be visible next to the image, with configurable layout.)
 */
public class CommandButton extends LinearLayout {
    private static final String TAG = "PHONE/CommandButton";

    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    private TextView mTitle;
    private int mTitleResource;
    private ImageView mIcon;
    private int mIconResource;

    public CommandButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void init() {
        if (LOCAL_LOGV) Log.v(TAG, "init()...");

        mTitle = (TextView) findViewById(R.id.title);
        mIcon = (ImageView) findViewById(R.id.icon);

        setFocusable(true);

        //if (LOCAL_LOGV) Log.v(TAG, "    - END of init():");
        //String orientation = "UNKNOWN!";
        //if (getOrientation() == HORIZONTAL) orientation = "HORIZONTAL";
        //if (getOrientation() == VERTICAL) orientation = "VERTICAL";
        //if (LOCAL_LOGV) Log.v(TAG, "    - orientation = " + orientation);
    }


    void setTitle(int resId) {
        if (LOCAL_LOGV) Log.v(TAG, "setTitle(resId " + resId + ")...");

        if (resId != 0 && resId == mTitleResource) {
            return;
        }

        mTitleResource = resId;

        String title = null;
        if (resId != 0) {
            title = getContext().getString(resId);
        }

        setTitle(title);
    }

    void setTitle(String title) {
        if (LOCAL_LOGV) Log.v(TAG, "setTitle(string " + title + ")...");

        mTitle.setText(title);
    }

    void setIcon(int resid) {
        if (LOCAL_LOGV) Log.v(TAG, "setIcon(resid " + resid + ")...");

        if (resid == mIconResource) {
            return;
        }

        mIconResource = resid;
        mIcon.setImageResource(resid);

        mIcon.setVisibility((resid == 0) ? View.GONE : View.VISIBLE);
    }
}
