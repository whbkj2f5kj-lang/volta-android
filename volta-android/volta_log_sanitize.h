// Redact credentials before either the exported log or Android logcat sees them.
#pragma once
#include <QRegularExpression>
#include <QString>
inline QString voltaSafeLog(QString text)
{
    for (const char *name : {"MAPTILER_KEY", "MAPBOX_TOKEN", "TOMTOM_KEY", "OWM_KEY", "OPENAI_API_KEY"}) {
        const QString value = QString::fromUtf8(qgetenv(name)).trimmed();
        if (value.size() >= 4) text.replace(value, QStringLiteral("[redacted]"));
    }
    static const QRegularExpression query(QStringLiteral("([?&](?:key|access_token|token|api_key|apikey)=)[^&\\s\\\"'<>]+"), QRegularExpression::CaseInsensitiveOption);
    text.replace(query, QStringLiteral("\\1[redacted]"));
    return text;
}
