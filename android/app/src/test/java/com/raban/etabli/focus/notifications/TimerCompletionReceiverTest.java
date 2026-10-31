// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.notifications;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNotificationManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class TimerCompletionReceiverTest {
    private final Application app = RuntimeEnvironment.getApplication();

    private NotificationManager manager() {
        new NotificationScheduler(app);
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void complete() {
        new TimerCompletionReceiver().onReceive(app, new Intent().putExtra("kind", "Focus"));
    }

    @Test public void deniedPermissionLeavesTheNotificationTrayUnchanged() {
        NotificationManager manager = manager();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        complete();
        assertNull(shadowOf(manager).getNotification(1001));
    }

    @Test public void grantedPermissionPostsTheCompletedBlock() {
        NotificationManager manager = manager();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        complete();
        Notification notification = shadowOf(manager).getNotification(1001);
        assertNotNull(notification);
        assertEquals("Focus block complete", notification.extras.getString(Notification.EXTRA_TITLE));
    }

    @Test @Config(shadows = PermissionRevokedNotificationManager.class)
    public void revocationDuringDeliveryDoesNotCrashTheReceiver() {
        manager();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        PermissionRevokedNotificationManager.attempted = false;
        complete();
        assertTrue(PermissionRevokedNotificationManager.attempted);
    }

    @Implements(NotificationManager.class)
    public static class PermissionRevokedNotificationManager extends ShadowNotificationManager {
        static boolean attempted;
        @Implementation public void notify(int id, Notification notification) {
            attempted = true;
            throw new SecurityException("Permission revoked during delivery");
        }
    }
}
