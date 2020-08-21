/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table;

import org.apache.hudi.avro.model.HoodieActionInstant;
import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieCleanPartitionMetadata;
import org.apache.hudi.avro.model.HoodieCleanerPlan;
import org.apache.hudi.avro.model.HoodieCompactionPlan;
import org.apache.hudi.avro.model.HoodieFileStatus;
import org.apache.hudi.client.HoodieWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieCleanStat;
import org.apache.hudi.common.bootstrap.TestBootstrapIndex;
import org.apache.hudi.common.fs.ConsistencyGuardConfig;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.BootstrapFileMapping;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFileGroup;
import org.apache.hudi.common.model.HoodieFileGroupId;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.table.timeline.versioning.clean.CleanMetadataMigrator;
import org.apache.hudi.common.table.timeline.versioning.clean.CleanPlanMigrator;
import org.apache.hudi.common.table.timeline.versioning.clean.CleanPlanV1MigrationHandler;
import org.apache.hudi.common.table.view.TableFileSystemView;
import org.apache.hudi.common.testutils.FileSystemTestUtils;
import org.apache.hudi.common.testutils.HoodieTestDataGenerator;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.CleanerUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.CompactionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.table.action.clean.CleanPlanner;
import org.apache.hudi.testutils.HoodieClientTestBase;
import org.apache.hudi.testutils.HoodieClientTestUtils;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import scala.Tuple3;

import static org.apache.hudi.common.testutils.HoodieTestUtils.DEFAULT_PARTITION_PATHS;
import static org.apache.hudi.testutils.Assertions.assertNoWriteErrors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test Cleaning related logic.
 */
public class TestCleaner extends HoodieClientTestBase {

  private static final int BIG_BATCH_INSERT_SIZE = 500;
  private static final Logger LOG = LogManager.getLogger(TestCleaner.class);

  /**
   * Helper method to do first batch of insert for clean by versions/commits tests.
   *
   * @param cfg Hoodie Write Config
   * @param client Hoodie Client
   * @param recordGenFunction Function to generate records for insertion
   * @param insertFn Insertion API for testing
   * @throws Exception in case of error
   */
  private void insertFirstBigBatchForClientCleanerTest(HoodieWriteConfig cfg, HoodieWriteClient client,
      Function2<List<HoodieRecord>, String, Integer> recordGenFunction,
      Function3<JavaRDD<WriteStatus>, HoodieWriteClient, JavaRDD<HoodieRecord>, String> insertFn,
      HoodieCleaningPolicy cleaningPolicy) throws Exception {

    /*
     * do a big insert (this is basically same as insert part of upsert, just adding it here so we can catch breakages
     * in insert(), if the implementation diverges.)
     */
    String newCommitTime = client.startCommit();

    List<HoodieRecord> records = recordGenFunction.apply(newCommitTime, BIG_BATCH_INSERT_SIZE);
    JavaRDD<HoodieRecord> writeRecords = jsc.parallelize(records, 5);

    List<WriteStatus> statuses = insertFn.apply(client, writeRecords, newCommitTime).collect();
    // Verify there are no errors
    assertNoWriteErrors(statuses);

    // verify that there is a commit
    metaClient = HoodieTableMetaClient.reload(metaClient);
    HoodieTimeline timeline = new HoodieActiveTimeline(metaClient).getCommitTimeline();
    assertEquals(1, timeline.findInstantsAfter("000", Integer.MAX_VALUE).countInstants(), "Expecting a single commit.");
    // Should have 100 records in table (check using Index), all in locations marked at commit
    HoodieTable table = HoodieTable.create(metaClient, client.getConfig(), hadoopConf);

    assertFalse(table.getCompletedCommitsTimeline().empty());
    // We no longer write empty cleaner plans when there is nothing to be cleaned.
    assertTrue(table.getCompletedCleanTimeline().empty());

    HoodieIndex index = HoodieIndex.createIndex(cfg);
    List<HoodieRecord> taggedRecords = index.tagLocation(jsc.parallelize(records, 1), jsc, table).collect();
    checkTaggedRecords(taggedRecords, newCommitTime);
  }

  /**
   * Test Clean-By-Versions using insert/upsert API.
   */
  @Test
  public void testInsertAndCleanByVersions() throws Exception {
    testInsertAndCleanByVersions(HoodieWriteClient::insert, HoodieWriteClient::upsert, false);
  }

  /**
   * Test Clean-By-Versions using prepped versions of insert/upsert API.
   */
  @Test
  public void testInsertPreppedAndCleanByVersions() throws Exception {
    testInsertAndCleanByVersions(HoodieWriteClient::insertPreppedRecords, HoodieWriteClient::upsertPreppedRecords,
        true);
  }

  /**
   * Test Clean-By-Versions using bulk-insert/upsert API.
   */
  @Test
  public void testBulkInsertAndCleanByVersions() throws Exception {
    testInsertAndCleanByVersions(HoodieWriteClient::bulkInsert, HoodieWriteClient::upsert, false);
  }

  /**
   * Test Clean-By-Versions using prepped versions of bulk-insert/upsert API.
   */
  @Test
  public void testBulkInsertPreppedAndCleanByVersions() throws Exception {
    testInsertAndCleanByVersions(
        (client, recordRDD, instantTime) -> client.bulkInsertPreppedRecords(recordRDD, instantTime, Option.empty()),
        HoodieWriteClient::upsertPreppedRecords, true);
  }

  /**
   * Test Helper for Cleaning by versions logic from HoodieWriteClient API perspective.
   *
   * @param insertFn Insert API to be tested
   * @param upsertFn Upsert API to be tested
   * @param isPreppedAPI Flag to indicate if a prepped-version is used. If true, a wrapper function will be used during
   *        record generation to also tag the regards (de-dupe is implicit as we use uniq record-gen APIs)
   * @throws Exception in case of errors
   */
  private void testInsertAndCleanByVersions(
      Function3<JavaRDD<WriteStatus>, HoodieWriteClient, JavaRDD<HoodieRecord>, String> insertFn,
      Function3<JavaRDD<WriteStatus>, HoodieWriteClient, JavaRDD<HoodieRecord>, String> upsertFn, boolean isPreppedAPI)
      throws Exception {
    int maxVersions = 2; // keep upto 2 versions for each file
    HoodieWriteConfig cfg = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS).retainFileVersions(maxVersions).build())
        .withParallelism(1, 1).withBulkInsertParallelism(1).withFinalizeWriteParallelism(1).withDeleteParallelism(1)
        .withConsistencyGuardConfig(ConsistencyGuardConfig.newBuilder().withConsistencyCheckEnabled(true).build())
        .build();
    try (HoodieWriteClient client = getHoodieWriteClient(cfg);) {

      final Function2<List<HoodieRecord>, String, Integer> recordInsertGenWrappedFunction =
          generateWrapRecordsFn(isPreppedAPI, cfg, dataGen::generateInserts);

      final Function2<List<HoodieRecord>, String, Integer> recordUpsertGenWrappedFunction =
          generateWrapRecordsFn(isPreppedAPI, cfg, dataGen::generateUniqueUpdates);

      insertFirstBigBatchForClientCleanerTest(cfg, client, recordInsertGenWrappedFunction, insertFn,
          HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS);

      Map<HoodieFileGroupId, FileSlice> compactionFileIdToLatestFileSlice = new HashMap<>();
      metaClient = HoodieTableMetaClient.reload(metaClient);
      HoodieTable table = HoodieTable.create(metaClient, getConfig(), hadoopConf);
      for (String partitionPath : dataGen.getPartitionPaths()) {
        TableFileSystemView fsView = table.getFileSystemView();
        Option<Boolean> added = Option.fromJavaOptional(fsView.getAllFileGroups(partitionPath).findFirst().map(fg -> {
          fg.getLatestFileSlice().map(fs -> compactionFileIdToLatestFileSlice.put(fg.getFileGroupId(), fs));
          return true;
        }));
        if (added.isPresent()) {
          // Select only one file-group for compaction
          break;
        }
      }

      // Create workload with selected file-slices
      List<Pair<String, FileSlice>> partitionFileSlicePairs = compactionFileIdToLatestFileSlice.entrySet().stream()
          .map(e -> Pair.of(e.getKey().getPartitionPath(), e.getValue())).collect(Collectors.toList());
      HoodieCompactionPlan compactionPlan =
          CompactionUtils.buildFromFileSlices(partitionFileSlicePairs, Option.empty(), Option.empty());
      List<String> instantTimes = HoodieTestUtils.monotonicIncreasingCommitTimestamps(9, 1);
      String compactionTime = instantTimes.get(0);
      table.getActiveTimeline().saveToCompactionRequested(
          new HoodieInstant(State.REQUESTED, HoodieTimeline.COMPACTION_ACTION, compactionTime),
          TimelineMetadataUtils.serializeCompactionPlan(compactionPlan));

      instantTimes = instantTimes.subList(1, instantTimes.size());
      // Keep doing some writes and clean inline. Make sure we have expected number of files
      // remaining.
      for (String newInstantTime : instantTimes) {
        try {
          client.startCommitWithTime(newInstantTime);
          List<HoodieRecord> records = recordUpsertGenWrappedFunction.apply(newInstantTime, 100);

          List<WriteStatus> statuses = upsertFn.apply(client, jsc.parallelize(records, 1), newInstantTime).collect();
          // Verify there are no errors
          assertNoWriteErrors(statuses);

          metaClient = HoodieTableMetaClient.reload(metaClient);
          table = HoodieTable.create(metaClient, getConfig(), hadoopConf);
          HoodieTimeline timeline = table.getMetaClient().getCommitsTimeline();

          TableFileSystemView fsView = table.getFileSystemView();
          // Need to ensure the following
          for (String partitionPath : dataGen.getPartitionPaths()) {
            // compute all the versions of all files, from time 0
            HashMap<String, TreeSet<String>> fileIdToVersions = new HashMap<>();
            for (HoodieInstant entry : timeline.getInstants().collect(Collectors.toList())) {
              HoodieCommitMetadata commitMetadata =
                  HoodieCommitMetadata.fromBytes(timeline.getInstantDetails(entry).get(), HoodieCommitMetadata.class);

              for (HoodieWriteStat wstat : commitMetadata.getWriteStats(partitionPath)) {
                if (!fileIdToVersions.containsKey(wstat.getFileId())) {
                  fileIdToVersions.put(wstat.getFileId(), new TreeSet<>());
                }
                fileIdToVersions.get(wstat.getFileId()).add(FSUtils.getCommitTime(new Path(wstat.getPath()).getName()));
              }
            }

            List<HoodieFileGroup> fileGroups = fsView.getAllFileGroups(partitionPath).collect(Collectors.toList());

            for (HoodieFileGroup fileGroup : fileGroups) {
              if (compactionFileIdToLatestFileSlice.containsKey(fileGroup.getFileGroupId())) {
                // Ensure latest file-slice selected for compaction is retained
                Option<HoodieBaseFile> dataFileForCompactionPresent =
                    Option.fromJavaOptional(fileGroup.getAllBaseFiles().filter(df -> {
                      return compactionFileIdToLatestFileSlice.get(fileGroup.getFileGroupId()).getBaseInstantTime()
                          .equals(df.getCommitTime());
                    }).findAny());
                assertTrue(dataFileForCompactionPresent.isPresent(),
                    "Data File selected for compaction is retained");
              } else {
                // file has no more than max versions
                String fileId = fileGroup.getFileGroupId().getFileId();
                List<HoodieBaseFile> dataFiles = fileGroup.getAllBaseFiles().collect(Collectors.toList());

                assertTrue(dataFiles.size() <= maxVersions,
                    "fileId " + fileId + " has more than " + maxVersions + " versions");

                // Each file, has the latest N versions (i.e cleaning gets rid of older versions)
                List<String> commitedVersions = new ArrayList<>(fileIdToVersions.get(fileId));
                for (int i = 0; i < dataFiles.size(); i++) {
                  assertEquals((dataFiles.get(i)).getCommitTime(),
                      commitedVersions.get(commitedVersions.size() - 1 - i),
                      "File " + fileId + " does not have latest versions on commits" + commitedVersions);
                }
              }
            }
          }
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }
      }
    }
  }

  /**
   * Test Clean-By-Versions using insert/upsert API.
   */
  @Test
  public void testInsertAndCleanByCommits() throws Exception {
    testInsertAndCleanByCommits(HoodieWriteClient::insert, HoodieWriteClient::upsert, false);
  }

  /**
   * Test Clean-By-Versions using prepped version of insert/upsert API.
   */
  @Test
  public void testInsertPreppedAndCleanByCommits() throws Exception {
    testInsertAndCleanByCommits(HoodieWriteClient::insertPreppedRecords, HoodieWriteClient::upsertPreppedRecords, true);
  }

  /**
   * Test Clean-By-Versions using prepped versions of bulk-insert/upsert API.
   */
  @Test
  public void testBulkInsertPreppedAndCleanByCommits() throws Exception {
    testInsertAndCleanByCommits(
        (client, recordRDD, instantTime) -> client.bulkInsertPreppedRecords(recordRDD, instantTime, Option.empty()),
        HoodieWriteClient::upsertPreppedRecords, true);
  }

  /**
   * Test Clean-By-Versions using bulk-insert/upsert API.
   */
  @Test
  public void testBulkInsertAndCleanByCommits() throws Exception {
    testInsertAndCleanByCommits(HoodieWriteClient::bulkInsert, HoodieWriteClient::upsert, false);
  }

  /**
   * Test Helper for Cleaning by versions logic from HoodieWriteClient API perspective.
   *
   * @param insertFn Insert API to be tested
   * @param upsertFn Upsert API to be tested
   * @param isPreppedAPI Flag to indicate if a prepped-version is used. If true, a wrapper function will be used during
   *        record generation to also tag the regards (de-dupe is implicit as we use uniq record-gen APIs)
   * @throws Exception in case of errors
   */
  private void testInsertAndCleanByCommits(
      Function3<JavaRDD<WriteStatus>, HoodieWriteClient, JavaRDD<HoodieRecord>, String> insertFn,
      Function3<JavaRDD<WriteStatus>, HoodieWriteClient, JavaRDD<HoodieRecord>, String> upsertFn, boolean isPreppedAPI)
      throws Exception {
    int maxCommits = 3; // keep upto 3 commits from the past
    HoodieWriteConfig cfg = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS).retainCommits(maxCommits).build())
        .withParallelism(1, 1).withBulkInsertParallelism(1).withFinalizeWriteParallelism(1).withDeleteParallelism(1)
        .withConsistencyGuardConfig(ConsistencyGuardConfig.newBuilder().withConsistencyCheckEnabled(true).build())
        .build();
    HoodieWriteClient client = getHoodieWriteClient(cfg);

    final Function2<List<HoodieRecord>, String, Integer> recordInsertGenWrappedFunction =
        generateWrapRecordsFn(isPreppedAPI, cfg, dataGen::generateInserts);

    final Function2<List<HoodieRecord>, String, Integer> recordUpsertGenWrappedFunction =
        generateWrapRecordsFn(isPreppedAPI, cfg, dataGen::generateUniqueUpdates);

    insertFirstBigBatchForClientCleanerTest(cfg, client, recordInsertGenWrappedFunction, insertFn,
        HoodieCleaningPolicy.KEEP_LATEST_COMMITS);

    // Keep doing some writes and clean inline. Make sure we have expected number of files remaining.
    HoodieTestUtils.monotonicIncreasingCommitTimestamps(8, 1).forEach(newCommitTime -> {
      try {
        client.startCommitWithTime(newCommitTime);
        List<HoodieRecord> records = recordUpsertGenWrappedFunction.apply(newCommitTime, 100);

        List<WriteStatus> statuses = upsertFn.apply(client, jsc.parallelize(records, 1), newCommitTime).collect();
        // Verify there are no errors
        assertNoWriteErrors(statuses);

        metaClient = HoodieTableMetaClient.reload(metaClient);
        HoodieTable table1 = HoodieTable.create(metaClient, cfg, hadoopConf);
        HoodieTimeline activeTimeline = table1.getCompletedCommitsTimeline();
        // NOTE: See CleanPlanner#getFilesToCleanKeepingLatestCommits. We explicitly keep one commit before earliest
        // commit
        Option<HoodieInstant> earliestRetainedCommit = activeTimeline.nthFromLastInstant(maxCommits);
        Set<HoodieInstant> acceptableCommits = activeTimeline.getInstants().collect(Collectors.toSet());
        if (earliestRetainedCommit.isPresent()) {
          acceptableCommits
              .removeAll(activeTimeline.findInstantsInRange("000", earliestRetainedCommit.get().getTimestamp())
                  .getInstants().collect(Collectors.toSet()));
          acceptableCommits.add(earliestRetainedCommit.get());
        }

        TableFileSystemView fsView = table1.getFileSystemView();
        // Need to ensure the following
        for (String partitionPath : dataGen.getPartitionPaths()) {
          List<HoodieFileGroup> fileGroups = fsView.getAllFileGroups(partitionPath).collect(Collectors.toList());
          for (HoodieFileGroup fileGroup : fileGroups) {
            Set<String> commitTimes = new HashSet<>();
            fileGroup.getAllBaseFiles().forEach(value -> {
              LOG.debug("Data File - " + value);
              commitTimes.add(value.getCommitTime());
            });
            assertEquals(acceptableCommits.stream().map(HoodieInstant::getTimestamp).collect(Collectors.toSet()), commitTimes,
                "Only contain acceptable versions of file should be present");
          }
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    });
  }

  /**
   * Helper to run cleaner and collect Clean Stats.
   *
   * @param config HoodieWriteConfig
   */
  private List<HoodieCleanStat> runCleaner(HoodieWriteConfig config) throws IOException {
    return runCleaner(config, false);
  }

  /**
   * Helper to run cleaner and collect Clean Stats.
   *
   * @param config HoodieWriteConfig
   */
  private List<HoodieCleanStat> runCleaner(HoodieWriteConfig config, boolean simulateRetryFailure) throws IOException {
    HoodieWriteClient<?> writeClient = getHoodieWriteClient(config);
    String cleanInstantTs = getNextInstant();
    HoodieCleanMetadata cleanMetadata1 = writeClient.clean(cleanInstantTs);

    if (null == cleanMetadata1) {
      return new ArrayList<>();
    }

    if (simulateRetryFailure) {
      HoodieInstant completedCleanInstant = new HoodieInstant(State.COMPLETED, HoodieTimeline.CLEAN_ACTION, cleanInstantTs);
      HoodieCleanMetadata metadata = CleanerUtils.getCleanerMetadata(metaClient, completedCleanInstant);
      metadata.getPartitionMetadata().values().forEach(p -> {
        String dirPath = metaClient.getBasePath() + "/" + p.getPartitionPath();
        p.getSuccessDeleteFiles().forEach(p2 -> {
          try {
            metaClient.getFs().create(new Path(dirPath, p2), true);
          } catch (IOException e) {
            throw new HoodieIOException(e.getMessage(), e);
          }
        });
      });
      metaClient.reloadActiveTimeline().revertToInflight(completedCleanInstant);
      HoodieCleanMetadata newCleanMetadata = writeClient.clean(getNextInstant());
      // No new clean metadata would be created. Only the previous one will be retried
      assertNull(newCleanMetadata);
      HoodieCleanMetadata cleanMetadata2 = CleanerUtils.getCleanerMetadata(metaClient, completedCleanInstant);
      assertEquals(cleanMetadata1.getEarliestCommitToRetain(), cleanMetadata2.getEarliestCommitToRetain());
      assertEquals(cleanMetadata1.getTotalFilesDeleted(), cleanMetadata2.getTotalFilesDeleted());
      assertEquals(cleanMetadata1.getPartitionMetadata().keySet(), cleanMetadata2.getPartitionMetadata().keySet());
      final HoodieCleanMetadata retriedCleanMetadata = CleanerUtils.getCleanerMetadata(HoodieTableMetaClient.reload(metaClient), completedCleanInstant);
      cleanMetadata1.getPartitionMetadata().keySet().forEach(k -> {
        HoodieCleanPartitionMetadata p1 = cleanMetadata1.getPartitionMetadata().get(k);
        HoodieCleanPartitionMetadata p2 = retriedCleanMetadata.getPartitionMetadata().get(k);
        assertEquals(p1.getDeletePathPatterns(), p2.getDeletePathPatterns());
        assertEquals(p1.getSuccessDeleteFiles(), p2.getSuccessDeleteFiles());
        assertEquals(p1.getFailedDeleteFiles(), p2.getFailedDeleteFiles());
        assertEquals(p1.getPartitionPath(), p2.getPartitionPath());
        assertEquals(k, p1.getPartitionPath());
      });
    }

    Map<String, HoodieCleanStat> cleanStatMap = cleanMetadata1.getPartitionMetadata().values().stream()
        .map(x -> new HoodieCleanStat.Builder().withPartitionPath(x.getPartitionPath())
            .withFailedDeletes(x.getFailedDeleteFiles()).withSuccessfulDeletes(x.getSuccessDeleteFiles())
            .withPolicy(HoodieCleaningPolicy.valueOf(x.getPolicy())).withDeletePathPattern(x.getDeletePathPatterns())
            .withEarliestCommitRetained(Option.ofNullable(cleanMetadata1.getEarliestCommitToRetain() != null
                ? new HoodieInstant(State.COMPLETED, HoodieTimeline.COMMIT_ACTION, "000")
                : null))
            .build())
        .collect(Collectors.toMap(HoodieCleanStat::getPartitionPath, x -> x));
    cleanMetadata1.getBootstrapPartitionMetadata().values().forEach(x -> {
      HoodieCleanStat s = cleanStatMap.get(x.getPartitionPath());
      cleanStatMap.put(x.getPartitionPath(), new HoodieCleanStat.Builder().withPartitionPath(x.getPartitionPath())
          .withFailedDeletes(s.getFailedDeleteFiles()).withSuccessfulDeletes(s.getSuccessDeleteFiles())
          .withPolicy(HoodieCleaningPolicy.valueOf(x.getPolicy())).withDeletePathPattern(s.getDeletePathPatterns())
          .withEarliestCommitRetained(Option.ofNullable(s.getEarliestCommitToRetain())
              .map(y -> new HoodieInstant(State.COMPLETED, HoodieTimeline.COMMIT_ACTION, y)))
          .withSuccessfulDeleteBootstrapBaseFiles(x.getSuccessDeleteFiles())
          .withFailedDeleteBootstrapBaseFiles(x.getFailedDeleteFiles())
          .withDeleteBootstrapBasePathPatterns(x.getDeletePathPatterns()).build());
    });
    return new ArrayList<>(cleanStatMap.values());
  }

  /**
   * Test HoodieTable.clean() Cleaning by versions for COW table.
   */
  @Test
  public void testKeepLatestFileVersions() throws IOException {
    testKeepLatestFileVersions(false);
  }

  /**
   * Test HoodieTable.clean() Cleaning by version logic for COW table with Bootstrap source file clean enable.
   */
  @Test
  public void testBootstrapSourceFileCleanWithKeepLatestFileVersions() throws IOException {
    testKeepLatestFileVersions(true);
  }

  /**
   * Test HoodieTable.clean() Cleaning by versions logic.
   */
  public void testKeepLatestFileVersions(Boolean enableBootstrapSourceClean) throws IOException {
    HoodieWriteConfig config =
        HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
            .withCompactionConfig(HoodieCompactionConfig.newBuilder()
                .withCleanBootstrapBaseFileEnabled(enableBootstrapSourceClean)
                .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS).retainFileVersions(1).build())
            .build();

    // make 1 commit, with 1 file per partition
    HoodieTestUtils.createCommitFiles(basePath, "00000000000001");

    Map<String, List<BootstrapFileMapping>> bootstrapMapping = enableBootstrapSourceClean ? generateBootstrapIndexAndSourceData() : null;

    String file1P0C0 = enableBootstrapSourceClean ? bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).get(0).getFileId()
        : UUID.randomUUID().toString();
    String file1P1C0 = enableBootstrapSourceClean ? bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH).get(0).getFileId()
        : UUID.randomUUID().toString();
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001", file1P0C0); // insert
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000001", file1P1C0); // insert

    List<HoodieCleanStat> hoodieCleanStatsOne = runCleaner(config);
    assertEquals(0, hoodieCleanStatsOne.size(), "Must not clean any files");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000001",
        file1P1C0));

    // make next commit, with 1 insert & 1 update per partition
    HoodieTestUtils.createCommitFiles(basePath, "00000000000002");
    metaClient = HoodieTableMetaClient.reload(metaClient);

    String file2P0C1 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002"); // insert
    String file2P1C1 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002"); // insert
    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002", file1P0C0); // update
    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002", file1P1C0); // update

    List<HoodieCleanStat> hoodieCleanStatsTwo = runCleaner(config);
    // enableBootstrapSourceClean would delete the bootstrap base file as the same time
    HoodieCleanStat cleanStat = getCleanStat(hoodieCleanStatsTwo, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH);
    assertEquals(enableBootstrapSourceClean ? 2 : 1, cleanStat.getSuccessDeleteFiles().size()
        + (cleanStat.getSuccessDeleteBootstrapBaseFiles() == null ? 0
        : cleanStat.getSuccessDeleteBootstrapBaseFiles().size()), "Must clean at least 1 file");
    if (enableBootstrapSourceClean) {
      HoodieFileStatus fstatus =
          bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).get(0).getBoostrapFileStatus();
      // This ensures full path is recorded in metadata.
      assertTrue(cleanStat.getSuccessDeleteBootstrapBaseFiles().contains(fstatus.getPath().getUri()),
          "Successful delete files were " + cleanStat.getSuccessDeleteBootstrapBaseFiles()
              + " but did not contain " + fstatus.getPath().getUri());
      assertFalse(new File(bootstrapMapping.get(
          HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).get(0).getBoostrapFileStatus().getPath().getUri()).exists());
    }
    cleanStat = getCleanStat(hoodieCleanStatsTwo, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH);
    assertEquals(enableBootstrapSourceClean ? 2 : 1, cleanStat.getSuccessDeleteFiles().size()
        + (cleanStat.getSuccessDeleteBootstrapBaseFiles() == null ? 0
        : cleanStat.getSuccessDeleteBootstrapBaseFiles().size()), "Must clean at least 1 file");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file2P0C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002",
        file2P1C1));
    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));
    if (enableBootstrapSourceClean) {
      HoodieFileStatus fstatus =
          bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH).get(0).getBoostrapFileStatus();
      // This ensures full path is recorded in metadata.
      assertTrue(cleanStat.getSuccessDeleteBootstrapBaseFiles().contains(fstatus.getPath().getUri()),
          "Successful delete files were " + cleanStat.getSuccessDeleteBootstrapBaseFiles()
              + " but did not contain " + fstatus.getPath().getUri());
      assertFalse(new File(bootstrapMapping.get(
          HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH).get(0).getBoostrapFileStatus().getPath().getUri()).exists());
    }
    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH,
        "00000000000001", file1P1C0));

    // make next commit, with 2 updates to existing files, and 1 insert
    HoodieTestUtils.createCommitFiles(basePath, "00000000000003");
    metaClient = HoodieTableMetaClient.reload(metaClient);

    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003", file1P0C0); // update
    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003", file2P0C1); // update
    String file3P0C2 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003");

    List<HoodieCleanStat> hoodieCleanStatsThree = runCleaner(config);
    assertEquals(2,
        getCleanStat(hoodieCleanStatsThree, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH)
            .getSuccessDeleteFiles().size(), "Must clean two files");
    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file1P0C0));
    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file2P0C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003",
        file3P0C2));

    // No cleaning on partially written file, with no commit.
    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000004", file3P0C2); // update
    List<HoodieCleanStat> hoodieCleanStatsFour = runCleaner(config);
    assertEquals(0, hoodieCleanStatsFour.size(), "Must not clean any files");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003",
        file3P0C2));
  }

  /**
   * Test HoodieTable.clean() Cleaning by versions logic for MOR table with Log files.
   */
  @Test
  public void testKeepLatestFileVersionsMOR() throws IOException {

    HoodieWriteConfig config =
        HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
            .withCompactionConfig(HoodieCompactionConfig.newBuilder()
                .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS).retainFileVersions(1).build())
            .build();

    HoodieTestUtils.init(hadoopConf, basePath, HoodieTableType.MERGE_ON_READ);

    // Make 3 files, one base file and 2 log files associated with base file
    String file1P0 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000");
    String file2P0L0 = HoodieTestUtils.createNewLogFile(fs, basePath,
        HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000", file1P0, Option.empty());
    HoodieTestUtils.createNewLogFile(fs, basePath,
        HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000", file1P0, Option.of(2));
    // make 1 compaction commit
    HoodieTestUtils.createCompactionCommitFiles(fs, basePath, "000");

    // Make 4 files, one base file and 3 log files associated with base file
    HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "001", file1P0);
    file2P0L0 = HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
        "001", file1P0, Option.of(3));
    // make 1 compaction commit
    HoodieTestUtils.createCompactionCommitFiles(fs, basePath, "001");

    List<HoodieCleanStat> hoodieCleanStats = runCleaner(config);
    assertEquals(3,
        getCleanStat(hoodieCleanStats, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).getSuccessDeleteFiles()
            .size(), "Must clean three files, one parquet and 2 log files");
    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000",
        file1P0));
    assertFalse(HoodieTestUtils.doesLogFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000",
        file2P0L0, Option.empty()));
    assertFalse(HoodieTestUtils.doesLogFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "000",
        file2P0L0, Option.of(2)));
  }

  @Test
  public void testCleanMetadataUpgradeDowngrade() {
    String instantTime = "000";

    String partition1 = DEFAULT_PARTITION_PATHS[0];
    String partition2 = DEFAULT_PARTITION_PATHS[1];

    String fileName1 = "data1_1_000.parquet";
    String fileName2 = "data2_1_000.parquet";

    String filePath1 = metaClient.getBasePath() + "/" + partition1 + "/" + fileName1;
    String filePath2 = metaClient.getBasePath() + "/" + partition1 + "/" + fileName2;

    List<String> deletePathPatterns1 = Arrays.asList(filePath1, filePath2);
    List<String> successDeleteFiles1 = Collections.singletonList(filePath1);
    List<String> failedDeleteFiles1 = Collections.singletonList(filePath2);

    // create partition1 clean stat.
    HoodieCleanStat cleanStat1 = new HoodieCleanStat(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS,
        partition1, deletePathPatterns1, successDeleteFiles1,
        failedDeleteFiles1, instantTime);

    List<String> deletePathPatterns2 = new ArrayList<>();
    List<String> successDeleteFiles2 = new ArrayList<>();
    List<String> failedDeleteFiles2 = new ArrayList<>();

    // create partition2 empty clean stat.
    HoodieCleanStat cleanStat2 = new HoodieCleanStat(HoodieCleaningPolicy.KEEP_LATEST_COMMITS,
        partition2, deletePathPatterns2, successDeleteFiles2,
        failedDeleteFiles2, instantTime);

    // map with absolute file path.
    Map<String, Tuple3> oldExpected = new HashMap<>();
    oldExpected.put(partition1, new Tuple3<>(deletePathPatterns1, successDeleteFiles1, failedDeleteFiles1));
    oldExpected.put(partition2, new Tuple3<>(deletePathPatterns2, successDeleteFiles2, failedDeleteFiles2));

    // map with relative path.
    Map<String, Tuple3> newExpected = new HashMap<>();
    newExpected.put(partition1, new Tuple3<>(Arrays.asList(fileName1, fileName2), Collections.singletonList(fileName1),
            Collections.singletonList(fileName2)));
    newExpected.put(partition2, new Tuple3<>(deletePathPatterns2, successDeleteFiles2, failedDeleteFiles2));

    HoodieCleanMetadata metadata = CleanerUtils.convertCleanMetadata(
        instantTime,
        Option.of(0L),
        Arrays.asList(cleanStat1, cleanStat2)
    );
    metadata.setVersion(CleanerUtils.CLEAN_METADATA_VERSION_1);

    // NOw upgrade and check
    CleanMetadataMigrator metadataMigrator = new CleanMetadataMigrator(metaClient);
    metadata = metadataMigrator.upgradeToLatest(metadata, metadata.getVersion());
    testCleanMetadataPathEquality(metadata, newExpected);

    CleanMetadataMigrator migrator = new CleanMetadataMigrator(metaClient);
    HoodieCleanMetadata oldMetadata =
        migrator.migrateToVersion(metadata, metadata.getVersion(), CleanerUtils.CLEAN_METADATA_VERSION_1);
    assertEquals(CleanerUtils.CLEAN_METADATA_VERSION_1, oldMetadata.getVersion());
    testCleanMetadataEquality(metadata, oldMetadata);
    testCleanMetadataPathEquality(oldMetadata, oldExpected);

    HoodieCleanMetadata newMetadata = migrator.upgradeToLatest(oldMetadata, oldMetadata.getVersion());
    assertEquals(CleanerUtils.LATEST_CLEAN_METADATA_VERSION, newMetadata.getVersion());
    testCleanMetadataEquality(oldMetadata, newMetadata);
    testCleanMetadataPathEquality(newMetadata, newExpected);
    testCleanMetadataPathEquality(oldMetadata, oldExpected);
  }

  public void testCleanMetadataEquality(HoodieCleanMetadata input1, HoodieCleanMetadata input2) {
    assertEquals(input1.getEarliestCommitToRetain(), input2.getEarliestCommitToRetain());
    assertEquals(input1.getStartCleanTime(), input2.getStartCleanTime());
    assertEquals(input1.getTimeTakenInMillis(), input2.getTimeTakenInMillis());
    assertEquals(input1.getTotalFilesDeleted(), input2.getTotalFilesDeleted());

    Map<String, HoodieCleanPartitionMetadata> map1 = input1.getPartitionMetadata();
    Map<String, HoodieCleanPartitionMetadata> map2 = input2.getPartitionMetadata();

    assertEquals(map1.keySet(), map2.keySet());

    List<String> partitions1 = map1.values().stream().map(HoodieCleanPartitionMetadata::getPartitionPath).collect(
        Collectors.toList());
    List<String> partitions2 = map2.values().stream().map(HoodieCleanPartitionMetadata::getPartitionPath).collect(
        Collectors.toList());
    assertEquals(partitions1, partitions2);

    List<String> policies1 = map1.values().stream().map(HoodieCleanPartitionMetadata::getPolicy).collect(Collectors.toList());
    List<String> policies2 = map2.values().stream().map(HoodieCleanPartitionMetadata::getPolicy).collect(Collectors.toList());
    assertEquals(policies1, policies2);
  }

  @Test
  public void testCleanPlanUpgradeDowngrade() {
    String instantTime = "000";

    String partition1 = DEFAULT_PARTITION_PATHS[0];
    String partition2 = DEFAULT_PARTITION_PATHS[1];

    String fileName1 = "data1_1_000.parquet";
    String fileName2 = "data2_1_000.parquet";

    Map<String, List<String>> filesToBeCleanedPerPartition = new HashMap<>();
    filesToBeCleanedPerPartition.put(partition1, Arrays.asList(fileName1));
    filesToBeCleanedPerPartition.put(partition2, Arrays.asList(fileName2));

    HoodieCleanerPlan version1Plan =
        HoodieCleanerPlan.newBuilder().setEarliestInstantToRetain(HoodieActionInstant.newBuilder()
            .setAction(HoodieTimeline.COMMIT_ACTION)
            .setTimestamp(instantTime).setState(State.COMPLETED.name()).build())
            .setPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS.name())
            .setFilesToBeDeletedPerPartition(filesToBeCleanedPerPartition)
            .setVersion(CleanPlanV1MigrationHandler.VERSION)
            .build();

    // Upgrade and Verify version 2 plan
    HoodieCleanerPlan version2Plan =
        new CleanPlanMigrator(metaClient).upgradeToLatest(version1Plan, version1Plan.getVersion());
    assertEquals(version1Plan.getEarliestInstantToRetain(), version2Plan.getEarliestInstantToRetain());
    assertEquals(version1Plan.getPolicy(), version2Plan.getPolicy());
    assertEquals(CleanPlanner.LATEST_CLEAN_PLAN_VERSION, version2Plan.getVersion());
    // Deprecated Field is not used.
    assertEquals(0, version2Plan.getFilesToBeDeletedPerPartition().size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().size(),
        version2Plan.getFilePathsToBeDeletedPerPartition().size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition1).size(),
        version2Plan.getFilePathsToBeDeletedPerPartition().get(partition1).size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition2).size(),
        version2Plan.getFilePathsToBeDeletedPerPartition().get(partition2).size());
    assertEquals(new Path(FSUtils.getPartitionPath(metaClient.getBasePath(), partition1), fileName1).toString(),
        version2Plan.getFilePathsToBeDeletedPerPartition().get(partition1).get(0).getFilePath());
    assertEquals(new Path(FSUtils.getPartitionPath(metaClient.getBasePath(), partition2), fileName2).toString(),
        version2Plan.getFilePathsToBeDeletedPerPartition().get(partition2).get(0).getFilePath());

    // Downgrade and verify version 1 plan
    HoodieCleanerPlan gotVersion1Plan = new CleanPlanMigrator(metaClient).migrateToVersion(version2Plan,
        version2Plan.getVersion(), version1Plan.getVersion());
    assertEquals(version1Plan.getEarliestInstantToRetain(), gotVersion1Plan.getEarliestInstantToRetain());
    assertEquals(version1Plan.getPolicy(), version2Plan.getPolicy());
    assertEquals(version1Plan.getVersion(), gotVersion1Plan.getVersion());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().size(),
        gotVersion1Plan.getFilesToBeDeletedPerPartition().size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition1).size(),
        gotVersion1Plan.getFilesToBeDeletedPerPartition().get(partition1).size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition2).size(),
        gotVersion1Plan.getFilesToBeDeletedPerPartition().get(partition2).size());
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition1).get(0),
        gotVersion1Plan.getFilesToBeDeletedPerPartition().get(partition1).get(0));
    assertEquals(version1Plan.getFilesToBeDeletedPerPartition().get(partition2).get(0),
        gotVersion1Plan.getFilesToBeDeletedPerPartition().get(partition2).get(0));
    assertTrue(gotVersion1Plan.getFilePathsToBeDeletedPerPartition().isEmpty());
    assertNull(version1Plan.getFilePathsToBeDeletedPerPartition());
  }

  private void testCleanMetadataPathEquality(HoodieCleanMetadata metadata, Map<String, Tuple3> expected) {

    Map<String, HoodieCleanPartitionMetadata> partitionMetadataMap = metadata.getPartitionMetadata();

    for (Map.Entry<String, HoodieCleanPartitionMetadata> entry : partitionMetadataMap.entrySet()) {
      String partitionPath = entry.getKey();
      HoodieCleanPartitionMetadata partitionMetadata = entry.getValue();

      assertEquals(expected.get(partitionPath)._1(), partitionMetadata.getDeletePathPatterns());
      assertEquals(expected.get(partitionPath)._2(), partitionMetadata.getSuccessDeleteFiles());
      assertEquals(expected.get(partitionPath)._3(), partitionMetadata.getFailedDeleteFiles());
    }
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for COW table.
   */
  @Test
  public void testKeepLatestCommits() throws IOException {
    testKeepLatestCommits(false, false, false);
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for COW table. Here the operations are simulated
   * such that first clean attempt failed after files were cleaned and a subsequent cleanup succeeds.
   */
  @Test
  public void testKeepLatestCommitsWithFailureRetry() throws IOException {
    testKeepLatestCommits(true, false, false);
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for COW table.
   */
  @Test
  public void testKeepLatestCommitsIncrMode() throws IOException {
    testKeepLatestCommits(false, true, false);
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for COW table with Bootstrap source file clean enable.
   */
  @Test
  public void testBootstrapSourceFileCleanWithKeepLatestCommits() throws IOException {
    testKeepLatestCommits(false, false, true);
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for COW table.
   */
  private void testKeepLatestCommits(boolean simulateFailureRetry, boolean enableIncrementalClean, boolean enableBootstrapSourceClean) throws IOException {
    HoodieWriteConfig config = HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withIncrementalCleaningMode(enableIncrementalClean)
            .withCleanBootstrapBaseFileEnabled(enableBootstrapSourceClean)
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS).retainCommits(2).build())
        .build();

    Map<String, List<BootstrapFileMapping>> bootstrapMapping = enableBootstrapSourceClean ? generateBootstrapIndexAndSourceData() : null;

    // make 1 commit, with 1 file per partition
    HoodieTestUtils.createInflightCommitFiles(basePath, "00000000000001");

    String file1P0C0 = enableBootstrapSourceClean ? bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).get(0).getFileId()
        : UUID.randomUUID().toString();
    String file1P1C0 = enableBootstrapSourceClean ? bootstrapMapping.get(HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH).get(0).getFileId()
        : UUID.randomUUID().toString();
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001", file1P0C0); // insert
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000001", file1P1C0); // insert

    HoodieCommitMetadata commitMetadata = generateCommitMetadata(
        Collections.unmodifiableMap(new HashMap<String, List<String>>() {
          {
            put(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, CollectionUtils.createImmutableList(file1P0C0));
            put(HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, CollectionUtils.createImmutableList(file1P1C0));
          }
        })
    );
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, "00000000000001"),
        Option.of(commitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

    metaClient = HoodieTableMetaClient.reload(metaClient);

    List<HoodieCleanStat> hoodieCleanStatsOne = runCleaner(config, simulateFailureRetry);
    assertEquals(0, hoodieCleanStatsOne.size(), "Must not scan any partitions and clean any files");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000001",
        file1P1C0));

    // make next commit, with 1 insert & 1 update per partition
    HoodieTestUtils.createInflightCommitFiles(basePath, "00000000000002");
    metaClient = HoodieTableMetaClient.reload(metaClient);

    String file2P0C1 =
        HoodieTestUtils
            .createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002"); // insert
    String file2P1C1 =
        HoodieTestUtils
            .createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002"); // insert
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002", file1P0C0); // update
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002", file1P1C0); // update
    commitMetadata = generateCommitMetadata(new HashMap<String, List<String>>() {
      {
        put(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, CollectionUtils.createImmutableList(file1P0C0, file2P0C1));
        put(HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, CollectionUtils.createImmutableList(file1P1C0, file2P1C1));
      }
    });
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, "00000000000002"),
        Option.of(commitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));
    List<HoodieCleanStat> hoodieCleanStatsTwo = runCleaner(config, simulateFailureRetry);
    assertEquals(0, hoodieCleanStatsTwo.size(), "Must not scan any partitions and clean any files");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file2P0C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000002",
        file2P1C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH, "00000000000001",
        file1P1C0));

    // make next commit, with 2 updates to existing files, and 1 insert
    HoodieTestUtils.createInflightCommitFiles(basePath, "00000000000003");
    metaClient = HoodieTableMetaClient.reload(metaClient);

    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003", file1P0C0); // update
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003", file2P0C1); // update
    String file3P0C2 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003");

    commitMetadata = generateCommitMetadata(CollectionUtils
        .createImmutableMap(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
            CollectionUtils.createImmutableList(file1P0C0, file2P0C1, file3P0C2)));
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, "00000000000003"),
        Option.of(commitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

    List<HoodieCleanStat> hoodieCleanStatsThree = runCleaner(config, simulateFailureRetry);
    assertEquals(0, hoodieCleanStatsThree.size(),
        "Must not clean any file. We have to keep 1 version before the latest commit time to keep");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));

    // make next commit, with 2 updates to existing files, and 1 insert
    HoodieTestUtils.createInflightCommitFiles(basePath, "00000000000004");
    metaClient = HoodieTableMetaClient.reload(metaClient);

    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000004", file1P0C0); // update
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000004", file2P0C1); // update
    String file4P0C3 =
        HoodieTestUtils.createNewDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000004");
    commitMetadata = generateCommitMetadata(CollectionUtils.createImmutableMap(
        HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, CollectionUtils.createImmutableList(file1P0C0, file2P0C1, file4P0C3)));
    metaClient.getActiveTimeline().saveAsComplete(
        new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, "00000000000004"),
        Option.of(commitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));

    List<HoodieCleanStat> hoodieCleanStatsFour = runCleaner(config, simulateFailureRetry);
    // enableBootstrapSourceClean would delete the bootstrap base file as the same time
    HoodieCleanStat partitionCleanStat =
        getCleanStat(hoodieCleanStatsFour, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH);

    assertEquals(enableBootstrapSourceClean ? 2 : 1, partitionCleanStat.getSuccessDeleteFiles().size()
        + (partitionCleanStat.getSuccessDeleteBootstrapBaseFiles() == null ? 0
        : partitionCleanStat.getSuccessDeleteBootstrapBaseFiles().size()), "Must clean at least one old file");

    assertFalse(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000001",
        file1P0C0));
    if (enableBootstrapSourceClean) {
      assertFalse(new File(bootstrapMapping.get(
          HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH).get(0).getBoostrapFileStatus().getPath().getUri()).exists());
    }
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file2P0C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003",
        file2P0C1));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000003",
        file3P0C2));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000004",
        file4P0C3));

    // No cleaning on partially written file, with no commit.
    HoodieTestUtils
        .createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000005", file3P0C2); // update
    commitMetadata = generateCommitMetadata(CollectionUtils.createImmutableMap(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
        CollectionUtils.createImmutableList(file3P0C2)));
    metaClient.getActiveTimeline().createNewInstant(
        new HoodieInstant(State.REQUESTED, HoodieTimeline.COMMIT_ACTION, "00000000000005"));
    metaClient.getActiveTimeline().transitionRequestedToInflight(
        new HoodieInstant(State.REQUESTED, HoodieTimeline.COMMIT_ACTION, "00000000000005"),
        Option.of(commitMetadata.toJsonString().getBytes(StandardCharsets.UTF_8)));
    List<HoodieCleanStat> hoodieCleanStatsFive = runCleaner(config, simulateFailureRetry);
    HoodieCleanStat cleanStat = getCleanStat(hoodieCleanStatsFive, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH);
    assertEquals(0,
        cleanStat != null ? cleanStat.getSuccessDeleteFiles().size() : 0, "Must not clean any files");
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file1P0C0));
    assertTrue(HoodieTestUtils.doesDataFileExist(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, "00000000000002",
        file2P0C1));
  }

  /**
   * Generate Bootstrap index, bootstrap base file and corresponding metaClient.
   * @return Partition to BootstrapFileMapping Map
   * @throws IOException
   */
  private Map<String, List<BootstrapFileMapping>> generateBootstrapIndexAndSourceData() throws IOException {
    // create bootstrap source data path
    java.nio.file.Path sourcePath = tempDir.resolve("data");
    java.nio.file.Files.createDirectories(sourcePath);
    assertTrue(new File(sourcePath.toString()).exists());

    // recreate metaClient with Bootstrap base path
    metaClient = HoodieTestUtils.init(basePath, getTableType(), sourcePath.toString());

    // generate bootstrap index
    Map<String, List<BootstrapFileMapping>> bootstrapMapping = TestBootstrapIndex.generateBootstrapIndex(metaClient, sourcePath.toString(),
        new String[] {HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, HoodieTestDataGenerator.DEFAULT_SECOND_PARTITION_PATH}, 1);

    for (Map.Entry<String, List<BootstrapFileMapping>> entry : bootstrapMapping.entrySet()) {
      new File(sourcePath.toString() + "/" + entry.getKey()).mkdirs();
      assertTrue(new File(entry.getValue().get(0).getBoostrapFileStatus().getPath().getUri()).createNewFile());
    }
    return bootstrapMapping;
  }

  /**
   * Test Cleaning functionality of table.rollback() API.
   */
  @Test
  public void testCleanMarkerDataFilesOnRollback() throws IOException {
    List<String> markerFiles = createMarkerFiles("000", 10);
    assertEquals(10, markerFiles.size(), "Some marker files are created.");
    assertEquals(markerFiles.size(), getTotalTempFiles(), "Some marker files are created.");

    HoodieWriteConfig config = HoodieWriteConfig.newBuilder().withPath(basePath).build();
    metaClient = HoodieTableMetaClient.reload(metaClient);
    HoodieTable table = HoodieTable.create(metaClient, config, hadoopConf);
    table.getActiveTimeline().createNewInstant(new HoodieInstant(State.REQUESTED,
        HoodieTimeline.COMMIT_ACTION, "000"));
    table.getActiveTimeline().transitionRequestedToInflight(
        new HoodieInstant(State.REQUESTED, HoodieTimeline.COMMIT_ACTION, "000"), Option.empty());
    metaClient.reloadActiveTimeline();
    table.rollback(jsc, "001", new HoodieInstant(State.INFLIGHT, HoodieTimeline.COMMIT_ACTION, "000"), true);
    assertEquals(0, getTotalTempFiles(), "All temp files are deleted.");
  }

  /**
   * Test CLeaner Stat when there are no partition paths.
   */
  @Test
  public void testCleaningWithZeroPartitionPaths() throws IOException {
    HoodieWriteConfig config = HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS).retainCommits(2).build())
        .build();

    // Make a commit, although there are no partitionPaths.
    // Example use-case of this is when a client wants to create a table
    // with just some commit metadata, but no data/partitionPaths.
    HoodieTestUtils.createCommitFiles(basePath, "000");

    metaClient = HoodieTableMetaClient.reload(metaClient);

    List<HoodieCleanStat> hoodieCleanStatsOne = runCleaner(config);
    assertTrue(hoodieCleanStatsOne.isEmpty(), "HoodieCleanStats should be empty for a table with empty partitionPaths");
  }

  /**
   * Test Keep Latest Commits when there are pending compactions.
   */
  @Test
  public void testKeepLatestCommitsWithPendingCompactions() throws IOException {
    HoodieWriteConfig config = HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_COMMITS).retainCommits(2).build())
        .build();
    // Deletions:
    // . FileId Parquet Logs Total Retained Commits
    // FileId7 5 10 15 009, 011
    // FileId6 5 10 15 009
    // FileId5 3 6 9 005
    // FileId4 2 4 6 003
    // FileId3 1 2 3 001
    // FileId2 0 0 0 000
    // FileId1 0 0 0 000
    testPendingCompactions(config, 48, 18, false);
  }

  /**
   * Test HoodieTable.clean() Cleaning by commit logic for MOR table with Log files. Here the operations are simulated
   * such that first clean attempt failed after files were cleaned and a subsequent cleanup succeeds.
   */
  @Test
  public void testKeepLatestVersionsWithPendingCompactions() throws IOException {
    testKeepLatestVersionsWithPendingCompactions(false);
  }


  /**
   * Test Keep Latest Versions when there are pending compactions.
   */
  @Test
  public void testKeepLatestVersionsWithPendingCompactionsAndFailureRetry() throws IOException {
    testKeepLatestVersionsWithPendingCompactions(true);
  }

  private void testKeepLatestVersionsWithPendingCompactions(boolean retryFailure) throws IOException {
    HoodieWriteConfig config =
        HoodieWriteConfig.newBuilder().withPath(basePath).withAssumeDatePartitioning(true)
            .withCompactionConfig(HoodieCompactionConfig.newBuilder()
                .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS).retainFileVersions(2).build())
            .build();
    // Deletions:
    // . FileId Parquet Logs Total Retained Commits
    // FileId7 5 10 15 009, 011
    // FileId6 4 8 12 007, 009
    // FileId5 2 4 6 003 005
    // FileId4 1 2 3 001, 003
    // FileId3 0 0 0 000, 001
    // FileId2 0 0 0 000
    // FileId1 0 0 0 000
    testPendingCompactions(config, 36, 9, retryFailure);
  }

  /**
   * Test clean previous corrupted cleanFiles.
   */
  @Test
  public void testCleanPreviousCorruptedCleanFiles() throws IOException {
    HoodieWriteConfig config =
        HoodieWriteConfig.newBuilder()
            .withPath(basePath).withAssumeDatePartitioning(true)
            .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_FILE_VERSIONS).retainFileVersions(1).build())
            .build();

    HoodieTestUtils.createCorruptedPendingCleanFiles(metaClient, getNextInstant());
    metaClient = HoodieTableMetaClient.reload(metaClient);

    List<HoodieCleanStat> cleanStats = runCleaner(config);
    assertEquals(0, cleanStats.size(), "Must not clean any files");
  }

  /**
   * Common test method for validating pending compactions.
   *
   * @param config Hoodie Write Config
   * @param expNumFilesDeleted Number of files deleted
   */
  private void testPendingCompactions(HoodieWriteConfig config, int expNumFilesDeleted,
      int expNumFilesUnderCompactionDeleted, boolean retryFailure) throws IOException {
    HoodieTableMetaClient metaClient =
        HoodieTestUtils.init(hadoopConf, basePath, HoodieTableType.MERGE_ON_READ);
    String[] instants = new String[] {"000", "001", "003", "005", "007", "009", "011", "013"};
    String[] compactionInstants = new String[] {"002", "004", "006", "008", "010"};
    Map<String, String> expFileIdToPendingCompaction = new HashMap<>();
    Map<String, String> fileIdToLatestInstantBeforeCompaction = new HashMap<>();
    Map<String, List<FileSlice>> compactionInstantsToFileSlices = new HashMap<>();

    for (String instant : instants) {
      HoodieTestUtils.createCommitFiles(basePath, instant);
    }

    // Generate 7 file-groups. First one has only one slice and no pending compaction. File Slices (2 - 5) has
    // multiple versions with pending compaction. File Slices (6 - 7) have multiple file-slices but not under
    // compactions
    // FileIds 2-5 will be under compaction
    int maxNumFileIds = 7;
    String[] fileIds = new String[] {"fileId1", "fileId2", "fileId3", "fileId4", "fileId5", "fileId6", "fileId7"};
    int maxNumFileIdsForCompaction = 4;
    for (int i = 0; i < maxNumFileIds; i++) {
      final String fileId = HoodieTestUtils.createDataFile(basePath,
          HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, instants[0], fileIds[i]);
      HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, instants[0],
          fileId, Option.empty());
      HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, instants[0],
          fileId, Option.of(2));
      fileIdToLatestInstantBeforeCompaction.put(fileId, instants[0]);
      for (int j = 1; j <= i; j++) {
        if (j == i && j <= maxNumFileIdsForCompaction) {
          expFileIdToPendingCompaction.put(fileId, compactionInstants[j]);
          metaClient = HoodieTableMetaClient.reload(metaClient);
          HoodieTable table = HoodieTable.create(metaClient, config, hadoopConf);
          FileSlice slice =
              table.getSliceView().getLatestFileSlices(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH)
                  .filter(fs -> fs.getFileId().equals(fileId)).findFirst().get();
          List<FileSlice> slices = new ArrayList<>();
          if (compactionInstantsToFileSlices.containsKey(compactionInstants[j])) {
            slices = compactionInstantsToFileSlices.get(compactionInstants[j]);
          }
          slices.add(slice);
          compactionInstantsToFileSlices.put(compactionInstants[j], slices);
          // Add log-files to simulate delta-commits after pending compaction
          HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
              compactionInstants[j], fileId, Option.empty());
          HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
              compactionInstants[j], fileId, Option.of(2));
        } else {
          HoodieTestUtils.createDataFile(basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, instants[j],
              fileId);
          HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
              instants[j], fileId, Option.empty());
          HoodieTestUtils.createNewLogFile(fs, basePath, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH,
              instants[j], fileId, Option.of(2));
          fileIdToLatestInstantBeforeCompaction.put(fileId, instants[j]);
        }
      }
    }

    // Setup pending compaction plans
    for (String instant : compactionInstants) {
      List<FileSlice> fileSliceList = compactionInstantsToFileSlices.get(instant);
      if (null != fileSliceList) {
        HoodieTestUtils.createCompactionRequest(metaClient, instant, fileSliceList.stream()
            .map(fs -> Pair.of(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, fs)).collect(Collectors.toList()));
      }
    }

    // Clean now
    metaClient = HoodieTableMetaClient.reload(metaClient);
    List<HoodieCleanStat> hoodieCleanStats = runCleaner(config, retryFailure);

    // Test for safety
    final HoodieTableMetaClient newMetaClient = HoodieTableMetaClient.reload(metaClient);
    final HoodieTable hoodieTable = HoodieTable.create(metaClient, config, hadoopConf);

    expFileIdToPendingCompaction.forEach((fileId, value) -> {
      String baseInstantForCompaction = fileIdToLatestInstantBeforeCompaction.get(fileId);
      Option<FileSlice> fileSliceForCompaction = Option.fromJavaOptional(hoodieTable.getSliceView()
          .getLatestFileSlicesBeforeOrOn(HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH, baseInstantForCompaction,
              true)
          .filter(fs -> fs.getFileId().equals(fileId)).findFirst());
      assertTrue(fileSliceForCompaction.isPresent(), "Base Instant for Compaction must be preserved");
      assertTrue(fileSliceForCompaction.get().getBaseFile().isPresent(), "FileSlice has data-file");
      assertEquals(2, fileSliceForCompaction.get().getLogFiles().count(), "FileSlice has log-files");
    });

    // Test for progress (Did we clean some files ?)
    long numFilesUnderCompactionDeleted = hoodieCleanStats.stream()
            .flatMap(cleanStat -> convertPathToFileIdWithCommitTime(newMetaClient, cleanStat.getDeletePathPatterns())
        .map(fileIdWithCommitTime -> {
          if (expFileIdToPendingCompaction.containsKey(fileIdWithCommitTime.getKey())) {
            assertTrue(HoodieTimeline.compareTimestamps(
                fileIdToLatestInstantBeforeCompaction.get(fileIdWithCommitTime.getKey()),
                HoodieTimeline.GREATER_THAN, fileIdWithCommitTime.getValue()),
                "Deleted instant time must be less than pending compaction");
            return true;
          }
          return false;
        })).filter(x -> x).count();
    long numDeleted =
        hoodieCleanStats.stream().mapToLong(cleanStat -> cleanStat.getDeletePathPatterns().size()).sum();
    // Tighter check for regression
    assertEquals(expNumFilesDeleted, numDeleted, "Correct number of files deleted");
    assertEquals(expNumFilesUnderCompactionDeleted, numFilesUnderCompactionDeleted,
        "Correct number of files under compaction deleted");
  }

  /**
   * Utility method to create temporary data files.
   *
   * @param instantTime Commit Timestamp
   * @param numFiles Number for files to be generated
   * @return generated files
   * @throws IOException in case of error
   */
  private List<String> createMarkerFiles(String instantTime, int numFiles) throws IOException {
    List<String> files = new ArrayList<>();
    for (int i = 0; i < numFiles; i++) {
      files.add(HoodieClientTestUtils.createNewMarkerFile(basePath, "2019/03/29", instantTime));
    }
    return files;
  }

  /***
   * Helper method to return temporary files count.
   * 
   * @return Number of temporary files found
   * @throws IOException in case of error
   */
  private int getTotalTempFiles() throws IOException {
    return FileSystemTestUtils.listRecursive(fs, new Path(basePath, HoodieTableMetaClient.TEMPFOLDER_NAME))
        .size();
  }

  private Stream<Pair<String, String>> convertPathToFileIdWithCommitTime(final HoodieTableMetaClient metaClient,
      List<String> paths) {
    Predicate<String> roFilePredicate =
        path -> path.contains(metaClient.getTableConfig().getBaseFileFormat().getFileExtension());
    Predicate<String> rtFilePredicate =
        path -> path.contains(metaClient.getTableConfig().getLogFileFormat().getFileExtension());
    Stream<Pair<String, String>> stream1 = paths.stream().filter(roFilePredicate).map(fullPath -> {
      String fileName = Paths.get(fullPath).getFileName().toString();
      return Pair.of(FSUtils.getFileId(fileName), FSUtils.getCommitTime(fileName));
    });
    Stream<Pair<String, String>> stream2 = paths.stream().filter(rtFilePredicate).map(path -> {
      return Pair.of(FSUtils.getFileIdFromLogPath(new Path(path)),
          FSUtils.getBaseCommitTimeFromLogPath(new Path(path)));
    });
    return Stream.concat(stream1, stream2);
  }

  private static HoodieCommitMetadata generateCommitMetadata(Map<String, List<String>> partitionToFilePaths) {
    HoodieCommitMetadata metadata = new HoodieCommitMetadata();
    partitionToFilePaths.forEach((key, value) -> value.forEach(f -> {
      HoodieWriteStat writeStat = new HoodieWriteStat();
      writeStat.setPartitionPath(key);
      writeStat.setPath(f);
      writeStat.setFileId(f);
      metadata.addWriteStat(key, writeStat);
    }));
    return metadata;
  }
}
