package com.android.phone;

import com.android.internal.telephony.CommandException;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.WindowManager;

import java.util.ArrayList;

interface  TimeConsumingPreferenceListener {
    public void onStarted(Preference preference, boolean reading);
    public void onFinished(Preference preference, boolean reading);
    public void onError(Preference preference, int error);
    public void onException(Preference preference, CommandException exception);
}

public class TimeConsumingPreferenceActivity extends PreferenceActivity
                        implements TimeConsumingPreferenceListener, DialogInterface.OnClickListener,
                        DialogInterface.OnCancelListener {
    private static final String LOG_TAG = "TimeConsumingPreferenceActivity";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private static final int BUSY_READING_DIALOG = 100;
    private static final int BUSY_SAVING_DIALOG = 200;

    static final int EXCEPTION_ERROR = 300;
    static final int RESPONSE_ERROR = 400;
    static final int RADIO_OFF_ERROR = 500;
    static final int FDN_CHECK_FAILURE = 600;

    private final ArrayList<String> mBusyList=new ArrayList<String> ();

    protected boolean mIsForeground = false;

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == BUSY_READING_DIALOG || id == BUSY_SAVING_DIALOG) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle(getText(R.string.updating_title));
            dialog.setIndeterminate(true);

            switch(id) {
                case BUSY_READING_DIALOG:
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(this);
                    dialog.setMessage(getText(R.string.reading_settings));
                    return dialog;
                case BUSY_SAVING_DIALOG:
                    dialog.setCancelable(false);
                    dialog.setMessage(getText(R.string.updating_settings));
                    return dialog;
            }
            return null;
        }

        if (id == RESPONSE_ERROR || id == RADIO_OFF_ERROR || id == EXCEPTION_ERROR
                || id == FDN_CHECK_FAILURE) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);

            int msgId;
            int titleId = R.string.error_updating_title;

            switch (id) {
                case RESPONSE_ERROR:
                    msgId = R.string.response_error;
                    // Set Button 2, tells the activity that the error is
                    // recoverable on dialog exit.
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case RADIO_OFF_ERROR:
                    msgId = R.string.radio_off_error;
                    // Set Button 3
                    b.setNeutralButton(R.string.close_dialog, this);
                    break;
                case FDN_CHECK_FAILURE:
                    msgId = R.string.fdn_only_error;
                    // Set Button 2
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case EXCEPTION_ERROR:
                default:
                    msgId = R.string.exception_error;
                    // Set Button 3, tells the activity that the error is
                    // not recoverable on dialog exit.
                    b.setNeutralButton(R.string.close_dialog, this);
                    break;
            }

            b.setTitle(getText(titleId));
            b.setMessage(getText(msgId));
            b.setCancelable(false);
            AlertDialog dialog = b.create();

            // make the dialog more obvious by blurring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;
        }
        return null;
    }

    @Override
    public void onResume() {
        super.onResume();
        mIsForeground = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
    }

    public void onStarted(Preference preference, boolean reading) {
        if (DBG) dumpState();
        if (DBG) Log.d(LOG_TAG, "onStarted, preference=" + preference.getKey()
                + ", reading=" + reading);
        mBusyList.add(preference.getKey());

        if (mIsForeground) {
              if (reading) {
                  showDialog(BUSY_READING_DIALOG);
              } else {
                  showDialog(BUSY_SAVING_DIALOG);
              }
        }

    }

    public void onFinished(Preference preference, boolean reading) {
        if (DBG) dumpState();
        if (DBG) Log.d(LOG_TAG, "onFinished, preference=" + preference.getKey()
                + ", reading=" + reading);
        mBusyList.remove(preference.getKey());

        if (mBusyList.isEmpty()) {
            if (reading) {
                dismissDialogSafely(BUSY_READING_DIALOG);
            } else {
                dismissDialogSafely(BUSY_SAVING_DIALOG);
            }
        }
    }

    public void onError(Preference preference, int error) {
        if (DBG) dumpState();
        if (DBG) Log.d(LOG_TAG, "onError, preference=" + preference.getKey() + ", error=" + error);

        if (mIsForeground) {
            showDialog(error);
        }
    }

    public void onException(Preference preference, CommandException exception) {
        if (exception.getCommandError() == CommandException.Error.FDN_CHECK_FAILURE) {
            onError(preference, FDN_CHECK_FAILURE);
        } else {
            preference.setEnabled(false);
            onError(preference, EXCEPTION_ERROR);
        }
    }
    public void onCancel(DialogInterface dialog) {
        if (DBG) dumpState();
        finish();
    }

    private void dismissDialogSafely(int id) {
        try {
            dismissDialog(id);
        } catch (IllegalArgumentException e) {
            // This is expected in the case where we were in the background
            // at the time we would normally have shown the dialog, so we didn't
            // show it.
        }
    }

    void dumpState() {
        Log.d(LOG_TAG, "dumpState begin");
        for (String key : mBusyList) {
            Log.d(LOG_TAG, "mBusyList: key=" + key);
        }
        Log.d(LOG_TAG, "dumpState end");
    }
}
