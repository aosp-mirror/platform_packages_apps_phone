package com.android.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class ShowMissedCallNotification extends Activity 
{

	private final String TAG = "ShowMissedCallNotification";
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
                String details = null;
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		final int number=intent.getIntExtra("key_number", 1);
		if(number==1){
			details =  number+" Missed Call";
		}
		if(number>1){
			details =  number+" Missed Calls";

		}

		AlertDialog.Builder builder = new AlertDialog.Builder(ShowMissedCallNotification.this);
		builder.setMessage(details)
		.setCancelable(false)
		.setPositiveButton("VIEW", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				Intent intent = new Intent("com.android.phone.action.RECENT_CALLS");
				startActivity(intent);
				finish();
			}
		})
		.setNegativeButton("CANCEL", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.cancel();
			}
		});
		AlertDialog alert = builder.create();
		builder.show();

	}


	@Override
	protected void onStop() {
		// TODO Auto-generated method stub
		super.onStop();
		finish();

	}


}
