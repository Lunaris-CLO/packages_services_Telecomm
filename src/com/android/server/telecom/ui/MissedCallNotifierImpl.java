/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.ui;

import static android.Manifest.permission.READ_PHONE_STATE;
import static android.app.admin.DevicePolicyResources.Strings.Telecomm.NOTIFICATION_MISSED_WORK_CALL_TITLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.app.admin.DevicePolicyManager;
import android.content.AsyncQueryHandler;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telecom.CallerInfo;
import android.telecom.Log;
import android.telecom.Logging.Runnable;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.Constants;
import com.android.server.telecom.DefaultDialerCache;
import com.android.server.telecom.DeviceIdleControllerAdapter;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.R;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.components.TelecomBroadcastReceiver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a notification for calls that the user missed (neither answered nor rejected).
 *
 * TODO: Make TelephonyManager.clearMissedCalls call into this class.
 */
public class MissedCallNotifierImpl extends CallsManagerListenerBase implements MissedCallNotifier {

    public interface MissedCallNotifierImplFactory {
        MissedCallNotifier makeMissedCallNotifierImpl(Context context,
                PhoneAccountRegistrar phoneAccountRegistrar,
                DefaultDialerCache defaultDialerCache,
                DeviceIdleControllerAdapter deviceIdleControllerAdapter,
                FeatureFlags featureFlags);
    }

    public interface NotificationBuilderFactory {
        Notification.Builder getBuilder(Context context);
    }

    private static class DefaultNotificationBuilderFactory implements NotificationBuilderFactory {
        public DefaultNotificationBuilderFactory() {}

        @Override
        public Notification.Builder getBuilder(Context context) {
            return new Notification.Builder(context);
        }
    }

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.NUMBER_PRESENTATION,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    private static final String CALL_LOG_WHERE_CLAUSE = "type=" + Calls.MISSED_TYPE +
            " AND new=1" +
            " AND is_read=0";

    public static final int CALL_LOG_COLUMN_ID = 0;
    public static final int CALL_LOG_COLUMN_NUMBER = 1;
    public static final int CALL_LOG_COLUMN_NUMBER_PRESENTATION = 2;
    public static final int CALL_LOG_COLUMN_DATE = 3;
    public static final int CALL_LOG_COLUMN_DURATION = 4;
    public static final int CALL_LOG_COLUMN_TYPE = 5;

    private static final int MISSED_CALL_NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_TAG = MissedCallNotifierImpl.class.getSimpleName();
    private static final String MISSED_CALL_POWER_SAVE_REASON = "missed-call";

    private final Context mContext;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final NotificationManager mNotificationManager;
    private final NotificationBuilderFactory mNotificationBuilderFactory;
    private final DefaultDialerCache mDefaultDialerCache;
    private final DeviceIdleControllerAdapter mDeviceIdleControllerAdapter;
    private UserHandle mCurrentUserHandle;

    // Used to guard access to mMissedCallCounts
    private final Object mMissedCallCountsLock = new Object();
    // Used to track the number of missed calls.
    private final Map<UserHandle, Integer> mMissedCallCounts;

    private Set<UserHandle> mUsersToLoadAfterBootComplete = new ArraySet<>();
    private FeatureFlags mFeatureFlags;

    public MissedCallNotifierImpl(Context context, PhoneAccountRegistrar phoneAccountRegistrar,
            DefaultDialerCache defaultDialerCache,
            DeviceIdleControllerAdapter deviceIdleControllerAdapter,
            FeatureFlags featureFlags) {
        this(context, phoneAccountRegistrar, defaultDialerCache,
                new DefaultNotificationBuilderFactory(), deviceIdleControllerAdapter, featureFlags);
    }

    public MissedCallNotifierImpl(Context context,
            PhoneAccountRegistrar phoneAccountRegistrar,
            DefaultDialerCache defaultDialerCache,
            NotificationBuilderFactory notificationBuilderFactory,
            DeviceIdleControllerAdapter deviceIdleControllerAdapter,
            FeatureFlags featureFlags) {
        mContext = context;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mDeviceIdleControllerAdapter = deviceIdleControllerAdapter;
        mDefaultDialerCache = defaultDialerCache;

        mNotificationBuilderFactory = notificationBuilderFactory;
        mMissedCallCounts = new ArrayMap<>();
        mFeatureFlags = featureFlags;
    }

    /** Clears missed call notification and marks the call log's missed calls as read. */
    @Override
    public void clearMissedCalls(UserHandle userHandle) {
        // If the default dialer is showing the missed call notification then it will modify the
        // call log and we don't have to do anything here.
        String dialerPackage = getDefaultDialerPackage(userHandle);
        if (!shouldManageNotificationThroughDefaultDialer(dialerPackage, userHandle)) {
            markMissedCallsAsRead(userHandle);
        }
        cancelMissedCallNotification(userHandle);
    }

    private void markMissedCallsAsRead(final UserHandle userHandle) {
        AsyncTask.execute(new Runnable("MCNI.mMCAR", null /*lock*/) {
            @Override
            public void loggedRun() {
                // Clear the list of new missed calls from the call log.
                ContentValues values = new ContentValues();
                values.put(Calls.NEW, 0);
                values.put(Calls.IS_READ, 1);
                StringBuilder where = new StringBuilder();
                where.append(Calls.NEW);
                where.append(" = 1 AND ");
                where.append(Calls.TYPE);
                where.append(" = ?");
                try {
                    Uri callsUri = ContentProvider
                            .maybeAddUserId(Calls.CONTENT_URI, userHandle.getIdentifier());
                    mContext.getContentResolver().update(callsUri, values,
                            where.toString(), new String[]{ Integer.toString(Calls.
                            MISSED_TYPE) });
                } catch (IllegalArgumentException e) {
                    Log.w(this, "ContactsProvider update command failed", e);
                }
            }
        }.prepare());
    }

    private String getDefaultDialerPackage(UserHandle userHandle) {
        String dialerPackage = mDefaultDialerCache.getDefaultDialerApplication(
                userHandle.getIdentifier());
        if (TextUtils.isEmpty(dialerPackage)) {
            return null;
        }
        return dialerPackage;
    }

    /**
     * Returns the missed-call notification intent to send to the default dialer for the given user.
     * Note, the passed in userHandle is always the non-managed user for SIM calls (multi-user
     * calls). In this case we return the default dialer for the logged in user. This is never the
     * managed (work profile) dialer.
     *
     * For non-multi-user calls (3rd party phone accounts), the passed in userHandle is the user
     * handle of the phone account. This could be a managed user. In that case we return the default
     * dialer for the given user which could be a managed (work profile) dialer.
     */
    private Intent getShowMissedCallIntentForDefaultDialer(String dialerPackage) {
        return new Intent(TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION)
            .setPackage(dialerPackage);
    }

    private boolean shouldManageNotificationThroughDefaultDialer(String dialerPackage,
            UserHandle userHandle) {
        if (TextUtils.isEmpty(dialerPackage)) return false;

        Intent intent = getShowMissedCallIntentForDefaultDialer(dialerPackage);
        if (intent == null) {
            return false;
        }

        List<ResolveInfo> receivers = mContext.getPackageManager()
                .queryBroadcastReceiversAsUser(intent, 0, userHandle.getIdentifier());
        return receivers.size() > 0;
    }

    /**
     * For dialers that manage missed call handling themselves, we must temporarily add them to the
     * power save exemption list, as they must perform operations such as modifying the call log and
     * power save restrictions can cause these types of operations to not complete (sometimes
     * causing ANRs).
     */
    private Bundle exemptFromPowerSavingTemporarily(String dialerPackage, UserHandle handle) {
        if (TextUtils.isEmpty(dialerPackage)) {
            return null;
        }
        BroadcastOptions bopts = BroadcastOptions.makeBasic();
        long duration = Timeouts.getDialerMissedCallPowerSaveExemptionTimeMillis(
                mContext.getContentResolver());
        mDeviceIdleControllerAdapter.exemptAppTemporarilyForEvent(dialerPackage, duration,
                handle.getIdentifier(), MISSED_CALL_POWER_SAVE_REASON);
        bopts.setTemporaryAppWhitelistDuration(duration);
        return bopts.toBundle();
    }

    private void sendNotificationThroughDefaultDialer(String dialerPackage, CallInfo callInfo,
            UserHandle userHandle, int missedCallCount, @Nullable Uri uri) {
        Intent intent = getShowMissedCallIntentForDefaultDialer(dialerPackage)
            .setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(TelecomManager.EXTRA_CLEAR_MISSED_CALLS_INTENT,
                    createClearMissedCallsPendingIntent(userHandle))
            .putExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, missedCallCount)
            .putExtra(TelecomManager.EXTRA_CALL_LOG_URI, uri)
            .putExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER,
                    callInfo == null ? null : callInfo.getPhoneNumber())
            .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    callInfo == null ? null : callInfo.getPhoneAccountHandle());
        if (missedCallCount == 1 && callInfo != null) {
            final Uri handleUri = callInfo.getHandle();
            String handle = handleUri == null ? null : handleUri.getSchemeSpecificPart();

            if (!TextUtils.isEmpty(handle) && !TextUtils.equals(handle,
                    mContext.getString(R.string.handle_restricted))) {
                intent.putExtra(TelecomManager.EXTRA_CALL_BACK_INTENT,
                        createCallBackPendingIntent(handleUri, userHandle));
            }
        }

        Log.i(this, "sendNotificationThroughDefaultDialer; count=%d, dialerPackage=%s",
                missedCallCount, intent.getPackage());
        Bundle options = exemptFromPowerSavingTemporarily(dialerPackage, userHandle);
        mContext.sendBroadcastAsUser(intent, userHandle, READ_PHONE_STATE, options);
    }

    /**
     * Create a system notification for the missed call.
     *
     * @param callInfo The missed call.
     */
    @Override
    public void showMissedCallNotification(@NonNull CallInfo callInfo, @Nullable Uri uri) {
        final PhoneAccountHandle phoneAccountHandle = callInfo.getPhoneAccountHandle();
        final PhoneAccount phoneAccount =
                mPhoneAccountRegistrar.getPhoneAccountUnchecked(phoneAccountHandle);
        UserHandle userHandle;
        if (phoneAccount != null &&
                phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_MULTI_USER)) {
            userHandle = mCurrentUserHandle;
        } else {
            userHandle = phoneAccountHandle.getUserHandle();
        }
        showMissedCallNotification(callInfo, userHandle, uri);
    }

    private void showMissedCallNotification(@NonNull CallInfo callInfo, UserHandle userHandle,
            @Nullable Uri uri) {
        int missedCallCounts;
        synchronized (mMissedCallCountsLock) {
            Integer currentCount = mMissedCallCounts.get(userHandle);
            missedCallCounts = currentCount == null ? 0 : currentCount;
            missedCallCounts++;
            mMissedCallCounts.put(userHandle, missedCallCounts);
        }

        Log.i(this, "showMissedCallNotification: userHandle=%d, missedCallCount=%d",
                userHandle.getIdentifier(), missedCallCounts);

        String dialerPackage = getDefaultDialerPackage(userHandle);
        if (shouldManageNotificationThroughDefaultDialer(dialerPackage, userHandle)) {
            sendNotificationThroughDefaultDialer(dialerPackage, callInfo, userHandle,
                    missedCallCounts, uri);
            return;
        }

        final String titleText;
        final String expandedText;  // The text in the notification's line 1 and 2.

        // Display the first line of the notification:
        // 1 missed call: <caller name || handle>
        // More than 1 missed call: <number of calls> + "missed calls"
        if (missedCallCounts == 1) {
            expandedText = getNameForMissedCallNotification(callInfo);

            CallerInfo ci = callInfo.getCallerInfo();
            if (ci != null && ci.userType == CallerInfo.USER_TYPE_WORK) {
                titleText = mContext.getSystemService(DevicePolicyManager.class).getResources()
                        .getString(NOTIFICATION_MISSED_WORK_CALL_TITLE, () ->
                                mContext.getString(R.string.notification_missedWorkCallTitle));
            } else {
                titleText = mContext.getString(R.string.notification_missedCallTitle);
            }
        } else {
            titleText = mContext.getString(R.string.notification_missedCallsTitle);
            expandedText =
                    mContext.getString(R.string.notification_missedCallsMsg, missedCallCounts);
        }

        // Create a public viewable version of the notification, suitable for display when sensitive
        // notification content is hidden.
        // We use user's context here to make sure notification is badged if it is a managed user.
        Context contextForUser = getContextForUser(userHandle);
        Notification.Builder publicBuilder = mNotificationBuilderFactory.getBuilder(contextForUser);
        publicBuilder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setColor(mContext.getResources().getColor(R.color.theme_color))
                .setWhen(callInfo.getCreationTimeMillis())
                .setShowWhen(true)
                // Show "Phone" for notification title.
                .setContentTitle(mContext.getText(R.string.userCallActivityLabel))
                // Notification details shows that there are missed call(s), but does not reveal
                // the missed caller information.
                .setContentText(titleText)
                .setContentIntent(createCallLogPendingIntent(userHandle))
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsPendingIntent(userHandle));

        // Create the notification suitable for display when sensitive information is showing.
        Notification.Builder builder = mNotificationBuilderFactory.getBuilder(contextForUser);
        builder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setColor(mContext.getResources().getColor(R.color.theme_color))
                .setWhen(callInfo.getCreationTimeMillis())
                .setShowWhen(true)
                .setContentTitle(titleText)
                .setContentText(expandedText)
                .setContentIntent(createCallLogPendingIntent(userHandle))
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsPendingIntent(userHandle))
                // Include a public version of the notification to be shown when the missed call
                // notification is shown on the user's lock screen and they have chosen to hide
                // sensitive notification information.
                .setPublicVersion(publicBuilder.build())
                .setChannelId(NotificationChannelManager.CHANNEL_ID_MISSED_CALLS);

        Uri handleUri = callInfo.getHandle();
        String handle = callInfo.getHandleSchemeSpecificPart();

        // Add additional actions when there is only 1 missed call, like call-back and SMS.
        if (missedCallCounts == 1) {
            Log.d(this, "Add actions with number %s.", Log.piiHandle(handle));

            if (!TextUtils.isEmpty(handle)
                    && !TextUtils.equals(handle, mContext.getString(R.string.handle_restricted))) {
                builder.addAction(R.drawable.ic_phone_24dp,
                        mContext.getString(R.string.notification_missedCall_call_back),
                        createCallBackPendingIntent(handleUri, userHandle));

                if (canRespondViaSms(callInfo)) {
                    builder.addAction(R.drawable.ic_message_24dp,
                            mContext.getString(R.string.notification_missedCall_message),
                            createSendSmsFromNotificationPendingIntent(handleUri, userHandle));
                }
            }

            Bitmap photoIcon = callInfo.getCallerInfo() == null ?
                    null : callInfo.getCallerInfo().cachedPhotoIcon;
            if (photoIcon != null) {
                builder.setLargeIcon(photoIcon);
            } else {
                Drawable photo = callInfo.getCallerInfo() == null ?
                        null : callInfo.getCallerInfo().cachedPhoto;
                if (photo != null && photo instanceof BitmapDrawable) {
                    builder.setLargeIcon(((BitmapDrawable) photo).getBitmap());
                }
            }
        } else {
            Log.d(this, "Suppress actions. handle: %s, missedCalls: %d.", Log.piiHandle(handle),
                    missedCallCounts);
        }

        Notification notification = builder.build();
        configureLedOnNotification(notification);

        Log.i(this, "Adding missed call notification for %s.", Log.pii(callInfo.getHandle()));
        long token = Binder.clearCallingIdentity();
        try {
            mNotificationManager.notifyAsUser(
                    NOTIFICATION_TAG, MISSED_CALL_NOTIFICATION_ID, notification, userHandle);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }


    /** Cancels the "missed call" notification. */
    private void cancelMissedCallNotification(UserHandle userHandle) {
        // Reset the number of missed calls to 0.
        synchronized(mMissedCallCountsLock) {
            mMissedCallCounts.put(userHandle, 0);
        }

        String dialerPackage = getDefaultDialerPackage(userHandle);
        if (shouldManageNotificationThroughDefaultDialer(dialerPackage, userHandle)) {
            sendNotificationThroughDefaultDialer(dialerPackage, null, userHandle,
                    /* missedCallCount= */ 0, /* uri= */ null);
            return;
        }

        long token = Binder.clearCallingIdentity();
        try {
            mNotificationManager.cancelAsUser(NOTIFICATION_TAG, MISSED_CALL_NOTIFICATION_ID,
                    userHandle);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the name to use in the missed call notification.
     */
    private String getNameForMissedCallNotification(@NonNull CallInfo callInfo) {
        String handle = callInfo.getHandleSchemeSpecificPart();
        String name = callInfo.getName();

        if (!TextUtils.isEmpty(handle)) {
            String formattedNumber = PhoneNumberUtils.formatNumber(handle,
                    getCurrentCountryIso(mContext));

            // The formatted number will be null if there was a problem formatting it, but we can
            // default to using the unformatted number instead (e.g. a SIP URI may not be able to
            // be formatted.
            if (!TextUtils.isEmpty(formattedNumber)) {
                handle = formattedNumber;
            }
        }

        if (!TextUtils.isEmpty(name) && TextUtils.isGraphic(name)) {
            return name;
        } else if (!TextUtils.isEmpty(handle)) {
            // A handle should always be displayed LTR using {@link BidiFormatter} regardless of the
            // content of the rest of the notification.
            // TODO: Does this apply to SIP addresses?
            BidiFormatter bidiFormatter = BidiFormatter.getInstance();
            return bidiFormatter.unicodeWrap(handle, TextDirectionHeuristics.LTR);
        } else {
            // Use "unknown" if the call is unidentifiable.
            return mContext.getString(R.string.unknown);
        }
    }

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in based on the
     *      network location.  If the network location does not exist, fall back to the locale
     *      setting.
     */
    @VisibleForTesting
    public String getCurrentCountryIso(Context context) {
        // Without framework function calls, this seems to be the most accurate location service
        // we can rely on.
        final TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String countryIso;
        try {
            countryIso = telephonyManager.getNetworkCountryIso().toUpperCase();
        } catch (UnsupportedOperationException ignored) {
            countryIso = null;
        }

        if (countryIso == null) {
            countryIso = Locale.getDefault().getCountry();
            Log.w(this, "No CountryDetector; falling back to countryIso based on locale: "
                    + countryIso);
        }
        return countryIso;
    }

    /**
     * Creates a new pending intent that sends the user to the call log.
     *
     * @return The pending intent.
     */
    private PendingIntent createCallLogPendingIntent(UserHandle userHandle) {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType(Calls.CONTENT_TYPE);

        TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(mContext);
        taskStackBuilder.addNextIntent(intent);

        return taskStackBuilder.getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE, null, userHandle);
    }

    /**
     * Creates an intent to be invoked when the missed call notification is cleared.
     */
    private PendingIntent createClearMissedCallsPendingIntent(UserHandle userHandle) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_CLEAR_MISSED_CALLS, null, userHandle);
    }

    /**
     * Creates an intent to be invoked when the user opts to "call back" from the missed call
     * notification.
     *
     * @param handle The handle to call back.
     */
    private PendingIntent createCallBackPendingIntent(Uri handle, UserHandle userHandle) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_BACK_FROM_NOTIFICATION, handle,
                userHandle);
    }

    /**
     * Creates an intent to be invoked when the user opts to "send sms" from the missed call
     * notification.
     */
    private PendingIntent createSendSmsFromNotificationPendingIntent(Uri handle,
            UserHandle userHandle) {
        return createTelecomPendingIntent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, handle.getSchemeSpecificPart(), null),
                userHandle);
    }

    /**
     * Creates generic pending intent from the specified parameters to be received by
     * {@link TelecomBroadcastIntentProcessor}.
     *
     * @param action The intent action.
     * @param data The intent data.
     */
    private PendingIntent createTelecomPendingIntent(String action, Uri data,
            UserHandle userHandle) {
        Intent intent = new Intent(action, data, mContext, TelecomBroadcastReceiver.class);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, userHandle);
        return PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Configures a notification to emit the blinky notification light.
     */
    private void configureLedOnNotification(Notification notification) {
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;
    }

    private boolean canRespondViaSms(@NonNull CallInfo callInfo) {
        // Only allow respond-via-sms for "tel:" calls.
        return callInfo.getHandle() != null &&
                PhoneAccount.SCHEME_TEL.equals(callInfo.getHandle().getScheme());
    }

    @Override
    public void reloadAfterBootComplete(final CallerInfoLookupHelper callerInfoLookupHelper,
            CallInfoFactory callInfoFactory) {
        if (!mUsersToLoadAfterBootComplete.isEmpty()) {
            for (UserHandle handle : mUsersToLoadAfterBootComplete) {
                Log.i(this, "reloadAfterBootComplete: user=%d", handle.getIdentifier());
                reloadFromDatabase(callerInfoLookupHelper, callInfoFactory, handle);
            }
            mUsersToLoadAfterBootComplete.clear();
        } else {
            Log.i(this, "reloadAfterBootComplete: no user(s) to check; skipping reload.");
        }
    }
    /**
     * Adds the missed call notification on startup if there are unread missed calls.
     */
    @Override
    public void reloadFromDatabase(final CallerInfoLookupHelper callerInfoLookupHelper,
            CallInfoFactory callInfoFactory, final UserHandle userHandle) {
        Log.d(this, "reloadFromDatabase: user=%d", userHandle.getIdentifier());
        if (TelecomSystem.getInstance() == null || !TelecomSystem.getInstance().isBootComplete()) {
            if (!mUsersToLoadAfterBootComplete.contains(userHandle)) {
                Log.i(this, "reloadFromDatabase: Boot not yet complete -- call log db may not be "
                        + "available. Deferring loading until boot complete for user %d",
                        userHandle.getIdentifier());
                mUsersToLoadAfterBootComplete.add(userHandle);
            }
            return;
        }

        // instantiate query handler
        AsyncQueryHandler queryHandler = new AsyncQueryHandler(mContext.getContentResolver()) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                Log.d(MissedCallNotifierImpl.this, "onQueryComplete()...");
                if (cursor != null) {
                    try {
                        synchronized(mMissedCallCountsLock) {
                            mMissedCallCounts.remove(userHandle);
                        }
                        while (cursor.moveToNext()) {
                            // Get data about the missed call from the cursor
                            final String handleString = cursor.getString(CALL_LOG_COLUMN_NUMBER);
                            final Uri uri;
                            if (mFeatureFlags.addCallUriForMissedCalls()){
                                uri = Calls.CONTENT_URI.buildUpon().appendPath(
                                        Long.toString(cursor.getInt(CALL_LOG_COLUMN_ID))).build();
                            }else{
                                uri = null;
                            }
                            final int presentation =
                                    cursor.getInt(CALL_LOG_COLUMN_NUMBER_PRESENTATION);
                            final long date = cursor.getLong(CALL_LOG_COLUMN_DATE);

                            final Uri handle;
                            if (presentation != Calls.PRESENTATION_ALLOWED
                                    || TextUtils.isEmpty(handleString)) {
                                handle = null;
                            } else {
                                // TODO: Remove the assumption that numbers are SIP or TEL only.
                                handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(handleString) ?
                                        PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL,
                                                handleString, null);
                            }

                            callerInfoLookupHelper.startLookup(handle,
                                    new CallerInfoLookupHelper.OnQueryCompleteListener() {
                                        @Override
                                        public void onCallerInfoQueryComplete(Uri queryHandle,
                                                CallerInfo info) {
                                            if (!Objects.equals(queryHandle, handle)) {
                                                Log.w(MissedCallNotifierImpl.this,
                                                        "CallerInfo query returned with " +
                                                                "different handle.");
                                                return;
                                            }
                                            if (info == null ||
                                                    info.getContactDisplayPhotoUri() == null) {
                                                // If there is no photo or if the caller info is
                                                // null, just show the notification.
                                                CallInfo callInfo = callInfoFactory.makeCallInfo(
                                                        info, null, handle, date);
                                                showMissedCallNotification(callInfo, userHandle,
                                                        /* uri= */ uri);
                                            }
                                        }

                                        @Override
                                        public void onContactPhotoQueryComplete(Uri queryHandle,
                                                CallerInfo info) {
                                            if (!Objects.equals(queryHandle, handle)) {
                                                Log.w(MissedCallNotifierImpl.this,
                                                        "CallerInfo query for photo returned " +
                                                                "with different handle.");
                                                return;
                                            }
                                            CallInfo callInfo = callInfoFactory.makeCallInfo(
                                                    info, null, handle, date);
                                            showMissedCallNotification(callInfo, userHandle,
                                                    /* uri= */ uri);
                                        }
                                    }
                            );
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
        };

        // setup query spec, look for all Missed calls that are new.
        Uri callsUri =
                ContentProvider.maybeAddUserId(Calls.CONTENT_URI, userHandle.getIdentifier());
        // start the query
        queryHandler.startQuery(0, null, callsUri, CALL_LOG_PROJECTION,
                CALL_LOG_WHERE_CLAUSE, null, Calls.DEFAULT_SORT_ORDER);
    }

    @Override
    public void setCurrentUserHandle(UserHandle currentUserHandle) {
        mCurrentUserHandle = currentUserHandle;
    }

    private Context getContextForUser(UserHandle user) {
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
        } catch (NameNotFoundException e) {
            // Default to mContext, not finding the package system is running as is unlikely.
            return mContext;
        }
    }
}
