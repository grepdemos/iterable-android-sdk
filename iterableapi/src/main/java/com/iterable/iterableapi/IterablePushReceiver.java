package com.iterable.iterableapi;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.content.IntentCompat;
import android.util.Log;

/**
 * Handles processing the Receive intent from GCM called when an
 *
 * TODO: Should we add this functionality to IterablePushOpenReceiver so that it will have a default implementation?
 *      Or should we keep it simplistic and require customers to implement their own push receivers for GCM.
 * Created by davidtruong on 4/19/16.
 */
public class IterablePushReceiver extends BroadcastReceiver{

    static final String TAG = "IterablePushReceiver";

    private static final String ACTION_GCM_RECEIVE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String ACTION_GCM_REGISTRATION_INTENT = "com.google.android.c2dm.intent.REGISTRATION";

    //TODO: handle ADM - https://developer.amazon.com/public/apis/engage/device-messaging/tech-docs/04-integrating-your-app-with-adm

    @Override
    public void onReceive(Context context, Intent intent) {
        String intentAction = intent.getAction();

        if (intentAction.equals(IterableConstants.ACTION_PUSH_REGISTRATION)) {
           handlePushRegistration(context, intent);
        } else if (intentAction.equals(ACTION_GCM_RECEIVE_INTENT)) {
            handlePushReceived(context, intent);
        } else if (intentAction.equals(ACTION_GCM_REGISTRATION_INTENT)) {
            Log.d(TAG, "received GCM registration intent action");
        }
    }

    private void handlePushRegistration(Context context, Intent intent) {
        String iterableAppId = intent.getStringExtra("IterableAppId");
        String projectNumber = intent.getStringExtra("GCMProjectNumber");
        new IterablePushRegistrationGCM().execute(iterableAppId, projectNumber);
    }

    private void handlePushReceived(Context context, Intent intent) {
        //TODO: handle the case where the app was already opened in the foreground, send  in-app notif instead
        //http://stackoverflow.com/questions/3667022/checking-if-an-android-application-is-running-in-the-background
        //http://stackoverflow.com/questions/23558986/gcm-how-do-i-detect-if-app-is-open-and-if-so-pop-up-an-alert-box-instead-of-nor

        //TODO: Process deeplinks here Create an override for the defaultClass opened.
        //Deep Link Example
        //Passed in from the Push Payload: { "deepLink": "main_page" }

        context = context.getApplicationContext();

        PackageManager packageManager = context.getPackageManager();
        Intent packageIntent = packageManager.getLaunchIntentForPackage(context.getPackageName());
        ComponentName componentPackageName = packageIntent.getComponent();
        String mainClassName = componentPackageName.getClassName();
        Class mainClass = null;
        try {
            mainClass = Class.forName(mainClassName);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        //TODO: set the notification icon in a config file (set by developer)
        int iconId = context.getApplicationInfo().icon;

        if (iconId != 0) {
            //TODO: ensure that this is never null since people might call notificationBuilder.something()
            IterableNotification notificationBuilder = IterableNotification.createNotification(
                    context, intent, mainClass, iconId); //have a default for no icon.

            IterableNotification.postNotificationOnDevice(context, notificationBuilder);
        }
    }
}