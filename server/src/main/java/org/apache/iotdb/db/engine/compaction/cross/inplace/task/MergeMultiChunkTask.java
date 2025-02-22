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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.engine.compaction.cross.inplace.task;

import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.compaction.cross.inplace.manage.CrossSpaceMergeContext;
import org.apache.iotdb.db.engine.compaction.cross.inplace.manage.CrossSpaceMergeResource;
import org.apache.iotdb.db.engine.compaction.cross.inplace.manage.MergeManager;
import org.apache.iotdb.db.engine.compaction.cross.inplace.recover.InplaceCompactionLogger;
import org.apache.iotdb.db.engine.compaction.cross.inplace.selector.IMergePathSelector;
import org.apache.iotdb.db.engine.compaction.cross.inplace.selector.NaivePathSelector;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.storagegroup.TsFileManager;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.MergeUtils;
import org.apache.iotdb.db.utils.MergeUtils.MetaListEntry;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.BatchData;
import org.apache.iotdb.tsfile.read.common.Chunk;
import org.apache.iotdb.tsfile.read.reader.IPointReader;
import org.apache.iotdb.tsfile.read.reader.chunk.ChunkReader;
import org.apache.iotdb.tsfile.write.chunk.ChunkWriterImpl;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;
import org.apache.iotdb.tsfile.write.writer.RestorableTsFileIOWriter;
import org.apache.iotdb.tsfile.write.writer.TsFileIOWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.iotdb.db.utils.MergeUtils.writeBatchPoint;
import static org.apache.iotdb.db.utils.MergeUtils.writeTVPair;
import static org.apache.iotdb.db.utils.QueryUtils.modifyChunkMetaData;

public class MergeMultiChunkTask {

  private static final Logger logger = LoggerFactory.getLogger("COMPACTION");
  private static int minChunkPointNum =
      IoTDBDescriptor.getInstance().getConfig().getMergeChunkPointNumberThreshold();

  private InplaceCompactionLogger inplaceCompactionLogger;
  private List<PartialPath> unmergedDevice;

  private String taskName;
  private CrossSpaceMergeResource resource;
  private TimeValuePair[] currTimeValuePairs;
  private boolean fullMerge;

  private CrossSpaceMergeContext mergeContext;

  private AtomicInteger mergedChunkNum = new AtomicInteger();
  private AtomicInteger unmergedChunkNum = new AtomicInteger();
  private int mergedDeviceCnt;
  private double progress;

  private int concurrentMergeSeriesNum;
  private List<PartialPath> currMergingPaths = new ArrayList<>();
  // need to be cleared every device
  private final Map<TsFileSequenceReader, Iterator<Map<String, List<ChunkMetadata>>>>
      measurementChunkMetadataListMapIteratorCache =
          new TreeMap<>(
              (o1, o2) ->
                  TsFileManager.compareFileName(
                      new File(o1.getFileName()), new File(o2.getFileName())));
  // need to be cleared every device
  private final Map<TsFileSequenceReader, Map<String, List<ChunkMetadata>>>
      chunkMetadataListCacheForMerge =
          new TreeMap<>(
              (o1, o2) ->
                  TsFileManager.compareFileName(
                      new File(o1.getFileName()), new File(o2.getFileName())));

  private String storageGroupName;

  public MergeMultiChunkTask(
      CrossSpaceMergeContext context,
      String taskName,
      InplaceCompactionLogger inplaceCompactionLogger,
      CrossSpaceMergeResource mergeResource,
      boolean fullMerge,
      List<PartialPath> unmergedDevice,
      int concurrentMergeSeriesNum,
      String storageGroupName) {
    this.mergeContext = context;
    this.taskName = taskName;
    this.inplaceCompactionLogger = inplaceCompactionLogger;
    this.resource = mergeResource;
    this.fullMerge = fullMerge;
    this.unmergedDevice = unmergedDevice;
    this.concurrentMergeSeriesNum = concurrentMergeSeriesNum;
    this.storageGroupName = storageGroupName;
  }

  void mergeSeries() throws IOException, MetadataException {
    long startTime = System.currentTimeMillis();
    for (TsFileResource seqFile : resource.getSeqFiles()) {
      // record the unmergeChunkStartTime for each sensor in each file
      mergeContext.getUnmergedChunkStartTimes().put(seqFile, new HashMap<>());
    }
    mergedDeviceCnt = 0;
    // merge each series and write data into each seqFile's corresponding temp merge file
    for (PartialPath device : unmergedDevice) {
      // TODO: use statistics of queries to better rearrange series
      List<PartialPath> measurementPathListByDevice;
      measurementPathListByDevice =
          new ArrayList<>(IoTDB.metaManager.getAllMeasurementByDevicePath(device));
      // just for unit tests, we need to consider whether there is a need to exist
      Collections.sort(measurementPathListByDevice);

      IMergePathSelector pathSelector =
          new NaivePathSelector(measurementPathListByDevice, concurrentMergeSeriesNum);
      while (pathSelector.hasNext()) {
        currMergingPaths = pathSelector.next();
        mergePaths();
        resource.clearChunkWriterCache();
        if (Thread.interrupted()) {
          logger.info("MergeMultiChunkTask {} aborted", taskName);
          Thread.currentThread().interrupt();
          return;
        }
      }
      mergedDeviceCnt++;
      logMergeProgress();
      measurementChunkMetadataListMapIteratorCache.clear();
      chunkMetadataListCacheForMerge.clear();
    }
    if (logger.isInfoEnabled()) {
      logger.info(
          "{} all series are merged after {}ms", taskName, System.currentTimeMillis() - startTime);
    }
    inplaceCompactionLogger.logAllTsEnd();
  }

  private void logMergeProgress() {
    if (logger.isInfoEnabled()) {
      double newProgress = 100 * mergedDeviceCnt / (double) (unmergedDevice.size());
      if (newProgress - progress >= 10.0) {
        progress = newProgress;
        logger.info("{} has merged {}% devices", taskName, progress);
      }
    }
  }

  public String getProgress() {
    return String.format("Processed %d/%d devices", mergedDeviceCnt, unmergedDevice.size());
  }

  private void mergePaths() throws IOException, MetadataException {
    inplaceCompactionLogger.logTSStart(currMergingPaths);
    IPointReader[] unseqReaders = resource.getUnseqReaders(currMergingPaths);
    currTimeValuePairs = new TimeValuePair[currMergingPaths.size()];
    for (int i = 0; i < currMergingPaths.size(); i++) {
      if (unseqReaders[i].hasNextTimeValuePair()) {
        currTimeValuePairs[i] = unseqReaders[i].currentTimeValuePair();
      }
    }

    for (int i = 0; i < resource.getSeqFiles().size(); i++) {
      pathsMergeOneFile(i, unseqReaders);

      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    inplaceCompactionLogger.logTSEnd();
  }

  private String getMaxSensor(List<PartialPath> sensors) {
    String maxSensor = sensors.get(0).getMeasurement();
    for (int i = 1; i < sensors.size(); i++) {
      if (maxSensor.compareTo(sensors.get(i).getMeasurement()) < 0) {
        maxSensor = sensors.get(i).getMeasurement();
      }
    }
    return maxSensor;
  }

  private void pathsMergeOneFile(int seqFileIdx, IPointReader[] unseqReaders)
      throws IOException, MetadataException {
    TsFileResource currTsFile = resource.getSeqFiles().get(seqFileIdx);
    // all paths in one call are from the same device
    String deviceId = currMergingPaths.get(0).getDevice();
    long currDeviceMinTime = currTsFile.getStartTime(deviceId);

    for (PartialPath path : currMergingPaths) {
      mergeContext.getUnmergedChunkStartTimes().get(currTsFile).put(path, new ArrayList<>());
    }

    // if this TsFile receives data later than fileLimitTime, it will overlap the next TsFile,
    // which is forbidden
    for (TimeValuePair timeValuePair : currTimeValuePairs) {
      if (timeValuePair != null && timeValuePair.getTimestamp() < currDeviceMinTime) {
        currDeviceMinTime = timeValuePair.getTimestamp();
      }
    }
    boolean isLastFile = seqFileIdx + 1 == resource.getSeqFiles().size();

    TsFileSequenceReader fileSequenceReader = resource.getFileReader(currTsFile);
    List<Modification>[] modifications = new List[currMergingPaths.size()];
    List<ChunkMetadata>[] seqChunkMeta = new List[currMergingPaths.size()];
    Iterator<Map<String, List<ChunkMetadata>>> measurementChunkMetadataListMapIterator =
        measurementChunkMetadataListMapIteratorCache.computeIfAbsent(
            fileSequenceReader,
            (tsFileSequenceReader -> {
              try {
                return tsFileSequenceReader.getMeasurementChunkMetadataListMapIterator(deviceId);
              } catch (IOException e) {
                logger.error(
                    "unseq compaction task {}, getMeasurementChunkMetadataListMapIterator meets error. iterator create failed.",
                    taskName,
                    e);
                return null;
              }
            }));
    if (measurementChunkMetadataListMapIterator == null) {
      return;
    }

    // need the max sensor in lexicographic order
    String lastSensor = getMaxSensor(currMergingPaths);
    String currSensor = null;
    Map<String, List<ChunkMetadata>> measurementChunkMetadataListMap = new TreeMap<>();
    // find all sensor to merge in order, if exceed, then break
    while (currSensor == null || currSensor.compareTo(lastSensor) < 0) {
      measurementChunkMetadataListMap =
          chunkMetadataListCacheForMerge.computeIfAbsent(
              fileSequenceReader, tsFileSequenceReader -> new TreeMap<>());
      // if empty, get measurementChunkMetadataList block to use later
      if (measurementChunkMetadataListMap.isEmpty()) {
        // if do not have more sensor, just break
        if (measurementChunkMetadataListMapIterator.hasNext()) {
          measurementChunkMetadataListMap.putAll(measurementChunkMetadataListMapIterator.next());
        } else {
          break;
        }
      }

      Iterator<Entry<String, List<ChunkMetadata>>> measurementChunkMetadataListEntryIterator =
          measurementChunkMetadataListMap.entrySet().iterator();
      while (measurementChunkMetadataListEntryIterator.hasNext()) {
        Entry<String, List<ChunkMetadata>> measurementChunkMetadataListEntry =
            measurementChunkMetadataListEntryIterator.next();
        currSensor = measurementChunkMetadataListEntry.getKey();

        // fill modifications and seqChunkMetas to be used later
        for (int i = 0; i < currMergingPaths.size(); i++) {
          if (currMergingPaths.get(i).getMeasurement().equals(currSensor)) {
            modifications[i] = resource.getModifications(currTsFile, currMergingPaths.get(i));
            seqChunkMeta[i] = measurementChunkMetadataListEntry.getValue();
            modifyChunkMetaData(seqChunkMeta[i], modifications[i]);
            for (ChunkMetadata chunkMetadata : seqChunkMeta[i]) {
              resource.updateStartTime(currTsFile, deviceId, chunkMetadata.getStartTime());
              resource.updateEndTime(currTsFile, deviceId, chunkMetadata.getEndTime());
            }

            if (Thread.interrupted()) {
              Thread.currentThread().interrupt();
              return;
            }
            break;
          }
        }

        // current sensor larger than last needed sensor, just break out to outer loop
        if (currSensor.compareTo(lastSensor) > 0) {
          break;
        } else {
          measurementChunkMetadataListEntryIterator.remove();
        }
      }
    }
    // update measurementChunkMetadataListMap
    chunkMetadataListCacheForMerge.put(fileSequenceReader, measurementChunkMetadataListMap);

    List<Integer> unskippedPathIndices = filterNoDataPaths(seqChunkMeta, seqFileIdx);

    if (unskippedPathIndices.isEmpty()) {
      return;
    }

    RestorableTsFileIOWriter mergeFileWriter = resource.getMergeFileWriter(currTsFile);
    for (PartialPath path : currMergingPaths) {
      IMeasurementSchema schema = resource.getSchema(path);
      mergeFileWriter.addSchema(path, schema);
    }
    // merge unseq data with seq data in this file or small chunks in this file into a larger chunk
    mergeFileWriter.startChunkGroup(deviceId);
    boolean dataWritten =
        mergeChunks(
            seqChunkMeta,
            isLastFile,
            fileSequenceReader,
            unseqReaders,
            mergeFileWriter,
            currTsFile);
    if (dataWritten) {
      mergeFileWriter.endChunkGroup();
      inplaceCompactionLogger.logFilePosition(mergeFileWriter.getFile());
      currTsFile.updateStartTime(deviceId, currDeviceMinTime);
    }
  }

  private List<Integer> filterNoDataPaths(List[] seqChunkMeta, int seqFileIdx) {
    // if the last seqFile does not contains this series but the unseqFiles do, data of this
    // series should also be written into a new chunk
    List<Integer> ret = new ArrayList<>();
    for (int i = 0; i < currMergingPaths.size(); i++) {
      if ((seqChunkMeta[i] == null || seqChunkMeta[i].isEmpty())
          && !(seqFileIdx + 1 == resource.getSeqFiles().size() && currTimeValuePairs[i] != null)) {
        continue;
      }
      ret.add(i);
    }
    return ret;
  }

  @SuppressWarnings("squid:S3776") // Suppress high Cognitive Complexity warning
  private boolean mergeChunks(
      List<ChunkMetadata>[] seqChunkMeta,
      boolean isLastFile,
      TsFileSequenceReader reader,
      IPointReader[] unseqReaders,
      RestorableTsFileIOWriter mergeFileWriter,
      TsFileResource currFile)
      throws IOException {
    int[] ptWrittens = new int[seqChunkMeta.length];
    int mergeChunkSubTaskNum =
        IoTDBDescriptor.getInstance().getConfig().getMergeChunkSubThreadNum();
    MetaListEntry[] metaListEntries = new MetaListEntry[currMergingPaths.size()];
    PriorityQueue<Integer>[] chunkIdxHeaps = new PriorityQueue[mergeChunkSubTaskNum];

    // if merge path is smaller than mergeChunkSubTaskNum, will use merge path number.
    // so thread are not wasted.
    if (currMergingPaths.size() < mergeChunkSubTaskNum) {
      mergeChunkSubTaskNum = currMergingPaths.size();
    }

    for (int i = 0; i < mergeChunkSubTaskNum; i++) {
      chunkIdxHeaps[i] = new PriorityQueue<>();
    }
    int idx = 0;
    for (int i = 0; i < currMergingPaths.size(); i++) {
      chunkIdxHeaps[idx % mergeChunkSubTaskNum].add(i);
      if (seqChunkMeta[i] == null || seqChunkMeta[i].isEmpty()) {
        continue;
      }

      MetaListEntry entry = new MetaListEntry(i, seqChunkMeta[i]);
      entry.next();
      metaListEntries[i] = entry;
      idx++;
      ptWrittens[i] = 0;
    }

    mergedChunkNum.set(0);
    unmergedChunkNum.set(0);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < mergeChunkSubTaskNum; i++) {
      futures.add(
          MergeManager.getINSTANCE()
              .submitChunkSubTask(
                  new MergeChunkHeapTask(
                      chunkIdxHeaps[i],
                      metaListEntries,
                      ptWrittens,
                      reader,
                      mergeFileWriter,
                      unseqReaders,
                      currFile,
                      isLastFile,
                      i)));

      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    for (int i = 0; i < mergeChunkSubTaskNum; i++) {
      try {
        futures.get(i).get();
      } catch (InterruptedException e) {
        logger.error("MergeChunkHeapTask interrupted", e);
        Thread.currentThread().interrupt();
        return false;
      } catch (ExecutionException e) {
        throw new IOException(e);
      }
    }

    // add merge and unmerged chunk statistic
    mergeContext
        .getMergedChunkCnt()
        .compute(
            currFile,
            (tsFileResource, anInt) ->
                anInt == null ? mergedChunkNum.get() : anInt + mergedChunkNum.get());
    mergeContext
        .getUnmergedChunkCnt()
        .compute(
            currFile,
            (tsFileResource, anInt) ->
                anInt == null ? unmergedChunkNum.get() : anInt + unmergedChunkNum.get());

    return mergedChunkNum.get() > 0;
  }

  /**
   * merge a sequence chunk SK
   *
   * <p>1. no need to write the chunk to .merge file when: isn't full merge & there isn't unclosed
   * chunk before & SK is big enough & SK isn't overflowed & SK isn't modified
   *
   * <p>
   *
   * <p>2. write SK to .merge.file without compressing when: is full merge & there isn't unclosed
   * chunk before & SK is big enough & SK isn't overflowed & SK isn't modified
   *
   * <p>3. other cases: need to unCompress the chunk and write 3.1 SK isn't overflowed 3.2 SK is
   * overflowed
   */
  @SuppressWarnings("java:S2445") // avoid writing the same writer concurrently
  private int mergeChunkV2(
      ChunkMetadata currMeta,
      boolean chunkOverflowed,
      boolean chunkTooSmall,
      Chunk chunk,
      int lastUnclosedChunkPoint,
      int pathIdx,
      TsFileIOWriter mergeFileWriter,
      IPointReader unseqReader,
      ChunkWriterImpl chunkWriter,
      TsFileResource currFile)
      throws IOException {
    int unclosedChunkPoint = lastUnclosedChunkPoint;
    boolean chunkModified =
        (currMeta.getDeleteIntervalList() != null && !currMeta.getDeleteIntervalList().isEmpty());

    // no need to write the chunk to .merge file
    if (!fullMerge
        && lastUnclosedChunkPoint == 0
        && !chunkTooSmall
        && !chunkOverflowed
        && !chunkModified) {
      unmergedChunkNum.incrementAndGet();
      mergeContext
          .getUnmergedChunkStartTimes()
          .get(currFile)
          .get(currMergingPaths.get(pathIdx))
          .add(currMeta.getStartTime());
      return 0;
    }

    // write SK to .merge.file without compressing
    if (fullMerge
        && lastUnclosedChunkPoint == 0
        && !chunkTooSmall
        && !chunkOverflowed
        && !chunkModified) {
      synchronized (mergeFileWriter) {
        mergeFileWriter.writeChunk(chunk, currMeta);
      }
      mergeContext.incTotalPointWritten(currMeta.getNumOfPoints());
      mergeContext.incTotalChunkWritten();
      mergedChunkNum.incrementAndGet();
      return 0;
    }

    // 3.1 SK isn't overflowed, just uncompress and write sequence chunk
    if (!chunkOverflowed) {
      unclosedChunkPoint += MergeUtils.writeChunkWithoutUnseq(chunk, chunkWriter);
      mergedChunkNum.incrementAndGet();
    } else {
      // 3.2 SK is overflowed, uncompress sequence chunk and merge with unseq chunk, then write
      unclosedChunkPoint +=
          writeChunkWithUnseq(chunk, chunkWriter, unseqReader, currMeta.getEndTime(), pathIdx);
      mergedChunkNum.incrementAndGet();
    }

    // update points written statistics
    mergeContext.incTotalPointWritten((long) unclosedChunkPoint - lastUnclosedChunkPoint);
    if (minChunkPointNum > 0 && unclosedChunkPoint >= minChunkPointNum
        || unclosedChunkPoint > 0 && minChunkPointNum < 0) {
      // the new chunk's size is large enough and it should be flushed
      synchronized (mergeFileWriter) {
        chunkWriter.writeToFileWriter(mergeFileWriter);
      }
      unclosedChunkPoint = 0;
    }
    return unclosedChunkPoint;
  }

  private int writeRemainingUnseq(
      ChunkWriterImpl chunkWriter, IPointReader unseqReader, long timeLimit, int pathIdx)
      throws IOException {
    int ptWritten = 0;
    while (currTimeValuePairs[pathIdx] != null
        && currTimeValuePairs[pathIdx].getTimestamp() < timeLimit) {
      writeTVPair(currTimeValuePairs[pathIdx], chunkWriter);
      ptWritten++;
      unseqReader.nextTimeValuePair();
      currTimeValuePairs[pathIdx] =
          unseqReader.hasNextTimeValuePair() ? unseqReader.currentTimeValuePair() : null;
    }
    return ptWritten;
  }

  private int writeChunkWithUnseq(
      Chunk chunk,
      ChunkWriterImpl chunkWriter,
      IPointReader unseqReader,
      long chunkLimitTime,
      int pathIdx)
      throws IOException {
    int cnt = 0;
    ChunkReader chunkReader = new ChunkReader(chunk, null);
    while (chunkReader.hasNextSatisfiedPage()) {
      BatchData batchData = chunkReader.nextPageData();
      cnt += mergeWriteBatch(batchData, chunkWriter, unseqReader, pathIdx);
    }
    cnt += writeRemainingUnseq(chunkWriter, unseqReader, chunkLimitTime, pathIdx);
    return cnt;
  }

  private int mergeWriteBatch(
      BatchData batchData, ChunkWriterImpl chunkWriter, IPointReader unseqReader, int pathIdx)
      throws IOException {
    int cnt = 0;
    for (int i = 0; i < batchData.length(); i++) {
      long time = batchData.getTimeByIndex(i);
      // merge data in batch and data in unseqReader
      boolean overwriteSeqPoint = false;
      // unseq point.time <= sequence point.time, write unseq point
      while (currTimeValuePairs[pathIdx] != null
          && currTimeValuePairs[pathIdx].getTimestamp() <= time) {
        writeTVPair(currTimeValuePairs[pathIdx], chunkWriter);
        if (currTimeValuePairs[pathIdx].getTimestamp() == time) {
          overwriteSeqPoint = true;
        }
        unseqReader.nextTimeValuePair();
        currTimeValuePairs[pathIdx] =
            unseqReader.hasNextTimeValuePair() ? unseqReader.currentTimeValuePair() : null;
        cnt++;
      }
      // unseq point.time > sequence point.time, write seq point
      if (!overwriteSeqPoint) {
        writeBatchPoint(batchData, i, chunkWriter);
        cnt++;
      }
    }
    return cnt;
  }

  public class MergeChunkHeapTask implements Callable<Void> {

    private PriorityQueue<Integer> chunkIdxHeap;
    private MetaListEntry[] metaListEntries;
    private int[] ptWrittens;
    private TsFileSequenceReader reader;
    private RestorableTsFileIOWriter mergeFileWriter;
    private IPointReader[] unseqReaders;
    private TsFileResource currFile;
    private boolean isLastFile;
    private int taskNum;

    private int totalSeriesNum;

    public MergeChunkHeapTask(
        PriorityQueue<Integer> chunkIdxHeap,
        MetaListEntry[] metaListEntries,
        int[] ptWrittens,
        TsFileSequenceReader reader,
        RestorableTsFileIOWriter mergeFileWriter,
        IPointReader[] unseqReaders,
        TsFileResource currFile,
        boolean isLastFile,
        int taskNum) {
      this.chunkIdxHeap = chunkIdxHeap;
      this.metaListEntries = metaListEntries;
      this.ptWrittens = ptWrittens;
      this.reader = reader;
      this.mergeFileWriter = mergeFileWriter;
      this.unseqReaders = unseqReaders;
      this.currFile = currFile;
      this.isLastFile = isLastFile;
      this.taskNum = taskNum;
      this.totalSeriesNum = chunkIdxHeap.size();
    }

    @Override
    public Void call() throws Exception {
      mergeChunkHeap();
      return null;
    }

    @SuppressWarnings("java:S2445") // avoid reading the same reader concurrently
    private void mergeChunkHeap() throws IOException, MetadataException {
      while (!chunkIdxHeap.isEmpty()) {
        int pathIdx = chunkIdxHeap.poll();
        PartialPath path = currMergingPaths.get(pathIdx);
        IMeasurementSchema measurementSchema = resource.getSchema(path);
        // chunkWriter will keep the data in memory
        ChunkWriterImpl chunkWriter = resource.getChunkWriter(measurementSchema);
        if (Thread.interrupted()) {
          Thread.currentThread().interrupt();
          return;
        }

        if (metaListEntries[pathIdx] != null) {
          MetaListEntry metaListEntry = metaListEntries[pathIdx];
          ChunkMetadata currMeta = metaListEntry.current();
          boolean isLastChunk = !metaListEntry.hasNext();
          boolean chunkOverflowed =
              MergeUtils.isChunkOverflowed(currTimeValuePairs[pathIdx], currMeta);
          boolean chunkTooSmall =
              MergeUtils.isChunkTooSmall(
                  ptWrittens[pathIdx], currMeta, isLastChunk, minChunkPointNum);

          Chunk chunk;
          synchronized (reader) {
            chunk = reader.readMemChunk(currMeta);
          }
          ptWrittens[pathIdx] =
              mergeChunkV2(
                  currMeta,
                  chunkOverflowed,
                  chunkTooSmall,
                  chunk,
                  ptWrittens[pathIdx],
                  pathIdx,
                  mergeFileWriter,
                  unseqReaders[pathIdx],
                  chunkWriter,
                  currFile);

          if (!isLastChunk) {
            metaListEntry.next();
            chunkIdxHeap.add(pathIdx);
            continue;
          }
        }
        // this only happens when the seqFiles do not contain this series, otherwise the remaining
        // data will be merged with the last chunk in the seqFiles
        if (isLastFile && currTimeValuePairs[pathIdx] != null) {
          ptWrittens[pathIdx] +=
              writeRemainingUnseq(chunkWriter, unseqReaders[pathIdx], Long.MAX_VALUE, pathIdx);
          mergedChunkNum.incrementAndGet();
        }
        // the last merged chunk may still be smaller than the threshold, flush it anyway
        if (ptWrittens[pathIdx] > 0) {
          synchronized (mergeFileWriter) {
            chunkWriter.writeToFileWriter(mergeFileWriter);
          }
        }
      }
    }

    public String getStorageGroupName() {
      return storageGroupName;
    }

    public String getTaskName() {
      return taskName + "_" + taskNum;
    }

    public String getProgress() {
      return String.format(
          "Processed %d/%d series", totalSeriesNum - chunkIdxHeap.size(), totalSeriesNum);
    }
  }
}
