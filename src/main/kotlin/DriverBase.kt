import io.vertx.core.*
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import java.lang.Exception

import java.util.ArrayList
import java.util.concurrent.ExecutionException
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

abstract class DriverBase(config: JsonObject) : AbstractVerticle() {
    protected abstract fun getType(): Topic.SystemType

    protected val id: String = config.getString("Id", DriverBase::class.java.simpleName)

    protected val uri = "${getType().name}/$id"

    protected val logger: Logger =  Logger.getLogger(id)

    private val subscribeOnStartup: List<String> =
        config.getJsonArray("SubscribeOnStartup", JsonArray()).filterIsInstance<String>().toList()

    private var messageHandlers: List<MessageConsumer<*>> = ArrayList()

    protected val registry = Registry()

    protected abstract fun connect(): Future<Boolean>
    protected abstract fun disconnect(): Future<Boolean>
    protected abstract fun shutdown()

    init {
        logger.level = Level.parse(config.getString("LogLevel", "INFO"))
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.warning("Shutdown [$id]")
            try {
                shutdown()
                logger.warning("Shutdown finished [$id]")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    override fun start(startPromise: Promise<Void>) {
        logger.info("Driver start [$id]")
        vertx.executeBlocking<Void> {
            try {
                connect().onComplete {
                    logger.info("Connect complete")
                    connectHandlers()
                    registerService()
                    subscribeOnStartup.forEach {
                        val topic = Topic.parseTopic("$uri/$it")
                        logger.info("Subscribe to topic [$topic]")
                        if (topic.isValid()) subscribeTopic(id, topic)
                    }
                    startPromise.complete()
                }.onFailure { result: Throwable ->
                    startPromise.fail(result.message)
                }
            } catch (e: Exception) {
                startPromise.fail(e)
            }
        }
    }

    override fun stop(stopPromise: Promise<Void>) {
        logger.info("Driver stop [$id]")
        vertx.executeBlocking<Void> {
            try {
                disconnectHandlers()
                disconnect().onSuccess {
                    stopPromise.complete()
                }.onFailure { result: Throwable -> stopPromise.fail(result.message) }
            } catch (e: Exception) {
                stopPromise.fail(e)
            }
        }
    }

    private fun registerService() {
        val handler = ServiceHandler(vertx, logger)
        handler.registerService(getType().name, id, uri).onComplete {
            if (it.succeeded()) {
                logger.info("Service registered.")
            } else {
                logger.warning("Service registration failed!")
            }
        }
    }

    private fun connectHandlers() {
        logger.info("Connect handlers to [$uri]")
        messageHandlers = listOf<MessageConsumer<JsonObject>>(
            vertx.eventBus().consumer("$uri/ServerInfo") { serverInfoHandler(it) },
            vertx.eventBus().consumer("$uri/Subscribe") { subscribeHandler(it) },
            vertx.eventBus().consumer("$uri/Unsubscribe") { unsubscribeHandler(it) },
            vertx.eventBus().consumer("$uri/Publish") { publishHandler(it) },
            vertx.eventBus().consumer("$uri/Read") { readHandler(it) },
            vertx.eventBus().consumer("$uri/Write") { writeHandler(it) },
            vertx.eventBus().consumer("$uri/Browse") { browseHandler(it) },
            vertx.eventBus().consumer("$uri/Schema") { schemaHandler(it) },
        )
    }

    private fun disconnectHandlers() {
        messageHandlers.forEach(Consumer { h: MessageConsumer<*> -> h.unregister() })
    }

    private fun serverInfoHandler(message: Message<JsonObject>) { // TODO: make it async
        val result = readServerInfo()
        message.reply(JsonObject().put("Ok", true).put("Result", result))
    }

    private fun subscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopic = Topic.decodeFromJson(request.getJsonObject("Topic"))
        subscribeTopic(clientId, tagTopic).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    private fun unsubscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopics = if (request.containsKey("Topic")) listOf(Topic.decodeFromJson(request.getJsonObject("Topic")))
        else request.getJsonArray("Topics").map { Topic.decodeFromJson(it as JsonObject) }

        unsubscribeTopics(clientId, tagTopics).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    private fun publishHandler(message: Message<JsonObject>) {
        val topic = Topic.decodeFromJson(message.body().getJsonObject("Topic"))
        val data = message.body().getBuffer("Data")
        logger.finest { "Publish [${topic.toString()}] [${data.toString()}]" }
        try {
            publishTopic(topic, data).onComplete { result: AsyncResult<Boolean> ->
                if (result.cause() != null) result.cause().printStackTrace()
                message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
            }
        } catch (e: ExecutionException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        } catch (e: InterruptedException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        }
    }

    private fun subscribeTopic(clientId: String, topic: Topic): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            val (count, added) = registry.addClient(clientId, topic)
            logger.finest { "Subscribe [${count}] [${topic}]" }
            if (!added) {
                logger.warning("Client [${clientId}] already subscribed to [${topic}]")
                ret.complete(true)
            } else if (count == 1) {
                subscribeTopics(listOf(topic)).onComplete {
                    if (it.succeeded()) {
                        ret.complete(it.result())
                    } else {
                        ret.fail(it.cause())
                    }
                }
            } else {
                ret.complete(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }

    fun resubscribe() {
        val topics = registry.getTopics()
        if (topics.isNotEmpty()) {
            logger.info("Resubscribe [${topics.size}] topics")
            topics.forEach(registry::delTopic)
            subscribeTopics(topics)
        }
    }

    private fun unsubscribeTopics(clientId: String, topics: List<Topic>): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        val items = ArrayList<MonitoredItem>()
        topics.forEach { topic ->
            val (count, removed) = registry.delClient(clientId, topic)
            logger.info("Unsubscribe [${count}] [${topic}]")
            if (!removed) {
                logger.warning("Client [${clientId}] was not subscribed to [${topic}]")
            } else if (count == 0) {
                logger.info("Client [${clientId}] was last client of [${topic}]")
                items.addAll(registry.delTopic(topic))
            }
        }
        if (items.size > 0) {
            unsubscribeTopics(topics, items).onComplete(ret)
        } else {
            ret.complete(true)
        }
        return ret.future()
    }

    // MQTT
    protected abstract fun subscribeTopics(topics: List<Topic>): Future<Boolean>
    protected abstract fun unsubscribeTopics(topics: List<Topic>, items: List<MonitoredItem>): Future<Boolean>
    protected abstract fun publishTopic(topic: Topic, value: Buffer): Future<Boolean>

    // GraphQL
    protected abstract fun readServerInfo(): JsonObject
    protected abstract fun readHandler(message: Message<JsonObject>)
    protected abstract fun writeHandler(message: Message<JsonObject>)
    protected abstract fun browseHandler(message: Message<JsonObject>)
    protected abstract fun schemaHandler(message: Message<JsonObject>)
}