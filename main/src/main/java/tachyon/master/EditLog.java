/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tachyon.master;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.hadoop.fs.FSDataOutputStream;

import tachyon.Constants;
import tachyon.Pair;
import tachyon.UnderFileSystem;
import tachyon.io.Utils;
import tachyon.thrift.BlockInfoException;
import tachyon.thrift.FileAlreadyExistException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.SuspectedFileSizeException;
import tachyon.thrift.TableDoesNotExistException;
import tachyon.thrift.TachyonException;
import tachyon.util.CommonUtils;

/**
 * Master operation journal.
 */
public class EditLog {
  static final byte OP_INVALID = -1;
  static final byte OP_ADD_CHECKPOINT = 0;
  static final byte OP_ADD_BLOCK = 1;
  static final byte OP_CREATE_FILE = 2;
  static final byte OP_DELETE = 3;
  static final byte OP_RENAME = 4;
  static final byte OP_UNPIN_FILE = 5;
  static final byte OP_UPDATE_RAW_TABLE_METADATA = 6;
  static final byte OP_COMPLETE_FILE = 7;
  static final byte OP_CREATE_DEPENDENCY = 8;
  static final byte OP_CREATE_RAW_TABLE = 9;

  private final static Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  public static void deleteCompletedLogs(String path, int upTo) {
    UnderFileSystem ufs = UnderFileSystem.get(path);
    String folder =
        path.substring(0, path.lastIndexOf(Constants.PATH_SEPARATOR) + 1) + "completed";
    try {
      for (int i = 0; i < upTo; i ++) {
        String toDelete = CommonUtils.concat(folder, i + ".editLog");
        LOG.info("Deleting editlog " + toDelete);
        ufs.delete(toDelete, true);
      }
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  // When a master is replaying an edit log, make the current edit log as an INACTIVE one.
  private final boolean INACTIVE;

  private final String PATH;
  private static int mBackUpLogStartNum = -1;

  private static long mCurrentTId = 0;

  /**
   * Load edit log.
   * 
   * @param info
   *          The Master Info.
   * @param path
   *          The path of the edit logs.
   * @param currentLogFileNum
   *          The smallest completed log number that this master has not loaded
   * @return The last transaction id.
   * @throws IOException
   */
  public static long load(MasterInfo info, String path, int currentLogFileNum) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.get(path);
    if (!ufs.exists(path)) {
      LOG.info("Edit Log " + path + " does not exist.");
      return 0;
    }
    LOG.info("currentLogNum passed in was " + currentLogFileNum);
    int completedLogs = currentLogFileNum;
    mBackUpLogStartNum = currentLogFileNum;
    int numFiles = 1;
    String completedPath =
        path.substring(0, path.lastIndexOf(Constants.PATH_SEPARATOR) + 1) + "completed";
    if (!ufs.exists(completedPath)) {
      LOG.info("No completed edit logs to be parsed");
    } else {
      while (ufs.exists(CommonUtils.concat(completedPath, (completedLogs ++) + ".editLog"))) {
        numFiles ++;
      }
    }
    String editLogs[] = new String[numFiles];
    for (int i = 0; i < numFiles; i ++) {
      if (i != numFiles - 1) {
        editLogs[i] = CommonUtils.concat(completedPath, (i + currentLogFileNum) + ".editLog");
      } else {
        editLogs[i] = path;
      }
    }

    for (String currentPath : editLogs) {
      LOG.info("Loading Edit Log " + currentPath);
      loadSingleLog(info, currentPath);
    }
    ufs.close();
    return mCurrentTId;
  }

  public static void loadSingleLog(MasterInfo info, String path) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.get(path);

    DataInputStream is = new DataInputStream(ufs.open(path));
    while (true) {
      byte op;
      long tId;
      try {
        tId = is.readLong();
      } catch (EOFException e) {
        break;
      }

      mCurrentTId = tId;
      op = is.readByte();
      try {
        switch (op) {
        case OP_ADD_CHECKPOINT: {
          info.addCheckpoint(-1, is.readInt(), is.readLong(), Utils.readString(is));
          break;
        }
        case OP_ADD_BLOCK: {
          info.opAddBlock(is.readInt(), is.readInt(), is.readLong());
          break;
        }
        case OP_CREATE_FILE: {
          info._createFile(is.readBoolean(), Utils.readString(is), is.readBoolean(),
              is.readLong(), is.readLong());
          break;
        }
        case OP_DELETE: {
          info.delete(is.readInt(), is.readBoolean());
          break;
        }
        case OP_RENAME: {
          info.rename(is.readInt(), Utils.readString(is));
          break;
        }
        case OP_UNPIN_FILE: {
          info.unpinFile(is.readInt());
          break;
        }
        case OP_UPDATE_RAW_TABLE_METADATA: {
          info.updateRawTableMetadata(is.readInt(), Utils.readByteBuffer(is));
          break;
        }
        case OP_COMPLETE_FILE: {
          info.completeFile(is.readInt());
          break;
        }
        case OP_CREATE_DEPENDENCY: {
          info._createDependency(Utils.readIntegerList(is), Utils.readIntegerList(is),
              Utils.readString(is), Utils.readByteBufferList(is), Utils.readString(is),
              Utils.readString(is), Utils.readString(is),
              DependencyType.getDependencyType(is.readInt()), is.readInt(), is.readLong());
          break;
        }
        case OP_CREATE_RAW_TABLE: {
          info._createRawTable(is.readInt(), is.readInt(), Utils.readByteBuffer(is));
          break;
        }
        default:
          throw new IOException("Invalid op type " + op);
        }
      } catch (SuspectedFileSizeException e) {
        throw new IOException(e);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TableDoesNotExistException e) {
        throw new IOException(e);
      }
    }

    is.close();
    ufs.close();
  }

  public static void markUpToDate(String path) {
    UnderFileSystem ufs = UnderFileSystem.get(path);
    String folder =
        path.substring(0, path.lastIndexOf(Constants.PATH_SEPARATOR) + 1) + "completed";
    try {
      // delete all loaded editlogs since mBackupLogStartNum.
      String toDelete = CommonUtils.concat(folder, mBackUpLogStartNum + ".editLog");
      while (ufs.exists(toDelete)) {
        LOG.info("Deleting editlog " + toDelete);
        ufs.delete(toDelete, true);
        mBackUpLogStartNum ++;
        toDelete = CommonUtils.concat(folder, mBackUpLogStartNum + ".editLog");
      }
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
    mBackUpLogStartNum = -1;
  }

  private UnderFileSystem UFS;
  private DataOutputStream DOS;
  private OutputStream OS;

  // Starting from 1.
  private long mFlushedTransactionId = 0;

  private long mTransactionId = 0;

  private int mCurrentLogFileNum = 0;

  private int mMaxLogSize = Constants.MB;

  public EditLog(String path, boolean inactive, long transactionId) throws IOException {
    INACTIVE = inactive;

    if (!INACTIVE) {
      LOG.info("Creating edit log file " + path);
      PATH = path;
      UFS = UnderFileSystem.get(path);
      if (mBackUpLogStartNum != -1) {
        String folder =
            path.substring(0, path.lastIndexOf(Constants.PATH_SEPARATOR) + 1) + "/completed";
        LOG.info("Deleting completed editlogs that are part of the image.");
        deleteCompletedLogs(path, mBackUpLogStartNum);
        LOG.info("Backing up logs from " + mBackUpLogStartNum + " since image is not updated.");
        UFS.mkdirs(folder, true);
        String toRename = CommonUtils.concat(folder, mBackUpLogStartNum + ".editLog");
        int currentLogFileNum = 0;
        while (UFS.exists(toRename)) {
          LOG.info("Rename " + toRename + " to "
              + CommonUtils.concat(folder, currentLogFileNum + ".editLog"));
          currentLogFileNum ++;
          mBackUpLogStartNum ++;
          toRename = CommonUtils.concat(folder, mBackUpLogStartNum + ".editLog");
        }
        if (UFS.exists(path)) {
          UFS.rename(path, CommonUtils.concat(folder, currentLogFileNum + ".editLog"));
          LOG.info("Rename " + path + " to "
              + CommonUtils.concat(folder, currentLogFileNum + ".editLog"));
          currentLogFileNum ++;
        }
        mBackUpLogStartNum = -1;
      }

      // In case this file is created by different dfs-clients, which has been
      // fixed in HDFS-3755 since 3.0.0, 2.0.2-alpha
      if (UFS.exists(path)) {
        UFS.delete(path, true);
      }
      OS = UFS.create(path);
      DOS = new DataOutputStream(OS);
      LOG.info("Created file " + path);
      mFlushedTransactionId = transactionId;
      mTransactionId = transactionId;
    } else {
      PATH = null;
      UFS = null;
      OS = null;
      DOS = null;
    }
  }

  /**
   * Only close those outputStreams.
   */
  private synchronized void _close() {
    try {
      if (DOS != null) {
        DOS.close();
      }
      if (OS != null) {
        OS.close();
      }
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void addBlock(int fileId, int blockIndex, long blockLength) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_ADD_BLOCK);
      DOS.writeInt(fileId);
      DOS.writeInt(blockIndex);
      DOS.writeLong(blockLength);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void addCheckpoint(int fileId, long length, String checkpointPath) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_ADD_CHECKPOINT);
      DOS.writeInt(fileId);
      DOS.writeLong(length);
      Utils.writeString(checkpointPath, DOS);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  /**
   * Close the log.
   */
  public synchronized void close() {
    if (INACTIVE) {
      return;
    }
    try {
      _close();
      UFS.close();
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void completeFile(int fileId) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_COMPLETE_FILE);
      DOS.writeInt(fileId);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void createDependency(List<Integer> parents, List<Integer> children,
      String commandPrefix, List<ByteBuffer> data, String comment, String framework,
      String frameworkVersion, DependencyType dependencyType, int depId, long creationTimeMs) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_CREATE_DEPENDENCY);
      Utils.writeIntegerList(parents, DOS);
      Utils.writeIntegerList(children, DOS);
      Utils.writeString(commandPrefix, DOS);
      Utils.writeByteBufferList(data, DOS);
      Utils.writeString(comment, DOS);
      Utils.writeString(framework, DOS);
      Utils.writeString(frameworkVersion, DOS);
      DOS.writeInt(dependencyType.getValue());
      DOS.writeInt(depId);
      DOS.writeLong(creationTimeMs);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void createFile(boolean recursive, String path, boolean directory,
      long blockSizeByte, long creationTimeMs) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_CREATE_FILE);
      DOS.writeBoolean(recursive);
      Utils.writeString(path, DOS);
      DOS.writeBoolean(directory);
      DOS.writeLong(blockSizeByte);
      DOS.writeLong(creationTimeMs);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void createRawTable(int tableId, int columns, ByteBuffer metadata) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_CREATE_RAW_TABLE);
      DOS.writeInt(tableId);
      DOS.writeInt(columns);
      Utils.writeByteBuffer(metadata, DOS);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void delete(int fileId, boolean recursive) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_DELETE);
      DOS.writeInt(fileId);
      DOS.writeBoolean(recursive);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  /**
   * Flush the log onto the storage.
   */
  public synchronized void flush() {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.flush();
      if (OS instanceof FSDataOutputStream) {
        ((FSDataOutputStream) OS).sync();
      }
      if (DOS.size() > mMaxLogSize) {
        rotateEditLog(PATH);
      }
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }

    mFlushedTransactionId = mTransactionId;
  }

  /**
   * Get the current TransactionId and FlushedTransactionId
   * 
   * @return (TransactionId, FlushedTransactionId)
   */
  public synchronized Pair<Long, Long> getTransactionIds() {
    return new Pair<Long, Long>(mTransactionId, mFlushedTransactionId);
  }

  public synchronized void rename(int fileId, String dstPath) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_RENAME);
      DOS.writeInt(fileId);
      Utils.writeString(dstPath, DOS);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public void rotateEditLog(String path) {
    if (INACTIVE) {
      return;
    }
    _close();
    LOG.info("Edit log max size reached, rotating edit log");
    String pathPrefix =
        path.substring(0, path.lastIndexOf(Constants.PATH_SEPARATOR) + 1) + "completed";
    LOG.info("path: " + path + " prefix: " + pathPrefix);
    try {
      if (!UFS.exists(pathPrefix)) {
        UFS.mkdirs(pathPrefix, true);
      }
      String newPath = CommonUtils.concat(pathPrefix, (mCurrentLogFileNum ++) + ".editLog");
      UFS.rename(path, newPath);
      LOG.info("Renamed " + path + " to " + newPath);
      OS = UFS.create(path);
      DOS = new DataOutputStream(OS);
      LOG.info("Created new log file " + path);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  /**
   * Changes the max log size for testing purposes.
   * 
   * @param size
   */
  public void setMaxLogSize(int size) {
    mMaxLogSize = size;
  }

  public synchronized void unpinFile(int fileId) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_UNPIN_FILE);
      DOS.writeInt(fileId);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }

  public synchronized void updateRawTableMetadata(int tableId, ByteBuffer metadata) {
    if (INACTIVE) {
      return;
    }

    try {
      DOS.writeLong(++ mTransactionId);
      DOS.writeByte(OP_UPDATE_RAW_TABLE_METADATA);
      DOS.writeInt(tableId);
      Utils.writeByteBuffer(metadata, DOS);
    } catch (IOException e) {
      CommonUtils.runtimeException(e);
    }
  }
}
