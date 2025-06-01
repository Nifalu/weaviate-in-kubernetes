import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.base.Result
import io.weaviate.client.v1.data.model.WeaviateObject
import io.weaviate.client.v1.filters.Operator
import io.weaviate.client.v1.filters.WhereFilter
import io.weaviate.client.v1.graphql.model.GraphQLResponse
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument
import io.weaviate.client.v1.graphql.query.argument.WhereArgument
import io.weaviate.client.v1.graphql.query.fields.Field
import io.weaviate.client.v1.misc.model.InvertedIndexConfig
import io.weaviate.client.v1.misc.model.VectorIndexConfig
import io.weaviate.client.v1.schema.model.DataType
import io.weaviate.client.v1.schema.model.Property
import io.weaviate.client.v1.schema.model.Property.NestedProperty
import io.weaviate.client.v1.schema.model.WeaviateClass
import java.util.UUID
import kotlin.random.Random


const val COLLECTION_NAME = "Vitrivr"
const val DESCRIPTOR_ENTITY_PREFIX = "descriptor"
const val RETRIEVABLE_ENTITY_DESCRIPTION = "Weaviate Collection of retrievables"
const val RETRIEVABLE_TYPE_PROPERTY_NAME = "type"
const val RETRIEVABLE_TYPE_PROPERTY_DESCRIPTION = "The type of the retrievable entity."

val namedVectors = listOf("averagecolor")
val client = WeaviateClient(Config("http", "10.34.64.158:30080"))

private fun noVectorizer(): Map<String, Any> = mapOf("none" to emptyMap<String, Any>())

fun createSchema() {
    val exists = client.schema().exists().withClassName(COLLECTION_NAME).run()
    if (exists.hasErrors()) {
        println("Error checking for existence of schema: ${exists.error}")
        return
    }
    if (!exists.result) {
        val invertedIndexConfig = InvertedIndexConfig.builder()
            .indexNullState(true)
            .indexPropertyLength(true)
            .build()

        val hnswConfig = VectorIndexConfig.builder()
            .distance("cosine")
            .efConstruction(128)
            .maxConnections(64)
            .ef(64)
            .build()

        val vectorConfig = mutableMapOf<String, WeaviateClass.VectorConfig>()
        namedVectors.forEach { vectorName ->
            val name = "${DESCRIPTOR_ENTITY_PREFIX}_${vectorName}"
            vectorConfig[name] = WeaviateClass.VectorConfig.builder()
                .vectorIndexType("hnsw")
                .vectorIndexConfig(hnswConfig)
                .vectorizer(noVectorizer())
                .build()
        }

        val wClass = WeaviateClass.builder()
            .className(COLLECTION_NAME)
            .description(RETRIEVABLE_ENTITY_DESCRIPTION)
            .invertedIndexConfig(invertedIndexConfig)
            .vectorConfig(vectorConfig)
            .properties(listOf(
                Property.builder()
                    .name(RETRIEVABLE_TYPE_PROPERTY_NAME)
                    .description(RETRIEVABLE_TYPE_PROPERTY_DESCRIPTION)
                    .dataType(listOf("text"))
                    .build()
            ))
            .build()

        val result = client.schema().classCreator().withClass(wClass).run()

        if (result.hasErrors()) {
            println("Error creating collection '$COLLECTION_NAME': ${result.error}")
        } else {
            println("Collection '$COLLECTION_NAME' created successfully.")
        }
    }
}

fun deleteSchema() {
    val exists = client.schema().exists().withClassName(COLLECTION_NAME).run()
    if (exists.hasErrors()) {
        println("Error checking for existence of schema: ${exists.error}")
        return
    }
    if (exists.result) {
        val result = client.schema().classDeleter().withClassName(COLLECTION_NAME).run()
        if (result.hasErrors()) {
            println("Error deleting collection '$COLLECTION_NAME': ${result.error}")
        } else {
            println("Collection '$COLLECTION_NAME' deleted successfully.")
            println(result.result)
        }
    }
}

fun isSchemaInitialized(): Boolean {
    val exists = client.schema().exists().withClassName(COLLECTION_NAME).run()
    if (exists.hasErrors()) {
        println("Error checking for existence of schema: ${exists.error}")
        return false
    }
    return exists.result
}

fun printWeaviateInfo() {
    client.misc().metaGetter().run().let { result ->
        if (result.hasErrors()) {
            println("Error retrieving Weaviate meta information:\n${result.error}")
        } else {
            println(" --- Weaviate meta information: ---")
            println("Version: ${result.result.version}")
            println("Hostname: ${result.result.hostname}")
            println("Modules: ${result.result.modules}")
            println(" ------------------------------------- ")
        }
    }
}

fun printCollectionInfo() {
    client.schema().classGetter().withClassName(COLLECTION_NAME).run().let { result ->
        if (result.hasErrors()) {
            println("Error retrieving schema:\n${result.error}")
        } else {
            println(" --- Schema retrieved successfully: ---")
            println("Schema: ${result.result.className}")
            for (prop in result.result.properties) {
                println("  Property: ${prop.name}, Type: ${prop.dataType}")
            }
            for (vec in result.result.vectorConfig) {
                println("  Vector: ${vec.key}, Type: ${vec.value.vectorIndexType}")
            }
            println(" ------------------------------------- ")
        }
    }
}

fun printNRetrievables(n: Int) {
    client.data().objectsGetter().withClassName(COLLECTION_NAME)
        .withLimit(n)
        .run().let { result ->
            if (result.hasErrors()) {
                println("Error retrieving retrievables:\n${result.error}")
            } else {
                println(" --- Retrievables retrieved successfully: ---")
                for (obj in result.result) {
                    println("  ID: ${obj.id}, Type: ${obj.properties[RETRIEVABLE_TYPE_PROPERTY_NAME]}")
                    for (prop in obj.properties) {
                        if (prop.key != RETRIEVABLE_TYPE_PROPERTY_NAME) {
                            println("    Property: ${prop.key}, Value: ${prop.value}")
                        }
                    }
                    for (vec in obj.vectors) {
                        println("  Vector: ${vec.key}, Type: ${vec.value.take(3)}...")
                    }
                }
                println(" ------------------------------------- ")
            }
        }
}

fun addRetrievable(uuid: UUID, type: String): Boolean {
    val result = client.data().creator()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .withProperties(
            mapOf(
                RETRIEVABLE_TYPE_PROPERTY_NAME to type
            )
        )
        .run()
    if (result.hasErrors()) {
        println("Error persisting retrievable $uuid to Weaviate: ${result.error}")
        return false
    }
    return true
}

fun addAllRetrievable(items: Iterable<UUID>, type: Iterable<String>): Boolean {
    val batcher = client.batch().objectsBatcher()
    val zipped = items.zip(type)

    zipped.forEach { (item, type) ->
        val wObject = WeaviateObject.builder()
            .className(COLLECTION_NAME)
            .id(item.toString())
            .properties(
                mapOf(
                    RETRIEVABLE_TYPE_PROPERTY_NAME to type
                )
            )
            .build()
        batcher.withObject(wObject)
    }

    val result = batcher.run()
    if (result.hasErrors()) {
        println("Error persisting retrievables to Weaviate: ${result.error}")
        return false
    } else {
        println("Successfully persisted ${zipped.size} retrievables to Weaviate.")
        return true
    }
}

fun updateRetrievable(item: UUID, type: String): Boolean {
    val result = client.data().updater()
        .withMerge()
        .withClassName(COLLECTION_NAME)
        .withID(item.toString())
        .withProperties(
            mapOf(
                RETRIEVABLE_TYPE_PROPERTY_NAME to type
            )
        )
        .run()
    if (result.hasErrors()) {
        println("Error updating retrievable $item to Weaviate: ${result.error}")
        return false
    } else {
        println("Successfully updated retrievable $item to type $type.")
        println(result.result)
    }
    return true
}

fun deleteRetrievable(uuid: UUID): Boolean {
    val result = client.data().deleter()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .run()
    if (result.hasErrors()) {
        print("Error deleting retrievable $uuid from Weaviate: ${result.error}")
        return false
    }
    return true
}

fun deleteAllRetrievable(items: Iterable<UUID>): Boolean {
    val uuids = items.map { it.toString() }.toTypedArray()

    val whereFilter = WhereFilter.builder()
        .path("id")
        .operator(Operator.ContainsAny)
        .valueText(*uuids)
        .build()

    val result = client.batch().objectsBatchDeleter()
        .withClassName(COLLECTION_NAME)
        .withWhere(whereFilter)
        .run()

    if (result.hasErrors()) {
        print("Error deleting retrievables from Weaviate: ${result.error}\n")
        return false
    }

    return true
}

fun connectRetrievable(a: UUID, b: UUID, predicate: String): Boolean {
    /* try to directly create the reference */
    val result = client.data().referenceCreator()
        .withClassName(COLLECTION_NAME)
        .withID(a.toString())
        .withReferenceProperty(predicate)
        .withReference(
            client.data().referencePayloadBuilder()
                .withClassName(COLLECTION_NAME)
                .withID(b.toString())
                .payload()
        )
        .run()

    if (!result.hasErrors()) {
        return true // early return if no errors
    }

    /* if the reference property (predicate) does not exist, the above will fail.
     * we need to create the reference property first. */
    if (result.error.messages.any { it.message.contains("'$predicate'") }) {
        println("Creating reference property '${predicate}'...")
        val property = Property.builder()
            .name(predicate)
            .description("references another retrievable")
            .dataType(listOf(COLLECTION_NAME))
            .build()

        /* create the reference property */
        client.schema().propertyCreator()
            .withClassName(COLLECTION_NAME)
            .withProperty(property)
            .run().let { r ->
                if (r.hasErrors()) {
                    println("Error creating reference property 'partOf': ${result.error}")
                    return false
                }
            }

        /* try to create the reference again */
        val result2 = client.data().referenceCreator()
            .withClassName(COLLECTION_NAME)
            .withID(a.toString())
            .withReferenceProperty(predicate)
            .withReference(
                client.data().referencePayloadBuilder()
                    .withClassName(COLLECTION_NAME)
                    .withID(b.toString())
                    .payload()
            )
            .run()

        if (result2.hasErrors()) {
            println("Error connecting retrievables $a and $b:\n" + result2.error)
            return false
        }
    } else {
        println("Error connecting retrievables $a and $b:\n" + result.error)
        return false
    }
    return true
}

fun disconnectRetrievable(a: UUID, b: UUID): Boolean {
    val result = client.data().referenceDeleter()
        .withClassName(COLLECTION_NAME)
        .withID(a.toString())
        .withReferenceProperty("partOf")
        .withReference(
            client.data().referencePayloadBuilder()
                .withClassName(COLLECTION_NAME)
                .withID(b.toString())
                .payload()
        )
        .run()

    if (result.hasErrors()) {
        println("Error disconnecting retrievables $a and $b:\n" + result.error)
        return false
    }
    return true
}

fun addVector(uuid: UUID, vectorName: String) {

    val vectors: MutableMap<String, Array<Float>> = mutableMapOf()

    vectors[vectorName] = vec()

    val result = client.data().updater()
        .withMerge()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .withVectors(
            vectors
        )
        .run()

    if (result.hasErrors()) {
        println("Error adding vector $vectorName to retrievable $uuid:\n" + result.error)
    } else {
        println("Successfully added vector $vectorName to retrievable $uuid.")
    }
}

fun getRetrievable(uuid: UUID) {
    val result = client.data().objectsGetter()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .withVector()
        .withLimit(1)
        .run()

    if (result.hasErrors()) {
        println("Error retrieving retrievable $uuid:\n" + result.error)
    } else if (result.result == null) {
        println("No retrievable found with ID $uuid.")
    } else {
        result.result.first().let { o ->
            println(o.id)
            println(o.properties["type"] as? String ?: "unknown")
            for (vec in o.vectors) {
                println("Vector: ${vec.key}, Type: ${vec.value.take(3)}...")
            }
        }
    }
}

fun getAllRetrievable(ids: Iterable<UUID>): Sequence<WeaviateObject>? {
    val idArray = ids.map(UUID::toString).toTypedArray()
    val whereArgument = WhereArgument.builder()
        .filter(
            WhereFilter.builder()
                .path("id")
                .operator(Operator.ContainsAny)
                .valueText(*idArray)
                .build())
        .build()

    val result = client.graphQL().get()
        .withClassName(COLLECTION_NAME)
        .withFields(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build(),
                Field.builder().name("vectors").fields(
                    Field.builder().name("descriptor_clip").build(),
                    Field.builder().name("descriptor_average_color").build()
                ).build()
            ).build(),
            Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
        )
        .withWhere(whereArgument)
        .run()

    return result.toWeaviateObject()
}

fun getAllRetrievable() : Sequence<WeaviateObject>? {
    val result = client.graphQL().get()
        .withClassName(COLLECTION_NAME)
        .withFields(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build()
            ).build(),
            Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
        )
        .run().toWeaviateObject()

    return result
}

internal fun <T> Result<GraphQLResponse<T>?>.toWeaviateObject(): Sequence<WeaviateObject>? {
    if (this.hasErrors()) {
        println(this.error)
        return null
    }

    val data = this.result?.data
    if (data !is Map<*, *>) {
        println("ERROR2")
        return null
    }

    val getSection = data["Get"]
    if (getSection !is Map<*, *>) {
        println("ERROR3")
        return null
    }

    val collectionObjects = getSection[COLLECTION_NAME]
    if (collectionObjects !is List<*>) {
        println("ERROR4")
        return null
    }

    val result = mutableListOf<WeaviateObject>()

    for (item in collectionObjects) {
        val contents = item as? Map<*, *>
        if (contents == null) {
            println("WARN1")
            continue
        }

        val additional = contents["_additional"] as? Map<*,*>
        if (additional == null) {
            println("WARN2")
            continue
        }

        val id = additional["id"] as? String
        if (id == null) {
            println("WARN3")
            continue
        }

        val vectorList = additional["vectors"] as? Map<*,*>
        val vectors: Map<String, Array<Float>> =
            vectorList?.filterValues { it != null }?.mapKeys { it.key.toString() }?.mapValues { (_, value) ->
                (value as List<*>).map {
                    when (it) {
                        is Float -> it
                        is Number -> it.toFloat()
                        else -> throw IllegalArgumentException("Invalid element type: ${it?.javaClass} within vector")
                    }
                }.toTypedArray() }
                ?: emptyMap()

        val properties = item
            .filterKeys { it != "_additional" }
            .filterValues { it != null }
            .mapKeys { it.key.toString() }
            .mapValues { it.value as Any }

        result.add(WeaviateObject.builder()
            .className(COLLECTION_NAME)
            .id(id)
            .properties(properties)
            .vectors(vectors)
            .build()
        )
    }
    return result.asSequence()
}

internal fun WeaviateClient.findPredicateProperties(): List<String> {
    this.schema().classGetter().withClassName(COLLECTION_NAME).run().let { result ->
        if (result.hasErrors()) {
            print("Error retrieving schema: ${result.error}")
            return emptyList()
        }

        return result.result.properties
            .filter { it.dataType.contains(COLLECTION_NAME) }
            .map { it.name }

    }
}

fun existsRetrievable(uuid: UUID): Boolean {
    val result = client.data().checker()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .run()

    if (result.hasErrors()) {
        println("Error checking existence of retrievable $uuid:\n" + result.error)
        return false
    }

    if (result.result == null) {
        println("No retrievable found with ID $uuid.")
        return false
    }

    return result.result
}

fun getConnection(a: Collection<UUID>, b: Collection<UUID>, pred: Collection<String>): MutableList<Triple<String, String, String>> {

    fun buildPredicateFields(predicates: Array<String>): MutableList<Field> {
        val fields = mutableListOf(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build()
            ).build(),
            Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
        )

        for (predicate in predicates) {
            val refField = Field.builder()
                .name(predicate)
                .fields(Field.builder().name("... on Retrievable") // "inline fragment" or something like that lol
                    .fields(Field.builder().name("_additional")
                        .fields(Field.builder().name("id").build())
                        .build())
                    .build())
                .build()
            fields.add(refField)
        }
        return fields
    }

    fun buildPredicateWhereFilter(predicates: Array<String>): WhereFilter {
        val hasPredicateFilter = predicates.map { p ->
            WhereFilter.builder()
                .path(p, COLLECTION_NAME, "id")
                .operator(Operator.NotEqual)
                .valueText("")
                .build()
        }
        val atLeastOneExistingPredicateFilter = WhereFilter.builder()
            .operator(Operator.Or)
            .operands(*hasPredicateFilter.toTypedArray())
            .build()

        return atLeastOneExistingPredicateFilter
    }

    val existingPredicates = client.findPredicateProperties().toTypedArray()
    val subjectsArray = a.map(UUID::toString).toTypedArray()
    val predicatesArray = pred.toTypedArray()
    val objectsArray = b.map(UUID::toString).toTypedArray()
    val filters = mutableListOf<WhereFilter>()

    /* Filter for retrievables that have the subjectId */
    if (subjectsArray.isNotEmpty()) {
        /* Only take the requested id's into account */
        val idFilter = WhereFilter.builder()
            .path("id")
            .operator(Operator.ContainsAny)
            .valueText(*subjectsArray) // must have a specific id
            .build()

        filters.add(idFilter)
        filters.add(buildPredicateWhereFilter(existingPredicates))
    }

    val fields: MutableList<Field> = mutableListOf(
        Field.builder().name("_additional").fields(
            Field.builder().name("id").build()
        ).build(),
        Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
    )
    /* Filter for retrievables that have a property named after a predicate*/
    if (predicatesArray.isNotEmpty()) {
        filters.add(buildPredicateWhereFilter(predicatesArray))
        /* we want to retrieve the predicates per retrievable */
        fields.addAll(buildPredicateFields(predicatesArray))
    } else {
        /* if no predicates are given we just take all existing predicates */
        fields.addAll(buildPredicateFields(existingPredicates))
    }

    /* Filter for retrievables that are referenced by another retrievable
    * To find references of our object id we search for known predicates and look where they reference to */
    if (objectsArray.isNotEmpty()) {
        val predicateFilters = existingPredicates.map { p ->
            WhereFilter.builder()
                .path(p, COLLECTION_NAME, "id")
                .operator(Operator.ContainsAny)
                .valueText(*objectsArray) // objects is a list of id's, however weaviate stores a reference beacon here...?
                .build()
        }.toTypedArray()

        filters.add(
            WhereFilter.builder()
                .operator(Operator.Or)
                .operands(*predicateFilters)
                .build()
        )
    }

    /* AND the individual filters together */
    val whereArgument = WhereArgument.builder()
        .filter(
            WhereFilter.builder()
                .operator(Operator.And)
                .operands(*filters.toTypedArray())
                .build()
        )
        .build()



    val result = client.graphQL().get()
        .withClassName(COLLECTION_NAME)
        .withFields(
            *fields.toTypedArray()
        )
        .withWhere(whereArgument)
        .run()

    if (result.hasErrors()) {
        println("Error retrieving retrievables:\n" + result.error)
        return mutableListOf()
    }
    if (result.result == null) {
        println("No retrievables match the query.")
        return mutableListOf()
    }
    println(result.result.data)

    val relationships: MutableList<Triple<String, String, String>> = mutableListOf()

    result.toWeaviateObject()?.forEach { res ->
        /* Find the Reference Properties for each Retrievable */
        val predicatesForResult = res.properties.filter {prop ->
            if (predicatesArray.isNotEmpty()) {
                prop.key in predicatesArray
            } else {
                prop.key in existingPredicates
            }
        }
        print("Predicates for ${res.id}: $predicatesForResult\n")
        /* Separate entry per predicate */
        predicatesForResult.forEach { (k, v) ->
            /* Separate entry for each target id within a predicate */
            (v as List<*>).forEach { targetRetrievable ->
                /* Extract the id from the target map... */
                val targetRetrievableMap = targetRetrievable as? Map<*, *>
                if (targetRetrievableMap != null) {
                    val targetId = targetRetrievableMap["_additional"] as? Map<*, *>
                    if (targetId?.get("id") != null) {
                        relationships.add(Triple(res.id, k, targetId["id"].toString()))
                    }
                }
            }
        }
    }

    return relationships
}

fun count(): Long {

    val metaCountField = Field.builder()
        .name("meta")
        .fields(Field.builder().name("count").build())
        .build()

    val result = client.graphQL().aggregate()
        .withClassName(COLLECTION_NAME)
        .withFields(metaCountField)
        .run()

    if (result.hasErrors()) {
        print( "Error counting retrievables: ${result.error}" )
        return -1
    }

    println("Count result: ${result.result}")
    return (result.result.data as? Map<*,*>)
        ?.let { it["Aggregate"] as? Map<*,*> }
        ?.let { it[COLLECTION_NAME] as? List<*> }
        ?.let { it.first() as? Map<*,*> }
        ?.let { it["meta"] as? Map<*,*> }
        ?.let { it["count"] as? Double }
        ?.toLong() ?: -1L

}

fun initializeDescriptor(property: Property) {
    val result = client.schema().propertyCreator()
        .withClassName(COLLECTION_NAME)
        .withProperty(property)
        .run()

    if (result.hasErrors()) {
        println("Error creating descriptor property '${property.name}': ${result.error}")
    } else {
        println("Descriptor property '${property.name}' created successfully.")
    }
}

fun isInitializedDescriptor(property: Property): Boolean {
    val result = client.schema().classGetter()
        .withClassName(COLLECTION_NAME)
        .run()

    if (result.hasErrors()) {
        println("Error checking if descriptor property '${property.name}' is initialized: ${result.error}")
        return false
    }

    println(result.result)

    return result.result.properties.any { it.name == property.name && it.dataType == property.dataType }
}

fun insertDescriptor(uuid: UUID, name: String, value: Boolean): Boolean {
    val result = client.data().updater()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .withMerge()
        .withProperties(
            mapOf(
                name to value
            )
        )
        .run()

    if (result.hasErrors()) {
        println("Failed to update retrievable '${uuid}' with descriptor $name due to exception: ${result.error}")
    }

    return result.result
}

fun deleteDescriptor(uuid: UUID, name: String): Boolean {
    val result = client.data().objectsGetter()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .run()

    if (result.hasErrors()) {
        println("failed to delete descriptor $name from retrievable $uuid: ${result.error}")
        return false
    }

    val wObject = result.result.firstOrNull() ?: return true // nothing to delete
    wObject.properties.remove(name) // remove the property
    val update = client.data().updater() // replace the entire object.
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .withProperties(wObject.properties)
        .run()

    if (update.hasErrors()) {
        println("failed to delete descriptor $name from retrievable $uuid: ${update.error}")
        return false
    }

    return true
}

fun existsDescriptor(uuid: UUID, name: String): Boolean {
    val result = client.data().objectsGetter()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .run()

    if (result.hasErrors()) {
        print( "Failed to fetch descriptor $uuid due to error." )
        return false
    }
    return result.result.first().properties.containsKey(name)
}

fun getDescriptor(uuid: UUID, name: String) {
    val result = client.data().objectsGetter()
        .withClassName(COLLECTION_NAME)
        .withID(uuid.toString())
        .run()

    if (result.hasErrors()) {
        print( "Failed to fetch descriptor $uuid due to error." )
    } else {
        result.result.first().properties[name]?.let {
            val id = UUID.fromString(result.result.first().id)
            println("Descriptor: ${it}:  $id")// descriptorId == retrievableId
        }
    }
}

fun getDescriptor(value: Any) {
    val whereFilter = WhereFilter.builder()
        .path("descriptor_is_cool")
        .operator(Operator.Equal)
        .valueBoolean(value as? Boolean ?: throw IllegalArgumentException("Comparison value of wrong type."))
        .build()

    val result = client.graphQL().get()
        .withClassName(COLLECTION_NAME)
        .withFields(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build()).build(),
            Field.builder().name("descriptor_is_cool").build())
        .withWhere(WhereArgument.builder().filter(whereFilter).build())
        .run()

    println(result)
}

fun getNearVector(vector: Array<Float>, limit: Int = 10): Sequence<WeaviateObject>? {
    val result = client.graphQL().get()
        .withClassName(COLLECTION_NAME)
        .withFields(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build(),
                Field.builder().name("vectors").fields(
                    Field.builder().name("descriptor_averagecolor").build()
                ).build()
            ).build(),
            Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
        )
        .withNearVector(
            NearVectorArgument.builder()
                .vector(vector)
                .targetVectors(arrayOf("descriptor_averagecolor"))
                .build()
        )
        .withLimit(limit)
        .run()

    return result.toWeaviateObject()
}

fun vec(len: Int = 128): Array<Float> {
    return FloatArray(len) { Random.nextFloat() }.toTypedArray()
}

fun addFileProp() {
    val file = Property.builder()
        .name("descriptor_file")
        .dataType(listOf(DataType.OBJECT))
        .nestedProperties(listOf(
            NestedProperty.builder()
                .name("path")
                .dataType(listOf(DataType.TEXT))
                .build(),
            NestedProperty.builder()
                .name("size")
                .dataType(listOf(DataType.INT))
                .build())
        ).build()

    initializeDescriptor(file)
}

fun insertIntoWeaviate() {

    val result = client.data().creator().withClassName(COLLECTION_NAME)
        .withID(UUID.randomUUID().toString())
        .withProperties(
            mapOf(
                RETRIEVABLE_TYPE_PROPERTY_NAME to "test_type",
                "descriptor_file" to mapOf(
                    "path" to "file/path/to/some/file.txt",
                    "size" to 123456
                )
            )
        )
        .withVectors(
            mapOf(
                "descriptor_averagecolor" to vec(3)
            )
        )
        .run()

    if (result.hasErrors()) {
        println("Error inserting into Weaviate: ${result.error}")
    } else {
        println("Successfully inserted into Weaviate with ID: ${result.result.id}")
    }

}

fun main() {

/*
    val result = client.graphQL().get()
        .withClassName(RETRIEVABLE_ENTITY_NAME)
        .withFields(
            Field.builder().name("_additional").fields(
                Field.builder().name("id").build(),
                Field.builder().name("vectors").fields(
                    Field.builder().name("descriptor_averagecolor").build()
                ).build()
            ).build(),
            Field.builder().name(RETRIEVABLE_TYPE_PROPERTY_NAME).build()
        )
        .withLimit(1)
        .run()

 */


    deleteSchema()
    //addFileProp()
    //printCollectionInfo()
    //insertIntoWeaviate()
    //printWeaviateInfo()
    //printNRetrievables(1)
    //count()
    /*
    val result = getNearVector(vec(), limit=3)
    println("Near Vector Result: ")
    result?.forEach { obj ->
        println("ID: ${obj.id}, Type: ${obj.properties[RETRIEVABLE_TYPE_PROPERTY_NAME]}")
        for (vec in obj.vectors) {
            println("Vector: ${vec.key}, Values: ${vec.value.take(3)}...")
        }
    }

     */

}

