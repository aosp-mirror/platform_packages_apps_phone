/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.ContentUris;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.CallerInfoAsyncQuery.OnQueryCompleteListener;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.ITelephonyServiceCallBack;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.ContactsAsyncHelper.OnImageLoadCompleteListener;

/**
 * Helper class for loading caller info.
 */
public class CallerInfoQueryHelper {

    private static final boolean DBG = true;
    private static final String LOG_TAG = "CallerInfoQueryHelper";

    public interface OnCallerInfoQueryListener {
        public void onCallerInfoQueryComplete(String name, String number,
                String typeofnumber, Drawable photo,
                int presentation, ITelephonyServiceCallBack cb);
    }

    /**
     * Uri being used to load contact photo for mPhoto. Will be null when nothing is being loaded,
     * or a photo is already loaded.
     */
    private Uri mLoadingPersonUri;

    // Track the state for the photo.
    private ContactsAsyncHelper.ImageTracker mPhotoTracker;

    private Context mContext;

    /**
     * Sent when it takes too long (MESSAGE_DELAY msec) to load a contact photo for the given
     * person, at which we just start showing the default avatar picture instead of the person's
     * one. Note that we will *not* cancel the ongoing query and eventually replace the avatar
     * with the person's photo, when it is available anyway.
     */
    private static final int MESSAGE_SHOW_UNKNOWN_PHOTO = 0;

    private static final int MESSAGE_DELAY = 500; // msec

    // Tokens for ContactsAsyncHelper.startObtainPhotoAsync
    private static final int TOKEN_UPDATE_PHOTO_FOR_CALL_STATE = 0;
    private static final int TOKEN_DO_NOTHING = 1;

    /**
     * Used with {@link ContactsAsyncHelper#startObtainPhotoAsync(int, Context, Uri,
     * ContactsAsyncHelper.OnImageLoadCompleteListener, Object)}
     */
    private static class AsyncLoadCookie {
        public final CallerInfo ci;
        public final Connection connection;
        public final OnCallerInfoQueryListener listener;
        public final String name;
        public final String number;
        public final String typeofnumber;
        public final int presentation;
        public final ITelephonyServiceCallBack cb;
        public AsyncLoadCookie(CallerInfo ci,
                Connection connection,
                OnCallerInfoQueryListener listener,
                String name,
                String number,
                String typeofnumber,
                int presentation,
                ITelephonyServiceCallBack cb) {
            this.ci = ci;
            this.connection = connection;
            this.listener = listener;
            this.name = name;
            this.number = number;
            this.typeofnumber = typeofnumber;
            this.presentation = presentation;
            this.cb = cb;
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MESSAGE_SHOW_UNKNOWN_PHOTO:
                    if (msg.obj instanceof AsyncLoadCookie) {
                        AsyncLoadCookie asyncLoadCookie = (AsyncLoadCookie)msg.obj;
                        OnCallerInfoQueryListener listener = asyncLoadCookie.listener;
                        if (listener != null) {
                            listener.onCallerInfoQueryComplete(asyncLoadCookie.name,
                                    asyncLoadCookie.number,
                                    asyncLoadCookie.typeofnumber,
                                    null, // get the unknown picture in client
                                    asyncLoadCookie.presentation,
                                    asyncLoadCookie.cb);
                        }
                    }
                    break;
            }
        }
    };

    /** The singleton instance. */
    private static CallerInfoQueryHelper sInstance;

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static CallerInfoQueryHelper init(Context context) {
        synchronized (CallerInfoQueryHelper.class) {
            if (sInstance == null) {
                sInstance = new CallerInfoQueryHelper(context);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Private constructor for static class
     */
    private CallerInfoQueryHelper(Context context) {
        // create a new object to track the state for the photo.
        mPhotoTracker = new ContactsAsyncHelper.ImageTracker();
        mContext = context;
    }

    /**
     * Starts an asynchronous caller description load. After finishing the load,
     * {@link OnCallerDescriptionUpdateListener#onCallerDescriptionUpdate(int token, CallerDescription callerDsp)}
     * will be called.
     */
    public void startObtainCallerDescription(Call call, OnCallerInfoQueryListener listener,
            ITelephonyServiceCallBack cb) {
        if (DBG) log("startObtainCallerDescription()");

        if (PhoneUtils.isConferenceCall(call)) {
            log("It's a conference call, get caller info by UI");
            return;
        }

        Connection conn = PhoneUtils.getConnectionFromCall(call);

        if (conn == null) {
            log("Connection is null, using default values.");
            // if the connection is null, we run through the behaviour
            // we had in the past, which breaks down into trivial steps
            // with the current implementation of getCallerInfo and
            // updateMainInfo.
            CallerInfo info = PhoneUtils.getCallerInfo(mContext, null /* conn */);
            getCallerDescription(info, PhoneConstants.PRESENTATION_ALLOWED,
                    false, conn, listener, cb);
        } else {
            log("  - CONN: " + conn + ", state = " + conn.getState());
            int presentation = conn.getNumberPresentation();

            // Make sure that we only make a new query when the current
            // callerinfo differs from what we've been requested to display.
            boolean runQuery = true;
            Object userData = conn.getUserData();
            if (userData instanceof PhoneUtils.CallerInfoToken) {
                runQuery = mPhotoTracker.isDifferentImageRequest(
                        ((PhoneUtils.CallerInfoToken) userData).currentInfo);
            } else {
                runQuery = mPhotoTracker.isDifferentImageRequest(conn);
            }

            if (runQuery) {
                log("Starting CallerInfo query...");
                PhoneUtils.CallerInfoToken info = PhoneUtils.startGetCallerInfo(mContext, conn,
                        mCallerInfoQueryCallBack,
                        new AsyncLoadCookie(null, conn, listener,
                                null, null, null, presentation, cb));
                getCallerDescription(info.currentInfo, presentation,
                        !info.isFinal, conn, listener, cb);
            } else {
                // No need to fire off a new query.  We do still need
                // to update the display, though (since we might have
                // previously been in the "conference call" state.)
                log("- updateMainCallStatus: using data we already have...");
                if (userData instanceof CallerInfo) {
                    CallerInfo ci = (CallerInfo) userData;
                    // Update CNAP information if Phone state change occurred
                    ci.cnapName = conn.getCnapName();
                    ci.numberPresentation = conn.getNumberPresentation();
                    ci.namePresentation = conn.getCnapNamePresentation();
                    log("- displayMainCallStatus: CNAP data from Connection: "
                            + "CNAP name=" + ci.cnapName
                            + ", Number/Name Presentation=" + ci.numberPresentation);
                    log("   ==> Got CallerInfo; updating display: ci = " + ci);
                    getCallerDescription(ci, presentation, false, conn, listener, cb);
                } else if (userData instanceof PhoneUtils.CallerInfoToken){
                    CallerInfo ci = ((PhoneUtils.CallerInfoToken) userData).currentInfo;
                    log("- displayMainCallStatus: CNAP data from Connection: "
                            + "CNAP name=" + ci.cnapName
                            + ", Number/Name Presentation=" + ci.numberPresentation);
                    log("   ==> Got CallerInfoToken; updating display: ci = " + ci);
                    getCallerDescription(ci, presentation, true, conn, listener, cb);
                } else {
                    log("runQuery was false, "
                            + "but we didn't have a cached CallerInfo object! userData = "
                            + userData);
                    // TODO: Do we need to do something here?
                }
            }
        }
    }

    public void getCallerDescription(CallerInfo info, int presentation,
            boolean isTemporary, Connection conn, OnCallerInfoQueryListener listener,
            ITelephonyServiceCallBack cb) {
        log("getCallerDescription(" + info + ")\npresentation:" +
                presentation + " isTemporary:" + isTemporary);

        boolean displayNameIsNumber = false;
        String displayName = null;
        String displayNumber = null;
        String label = null;
        Uri personUri = null;
        Drawable photo = null;

        if (info != null) {
            // It appears that there is a small change in behaviour with the
            // PhoneUtils' startGetCallerInfo whereby if we query with an
            // empty number, we will get a valid CallerInfo object, but with
            // fields that are all null, and the isTemporary boolean input
            // parameter as true.

            // In the past, we would see a NULL callerinfo object, but this
            // ends up causing null pointer exceptions elsewhere down the
            // line in other cases, so we need to make this fix instead. It
            // appears that this was the ONLY call to PhoneUtils
            // .getCallerInfo() that relied on a NULL CallerInfo to indicate
            // an unknown contact.

            // Currently, info.phoneNumber may actually be a SIP address, and
            // if so, it might sometimes include the "sip:" prefix.  That
            // prefix isn't really useful to the user, though, so strip it off
            // if present.  (For any other URI scheme, though, leave the
            // prefix alone.)
            // TODO: It would be cleaner for CallerInfo to explicitly support
            // SIP addresses instead of overloading the "phoneNumber" field.
            // Then we could remove this hack, and instead ask the CallerInfo
            // for a "user visible" form of the SIP address.
            String number = info.phoneNumber;
            if ((number != null) && number.startsWith("sip:")) {
                number = number.substring(4);
            }

            if (TextUtils.isEmpty(info.name)) {
                // No valid "name" in the CallerInfo, so fall back to
                // something else.
                if (!TextUtils.isEmpty(info.cnapName)) {
                    // No name, but we do have a valid CNAP name, so use that.
                    displayName = info.cnapName;
                    info.name = info.cnapName;
                    displayNumber = number;
                } else if (!TextUtils.isEmpty(number)) {
                    // No name; all we have is a number.  This is the typical
                    // case when an incoming call doesn't match any contact,
                    // or if you manually dial an outgoing number using the
                    // dialpad.

                    // Promote the phone number up to the "name" slot:
                    displayName = number;
                    displayNameIsNumber = true;

                    // ...and use the "number" slot for a geographical description
                    // string if available (but only for incoming calls.)
                    if ((conn != null) && (conn.isIncoming())) {
                        // TODO (CallerInfoAsyncQuery cleanup): Fix the CallerInfo
                        // query to only do the geoDescription lookup in the first
                        // place for incoming calls.
                        displayNumber = info.geoDescription;  // may be null
                    }

                    log("  ==>  no name; falling back to number: displayName '"
                            + displayName + "', displayNumber '" + displayNumber + "'");
                }
            } else {
                displayName = info.name;
                displayNumber = number;
                label = info.phoneLabel;
                log("  ==>  name is present in CallerInfo: displayName '"
                        + displayName + "', displayNumber '" + displayNumber + "'");
            }

            personUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, info.person_id);
            log("- got personUri: '" + personUri
                    + "', based on info.person_id: " + info.person_id);
        }

        boolean updateNameAndNumber = true;
        // If the new info is just a phone number, check to make sure it's not less
        // information than what's already being displayed.
        if (displayNameIsNumber) {
            // If the new number is the same as the number already displayed, ignore it
            // because that means we're also already displaying a name for it.
            // If the new number is the same as the name currently being displayed, only
            // display if the new number is longer (ie, has formatting).
            String visiblePhoneNumber = null;
            if (displayNumber != null) {
                visiblePhoneNumber = displayNumber;
            }
            if (visiblePhoneNumber != null &&
                    PhoneNumberUtils.compare(visiblePhoneNumber, displayName)) {
                updateNameAndNumber = false;
            }
        }

        if (updateNameAndNumber) {
            if (conn.getCall().isGeneric()) {
                displayName = mContext.getString(R.string.card_title_in_call);
                displayNumber = null;
                label = null;
            }
        }

        // Update mPhoto
        // if the temporary flag is set, we know we'll be getting another call after
        // the CallerInfo has been correctly updated.  So, we can skip the image
        // loading until then.

        // If the photoResource is filled in for the CallerInfo, (like with the
        // Emergency Number case), then we can just set the photo image without
        // requesting for an image load. Please refer to CallerInfoAsyncQuery.java
        // for cases where CallerInfo.photoResource may be set.  We can also avoid
        // the image load step if the image data is cached.
        if (isTemporary && (info == null || !info.isCachedPhotoCurrent)) {
            photo = null;
        } else if (info != null && info.photoResource != 0){
            photo = mContext.getResources().getDrawable(info.photoResource);
        } else if ((info != null) && info.isCachedPhotoCurrent) {
            if (info.cachedPhoto != null) {
                photo = info.cachedPhoto;
            } else {
                photo = mContext.getResources().getDrawable(R.drawable.picture_unknown);
            }
        } else {
            if (personUri == null) {
                Log.w(LOG_TAG, "personPri is null. Just use Unknown picture.");
                photo = mContext.getResources().getDrawable(R.drawable.picture_unknown);
            } else if (personUri.equals(mLoadingPersonUri)) {
                log("The requested Uri (" + personUri + ") is being loaded already."
                        + " Ignoret the duplicate load request.");
            } else {
                // Remember which person's photo is being loaded right now so that we won't issue
                // unnecessary load request multiple times, which will mess up animation around
                // the contact photo.
                mLoadingPersonUri = personUri;

                photo = null;
                AsyncLoadCookie asyncLoadCookie = new AsyncLoadCookie(info, conn, listener,
                        displayName, displayNumber, label, presentation, cb);
                // Load the image with a callback to update the image state.
                // When the load is finished, onImageLoadComplete() will be called.
                ContactsAsyncHelper.startObtainPhotoAsync(TOKEN_UPDATE_PHOTO_FOR_CALL_STATE,
                        mContext, personUri, mImageLoadCallBack, asyncLoadCookie);

                // If the image load is too slow, we show a default avatar icon afterward.
                // If it is fast enough, this message will be canceled on onImageLoadComplete().
                mHandler.removeMessages(MESSAGE_SHOW_UNKNOWN_PHOTO);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_SHOW_UNKNOWN_PHOTO,
                        asyncLoadCookie), MESSAGE_DELAY);
            }
        }

        // Notify listener caller description been updated.
      if (listener != null) {
          listener.onCallerInfoQueryComplete(displayName,
                  displayNumber, label, photo, presentation, cb);
      }

    }

    /**
     * Callback for ContactsAsyncHelper.startObtainPhotoAsync
     */
    private OnImageLoadCompleteListener mImageLoadCallBack = new OnImageLoadCompleteListener() {

        /**
         * Implemented for ContactsAsyncHelper.OnImageLoadCompleteListener interface.
         * make sure that the call state is reflected after the image is loaded.
         */
        public void onImageLoadComplete(int token, Drawable photo, Bitmap photoIcon, Object cookie) {
            mHandler.removeMessages(MESSAGE_SHOW_UNKNOWN_PHOTO);
            if (mLoadingPersonUri != null) {
                // Start sending view notification after the current request being done.
                // New image may possibly be available from the next phone calls.
                //
                // TODO: may be nice to update the image view again once the newer one
                // is available on contacts database.
                PhoneUtils.sendViewNotificationAsync(mContext, mLoadingPersonUri);
            } else {
                // This should not happen while we need some verbose info if it happens..
                Log.w(LOG_TAG, "Person Uri isn't available while Image is successfully loaded.");
            }
            mLoadingPersonUri = null;

            AsyncLoadCookie asyncLoadCookie = (AsyncLoadCookie) cookie;
            CallerInfo callerInfo = asyncLoadCookie.ci;
            OnCallerInfoQueryListener listener = asyncLoadCookie.listener;
            ITelephonyServiceCallBack callback = asyncLoadCookie.cb;

            callerInfo.cachedPhoto = photo;
            callerInfo.cachedPhotoIcon = photoIcon;
            callerInfo.isCachedPhotoCurrent = true;

            Drawable showPhoto = null;
            // Note: previously ContactsAsyncHelper has done this job.
            // TODO: We will need fade-in animation. See issue 5236130.
            if (photo != null) {
                showPhoto = photo;
            } else if (photoIcon != null) {
//                showImage(imageView, photoIcon);
            } else {
                showPhoto = mContext.getResources().getDrawable(R.drawable.picture_unknown);
            }

            // Notify listener caller description been updated.
            if (listener != null) {
                listener.onCallerInfoQueryComplete(asyncLoadCookie.name, asyncLoadCookie.number,
                        asyncLoadCookie.typeofnumber, showPhoto,
                        asyncLoadCookie.presentation, callback);
            }
        }
    };

    /**
     * Callback for startGetCallerInfo
     */
    private OnQueryCompleteListener mCallerInfoQueryCallBack = new OnQueryCompleteListener() {
        /**
         * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
         * refreshes the CallCard data when it called.  If called with this
         * class itself, it is assumed that we have been waiting for the ringtone
         * and direct to voicemail settings to update.
         */
        @Override
        public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
            log("CallerInfo query complete");
            if (cookie instanceof Long) {
                log("-posting missed call notification");

//                PhoneGlobals.getInstance().notificationMgr.notifyMissedCall(ci.name,
//                        ci.phoneNumber, ci.phoneLabel, ci.cachedPhoto, ci.cachedPhotoIcon,
//                        ((Long)cookie).longValue());
            } else if (cookie instanceof AsyncLoadCookie) {
                log("-updating state for call..");

                AsyncLoadCookie asyncLoadCookie = (AsyncLoadCookie)cookie;
                Connection conn = asyncLoadCookie.connection;
                ITelephonyServiceCallBack callback = asyncLoadCookie.cb;
                PhoneUtils.CallerInfoToken cit =
                       PhoneUtils.startGetCallerInfo(mContext, conn, mCallerInfoQueryCallBack, null);

                int presentation = PhoneConstants.PRESENTATION_ALLOWED;
                if (conn != null) presentation = conn.getNumberPresentation();
                log("- onQueryComplete: presentation=" + presentation
                        + ", contactExists=" + ci.contactExists);

                OnCallerInfoQueryListener listener = asyncLoadCookie.listener;
                // Depending on whether there was a contact match or not, we want to pass in
                // different CallerInfo (for CNAP). Therefore if ci.contactExists then use the ci
                // passed in. Otherwise, regenerate the CIT from the Connection and use the
                // CallerInfo from there.
                if (ci.contactExists) {
                    getCallerDescription(ci, PhoneConstants.PRESENTATION_ALLOWED,
                            false, conn, listener, callback);
                } else {
                    getCallerDescription(cit.currentInfo, presentation,
                            false, conn, listener, callback);
                }
            }
        }
    };

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
