/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.carbondata.core.writer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.carbondata.common.logging.LogService;
import org.carbondata.common.logging.LogServiceFactory;
import org.carbondata.core.carbon.CarbonTableIdentifier;
import org.carbondata.core.carbon.path.CarbonStorePath;
import org.carbondata.core.carbon.path.CarbonTablePath;
import org.carbondata.core.constants.CarbonCommonConstants;
import org.carbondata.core.datastorage.store.filesystem.CarbonFile;
import org.carbondata.core.datastorage.store.impl.FileFactory;
import org.carbondata.core.reader.CarbonDictionaryColumnMetaChunk;
import org.carbondata.core.reader.CarbonDictionaryMetadataReaderImpl;
import org.carbondata.core.util.CarbonCoreLogEvent;
import org.carbondata.core.util.CarbonProperties;
import org.carbondata.core.util.CarbonUtil;
import org.carbondata.format.ColumnDictionaryChunk;
import org.carbondata.format.ColumnDictionaryChunkMeta;

import org.apache.thrift.TBase;

/**
 * This class is responsible for writing the dictionary file and its metadata
 */
public class CarbonDictionaryWriterImpl implements CarbonDictionaryWriter {

  /**
   * LOGGER
   */
  private static final LogService LOGGER =
      LogServiceFactory.getLogService(CarbonDictionaryWriterImpl.class.getName());

  /**
   * carbon type identifier
   */
  private CarbonTableIdentifier carbonTableIdentifier;

  /**
   * list which will hold values upto maximum of one dictionary chunk size
   */
  private List<ByteBuffer> oneDictionaryChunkList;

  /**
   * Meta object which will hold last segment entry details
   */
  private CarbonDictionaryColumnMetaChunk chunkMetaObjectForLastSegmentEntry;

  /**
   * dictionary file and meta thrift writer
   */
  private ThriftWriter dictionaryThriftWriter;

  /**
   * column name
   */
  private String columnIdentifier;

  /**
   * HDFS store path
   */
  private String hdfsStorePath;

  /**
   * dictionary file path
   */
  private String dictionaryFilePath;

  /**
   * dictionary metadata file path
   */
  private String dictionaryMetaFilePath;

  /**
   * start offset of dictionary chunk  for a segment
   */
  private long chunk_start_offset;

  /**
   * end offset of a dictionary chunk for a segment
   */
  private long chunk_end_offset;

  /**
   * total dictionary value record count for one segment
   */
  private int totalRecordCount;

  /**
   * total thrift object chunk count written for one segment
   */
  private int chunk_count;

  /**
   * chunk size for a dictionary file after which data will be written to disk
   */
  private int dictionary_one_chunk_size;

  /**
   * flag to check whether write method is called for first time
   */
  private boolean isFirstTime;

  private static final Charset defaultCharset = Charset.forName(
      CarbonCommonConstants.DEFAULT_CHARSET);

  /**
   * Constructor
   *
   * @param hdfsStorePath         HDFS store path
   * @param carbonTableIdentifier table identifier which will give table name and database name
   * @param columnIdentifier      column unique identifier
   */
  public CarbonDictionaryWriterImpl(String hdfsStorePath,
      CarbonTableIdentifier carbonTableIdentifier, String columnIdentifier) {
    this.carbonTableIdentifier = carbonTableIdentifier;
    this.columnIdentifier = columnIdentifier;
    this.hdfsStorePath = hdfsStorePath;
    this.isFirstTime = true;
  }

  /**
   * This method will write the data in thrift format to disk. This method will be guided by
   * parameter dictionary_one_chunk_size and data will be divided into chunks
   * based on this parameter
   *
   * @param value unique dictionary value
   * @throws IOException if an I/O error occurs
   */
  @Override public void write(String value) throws IOException {
    write(value.getBytes(defaultCharset));
  }

  /**
   * This method will write the data in thrift format to disk. This method will be guided by
   * parameter dictionary_one_chunk_size and data will be divided into chunks
   * based on this parameter
   *
   * @param value unique dictionary value
   * @throws IOException if an I/O error occurs
   */
  @Override public void write(byte[] value) throws IOException {
    if (isFirstTime) {
      init();
      isFirstTime = false;
    }
    // if one chunk size is equal to list size then write the data to file
    checkAndWriteDictionaryChunkToFile();
    oneDictionaryChunkList.add(ByteBuffer.wrap(value));
    totalRecordCount++;
  }

  /**
   * This method will write the data in thrift format to disk. This method will not be guided by
   * parameter dictionary_one_chunk_size and complete data will be written as one chunk
   *
   * @param valueList list of byte array. Each byte array is unique dictionary value
   * @throws IOException if an I/O error occurs
   */
  @Override public void write(List<byte[]> valueList) throws IOException {
    if (isFirstTime) {
      init();
      isFirstTime = false;
    }
    for (byte[] value : valueList) {
      oneDictionaryChunkList.add(ByteBuffer.wrap(value));
      totalRecordCount++;
    }
  }

  /**
   * write dictionary metadata file and close thrift object
   *
   * @throws IOException if an I/O error occurs
   */
  @Override public void close() throws IOException {
    if (null != dictionaryThriftWriter) {
      writeDictionaryFile();
      // close the thrift writer for dictionary file
      closeThriftWriter();
      this.chunk_end_offset = CarbonUtil.getFileSize(this.dictionaryFilePath);
      writeDictionaryMetadataFile();
    }
  }

  /**
   * check if the threshold has been reached for the number of
   * values that can kept in memory and then flush the data to file
   *
   * @throws IOException if an I/O error occurs
   */
  private void checkAndWriteDictionaryChunkToFile() throws IOException {
    if (oneDictionaryChunkList.size() >= dictionary_one_chunk_size) {
      writeDictionaryFile();
      createChunkList();
    }
  }

  /**
   * This method will serialize the object of dictionary file
   *
   * @throws IOException if an I/O error occurs
   */
  private void writeDictionaryFile() throws IOException {
    ColumnDictionaryChunk columnDictionaryChunk = new ColumnDictionaryChunk();
    columnDictionaryChunk.setValues(oneDictionaryChunkList);
    writeThriftObject(columnDictionaryChunk);
  }

  /**
   * This method will check and created the directory path where dictionary file has to be created
   *
   * @throws IOException if an I/O error occurs
   */
  private void init() throws IOException {
    initDictionaryChunkSize();
    CarbonTablePath carbonTablePath =
        CarbonStorePath.getCarbonTablePath(this.hdfsStorePath, carbonTableIdentifier);
    this.dictionaryFilePath = carbonTablePath.getDictionaryFilePath(columnIdentifier);
    this.dictionaryMetaFilePath = carbonTablePath.getDictionaryMetaFilePath(columnIdentifier);
    if (CarbonUtil.isFileExists(this.dictionaryFilePath)) {
      this.chunk_start_offset = CarbonUtil.getFileSize(this.dictionaryFilePath);
      validateDictionaryFileOffsetWithLastSegmentEntryOffset();
    }
    openThriftWriter(this.dictionaryFilePath);
    createChunkList();
  }

  /**
   * initialize the value of dictionary chunk that can be kept in memory at a time
   */
  private void initDictionaryChunkSize() {
    try {
      dictionary_one_chunk_size = Integer.parseInt(CarbonProperties.getInstance()
          .getProperty(CarbonCommonConstants.DICTIONARY_ONE_CHUNK_SIZE,
              CarbonCommonConstants.DICTIONARY_ONE_CHUNK_SIZE_DEFAULT));
    } catch (NumberFormatException e) {
      dictionary_one_chunk_size =
          Integer.parseInt(CarbonCommonConstants.DICTIONARY_ONE_CHUNK_SIZE_DEFAULT);
      LOGGER.error(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG,
          "Dictionary chunk size not configured properly. Taking default size "
              + dictionary_one_chunk_size);
    }
  }

  /**
   * initialise one dictionary size chunk list and increment chunk count
   */
  private void createChunkList() {
    this.oneDictionaryChunkList = new ArrayList<ByteBuffer>(dictionary_one_chunk_size);
    chunk_count++;
  }

  /**
   * if file already exists then read metadata file and
   * validate the last entry end offset with file size. If
   * they are not equal that means some invalid data is present which needs
   * to be truncated
   *
   * @throws IOException if an I/O error occurs
   */
  private void validateDictionaryFileOffsetWithLastSegmentEntryOffset() throws IOException {
    // read last dictionary chunk meta entry from dictionary metadata file
    chunkMetaObjectForLastSegmentEntry = getChunkMetaObjectForLastSegmentEntry();
    int bytesToTruncate =
        (int) (chunk_start_offset - chunkMetaObjectForLastSegmentEntry.getEnd_offset());
    if (bytesToTruncate > 0) {
      LOGGER.info(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG,
          "some inconsistency in dictionary file for column " + this.columnIdentifier);
      // truncate the dictionary data till chunk meta end offset
      FileFactory.FileType fileType = FileFactory.getFileType(this.dictionaryFilePath);
      CarbonFile carbonFile = FileFactory.getCarbonFile(this.dictionaryFilePath, fileType);
      boolean truncateSuccess = carbonFile
          .truncate(this.dictionaryFilePath, chunkMetaObjectForLastSegmentEntry.getEnd_offset());
      if (!truncateSuccess) {
        LOGGER.info(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG,
            "Diction file not truncated successfully for column " + this.columnIdentifier);
      }
    }
  }

  /**
   * This method will write the dictionary metadata file for a given column
   *
   * @throws IOException if an I/O error occurs
   */
  private void writeDictionaryMetadataFile() throws IOException {
    // Format of dictionary metadata file
    // min, max, start offset, end offset and chunk count
    int min_surrogate_key = 0;
    int max_surrogate_key = 0;
    // case 1: first time dictionary writing
    // previousMax = 0, totalRecordCount = 5, min = 1, max= 5
    // case2: file already exists
    // previousMax = 5, totalRecordCount = 10, min = 6, max = 15
    // case 3: no unique values, total records 0
    // previousMax = 15, totalRecordCount = 0, min = 15, max = 15
    // both min and max equal to previous max
    if (null != chunkMetaObjectForLastSegmentEntry) {
      if (0 == totalRecordCount) {
        min_surrogate_key = chunkMetaObjectForLastSegmentEntry.getMax_surrogate_key();
      } else {
        min_surrogate_key = chunkMetaObjectForLastSegmentEntry.getMax_surrogate_key() + 1;
      }
      max_surrogate_key =
          chunkMetaObjectForLastSegmentEntry.getMax_surrogate_key() + totalRecordCount;
    } else {
      if (totalRecordCount > 0) {
        min_surrogate_key = 1;
      }
      max_surrogate_key = totalRecordCount;
    }
    ColumnDictionaryChunkMeta dictionaryChunkMeta =
        new ColumnDictionaryChunkMeta(min_surrogate_key, max_surrogate_key, chunk_start_offset,
            chunk_end_offset, chunk_count);
    openThriftWriter(this.dictionaryMetaFilePath);
    // write dictionary metadata file
    writeThriftObject(dictionaryChunkMeta);
    closeThriftWriter();
    LOGGER.info(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG,
        "Dictionary metadata file written successfully for column " + this.columnIdentifier
            + " at path " + this.dictionaryMetaFilePath);
  }

  /**
   * open thrift writer for writing dictionary chunk/meta object
   *
   * @param dictionaryFile can be dictionary file name or dictionary metadata file name
   * @throws IOException if an I/O error occurs
   */
  private void openThriftWriter(String dictionaryFile) throws IOException {
    // create thrift writer instance
    dictionaryThriftWriter = new ThriftWriter(dictionaryFile, true);
    // open the file stream
    dictionaryThriftWriter.open();
  }

  /**
   * This method will write the thrift object to a file
   *
   * @param dictionaryThriftObject can be dictionary thrift object or dictionary metadata
   *                               thrift object
   * @throws IOException if an I/O error occurs
   */
  private void writeThriftObject(TBase dictionaryThriftObject) throws IOException {
    dictionaryThriftWriter.write(dictionaryThriftObject);
  }

  /**
   * close dictionary thrift writer
   */
  private void closeThriftWriter() {
    if (null != dictionaryThriftWriter) {
      dictionaryThriftWriter.close();
    }
  }

  /**
   * This method will read the dictionary chunk metadata thrift object for last entry
   *
   * @return last entry of dictionary meta chunk
   * @throws IOException if an I/O error occurs
   */
  private CarbonDictionaryColumnMetaChunk getChunkMetaObjectForLastSegmentEntry()
      throws IOException {
    CarbonDictionaryColumnMetaChunk carbonDictionaryColumnMetaChunk = null;
    CarbonDictionaryMetadataReaderImpl columnMetadataReaderImpl =
        new CarbonDictionaryMetadataReaderImpl(this.hdfsStorePath, this.carbonTableIdentifier,
            this.columnIdentifier);
    try {
      // read the last segment entry for dictionary metadata
      carbonDictionaryColumnMetaChunk =
          columnMetadataReaderImpl.readLastEntryOfDictionaryMetaChunk();
    } finally {
      // Close metadata reader
      columnMetadataReaderImpl.close();
    }
    return carbonDictionaryColumnMetaChunk;
  }
}
