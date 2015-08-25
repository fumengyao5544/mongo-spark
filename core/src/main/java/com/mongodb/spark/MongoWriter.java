/*
 * Copyright (c) 2008-2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.spark;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.rdd.RDD;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.CollectibleCodec;
import scala.collection.Iterator;
import scala.reflect.ClassTag$;
import scala.runtime.BoxedUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for writing RDDs to Mongo collections.
 */
public final class MongoWriter {
    private static final Log LOG = LogFactory.getLog(MongoWriter.class);

    /**
     * Writes the given RDD to the collection specified in the collection provider.
     * If there is a CollectibleCodec available for the documents and document ids
     * exist, the writer will attempt to replace documents in the specified
     * collection with documents in the RDD with the provided upsert option.
     *
     * Note: the collection provider will be broadcasted through the RDD's SparkContext.
     *
     * @param rdd the rdd to write
     * @param provider the collection provider
     * @param upsert true if upsert
     * @param ordered true if ordered
     * @param <T> the type of documents in the collection
     */
    public static <T> void writeToMongo(final RDD<T> rdd, final MongoCollectionProvider<T> provider, final Boolean upsert,
                                        final Boolean ordered) {
        LOG.debug("Writing to collection " + provider.getCollection().getNamespace().getFullName());

        Broadcast<MongoCollectionProvider<T>> collectionProvider =
                rdd.sparkContext().broadcast(provider, ClassTag$.MODULE$.apply(provider.getClass()));

        rdd.foreachPartition(new SerializableAbstractFunction1<Iterator<T>, BoxedUnit>() {
            @Override
            public BoxedUnit apply(final Iterator<T> elements) {
                MongoCollection<T> collection = collectionProvider.value().getCollection();
                BulkWriteOptions bulkWriteOptions = new BulkWriteOptions().ordered(ordered);

                Codec<T> codec = collection.getCodecRegistry().get(collection.getDocumentClass());
                boolean isCollectibleCodec = codec instanceof CollectibleCodec;

                List<WriteModel<T>> writeModels = new ArrayList<>();
                T element;

                while (elements.hasNext()) {
                    element = elements.next();
                    if (isCollectibleCodec && ((CollectibleCodec<T>) codec).documentHasId(element)) {
                        writeModels.add(new ReplaceOneModel<>(new Document("_id", ((CollectibleCodec<T>) codec).getDocumentId(element)),
                                                              element, new UpdateOptions().upsert(upsert)));
                    } else {
                        writeModels.add(new InsertOneModel<>(element));
                    }

                    // limit batch size to 1000
                    if (writeModels.size() == 1000) {
                        try {
                            collection.bulkWrite(writeModels, bulkWriteOptions);
                        } catch (MongoBulkWriteException e) {
                            LOG.info(e.getMessage(), e);

                            if (bulkWriteOptions.isOrdered()) {
                                LOG.info("Stopping ordered bulk write operation");
                                return null;
                            }
                        } finally {
                            writeModels.clear();
                        }
                    }
                }

                if (writeModels.size() > 0) {
                    try {
                        collection.bulkWrite(writeModels, bulkWriteOptions);
                    } catch (MongoBulkWriteException e) {
                        LOG.info(e.getMessage(), e);
                    }
                }

                return null;
            }
        });
    }

    private MongoWriter() {
    }
}