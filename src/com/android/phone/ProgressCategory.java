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

import com.android.internal.telephony.Phone;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import java.util.Map;

public class ProgressCategory extends PreferenceCategory {

    private boolean mProgress = false;
    private String mTitle = null;

    public ProgressCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_progress_category);
    }

    @Override
    public void onBindView(View view) {
        super.onBindView(view);

        View progressBar = view.findViewById(R.id.scanning_progress);

        int visibility = mProgress ? View.VISIBLE : View.INVISIBLE;
        progressBar.setVisibility(visibility);

        TextView titleBar = (TextView)view.findViewById(R.id.title);

        if (mTitle != null) {
            titleBar.setText(mTitle);
        }
        titleBar.setVisibility(View.VISIBLE);
    }

    /**
     * Turn on/off the progress indicator.
     *
     * @param progressOn whether or not the progress should be displayed
     */
    public void setProgress(boolean progressOn) {
        mProgress = progressOn;
        notifyChanged();
    }

    /**
     * Sets the title
     */
    public void setTitle(String title) {
        mTitle = title;
        notifyChanged();
    }
}
