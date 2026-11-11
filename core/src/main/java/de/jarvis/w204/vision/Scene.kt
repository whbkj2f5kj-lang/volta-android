package de.jarvis.w204.vision
// Minimal shared detection types from the W204 technical basis. No dashboard or lane UI.
enum class ObjKind { CAR, TRUCK, MOTORBIKE, BICYCLE, PERSON }
data class Detection(val kind: ObjKind, val l: Float, val t: Float, val r: Float, val b: Float, val score: Float)
