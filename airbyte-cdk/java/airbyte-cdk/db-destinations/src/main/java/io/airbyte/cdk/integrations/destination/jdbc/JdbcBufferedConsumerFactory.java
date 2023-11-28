/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.destination.jdbc;

import static io.airbyte.cdk.integrations.destination.jdbc.constants.GlobalDataSizeConstants.DEFAULT_MAX_BATCH_SIZE_BYTES;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.integrations.base.AirbyteMessageConsumer;
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer;
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer;
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.BufferedStreamConsumer;
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnCloseFunction;
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnStartFunction;
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.RecordWriter;
import io.airbyte.cdk.integrations.destination.record_buffer.InMemoryRecordBufferingStrategy;
import io.airbyte.cdk.integrations.destination_async.AsyncStreamConsumer;
import io.airbyte.cdk.integrations.destination_async.buffers.BufferManager;
import io.airbyte.commons.json.Jsons;
import io.airbyte.integrations.base.destination.typing_deduping.ParsedCatalog;
import io.airbyte.integrations.base.destination.typing_deduping.TypeAndDedupeOperationValve;
import io.airbyte.integrations.base.destination.typing_deduping.TyperDeduper;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteRecordMessage;
import io.airbyte.protocol.models.v0.AirbyteStream;
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.v0.DestinationSyncMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy:
 * <p>
 * 1. Create a final table for each stream
 * <p>
 * 2. Accumulate records in a buffer. One buffer per stream
 * <p>
 * 3. As records accumulate write them in batch to the database. We set a minimum numbers of records
 * before writing to avoid wasteful record-wise writes. In the case with slow syncs this will be
 * superseded with a periodic record flush from {@link BufferedStreamConsumer#periodicBufferFlush()}
 * <p>
 * 4. Once all records have been written to buffer, flush the buffer and write any remaining records
 * to the database (regardless of how few are left)
 */
public class JdbcBufferedConsumerFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcBufferedConsumerFactory.class);

  public static AirbyteMessageConsumer create(final Consumer<AirbyteMessage> outputRecordCollector,
                                              final JdbcDatabase database,
                                              final SqlOperations sqlOperations,
                                              final NamingConventionTransformer namingResolver,
                                              final JsonNode config,
                                              final ConfiguredAirbyteCatalog catalog) {
    final List<WriteConfig> writeConfigs = createWriteConfigs(namingResolver, config, catalog, sqlOperations.isSchemaRequired());

    return new BufferedStreamConsumer(
        outputRecordCollector,
        onStartFunction(database, sqlOperations, writeConfigs),
        new InMemoryRecordBufferingStrategy(recordWriterFunction(database, sqlOperations, writeConfigs, catalog), DEFAULT_MAX_BATCH_SIZE_BYTES),
        onCloseFunction(),
        catalog,
        sqlOperations::isValidData);
  }

  public static SerializedAirbyteMessageConsumer createAsync(final Consumer<AirbyteMessage> outputRecordCollector,
                                                             final JdbcDatabase database,
                                                             final SqlOperations sqlOperations,
                                                             final NamingConventionTransformer namingResolver,
                                                             final JsonNode config,
                                                             final ConfiguredAirbyteCatalog catalog,
                                                             final String defaultNamespace,
                                                             // TODO these are currently unused, but we'll need them for DV2 things
                                                             final boolean use1s1t,
                                                             final ParsedCatalog parsedCatalog,
                                                             final TyperDeduper typerDeduper,
                                                             final TypeAndDedupeOperationValve typerDeduperValve) {
    final List<WriteConfig> writeConfigs = createWriteConfigs(namingResolver, config, catalog, sqlOperations.isSchemaRequired());
    final Map<AirbyteStreamNameNamespacePair, WriteConfig> streamDescToWriteConfig = toMap(writeConfigs);
    return new AsyncStreamConsumer(
        outputRecordCollector,
        onStartFunction(database, sqlOperations, writeConfigs),
        io.airbyte.cdk.integrations.destination_async.OnCloseFunction.fromNonAsync(onCloseFunction()),
        new JdbcInsertFlushFunction(recordWriterFunction(database, sqlOperations, writeConfigs, catalog)),
        catalog,
        new BufferManager((long) (Runtime.getRuntime().maxMemory() * 0.4)),
        defaultNamespace,
        Executors.newFixedThreadPool(2));
  }

  private static List<WriteConfig> createWriteConfigs(final NamingConventionTransformer namingResolver,
                                                      final JsonNode config,
                                                      final ConfiguredAirbyteCatalog catalog,
                                                      final boolean schemaRequired) {
    if (schemaRequired) {
      Preconditions.checkState(config.has("schema"), "jdbc destinations must specify a schema.");
    }
    final Instant now = Instant.now();
    return catalog.getStreams().stream().map(toWriteConfig(namingResolver, config, now, schemaRequired)).collect(Collectors.toList());
  }

  private static Function<ConfiguredAirbyteStream, WriteConfig> toWriteConfig(
                                                                              final NamingConventionTransformer namingResolver,
                                                                              final JsonNode config,
                                                                              final Instant now,
                                                                              final boolean schemaRequired) {
    return stream -> {
      Preconditions.checkNotNull(stream.getDestinationSyncMode(), "Undefined destination sync mode");
      final AirbyteStream abStream = stream.getStream();

      final String defaultSchemaName = schemaRequired ? namingResolver.getIdentifier(config.get("schema").asText())
          : namingResolver.getIdentifier(config.get(JdbcUtils.DATABASE_KEY).asText());
      final String outputSchema = getOutputSchema(abStream, defaultSchemaName, namingResolver);

      final String streamName = abStream.getName();
      final String tableName = namingResolver.getRawTableName(streamName);
      final String tmpTableName = namingResolver.getTmpTableName(streamName);
      final DestinationSyncMode syncMode = stream.getDestinationSyncMode();

      final WriteConfig writeConfig = new WriteConfig(streamName, abStream.getNamespace(), outputSchema, tmpTableName, tableName, syncMode);
      LOGGER.info("Write config: {}", writeConfig);

      return writeConfig;
    };
  }

  /**
   * Defer to the {@link AirbyteStream}'s namespace. If this is not set, use the destination's default
   * schema. This namespace is source-provided, and can be potentially empty.
   * <p>
   * The logic here matches the logic in the catalog_process.py for Normalization. Any modifications
   * need to be reflected there and vice versa.
   */
  private static String getOutputSchema(final AirbyteStream stream,
                                        final String defaultDestSchema,
                                        final NamingConventionTransformer namingResolver) {
    return stream.getNamespace() != null
        ? namingResolver.getNamespace(stream.getNamespace())
        : namingResolver.getNamespace(defaultDestSchema);
  }

  /**
   * Sets up destination storage through:
   * <p>
   * 1. Creates Schema (if not exists)
   * <p>
   * 2. Creates airybte_raw table (if not exists)
   * <p>
   * 3. <Optional>Truncates table if sync mode is in OVERWRITE
   *
   * @param database JDBC database to connect to
   * @param sqlOperations interface for execution SQL queries
   * @param writeConfigs settings for each stream
   */
  private static OnStartFunction onStartFunction(final JdbcDatabase database,
                                                 final SqlOperations sqlOperations,
                                                 final Collection<WriteConfig> writeConfigs) {
    return () -> {
      LOGGER.info("Preparing raw tables in destination started for {} streams", writeConfigs.size());
      final List<String> queryList = new ArrayList<>();
      for (final WriteConfig writeConfig : writeConfigs) {
        final String schemaName = writeConfig.getOutputSchemaName();
        final String dstTableName = writeConfig.getOutputTableName();
        LOGGER.info("Preparing raw table in destination started for stream {}. schema: {}, table name: {}",
            writeConfig.getStreamName(),
            schemaName,
            dstTableName);
        sqlOperations.createSchemaIfNotExists(database, schemaName);
        sqlOperations.createTableIfNotExists(database, schemaName, dstTableName);
        switch (writeConfig.getSyncMode()) {
          case OVERWRITE -> queryList.add(sqlOperations.truncateTableQuery(database, schemaName, dstTableName));
          case APPEND, APPEND_DEDUP -> {}
          default -> throw new IllegalStateException("Unrecognized sync mode: " + writeConfig.getSyncMode());
        }
      }
      sqlOperations.executeTransaction(database, queryList);
      LOGGER.info("Preparing raw tables in destination completed.");
    };
  }

  /**
   * Writes {@link AirbyteRecordMessage} to JDBC database's airbyte_raw table
   *
   * @param database JDBC database to connect to
   * @param sqlOperations interface of SQL queries to execute
   * @param writeConfigs settings for each stream
   * @param catalog catalog of all streams to sync
   */
  private static RecordWriter<AirbyteRecordMessage> recordWriterFunction(final JdbcDatabase database,
                                                                         final SqlOperations sqlOperations,
                                                                         final List<WriteConfig> writeConfigs,
                                                                         final ConfiguredAirbyteCatalog catalog) {
    final Map<AirbyteStreamNameNamespacePair, WriteConfig> pairToWriteConfig = toMap(writeConfigs);

    return (pair, records) -> {
      if (!pairToWriteConfig.containsKey(pair)) {
        throw new IllegalArgumentException(
            String.format("Message contained record from a stream that was not in the catalog. \ncatalog: %s", Jsons.serialize(catalog)));
      }

      final WriteConfig writeConfig = pairToWriteConfig.get(pair);
      sqlOperations.insertRecords(database, records, writeConfig.getOutputSchemaName(), writeConfig.getOutputTableName());
    };
  }

  @NotNull
  private static Map<AirbyteStreamNameNamespacePair, WriteConfig> toMap(final List<WriteConfig> writeConfigs) {
    return writeConfigs.stream()
        .collect(Collectors.toUnmodifiableMap(JdbcBufferedConsumerFactory::toNameNamespacePair, Function.identity()));
  }

  /**
   * Tear down functionality
   */
  private static OnCloseFunction onCloseFunction() {
    // TODO something like this for DV2
    // (hasFailed) -> {
    // if (use1s1t) {
    // try {
    // typerDeduper.typeAndDedupe();
    // typerDeduper.commitFinalTables();
    // typerDeduper.cleanup();
    // } catch (final Exception e) {
    // throw new RuntimeException(e);
    // }
    // }
    // }
    return (hasFailed) -> {};
  }

  private static AirbyteStreamNameNamespacePair toNameNamespacePair(final WriteConfig config) {
    return new AirbyteStreamNameNamespacePair(config.getStreamName(), config.getNamespace());
  }

}
