import io.vertx.core.json.JsonObject
import java.lang.Exception
import java.time.Instant

abstract class TopicValue {
    val className: String = this.javaClass.simpleName // must be public to be added in json

    fun encodeToJson(): JsonObject = JsonObject.mapFrom(this)

    abstract fun hasValue(): Boolean

    abstract fun valueAsObject(): Any?
    abstract fun statusAsString(): String
    abstract fun valueAsString(): String

    open fun valueAsDouble(): Double? = valueAsString().toDoubleOrNull()

    abstract fun sourceTime(): Instant
    abstract fun serverTime(): Instant

    fun serverTimeAsISO(): String = serverTime().toString()
    fun sourceTimeAsISO(): String = sourceTime().toString()

    open fun dataTypeName(): String = valueAsObject()?.javaClass?.simpleName ?: ""

    abstract fun hasStruct(): Boolean
    abstract fun asFlatMap(): Map<String, Any>

    companion object {
        fun fromJsonObject(json: JsonObject): TopicValue {
            return when (val objectClassName = json.getString("className", "")) {
                TopicValuePlc::class.java.simpleName -> TopicValuePlc.fromJsonObject(json)
                else -> throw Exception("Unhandled class [$objectClassName] in JsonObject!")
            }
        }
    }
}