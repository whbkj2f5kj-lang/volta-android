import QtQuick
QtObject {
    enum PersistentCookiesPolicy { NoPersistentCookies, AllowPersistentCookies, ForcePersistentCookies }
    enum HttpCacheType { MemoryHttpCache, DiskHttpCache, NoCache }
    property string storageName
    property bool offTheRecord: false
    property string httpUserAgent
    property string httpAcceptLanguage
    property int persistentCookiesPolicy: 0
    property int httpCacheType: 0
    property int httpCacheMaximumSize: 0
    property string persistentStoragePath
    property string cachePath
    property string downloadPath
    property bool spellCheckEnabled: false
    signal downloadRequested(var download)
    signal downloadFinished(var download)
    signal presentNotification(var notification)
    function clearHttpCache() {}
}
