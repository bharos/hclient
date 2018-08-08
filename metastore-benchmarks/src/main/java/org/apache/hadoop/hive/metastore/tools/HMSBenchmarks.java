/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.metastore.tools;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.apache.hadoop.hive.metastore.tools.Util.addManyPartitions;
import static org.apache.hadoop.hive.metastore.tools.Util.addManyPartitionsNoException;
import static org.apache.hadoop.hive.metastore.tools.Util.createSchema;
import static org.apache.hadoop.hive.metastore.tools.Util.generatePartitionNames;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.apache.hadoop.hive.metastore.tools.Util.throwingSupplierWrapper;

/**
 * Actual benchmark code.
 */
final class HMSBenchmarks {
  private static final Logger LOG = LoggerFactory.getLogger(HMSBenchmarks.class);

  private static final String PARAM_KEY = "parameter_";
  private static final String PARAM_VALUE = "value_";

  static DescriptiveStatistics benchmarkListDatabases(@NotNull MicroBenchmark benchmark,
                                                      @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    return benchmark.measure(() ->
        throwingSupplierWrapper(() -> client.getAllDatabases(null)));
  }

  static DescriptiveStatistics benchmarkListAllTables(@NotNull MicroBenchmark benchmark,
                                                      @NotNull BenchData data) {

    final HMSClient client = data.getClient();
    String dbName = data.dbName;

    return benchmark.measure(() ->
        throwingSupplierWrapper(() -> client.getAllTables(dbName, null)));
  }

  static DescriptiveStatistics benchmarkTableCreate(@NotNull MicroBenchmark bench,
                                                    @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;
    Table table = Util.TableBuilder.buildDefaultTable(dbName, tableName);

    return bench.measure(null,
        () -> throwingSupplierWrapper(() -> client.createTable(table)),
        () -> throwingSupplierWrapper(() -> client.dropTable(dbName, tableName)));
  }

  static DescriptiveStatistics benchmarkDeleteCreate(@NotNull MicroBenchmark bench,
                                                     @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;
    Table table = Util.TableBuilder.buildDefaultTable(dbName, tableName);

    return bench.measure(
        () -> throwingSupplierWrapper(() -> client.createTable(table)),
        () -> throwingSupplierWrapper(() -> client.dropTable(dbName, tableName)),
        null);
  }

  static DescriptiveStatistics benchmarkDeleteWithPartitions(@NotNull MicroBenchmark bench,
                                                             @NotNull BenchData data,
                                                             int howMany,
                                                             int nparams) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    // Create many parameters
    Map<String, String> parameters = new HashMap<>(nparams);
    for (int i = 0; i < nparams; i++) {
      parameters.put(PARAM_KEY + i, PARAM_VALUE + i);
    }

    return bench.measure(
        () -> throwingSupplierWrapper(() -> {
          createPartitionedTable(client, dbName, tableName);
          addManyPartitions(client, dbName, tableName, parameters,
              Collections.singletonList("d"), howMany);
          return true;
        }),
        () -> throwingSupplierWrapper(() -> client.dropTable(dbName, tableName)),
        null);
  }

  static DescriptiveStatistics benchmarkGetTable(@NotNull MicroBenchmark bench,
                                                 @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      return bench.measure(() ->
          throwingSupplierWrapper(() -> client.getTable(dbName, tableName)));
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkListTables(@NotNull MicroBenchmark bench,
                                                   @NotNull BenchData data,
                                                   int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;

    // Create a bunch of tables
    String format = "tmp_table_%d";
    try {
      createManyTables(client, count, dbName, format);
      return bench.measure(() ->
          throwingSupplierWrapper(() -> client.getAllTables(dbName, null)));
    } finally {
      dropManyTables(client, count, dbName, format);
    }
  }

  static DescriptiveStatistics benchmarkCreatePartition(@NotNull MicroBenchmark bench,
                                                        @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    final List<String> values = Collections.singletonList("d1");
    try {
      Table t = client.getTable(dbName, tableName);
      Partition partition = new Util.PartitionBuilder(t)
          .withValues(values)
          .build();

      return bench.measure(null,
          () -> throwingSupplierWrapper(() -> client.addPartition(partition)),
          () -> throwingSupplierWrapper(() -> client.dropPartition(dbName, tableName, values)));
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkListPartition(@NotNull MicroBenchmark bench,
                                                      @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitions(client, dbName, tableName, null,
          Collections.singletonList("d"), 1);

      return bench.measure(() ->
          throwingSupplierWrapper(() -> client.listPartitions(dbName, tableName)));
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkListManyPartitions(@NotNull MicroBenchmark bench,
                                                           @NotNull BenchData data,
                                                           int howMany) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitions(client, dbName, tableName, null, Collections.singletonList("d"), howMany);
      LOG.debug("Created {} partitions", howMany);
      LOG.debug("started benchmark... ");
      return bench.measure(() ->
          throwingSupplierWrapper(() -> client.listPartitions(dbName, tableName)));
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkGetPartitions(@NotNull MicroBenchmark bench,
                                                      @NotNull BenchData data,
                                                      int howMany) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitions(client, dbName, tableName, null, Collections.singletonList("d"), howMany);
      LOG.debug("Created {} partitions", howMany);
      LOG.debug("started benchmark... ");
      return bench.measure(() ->
          throwingSupplierWrapper(() -> client.getPartitions(dbName, tableName)));
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }
  static DescriptiveStatistics benchmarkCAAD(@NotNull MicroBenchmark bench,
      @NotNull BenchData data,
      int howMany,
      int nparams) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    // Create many parameters
    Map<String, String> parameters = new HashMap<>(nparams);
    for (int i = 0; i < nparams; i++) {
      parameters.put(PARAM_KEY + i, PARAM_VALUE + i);
    }

    DescriptiveStatistics stats = bench.measure(() -> {
      // Measuring 2 alter partitions, so the tests are idempotent
        try {
          createPartitionedTable(client, dbName, tableName);
          addManyPartitions(client, dbName, tableName, parameters, Collections.singletonList("d"), howMany);
          List<Partition> oldPartitions = client.getPartitions(dbName, tableName);
          List<Partition> newPartitions = new ArrayList<>();
          for (Partition partition : oldPartitions) {
            Partition newPartition = partition.deepCopy();
            StorageDescriptor sd = partition.getSd();
            sd.setLocation(partition.getSd().getLocation() + "/newLocation");
            newPartition.setSd(sd);
            newPartitions.add(newPartition);
          }
          client.alterPartitions(dbName, tableName, newPartitions);
          client.alterPartitions(dbName, tableName, oldPartitions);
          client.dropTable(dbName, tableName);
        } catch (TException e) {
          e.printStackTrace();
        }
      });
    return stats;

  }

  static DescriptiveStatistics benchmarkConcurrentCAAD(@NotNull MicroBenchmark bench, @NotNull BenchData data,
      int howMany, int nparams, int nThreads) {

    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;
    Long startNotificationId = 0L;
    Long endNotificationId = 0L;
    DescriptiveStatistics stats = new DescriptiveStatistics();

    try {
      startNotificationId = client.getCurrentNotificationId();
    } catch (TException e) {
      LOG.error("Could not fetch start notification ID.. Exiting..");
      return stats;
    }
    ExecutorService executor = newFixedThreadPool(nThreads);
    // Create many parameters

    try {
      stats = bench.measure(() -> executeCAAD(client, executor, dbName, tableName, howMany, nparams, nThreads));

      try {
        endNotificationId = client.getCurrentNotificationId();
      } catch (TException e) {
        LOG.error("Could not fetch end notification ID.. Exiting..");
        return stats;
      }
      long numNotifications = endNotificationId - startNotificationId;
      //scale = 1000000 # get time in milli seconds
      double totalTime = stats.getSum()/1000000;
      LOG.info("Number of notifications generated : " + numNotifications);
      LOG.info("Total time : " + totalTime);
      double eventsPerUnitTime = numNotifications / totalTime;
      LOG.info("Events per second : " + eventsPerUnitTime*1000);
    } finally {
      executor.shutdownNow();
    }
    return stats;
  }

  private static void executeCAAD(HMSClient client, ExecutorService executor, String dbName, String tableName,
      int howMany, int nparams, int nThreads) {

    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < nThreads; i++) {
      final int j = i;
      LOG.info("Execute thread " + j);
      String tableNameForCurThread = tableName + "_" + j;
      LOG.info("TableName : " + tableNameForCurThread);
      //cloning the client
      results.add(executor.submit(() -> caad(client.Clone(), dbName, tableNameForCurThread, nparams, howMany)));
    }
    // Wait for results
    results.forEach(r -> throwingSupplierWrapper(r::get));

  }

  private static boolean caad(HMSClient client, String dbName,
      String tableName, int nparams, int howMany) {
    try {
      createPartitionedTable(client, dbName, tableName);
      Map<String, String> parameters = new HashMap<>(nparams);
      for (int i = 0; i < nparams; i++) {
        parameters.put(PARAM_KEY + i, PARAM_VALUE + i);
      }
      addManyPartitions(client, dbName, tableName, parameters, Collections.singletonList("d"), howMany);
      List<Partition> oldPartitions = client.getPartitions(dbName, tableName);
      List<Partition> newPartitions = new ArrayList<>();
      for (Partition partition : oldPartitions) {
        Partition newPartition = partition.deepCopy();
        StorageDescriptor sd = partition.getSd().deepCopy();
        sd.setLocation(sd.getLocation() + "/newLocation");
        newPartition.setSd(sd);
        newPartitions.add(newPartition);
      }
      client.alterPartitions(dbName, tableName, newPartitions);


      for (Partition partition : oldPartitions) {
        client.alterPartition(dbName, tableName, partition);
      }
      client.dropTable(dbName, tableName);
    } catch (TException e) {
      e.printStackTrace();
    }
    return true;
  }

  static DescriptiveStatistics benchmarkAlterPartitions(@NotNull MicroBenchmark bench, @NotNull BenchData data,
      int howMany, int nparams) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;
    List<Partition> newPartitions = new ArrayList<>();
    List<Partition> oldPartitions;
    // Create many parameters
    Map<String, String> parameters = new HashMap<>(nparams);
    for (int i = 0; i < nparams; i++) {
      parameters.put(PARAM_KEY + i, PARAM_VALUE + i);
    }
    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitions(client, dbName, tableName, parameters,
          Collections.singletonList("d"), howMany);
      oldPartitions = client.getPartitions(dbName, tableName);

      for(Partition partition : oldPartitions) {
        Partition newPartition = partition.deepCopy();
        StorageDescriptor sd = partition.getSd().deepCopy();
        sd.setLocation(sd.getLocation()+"/newLocation");
        newPartition.setSd(sd);
        newPartitions.add(newPartition);
      }

      DescriptiveStatistics stats = bench.measure(() -> {
        // Measuring 2 alter partitions, so the tests are idempotent
          try {
            client.alterPartitions(dbName, tableName, newPartitions);
            List<Partition> partitions = client.getPartitions(dbName, tableName);
            for(Partition p : partitions) {
              p.getSd().setLocation(p.getSd().getLocation()+"/newLocation2");
              client.alterPartition(dbName, tableName, p);
            }

          } catch (TException e) {
            e.printStackTrace();
          }
        });
      client.dropTable(dbName, tableName);
      return stats;
    } catch (TException e) {
      e.printStackTrace();
    }
    return new DescriptiveStatistics();
  }

  static DescriptiveStatistics benchmarkDropPartition(@NotNull MicroBenchmark bench,
                                                      @NotNull BenchData data) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    final List<String> values = Collections.singletonList("d1");
    try {
      Table t = client.getTable(dbName, tableName);
      Partition partition = new Util.PartitionBuilder(t)
          .withValues(values)
          .build();

      return bench.measure(
          () -> throwingSupplierWrapper(() -> client.addPartition(partition)),
          () -> throwingSupplierWrapper(() -> client.dropPartition(dbName, tableName, values)),
          null);
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkCreatePartitions(@NotNull MicroBenchmark bench,
                                                         @NotNull BenchData data,
                                                         int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      return bench.measure(
          null,
          () -> addManyPartitionsNoException(client, dbName, tableName, null,
              Collections.singletonList("d"), count),
          () -> throwingSupplierWrapper(() ->
              client.dropPartitions(dbName, tableName, null))
      );
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkDropPartitions(@NotNull MicroBenchmark bench,
                                                       @NotNull BenchData data,
                                                       int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      return bench.measure(
          () -> addManyPartitionsNoException(client, dbName, tableName, null,
              Collections.singletonList("d"), count),
          () -> throwingSupplierWrapper(() ->
              client.dropPartitions(dbName, tableName, null)),
          null
      );
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkGetPartitionNames(@NotNull MicroBenchmark bench,
                                                          @NotNull BenchData data,
                                                          int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitionsNoException(client, dbName, tableName, null,
          Collections.singletonList("d"), count);
      return bench.measure(
          () -> throwingSupplierWrapper(() -> client.getPartitionNames(dbName, tableName))
      );
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkGetPartitionsByName(@NotNull MicroBenchmark bench,
                                                            @NotNull BenchData data,
                                                            int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitionsNoException(client, dbName, tableName, null,
          Collections.singletonList("d"), count);
      List<String> partitionNames = throwingSupplierWrapper(() ->
          client.getPartitionNames(dbName, tableName));
      return bench.measure(
          () ->
              throwingSupplierWrapper(() ->
                  client.getPartitionsByNames(dbName, tableName, partitionNames))
      );
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkRenameTable(@NotNull MicroBenchmark bench,
                                                    @NotNull BenchData data,
                                                    int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    createPartitionedTable(client, dbName, tableName);
    try {
      addManyPartitionsNoException(client, dbName, tableName, null,
          Collections.singletonList("d"), count);
      Table oldTable = client.getTable(dbName, tableName);
      oldTable.getSd().setLocation("");
      Table newTable = oldTable.deepCopy();
      newTable.setTableName(tableName + "_renamed");

      return bench.measure(
          () -> {
            // Measuring 2 renames, so the tests are idempotent
            throwingSupplierWrapper(() ->
                client.alterTable(oldTable.getDbName(), oldTable.getTableName(), newTable));
            throwingSupplierWrapper(() ->
                client.alterTable(newTable.getDbName(), newTable.getTableName(), oldTable));
          }
      );
    } catch (TException e) {
      e.printStackTrace();
      return new DescriptiveStatistics();
    } finally {
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  static DescriptiveStatistics benchmarkDropDatabase(@NotNull MicroBenchmark bench,
                                                     @NotNull BenchData data,
                                                     int count) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;

    throwingSupplierWrapper(() -> client.dropDatabase(dbName));
    try {
      return bench.measure(
          () -> {
            throwingSupplierWrapper(() -> client.createDatabase(dbName));
            createManyTables(client, count, dbName, "tmp_table_%d");
          },
          () -> throwingSupplierWrapper(() -> client.dropDatabase(dbName)),
          null
      );
    } finally {
      throwingSupplierWrapper(() -> client.createDatabase(dbName));
    }
  }

  static DescriptiveStatistics benchmarkConcurrentPartitionOps(@NotNull MicroBenchmark bench,
                                                               @NotNull BenchData data,
                                                               int instances, int nThreads) {
    final HMSClient client = data.getClient();
    String dbName = data.dbName;
    String tableName = data.tableName;

    ExecutorService executor = newFixedThreadPool(nThreads);
    createPartitionedTable(client, dbName, tableName);
    try {
      Table tbl = throwingSupplierWrapper(() -> client.getTable(dbName, tableName));
      return bench.measure(() -> executeAddPartitions(client, executor, tbl,
          instances, nThreads));
    } finally {
      executor.shutdownNow();
      throwingSupplierWrapper(() -> client.dropTable(dbName, tableName));
    }
  }

  private static void createManyTables(HMSClient client, int howMany, String dbName, String format) {
    List<FieldSchema> columns = createSchema(new ArrayList<>(Arrays.asList("name", "string")));
    List<FieldSchema> partitions = createSchema(new ArrayList<>(Arrays.asList("date", "string")));
    IntStream.range(0, howMany)
        .forEach(i ->
            throwingSupplierWrapper(() -> client.createTable(
                new Util.TableBuilder(dbName, String.format(format, i))
                    .withType(TableType.MANAGED_TABLE)
                    .withColumns(columns)
                    .withPartitionKeys(partitions)
                    .build())));
  }

  private static void dropManyTables(HMSClient client, int howMany, String dbName, String format) {
    IntStream.range(0, howMany)
        .forEach(i ->
            throwingSupplierWrapper(() -> client.dropTable(dbName, String.format(format, i))));
  }

  // Create a simple table with a single column and single partition
  private static void createPartitionedTable(HMSClient client, String dbName, String tableName) {
    throwingSupplierWrapper(() -> client.createTable(
        new Util.TableBuilder(dbName, tableName)
            .withType(TableType.MANAGED_TABLE)
            .withColumns(createSchema(Collections.singletonList("name:string")))
            .withPartitionKeys(createSchema(Collections.singletonList("date")))
            .build()));
  }

  static DescriptiveStatistics benchmarkGetNotificationId(@NotNull MicroBenchmark benchmark,
                                                          @NotNull BenchData data) {
    HMSClient client = data.getClient();
    return benchmark.measure(() ->
        throwingSupplierWrapper(client::getCurrentNotificationId));
  }


  private static void executeAddPartitions(HMSClient client, ExecutorService executor,
                                           Table tbl,
                                           int instances, int count) {
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final int j = i;
      results.add(executor.submit(() -> addDropPartitions(client, tbl, instances, j)));
    }
    // Wait for results
    results.forEach(r -> throwingSupplierWrapper(r::get));
  }

  private static boolean addDropPartitions(HMSClient c, Table tbl, int instances, int instance) {
    List<Partition> partitions = Util.createManyPartitions(tbl, null,
        Collections.singletonList("d"+instance), instances);
    try (HMSClient client = c.Clone()) {
      client.addPartitions(partitions);
      client.dropPartitions(tbl.getDbName(), tbl.getTableName(),
          generatePartitionNames("date=d"+instance, instances));
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
