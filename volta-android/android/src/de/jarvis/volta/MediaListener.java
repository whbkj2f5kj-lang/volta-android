// Android-Port von Volta OS: noetig, damit Volta die laufende Musik anderer Apps sehen darf
// (Android gibt Mediensitzungen nur an Apps mit Benachrichtigungszugriff). Tut sonst nichts.
// GPL-3.0-or-later
package de.jarvis.volta;

import android.service.notification.NotificationListenerService;

public class MediaListener extends NotificationListenerService {
}
