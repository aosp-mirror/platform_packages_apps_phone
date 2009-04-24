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
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils.TruncateAt;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;


/**
 * The View for each item in the {@link InCallMenuView}.
 *
 * Each in-call menu item has a text label, an optional "green LED" on/off
 * indicator below the text, and an optional icon above the text.
 * (It's really just a TextView, using "compound drawables" for
 * the indicator and icon.)
 *
 * Originally modeled after android/widget/IconMenuItemView.java.
 */
class InCallMenuItemView extends TextView {
    private static final String LOG_TAG = "PHONE/InCallMenuItemView";
    private static final boolean DBG = false;

    private boolean mIndicatorVisible;
    private boolean mIndicatorState;
    private Drawable mIndicatorDrawable;
    private Drawable mIcon;

    public InCallMenuItemView(Context context) {
        super(context);
        if (DBG) log("InCallMenuView constructor...");

        setGravity(Gravity.CENTER);

        TypedArray a =
                context.obtainStyledAttributes(
                        com.android.internal.R.styleable.MenuView);
        int textAppearance = a.getResourceId(com.android.internal.R.styleable.
                                             MenuView_itemTextAppearance, -1);
        // TODO: any other styleable attrs we need from the standard menu item style?
        a.recycle();

        setClickable(true);
        setFocusable(true);
        setTextAppearance(context, textAppearance);

        // Set the padding like the regular menu items do
        setPadding(3, getPaddingTop(), 3, getPaddingBottom());
    }

    //
    // Visibility: we only ever use the VISIBLE and GONE states.
    //

    public void setVisible(boolean isVisible) {
        setVisibility(isVisible ? VISIBLE : GONE);
    }

    public boolean isVisible() {
        return (getVisibility() == VISIBLE);
    }

    /**
     * Sets whether or not this item's "green LED" state indicator
     * should be visible.
     */
    public void setIndicatorVisible(boolean isVisible) {
        if (DBG) log("setIndicatorVisible(" + isVisible + ")...");
        mIndicatorVisible = isVisible;
        updateIndicator();
        updateCompoundDrawables();
    }

    /**
     * Turns this item's "green LED" state indicator on or off.
     */
    public void setIndicatorState(boolean onoff) {
        if (DBG) log("setIndicatorState(" + onoff + ")...");
        mIndicatorState = onoff;
        updateIndicator();
        updateCompoundDrawables();
    }

    /**
     * Sets this item's icon, to be drawn above the text label.
     */
    public void setIcon(Drawable icon) {
        if (DBG) log("setIcon(" + icon + ")...");
        mIcon = icon;
        updateCompoundDrawables();

        // If there's an icon, we'll only have enough room for one line of text.
        if (icon != null) setSingleLineMarquee();
    }

    /**
     * Sets this item's icon, to be drawn above the text label.
     */
    public void setIconResource(int resId) {
        if (DBG) log("setIconResource(" + resId + ")...");
         Drawable iconDrawable = getResources().getDrawable(resId);
         setIcon(iconDrawable);
    }

    /**
     * Updates mIndicatorDrawable based on mIndicatorVisible and mIndicatorState.
     */
    private void updateIndicator() {
        if (mIndicatorVisible) {
            int resId = mIndicatorState ? android.R.drawable.button_onoff_indicator_on
                    : android.R.drawable.button_onoff_indicator_off;
            mIndicatorDrawable = getResources().getDrawable(resId);
        } else {
            mIndicatorDrawable = null;
        }
    }

    /**
     * Installs mIcon and mIndicatorDrawable as our TextView "compound drawables",
     * and does any necessary layout tweaking depending on the presence or
     * absence of the icon or indicator.
     */
    private void updateCompoundDrawables() {
        // TODO: There are several hand-tweaked layout constants hardcoded here.
        // If we ever move this widget into the framework (and make it
        // usable from XML), be sure to move these constants to XML too.

        // If the icon is visible, add a bit of negative padding to scoot
        // it down closer to the text.
        if (mIcon != null) {
            setCompoundDrawablePadding(-10);
        }

        // Add some top/bottom padding when the indicator and/or icon are
        // visible (to add a little vertical space between the indicator
        // and the bottom of the item, or the icon and the top of the
        // item.)
        int topPadding = (mIcon != null) ? 5 : 0;
        int bottomPadding = (mIndicatorDrawable != null) ? 5 : 0;
        setPadding(0, topPadding, 0, bottomPadding);

        // TODO: topPadding seems to have no effect here.
        // Regardless of the value I use, the icon image
        // ends up right up against the top edge of the button...
        // (Maybe we're just out of room?)
        // if (DBG) log("updateCompoundDrawables: padding: top " + topPadding
        //              + ", bottom " + bottomPadding);

        setCompoundDrawablesWithIntrinsicBounds(null, mIcon, null, mIndicatorDrawable);
    }

    /**
     * Forces this menu item into "single line" mode, with marqueeing enabled.
     * This is only necessary when an icon is present, since otherwise
     * there's enough room for long labels to wrap onto two lines.
     */
    private void setSingleLineMarquee() {
        setEllipsize(TruncateAt.MARQUEE);
        setHorizontalFadingEdgeEnabled(true);
        setSingleLine(true);
    }

    @Override
    public String toString() {
        return "'" + getText() + "' (" + super.toString() + ")";
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
