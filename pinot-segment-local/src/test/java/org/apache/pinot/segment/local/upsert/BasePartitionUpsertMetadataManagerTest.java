/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.upsert;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.helix.HelixManager;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.pinot.common.metadata.segment.SegmentZKMetadata;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.segment.local.data.manager.TableDataManager;
import org.apache.pinot.segment.local.indexsegment.immutable.ImmutableSegmentImpl;
import org.apache.pinot.segment.local.indexsegment.mutable.MutableSegmentImpl;
import org.apache.pinot.segment.local.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.segment.spi.IndexSegment;
import org.apache.pinot.segment.spi.MutableSegment;
import org.apache.pinot.segment.spi.SegmentContext;
import org.apache.pinot.segment.spi.V1Constants;
import org.apache.pinot.segment.spi.index.metadata.SegmentMetadataImpl;
import org.apache.pinot.segment.spi.index.mutable.ThreadSafeMutableRoaringBitmap;
import org.apache.pinot.segment.spi.store.SegmentDirectory;
import org.apache.pinot.spi.config.instance.InstanceDataManagerConfig;
import org.apache.pinot.spi.config.table.HashFunction;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.UpsertConfig;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.data.readers.PrimaryKey;
import org.apache.pinot.spi.utils.CommonConstants;
import org.roaringbitmap.buffer.MutableRoaringBitmap;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;


public class BasePartitionUpsertMetadataManagerTest {
  private static final File TEMP_DIR = new File(FileUtils.getTempDirectory(), "BasePartitionUpsertMetadataManagerTest");

  @BeforeMethod
  public void setUp()
      throws IOException {
    FileUtils.forceMkdir(TEMP_DIR);
    ServerMetrics.register(mock(ServerMetrics.class));
  }

  @AfterMethod
  public void tearDown()
      throws IOException {
    FileUtils.forceDelete(TEMP_DIR);
  }

  @Test
  public void testPreloadSegments()
      throws Exception {
    String realtimeTableName = "testTable_REALTIME";
    String instanceId = "server01";
    Map<String, Map<String, String>> segmentAssignment = new HashMap<>();
    Map<String, SegmentZKMetadata> segmentMetadataMap = new HashMap<>();
    Set<String> preloadedSegments = new HashSet<>();
    AtomicBoolean wasPreloading = new AtomicBoolean(false);
    TableDataManager tableDataManager = mock(TableDataManager.class);
    UpsertContext upsertContext = mock(UpsertContext.class);
    when(upsertContext.isSnapshotEnabled()).thenReturn(true);
    when(upsertContext.isPreloadEnabled()).thenReturn(true);
    when(upsertContext.getTableDataManager()).thenReturn(tableDataManager);
    DummyPartitionUpsertMetadataManager upsertMetadataManager =
        new DummyPartitionUpsertMetadataManager(realtimeTableName, 0, upsertContext) {

          @Override
          Map<String, Map<String, String>> getSegmentAssignment(HelixManager helixManager) {
            return segmentAssignment;
          }

          @Override
          Map<String, SegmentZKMetadata> getSegmentsZKMetadata(HelixManager helixManager) {
            return segmentMetadataMap;
          }

          @Override
          void doPreloadSegmentWithSnapshot(TableDataManager tableDataManager, String segmentName,
              IndexLoadingConfig indexLoadingConfig, SegmentZKMetadata segmentZKMetadata) {
            wasPreloading.set(isPreloading());
            preloadedSegments.add(segmentName);
          }
        };

    // Setup mocks for TableConfig and Schema.
    TableConfig tableConfig = mock(TableConfig.class);
    UpsertConfig upsertConfig = new UpsertConfig();
    upsertConfig.setComparisonColumn("ts");
    upsertConfig.setEnablePreload(true);
    upsertConfig.setEnableSnapshot(true);
    when(tableConfig.getUpsertConfig()).thenReturn(upsertConfig);
    when(tableConfig.getTableName()).thenReturn(realtimeTableName);
    Schema schema = mock(Schema.class);
    when(schema.getPrimaryKeyColumns()).thenReturn(Collections.singletonList("pk"));
    IndexLoadingConfig indexLoadingConfig = mock(IndexLoadingConfig.class);
    when(indexLoadingConfig.getTableConfig()).thenReturn(tableConfig);

    // Setup mocks for HelixManager.
    HelixManager helixManager = mock(HelixManager.class);
    ZkHelixPropertyStore<ZNRecord> propertyStore = mock(ZkHelixPropertyStore.class);
    when(helixManager.getHelixPropertyStore()).thenReturn(propertyStore);

    // Setup segment assignment. Only ONLINE segments are preloaded.
    segmentAssignment.put("consuming_seg01", ImmutableMap.of(instanceId, "CONSUMING"));
    segmentAssignment.put("consuming_seg02", ImmutableMap.of(instanceId, "CONSUMING"));
    segmentAssignment.put("offline_seg01", ImmutableMap.of(instanceId, "OFFLINE"));
    segmentAssignment.put("offline_seg02", ImmutableMap.of(instanceId, "OFFLINE"));
    String seg01Name = "testTable__0__1__" + System.currentTimeMillis();
    segmentAssignment.put(seg01Name, ImmutableMap.of(instanceId, "ONLINE"));
    String seg02Name = "testTable__0__2__" + System.currentTimeMillis();
    segmentAssignment.put(seg02Name, ImmutableMap.of(instanceId, "ONLINE"));
    // This segment is skipped as it's not from partition 0.
    String seg03Name = "testTable__1__3__" + System.currentTimeMillis();
    segmentAssignment.put(seg03Name, ImmutableMap.of(instanceId, "ONLINE"));

    SegmentZKMetadata zkMetadata = new SegmentZKMetadata(seg01Name);
    zkMetadata.setStatus(CommonConstants.Segment.Realtime.Status.DONE);
    segmentMetadataMap.put(seg01Name, zkMetadata);
    zkMetadata = new SegmentZKMetadata(seg02Name);
    zkMetadata.setStatus(CommonConstants.Segment.Realtime.Status.DONE);
    segmentMetadataMap.put(seg02Name, zkMetadata);
    zkMetadata = new SegmentZKMetadata(seg03Name);
    zkMetadata.setStatus(CommonConstants.Segment.Realtime.Status.DONE);
    segmentMetadataMap.put(seg03Name, zkMetadata);

    // Setup mocks to get file path to validDocIds snapshot.
    ExecutorService segmentPreloadExecutor = Executors.newFixedThreadPool(1);
    File tableDataDir = new File(TEMP_DIR, realtimeTableName);
    when(tableDataManager.getHelixManager()).thenReturn(helixManager);
    when(tableDataManager.getSegmentPreloadExecutor()).thenReturn(segmentPreloadExecutor);
    when(tableDataManager.getTableDataDir()).thenReturn(tableDataDir);
    InstanceDataManagerConfig instanceDataManagerConfig = mock(InstanceDataManagerConfig.class);
    when(instanceDataManagerConfig.getInstanceId()).thenReturn(instanceId);
    when(tableDataManager.getInstanceDataManagerConfig()).thenReturn(instanceDataManagerConfig);

    // No snapshot file for seg01, so it's skipped.
    File seg01IdxDir = new File(tableDataDir, seg01Name);
    FileUtils.forceMkdir(seg01IdxDir);
    when(tableDataManager.getSegmentDataDir(seg01Name, null, tableConfig)).thenReturn(seg01IdxDir);

    File seg02IdxDir = new File(tableDataDir, seg02Name);
    FileUtils.forceMkdir(seg02IdxDir);
    FileUtils.touch(new File(new File(seg02IdxDir, "v3"), V1Constants.VALID_DOC_IDS_SNAPSHOT_FILE_NAME));
    when(tableDataManager.getSegmentDataDir(seg02Name, null, tableConfig)).thenReturn(seg02IdxDir);

    try {
      // If preloading is enabled, the _isPreloading flag is true initially, until preloading is done.
      assertTrue(upsertMetadataManager.isPreloading());
      upsertMetadataManager.preloadSegments(indexLoadingConfig);
      assertEquals(preloadedSegments.size(), 1);
      assertTrue(preloadedSegments.contains(seg02Name));
      assertTrue(wasPreloading.get());
      assertFalse(upsertMetadataManager.isPreloading());
    } finally {
      segmentPreloadExecutor.shutdownNow();
    }
  }

  @Test
  public void testTakeSnapshotInOrder()
      throws IOException {
    DummyPartitionUpsertMetadataManager upsertMetadataManager =
        new DummyPartitionUpsertMetadataManager("myTable", 0, mock(UpsertContext.class));

    List<String> segmentsTakenSnapshot = new ArrayList<>();

    File segDir01 = new File(TEMP_DIR, "seg01");
    ImmutableSegmentImpl seg01 = createImmutableSegment("seg01", segDir01, segmentsTakenSnapshot);
    seg01.enableUpsert(upsertMetadataManager, createValidDocIds(0, 1, 2, 3), null);
    upsertMetadataManager.trackSegment(seg01);
    // seg01 has a tmp snapshot file, but no snapshot file
    FileUtils.touch(new File(segDir01, V1Constants.VALID_DOC_IDS_SNAPSHOT_FILE_NAME + "_tmp"));

    File segDir02 = new File(TEMP_DIR, "seg02");
    ImmutableSegmentImpl seg02 = createImmutableSegment("seg02", segDir02, segmentsTakenSnapshot);
    seg02.enableUpsert(upsertMetadataManager, createValidDocIds(0, 1, 2, 3, 4, 5), null);
    upsertMetadataManager.trackSegment(seg02);
    // seg02 has snapshot file, so its snapshot is taken first.
    FileUtils.touch(new File(segDir02, V1Constants.VALID_DOC_IDS_SNAPSHOT_FILE_NAME));

    File segDir03 = new File(TEMP_DIR, "seg03");
    ImmutableSegmentImpl seg03 = createImmutableSegment("seg03", segDir03, segmentsTakenSnapshot);
    seg03.enableUpsert(upsertMetadataManager, createValidDocIds(3, 4, 7), null);
    upsertMetadataManager.trackSegment(seg03);

    // The mutable segments will be skipped.
    upsertMetadataManager.trackSegment(mock(MutableSegmentImpl.class));

    upsertMetadataManager.doTakeSnapshot();
    assertEquals(segmentsTakenSnapshot.size(), 3);
    // The snapshot of seg02 was taken firstly, as it's the only segment with existing snapshot.
    assertEquals(segmentsTakenSnapshot.get(0), "seg02");
    // Set is used to track segments internally, so we can't assert the order of the other segments deterministically,
    // but all 3 segments should have taken their snapshots.
    assertTrue(segmentsTakenSnapshot.containsAll(Arrays.asList("seg01", "seg02", "seg03")));

    assertEquals(TEMP_DIR.list().length, 3);
    assertTrue(segDir01.exists());
    assertEquals(seg01.loadValidDocIdsFromSnapshot().getCardinality(), 4);
    assertTrue(segDir02.exists());
    assertEquals(seg02.loadValidDocIdsFromSnapshot().getCardinality(), 6);
    assertTrue(segDir03.exists());
    assertEquals(seg03.loadValidDocIdsFromSnapshot().getCardinality(), 3);
  }

  @Test
  public void testConsistencyModeSync()
      throws Exception {
    UpsertContext upsertContext = mock(UpsertContext.class);
    when(upsertContext.getConsistencyMode()).thenReturn(UpsertConfig.ConsistencyMode.SYNC);
    DummyPartitionUpsertMetadataManager upsertMetadataManager =
        new DummyPartitionUpsertMetadataManager("myTable", 0, upsertContext);

    CountDownLatch latch = new CountDownLatch(1);
    Map<IndexSegment, ThreadSafeMutableRoaringBitmap> segmentQueryableDocIdsMap = new HashMap<>();
    IndexSegment seg01 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds01 = createThreadSafeMutableRoaringBitmap(10);
    AtomicBoolean called = new AtomicBoolean(false);
    when(seg01.getValidDocIds()).then(invocationOnMock -> {
      called.set(true);
      latch.await();
      return validDocIds01;
    });
    upsertMetadataManager.trackSegment(seg01);
    segmentQueryableDocIdsMap.put(seg01, validDocIds01);

    IndexSegment seg02 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds02 = createThreadSafeMutableRoaringBitmap(11);
    when(seg02.getValidDocIds()).thenReturn(validDocIds02);
    upsertMetadataManager.trackSegment(seg02);
    segmentQueryableDocIdsMap.put(seg02, validDocIds02);

    IndexSegment seg03 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds03 = createThreadSafeMutableRoaringBitmap(12);
    when(seg03.getValidDocIds()).thenReturn(validDocIds03);
    upsertMetadataManager.trackSegment(seg03);
    segmentQueryableDocIdsMap.put(seg03, validDocIds03);

    List<SegmentContext> segmentContexts = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // This thread does replaceDocId and takes WLock first.
      executor.submit(() -> {
        RecordInfo recordInfo = new RecordInfo(null, 5, null, false);
        upsertMetadataManager.replaceDocId(seg03, validDocIds03, null, seg01, 0, 12, recordInfo);
      });
      // This thread gets segment contexts, but it's blocked until the first thread finishes replaceDocId.
      long startMs = System.currentTimeMillis();
      Future<Long> future = executor.submit(() -> {
        // Check called flag to let the first thread do replaceDocId, thus get WLock, first.
        while (!called.get()) {
          Thread.sleep(10);
        }
        segmentQueryableDocIdsMap.forEach((k, v) -> segmentContexts.add(new SegmentContext(k)));
        upsertMetadataManager.setSegmentContexts(segmentContexts, new HashMap<>());
        return System.currentTimeMillis() - startMs;
      });
      // The first thread can only finish after the latch is counted down after 2s.
      // So the 2nd thread getting segment contexts will be blocked for 2s+.
      Thread.sleep(2000);
      latch.countDown();
      long duration = future.get(3, TimeUnit.SECONDS);
      assertTrue(duration >= 2000, duration + " was less than expected");
    } finally {
      executor.shutdownNow();
    }

    for (SegmentContext sc : segmentContexts) {
      ThreadSafeMutableRoaringBitmap validDocIds = segmentQueryableDocIdsMap.get(sc.getIndexSegment());
      assertNotNull(validDocIds);
      // SegmentContext holds a clone of the original queryableDocIds held by the segment object.
      assertNotSame(sc.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      assertEquals(sc.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      // docId=0 in seg01 got invalidated.
      if (sc.getIndexSegment() == seg01) {
        assertFalse(sc.getQueryableDocIdsSnapshot().contains(0));
      }
      // docId=12 in seg03 was newly added.
      if (sc.getIndexSegment() == seg03) {
        assertTrue(sc.getQueryableDocIdsSnapshot().contains(12));
      }
    }
  }

  @Test
  public void testConsistencyModeSnapshot()
      throws Exception {
    UpsertContext upsertContext = mock(UpsertContext.class);
    when(upsertContext.getConsistencyMode()).thenReturn(UpsertConfig.ConsistencyMode.SNAPSHOT);
    when(upsertContext.getUpsertViewRefreshIntervalMs()).thenReturn(3000L);
    DummyPartitionUpsertMetadataManager upsertMetadataManager =
        new DummyPartitionUpsertMetadataManager("myTable", 0, upsertContext);

    CountDownLatch latch = new CountDownLatch(1);
    Map<IndexSegment, ThreadSafeMutableRoaringBitmap> segmentQueryableDocIdsMap = new HashMap<>();
    IndexSegment seg01 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds01 = createThreadSafeMutableRoaringBitmap(10);
    AtomicBoolean called = new AtomicBoolean(false);
    when(seg01.getValidDocIds()).then(invocationOnMock -> {
      called.set(true);
      latch.await();
      return validDocIds01;
    });
    upsertMetadataManager.trackSegment(seg01);
    segmentQueryableDocIdsMap.put(seg01, validDocIds01);

    IndexSegment seg02 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds02 = createThreadSafeMutableRoaringBitmap(11);
    when(seg02.getValidDocIds()).thenReturn(validDocIds02);
    upsertMetadataManager.trackSegment(seg02);
    segmentQueryableDocIdsMap.put(seg02, validDocIds02);

    IndexSegment seg03 = mock(IndexSegment.class);
    ThreadSafeMutableRoaringBitmap validDocIds03 = createThreadSafeMutableRoaringBitmap(12);
    when(seg03.getValidDocIds()).thenReturn(validDocIds03);
    upsertMetadataManager.trackSegment(seg03);
    segmentQueryableDocIdsMap.put(seg03, validDocIds03);

    List<SegmentContext> segmentContexts = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // This thread does replaceDocId and takes WLock first, and it'll refresh the upsert view
      executor.submit(() -> {
        RecordInfo recordInfo = new RecordInfo(null, 5, null, false);
        upsertMetadataManager.replaceDocId(seg03, validDocIds03, null, seg01, 0, 12, recordInfo);
      });
      // This thread gets segment contexts, but it's blocked until the first thread finishes replaceDocId.
      long startMs = System.currentTimeMillis();
      Future<Long> future = executor.submit(() -> {
        // Check called flag to let the first thread do replaceDocId, thus get WLock, first.
        while (!called.get()) {
          Thread.sleep(10);
        }
        segmentQueryableDocIdsMap.forEach((k, v) -> segmentContexts.add(new SegmentContext(k)));
        // This thread reuses the upsert view refreshed by the first thread above.
        upsertMetadataManager.setSegmentContexts(segmentContexts, new HashMap<>());
        return System.currentTimeMillis() - startMs;
      });
      // The first thread can only finish after the latch is counted down after 2s.
      // So the 2nd thread getting segment contexts will be blocked for 2s+.
      Thread.sleep(2000);
      latch.countDown();
      long duration = future.get(3, TimeUnit.SECONDS);
      assertTrue(duration >= 2000, duration + " was less than expected");
    } finally {
      executor.shutdownNow();
    }

    // Get the upsert view again, and the existing bitmap objects should be set in segment contexts.
    // The segmentContexts initialized above holds the same bitmaps objects as from the upsert view.
    List<SegmentContext> reuseSegmentContexts = new ArrayList<>();
    upsertMetadataManager.setSegmentContexts(reuseSegmentContexts, new HashMap<>());
    for (SegmentContext reuseSC : reuseSegmentContexts) {
      for (SegmentContext sc : segmentContexts) {
        if (reuseSC.getIndexSegment() == sc.getIndexSegment()) {
          assertSame(reuseSC.getQueryableDocIdsSnapshot(), sc.getQueryableDocIdsSnapshot());
        }
      }
      ThreadSafeMutableRoaringBitmap validDocIds = segmentQueryableDocIdsMap.get(reuseSC.getIndexSegment());
      assertNotNull(validDocIds);
      // The upsert view holds a clone of the original queryableDocIds held by the segment object.
      assertNotSame(reuseSC.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      assertEquals(reuseSC.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      // docId=0 in seg01 got invalidated.
      if (reuseSC.getIndexSegment() == seg01) {
        assertFalse(reuseSC.getQueryableDocIdsSnapshot().contains(0));
      }
      // docId=12 in seg03 was newly added.
      if (reuseSC.getIndexSegment() == seg03) {
        assertTrue(reuseSC.getQueryableDocIdsSnapshot().contains(12));
      }
    }

    // Force refresh the upsert view when getting it, so different bitmap objects should be set in segment contexts.
    List<SegmentContext> refreshSegmentContexts = new ArrayList<>();
    Map<String, String> queryOptions = new HashMap<>();
    queryOptions.put("upsertViewFreshnessMs", "0");
    upsertMetadataManager.setSegmentContexts(refreshSegmentContexts, queryOptions);
    for (SegmentContext refreshSC : refreshSegmentContexts) {
      for (SegmentContext sc : segmentContexts) {
        if (refreshSC.getIndexSegment() == sc.getIndexSegment()) {
          assertNotSame(refreshSC.getQueryableDocIdsSnapshot(), sc.getQueryableDocIdsSnapshot());
        }
      }
      ThreadSafeMutableRoaringBitmap validDocIds = segmentQueryableDocIdsMap.get(refreshSC.getIndexSegment());
      assertNotNull(validDocIds);
      // The upsert view holds a clone of the original queryableDocIds held by the segment object.
      assertNotSame(refreshSC.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      assertEquals(refreshSC.getQueryableDocIdsSnapshot(), validDocIds.getMutableRoaringBitmap());
      // docId=0 in seg01 got invalidated.
      if (refreshSC.getIndexSegment() == seg01) {
        assertFalse(refreshSC.getQueryableDocIdsSnapshot().contains(0));
      }
      // docId=12 in seg03 was newly added.
      if (refreshSC.getIndexSegment() == seg03) {
        assertTrue(refreshSC.getQueryableDocIdsSnapshot().contains(12));
      }
    }
  }

  private static ThreadSafeMutableRoaringBitmap createThreadSafeMutableRoaringBitmap(int docCnt) {
    ThreadSafeMutableRoaringBitmap bitmap = new ThreadSafeMutableRoaringBitmap();
    for (int i = 0; i < docCnt; i++) {
      bitmap.add(i);
    }
    return bitmap;
  }

  @Test
  public void testResolveComparisonTies() {
    // Build a record info list for testing
    int[] primaryKeys = new int[]{0, 1, 2, 0, 1, 0};
    int[] timestamps = new int[]{0, 0, 0, 0, 0, 0};
    int numRecords = primaryKeys.length;
    List<RecordInfo> recordInfoList = new ArrayList<>();
    for (int docId = 0; docId < numRecords; docId++) {
      recordInfoList.add(new RecordInfo(makePrimaryKey(primaryKeys[docId]), docId, timestamps[docId], false));
    }
    // Resolve comparison ties
    Iterator<RecordInfo> deDuplicatedRecords =
        BasePartitionUpsertMetadataManager.resolveComparisonTies(recordInfoList.iterator(), HashFunction.NONE);
    // Ensure we have only 1 record for each unique primary key
    Map<PrimaryKey, RecordInfo> recordsByPrimaryKeys = new HashMap<>();
    while (deDuplicatedRecords.hasNext()) {
      RecordInfo recordInfo = deDuplicatedRecords.next();
      assertFalse(recordsByPrimaryKeys.containsKey(recordInfo.getPrimaryKey()));
      recordsByPrimaryKeys.put(recordInfo.getPrimaryKey(), recordInfo);
    }
    assertEquals(recordsByPrimaryKeys.size(), 3);
    // Ensure that to resolve ties, we pick the last docId
    assertEquals(recordsByPrimaryKeys.get(makePrimaryKey(0)).getDocId(), 5);
    assertEquals(recordsByPrimaryKeys.get(makePrimaryKey(1)).getDocId(), 4);
    assertEquals(recordsByPrimaryKeys.get(makePrimaryKey(2)).getDocId(), 2);
  }

  private static ThreadSafeMutableRoaringBitmap createValidDocIds(int... docIds) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    bitmap.add(docIds);
    return new ThreadSafeMutableRoaringBitmap(bitmap);
  }

  private static ImmutableSegmentImpl createImmutableSegment(String segName, File segDir,
      List<String> segmentsTakenSnapshot)
      throws IOException {
    FileUtils.forceMkdir(segDir);
    SegmentMetadataImpl meta = mock(SegmentMetadataImpl.class);
    when(meta.getName()).thenReturn(segName);
    when(meta.getIndexDir()).thenReturn(segDir);
    return new ImmutableSegmentImpl(mock(SegmentDirectory.class), meta, new HashMap<>(), null) {
      public void persistValidDocIdsSnapshot() {
        segmentsTakenSnapshot.add(segName);
        super.persistValidDocIdsSnapshot();
      }
    };
  }

  private static PrimaryKey makePrimaryKey(int value) {
    return new PrimaryKey(new Object[]{value});
  }

  private static class DummyPartitionUpsertMetadataManager extends BasePartitionUpsertMetadataManager {

    protected DummyPartitionUpsertMetadataManager(String tableNameWithType, int partitionId, UpsertContext context) {
      super(tableNameWithType, partitionId, context);
    }

    public void trackSegment(IndexSegment seg) {
      _trackedSegments.add(seg);
    }

    @Override
    protected long getNumPrimaryKeys() {
      return 0;
    }

    @Override
    protected void addOrReplaceSegment(ImmutableSegmentImpl segment, ThreadSafeMutableRoaringBitmap validDocIds,
        @Nullable ThreadSafeMutableRoaringBitmap queryableDocIds, Iterator<RecordInfo> recordInfoIterator,
        @Nullable IndexSegment oldSegment, @Nullable MutableRoaringBitmap validDocIdsForOldSegment) {
    }

    @Override
    protected boolean doAddRecord(MutableSegment segment, RecordInfo recordInfo) {
      return false;
    }

    @Override
    protected void removeSegment(IndexSegment segment, MutableRoaringBitmap validDocIds) {
    }

    @Override
    protected GenericRow doUpdateRecord(GenericRow record, RecordInfo recordInfo) {
      return null;
    }

    @Override
    protected void doRemoveExpiredPrimaryKeys() {
    }
  }
}
