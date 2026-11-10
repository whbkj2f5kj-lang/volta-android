// Fahrzeug-Technik aus dem Dashboard W204 (OBD, nur lesend) als Baustein fuer Volta OS
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } } } }
rootProject.name = "volta-core"
