/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;


/**
 * ImageView subclass used for contact photos in the in-call UI.
 *
 * For contact photos larger than 96x96, this view behaves just like a regular
 * ImageView.  But for 96x96 or smaller (i.e. the size of contact thumbnails
 * we typically get from contacts sync), apply a "blur + inset" special effect
 * rather than simply scaling up the image.  (Scaling looks terrible because
 * the onscreen ImageView is so much larger than the source image.)
 *
 * Watch out: this widget only does the "blur + inset" effect in one very
 * specific case: you must set the photo using the setImageDrawable() API,
 * *and* pass in a drawable that's an instance of BitmapDrawable.
 * (This is exactly what the in-call UI does; see CallCard.java and also
 * android.pim.ContactsAsyncHelper.)
 *
 * TODO: If we ever intend to expose this class for more general use (or move
 * it into the framework) we'll need to make this effect work for all the
 * various setImage*() calls, with any kind of drawable.
 *
 * TODO: other features to consider adding here:
 * - any special scaling / cropping behavior?
 * - special handling for the "unknown" contact photo and the "conference
     call" state?
 * - allow the whole image to be blurred or dimmed, regardless of the
 *   size of the input image (like for a call that's on hold)
 */
public class InCallContactPhoto extends ImageView {
    private static final String TAG = "InCallContactPhoto";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = false;

    /**
     * If true, enable the "blur + inset" special effect for lo-res
     * images.  (This flag provides a quick way to disable this class
     * entirely; if false, InCallContactPhoto instances will behave just
     * like plain old ImageViews.)
     */
    private static final boolean ENABLE_BLUR_INSET_EFFECT = false;

    private Drawable mPreviousImageDrawable;
    private ImageView mInsetImageView;

    public InCallContactPhoto(Context context) {
        super(context);
    }

    public InCallContactPhoto(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public InCallContactPhoto(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setInsetImageView(ImageView imageView) {
        mInsetImageView = imageView;
    }

    @Override
    public void setImageResource(int resId) {
        if (DBG) log("setImageResource(" + resId + ")...");
        // For now, at least, this method doesn't trigger any special effects
        // (see the TODO comment in the class javadoc.)
        mPreviousImageDrawable = null;
        hideInset();
        super.setImageResource(resId);
    }

    @Override
    public void setImageURI(Uri uri) {
        if (DBG) log("setImageURI(" + uri + ")...");
        // For now, at least, this method doesn't trigger any special effects
        // (see the TODO comment in the class javadoc.)
        mPreviousImageDrawable = null;
        hideInset();
        super.setImageURI(uri);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        if (DBG) log("setImageBitmap(" + bm + ")...");
        // For now, at least, this method doesn't trigger any special effects
        // (see the TODO comment in the class javadoc.)
        mPreviousImageDrawable = null;
        hideInset();
        super.setImageBitmap(bm);
    }

    @Override
    public void setImageDrawable(Drawable inputDrawable) {
        if (DBG) log("setImageDrawable(" + inputDrawable + ")...");
        long startTime = SystemClock.uptimeMillis();

        BitmapDrawable blurredBitmapDrawable = null;

        if (VDBG) log("################# setImageDrawable()... ################");
        if (VDBG) log("- this: " + this);
        if (VDBG) log("- inputDrawable: " + inputDrawable);
        if (VDBG) log("- mPreviousImageDrawable: " + mPreviousImageDrawable);

        if (inputDrawable != mPreviousImageDrawable) {

            mPreviousImageDrawable = inputDrawable;

            if (inputDrawable instanceof BitmapDrawable) {
                Bitmap inputBitmap = ((BitmapDrawable) inputDrawable).getBitmap();

                if (VDBG) log("- inputBitmap: " + inputBitmap);
                if (VDBG) log("  - dimensions: " + inputBitmap.getWidth()
                              + " x " + inputBitmap.getHeight());
                if (VDBG) log("  - config: " + inputBitmap.getConfig());
                if (VDBG) log("  - byte count: " + inputBitmap.getByteCount());

                if (!ENABLE_BLUR_INSET_EFFECT) {
                    if (DBG) log("- blur+inset disabled; no special effect.");
                    // ...and leave blurredBitmapDrawable = null so that we'll
                    // fall back to the regular ImageView behavior (see below.)
                } else if (inputBitmap == null) {
                    Log.w(TAG, "setImageDrawable: null bitmap from inputDrawable.getBitmap()!");
                    // ...and leave blurredBitmapDrawable = null so that we'll
                    // fall back to the regular ImageView behavior (see below.)
                } else if (!isLoRes(inputBitmap)) {
                    if (DBG) log("- not a lo-res bitmap; no special effect.");
                    // ...and leave blurredBitmapDrawable = null so that we'll
                    // fall back to the regular ImageView behavior (see below.)
                } else {
                    // Ok, we have a valid bitmap *and* it's lo-res.
                    // Do the blur + inset effect.
                    if (DBG) log("- got a lo-res bitmap; blurring...");
                    Bitmap blurredBitmap = BitmapUtils.createBlurredBitmap(inputBitmap);
                    if (VDBG) log("- blurredBitmap: " + blurredBitmap);
                    if (VDBG) log("  - dimensions: " + blurredBitmap.getWidth()
                                  + " x " + blurredBitmap.getHeight());
                    if (VDBG) log("  - config: " + blurredBitmap.getConfig());
                    if (VDBG) log("  - byte count: " + blurredBitmap.getByteCount());

                    blurredBitmapDrawable = new BitmapDrawable(getResources(), blurredBitmap);
                    if (DBG) log("- Created blurredBitmapDrawable: " + blurredBitmapDrawable);
                }
            } else {
                Log.w(TAG, "setImageDrawable: inputDrawable '" + inputDrawable
                      + "' is not a BitmapDrawable");
                // For now, at least, we don't trigger any special effects in
                // this case (see the TODO comment in the class javadoc.)
                // Just leave blurredBitmapDrawable = null so that we'll
                // fall back to the regular ImageView behavior (see below.)
            }

            if (blurredBitmapDrawable != null) {
                if (DBG) log("- Show the special effect!  blurredBitmapDrawable = "
                             + blurredBitmapDrawable);
                super.setImageDrawable(blurredBitmapDrawable);
                // And show the original (sharp) image in the inset.
                showInset(inputDrawable);
            } else {
                if (DBG) log("- null blurredBitmapDrawable; don't show the special effect.");
                // Otherwise,  Just fall back to the regular ImageView behavior.
                super.setImageDrawable(inputDrawable);
                hideInset();
            }
        }

        long endTime = SystemClock.uptimeMillis();
        if (DBG) log("setImageDrawable() done: *ELAPSED* = " + (endTime - startTime) + " msec");
    }

    /**
     * @return true if the specified bitmap is a lo-res contact photo
     *         (i.e. if we *should* use the blur+inset effect for this photo
     *         in the in-call UI.)
     */
    private boolean isLoRes(Bitmap bitmap) {
        // In practice, contact photos will almost always be either 96x96 (for
        // thumbnails from contacts sync) or 256x256 (if you pick a photo from
        // the gallery or camera via the contacts app.)
        //
        // So enable the blur+inset effect *only* for width = 96 or smaller.
        // (If the user somehow gets a contact to have a photo that's between
        // 97 and 255 pixels wide, that's OK, we'll just show it as-is with no
        // special effects.)
        final int LO_RES_THRESHOLD_WIDTH = 96;
        if (DBG) log("- isLoRes: checking bitmap with width " + bitmap.getWidth() + "...");
        return (bitmap.getWidth() <= LO_RES_THRESHOLD_WIDTH);
    }

    private void hideInset() {
        if (DBG) log("- hideInset()...");
        if (mInsetImageView != null) {
            mInsetImageView.setVisibility(View.GONE);
        }
    }

    private void showInset(Drawable drawable) {
        if (DBG) log("- showInset(Drawable " + drawable + ")...");
        if (mInsetImageView != null) {
            mInsetImageView.setImageDrawable(drawable);
            mInsetImageView.setVisibility(View.VISIBLE);
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
