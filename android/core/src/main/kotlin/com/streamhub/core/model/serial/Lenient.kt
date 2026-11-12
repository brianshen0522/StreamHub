package com.streamhub.core.model.serial

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * Whole seconds that tolerate how JavaScript writes numbers.
 *
 * The server is Node: nothing in JSON or JS separates an integer from a
 * float, and a value that is whole today (`Math.round` on the way out) can be
 * `31.6` after one refactor. kotlinx refuses a fractional number for an `Int`
 * property and fails the *whole* document with it — a library shelf, or every
 * remaining line of an NDJSON stream. This rounds instead, as the Tizen client
 * does (it reads every number as `f64`), and reads null, a numeric string, or
 * anything unreadable as 0.
 */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.streamhub.core.LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val element = decoder.decodeJsonElement()
        if (element !is JsonPrimitive || element is JsonNull) return 0
        val number = element.content.toDoubleOrNull() ?: return 0
        if (number.isNaN()) return 0
        return number.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/**
 * Text that also accepts a bare number (`"year": 2019` as well as `"2019"`),
 * reading anything else — an object, an array — as absent rather than failing
 * the document. Blank text is absent too.
 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.streamhub.core.LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val element = decoder.decodeJsonElement()
        if (element !is JsonPrimitive || element is JsonNull) return null
        return element.content.takeIf { it.isNotBlank() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
