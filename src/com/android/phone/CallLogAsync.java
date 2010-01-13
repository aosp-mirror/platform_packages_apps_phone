/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.CallLog.Calls;
import android.util.Log;
import com.android.internal.telephony.CallerInfo;

/**
 * Class to access the call logs database asynchronously since
 * database ops can take a long time depending on the system's load.
 * It uses AsyncTask which has its own thread pool.
 *
 * <pre class="prettyprint">
 * Typical usage:
 * ==============
 *
 *  // From an activity...
 *  CallLogAsync log = new CallLogAsync();
 *
 *  CallLogAsync.AddCallArgs args = new CallLogAsync.AddCallArgs(
 *      this, ci, number, presentation, type, timestamp, duration);
 *
 *  // Does not block.
 *  log.addCall(args);
 * </pre>
 *
 */

/* package */ class CallLogAsync {
    private static final String TAG = "CallLogAsync";

    /**
     * Parameter object to hold the args to add a call in the call log DB.
     */
    /* package */ static class AddCallArgs {
        /**
         * @param ci               CallerInfo.
         * @param number           To be logged.
         * @param presentation     Of the number.
         * @param callType         The type of call (e.g INCOMING_TYPE). @see
         *                         android.provider.CallLog for the list of values.
         * @param timestamp        Of the call (millisecond since epoch).
         * @param durationInMillis Of the call (millisecond).
         */
        AddCallArgs(Context context,
                    CallerInfo ci,
                    String number,
                    int presentation,
                    int callType,
                    long timestamp,
                    long durationInMillis) {
            // Note that the context is passed each time. We could
            // have stored it in a member but we've run into a bunch
            // of memory leaks in the past that resulted from storing
            // references to contexts in places that were long lived
            // when the contexts were expected to be short lived. For
            // example, if you initialize this class with an Activity
            // instead of an Application the Activity can't be GCed
            // until this class can, and Activities tend to hold
            // references to large amounts of RAM for things like the
            // bitmaps in their views.
            //
            // Having hit more than a few of those bugs in the past
            // we've grown cautious of storing references to Contexts
            // when it's not very clear that the thing holding the
            // references is tightly tied to the Context, for example
            // Views the Activity is displaying.

            this.context = context;
            this.ci = ci;
            this.number = number;
            this.presentation = presentation;
            this.callType = callType;
            this.timestamp = timestamp;
            this.durationInSec = (int)(durationInMillis / 1000);
        }
        // Since the members are accessed directly, we don't use the
        // mXxxx notation.
        public final Context context;
        public final CallerInfo ci;
        public final String number;
        public final int presentation;
        public final int callType;
        public final long timestamp;
        public final int durationInSec;
    }

    /**
     * Non blocking version of CallLog.addCall(...)
     */
    /* package */ void addCall(AddCallArgs args) {
        new AddCallTask().execute(args);
    }


    /**
     * AsyncTask to save calls in the DB.
     */
    private class AddCallTask extends AsyncTask<AddCallArgs, Void, Uri[]> {
        protected Uri[] doInBackground(AddCallArgs... callList) {
            int count = callList.length;
            Uri[] result = new Uri[count];
            for (int i = 0; i < count; i++) {
                AddCallArgs c = callList[i];

                // May block.
                result[i] = Calls.addCall(c.ci, c.context, c.number, c.presentation,
                                          c.callType, c.timestamp, c.durationInSec);
            }
            return result;
        }

        // Perform a simple sanity check to make sure the call was
        // written in the database. Typically there is only one result
        // per call so it is easy to identify which one failed.
        protected void onPostExecute(Uri[] result) {
            for (Uri uri : result) {
                if (uri == null) {
                    Log.e(TAG, "Failed to write call to the log.");
                }
            }
        }
    }
}
