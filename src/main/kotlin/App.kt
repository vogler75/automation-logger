import kotlin.Throws
import kotlin.jvm.JvmStatic

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.util.logging.Logger

object App {

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Common.initLogging()
        Common.initVertx(args, Vertx.vertx(), App::createServices)
    }

    private fun createServices(vertx: Vertx, config: JsonObject) {

        // Plc4x
        config.getJsonObject("Plc4x")
            ?.getJsonArray("Drivers")
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.getBoolean("Enabled") }
            ?.forEach {
                vertx.deployVerticle(Plc4xDriver(it))
            }

        // DB Logger
        config.getJsonObject("Database")
            ?.getJsonArray("Logger")
            ?.filterIsInstance<JsonObject>()
            ?.forEach {
                createLogger(vertx, it)
            }
    }

    private fun createLogger(vertx: Vertx, config: JsonObject) {
        val logger = Logger.getLogger(javaClass.simpleName)
        if (config.getBoolean("Enabled", true)) {
            when (val type = config.getString("Type")) {
                "IoTDB" -> {
                    vertx.deployVerticle(IoTDBLogger(config))
                }
                "Kafka" -> {
                    //vertx.deployVerticle(KafkaLogger(config))
                }
                else -> logger.severe("Unknown database type [${type}]")
            }
        }
    }
}