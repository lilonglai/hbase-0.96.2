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

package org.apache.hadoop.hbase.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScannable;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;

import com.google.common.collect.Lists;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public abstract class Mutation extends OperationWithAttributes implements Row, CellScannable,
    HeapSize {
  public static final long MUTATION_OVERHEAD = ClassSize.align(
      // This
      ClassSize.OBJECT +
      // row + OperationWithAttributes.attributes
      2 * ClassSize.REFERENCE +
      // Timestamp
      1 * Bytes.SIZEOF_LONG +
      // durability
      ClassSize.REFERENCE +
      // familyMap
      ClassSize.REFERENCE +
      // familyMap
      ClassSize.TREEMAP);

  /**
   * The attribute for storing the list of clusters that have consumed the change.
   */
  private static final String CONSUMED_CLUSTER_IDS = "_cs.id";

  protected byte [] row = null;
  protected long ts = HConstants.LATEST_TIMESTAMP;
  protected Durability durability = Durability.USE_DEFAULT;
  
  // A Map sorted by column family.
  protected NavigableMap<byte [], List<Cell>> familyMap =
    new TreeMap<byte [], List<Cell>>(Bytes.BYTES_COMPARATOR);

  @Override
  public CellScanner cellScanner() {
    return CellUtil.createCellScanner(getFamilyCellMap());
  }

  /**
   * Creates an empty list if one doesn't exist for the given column family
   * or else it returns the associated list of Cell objects.
   *
   * @param family column family
   * @return a list of Cell objects, returns an empty list if one doesn't exist.
   */
  List<Cell> getCellList(byte[] family) {
    List<Cell> list = this.familyMap.get(family);
    if (list == null) {
      list = new ArrayList<Cell>();
    }
    return list;
  }

  /*
   * Create a nnnnnnnn with this objects row key and the Put identifier.
   *
   * @return a KeyValue with this objects row key and the Put identifier.
   */
  KeyValue createPutKeyValue(byte[] family, byte[] qualifier, long ts, byte[] value) {
    return new KeyValue(this.row, family, qualifier, ts, KeyValue.Type.Put, value);
  }

  /*
   * Create a KeyValue with this objects row key and the Put identifier.
   *
   * @return a KeyValue with this objects row key and the Put identifier.
   */
  KeyValue createPutKeyValue(byte[] family, ByteBuffer qualifier, long ts, ByteBuffer value) {
    return new KeyValue(this.row, 0, this.row == null ? 0 : this.row.length,
        family, 0, family == null ? 0 : family.length,
        qualifier, ts, KeyValue.Type.Put, value);
  }

  /**
   * Compile the column family (i.e. schema) information
   * into a Map. Useful for parsing and aggregation by debugging,
   * logging, and administration tools.
   * @return Map
   */
  @Override
  public Map<String, Object> getFingerprint() {
    Map<String, Object> map = new HashMap<String, Object>();
    List<String> families = new ArrayList<String>();
    // ideally, we would also include table information, but that information
    // is not stored in each Operation instance.
    map.put("families", families);
    for (Map.Entry<byte [], List<Cell>> entry : this.familyMap.entrySet()) {
      families.add(Bytes.toStringBinary(entry.getKey()));
    }
    return map;
  }

  /**
   * Compile the details beyond the scope of getFingerprint (row, columns,
   * timestamps, etc.) into a Map along with the fingerprinted information.
   * Useful for debugging, logging, and administration tools.
   * @param maxCols a limit on the number of columns output prior to truncation
   * @return Map
   */
  @Override
  public Map<String, Object> toMap(int maxCols) {
    // we start with the fingerprint map and build on top of it.
    Map<String, Object> map = getFingerprint();
    // replace the fingerprint's simple list of families with a
    // map from column families to lists of qualifiers and kv details
    Map<String, List<Map<String, Object>>> columns =
      new HashMap<String, List<Map<String, Object>>>();
    map.put("families", columns);
    map.put("row", Bytes.toStringBinary(this.row));
    int colCount = 0;
    // iterate through all column families affected
    for (Map.Entry<byte [], List<Cell>> entry : this.familyMap.entrySet()) {
      // map from this family to details for each cell affected within the family
      List<Map<String, Object>> qualifierDetails = new ArrayList<Map<String, Object>>();
      columns.put(Bytes.toStringBinary(entry.getKey()), qualifierDetails);
      colCount += entry.getValue().size();
      if (maxCols <= 0) {
        continue;
      }
      // add details for each cell
      for (Cell cell: entry.getValue()) {
        if (--maxCols <= 0 ) {
          continue;
        }
        // KeyValue v1 expectation.  Cast for now until we go all Cell all the time.
        KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
        Map<String, Object> kvMap = kv.toStringMap();
        // row and family information are already available in the bigger map
        kvMap.remove("row");
        kvMap.remove("family");
        qualifierDetails.add(kvMap);
      }
    }
    map.put("totalColumns", colCount);
    // add the id if set
    if (getId() != null) {
      map.put("id", getId());
    }
    return map;
  }

  /**
   * Set the durability for this mutation
   * @param d
   */
  public void setDurability(Durability d) {
    this.durability = d;
  }

  /** Get the current durability */
  public Durability getDurability() {
    return this.durability;
  }

  /**
   * Set the durability for this mutation. If this is set to true,
   * the default durability of the table is set.
   * @param writeToWal
   */
  @Deprecated
  public void setWriteToWAL(boolean writeToWal) {
    if(!writeToWal) {
      setDurability(Durability.SKIP_WAL);
    } else {
    // This is required to handle the case where this method is
    // called twice, first with writeToWal = false,
    // and then with writeToWal = true
      setDurability(Durability.USE_DEFAULT);
    }
  }

  /**
   * Get the durability for this mutation.
   * @return - true if this mutation is set to write to the WAL either
   * synchronously, asynchronously or fsync to disk on the file system.
   * - to get the exact durability, use the {#getDurability} method.
   */
  @Deprecated
  public boolean getWriteToWAL() {
    return Durability.SKIP_WAL != getDurability();
  }

  /*
   * Method for retrieving the put's familyMap  (family -> KeyValues)
   * Application should use the getFamilyCellMap and the Cell interface instead of KeyValue.
   *
   * @return familyMap
   */
  @Deprecated
  public Map<byte[], List<KeyValue>> getFamilyMap() {
    Map<byte[], List<KeyValue>> fm = new TreeMap<byte[], List<KeyValue>>(Bytes.BYTES_COMPARATOR);
    for (Map.Entry<byte[], List<Cell>> e : this.familyMap.entrySet()) {
      byte[] family = e.getKey();
      List<Cell> cells = e.getValue();
      List<KeyValue> kvs = new ArrayList<KeyValue>(cells.size());
      for (Cell c : cells) {
         KeyValue kv = KeyValueUtil.ensureKeyValue(c);
         kvs.add(kv);
       }
       fm.put(family, kvs);
     }
     return fm;
   }
  
  /**
   * Method for retrieving the put's familyMap
   * @return familyMap
   */
  public NavigableMap<byte [], List<Cell>> getFamilyCellMap() {
    return this.familyMap;
  }

  /**
   * Method for setting the put's familyMap
   */
  public void setFamilyCellMap(NavigableMap<byte [], List<Cell>> map) {
    // TODO: Shut this down or move it up to be a Constructor.  Get new object rather than change
    // this internal data member.
    this.familyMap = map;
  }

  /**
   * Method for setting the put's familyMap that is deprecated and inefficient.
   * @deprecated use {@link #setFamilyCellMap(NavigableMap)} instead.
   */
  @Deprecated
  public void setFamilyMap(NavigableMap<byte [], List<KeyValue>> map) {
    TreeMap<byte[], List<Cell>> fm = new TreeMap<byte[], List<Cell>>(Bytes.BYTES_COMPARATOR);
    for (Map.Entry<byte[], List<KeyValue>> e : map.entrySet()) {
      fm.put(e.getKey(), Lists.<Cell>newArrayList(e.getValue()));
    }
    this.familyMap = fm;
  }

  /**
   * Method to check if the familyMap is empty
   * @return true if empty, false otherwise
   */
  public boolean isEmpty() {
    return familyMap.isEmpty();
  }

  /**
   * Method for retrieving the delete's row
   * @return row
   */
  @Override
  public byte [] getRow() {
    return this.row;
  }

  public int compareTo(final Row d) {
    return Bytes.compareTo(this.getRow(), d.getRow());
  }

  /**
   * Method for retrieving the timestamp
   * @return timestamp
   */
  public long getTimeStamp() {
    return this.ts;
  }

  /**
   * Marks that the clusters with the given clusterIds have consumed the mutation
   * @param clusterIds of the clusters that have consumed the mutation
   */
  public void setClusterIds(List<UUID> clusterIds) {
    ByteArrayDataOutput out = ByteStreams.newDataOutput();
    out.writeInt(clusterIds.size());
    for (UUID clusterId : clusterIds) {
      out.writeLong(clusterId.getMostSignificantBits());
      out.writeLong(clusterId.getLeastSignificantBits());
    }
    setAttribute(CONSUMED_CLUSTER_IDS, out.toByteArray());
  }

  /**
   * @return the set of clusterIds that have consumed the mutation
   */
  public List<UUID> getClusterIds() {
    List<UUID> clusterIds = new ArrayList<UUID>();
    byte[] bytes = getAttribute(CONSUMED_CLUSTER_IDS);
    if(bytes != null) {
      ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
      int numClusters = in.readInt();
      for(int i=0; i<numClusters; i++){
        clusterIds.add(new UUID(in.readLong(), in.readLong()));
      }
    }
    return clusterIds;
  }

  /**
   * Number of KeyValues carried by this Mutation.
   * @return the total number of KeyValues
   */
  public int size() {
    int size = 0;
    for (List<Cell> cells : this.familyMap.values()) {
      size += cells.size();
    }
    return size;
  }

  /**
   * @return the number of different families
   */
  public int numFamilies() {
    return familyMap.size();
  }

  /**
   * @return Calculate what Mutation adds to class heap size.
   */
  @Override
  public long heapSize() {
    long heapsize = MUTATION_OVERHEAD;
    // Adding row
    heapsize += ClassSize.align(ClassSize.ARRAY + this.row.length);

    // Adding map overhead
    heapsize +=
      ClassSize.align(this.familyMap.size() * ClassSize.MAP_ENTRY);
    for(Map.Entry<byte [], List<Cell>> entry : this.familyMap.entrySet()) {
      //Adding key overhead
      heapsize +=
        ClassSize.align(ClassSize.ARRAY + entry.getKey().length);

      //This part is kinds tricky since the JVM can reuse references if you
      //store the same value, but have a good match with SizeOf at the moment
      //Adding value overhead
      heapsize += ClassSize.align(ClassSize.ARRAYLIST);
      int size = entry.getValue().size();
      heapsize += ClassSize.align(ClassSize.ARRAY +
          size * ClassSize.REFERENCE);

      for(Cell cell : entry.getValue()) {
        KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
        heapsize += kv.heapSize();
      }
    }
    heapsize += getAttributeSize();
    heapsize += extraHeapSize();
    return ClassSize.align(heapsize);
  }

  /**
   * Subclasses should override this method to add the heap size of their own fields.
   * @return the heap size to add (will be aligned).
   */
  protected long extraHeapSize(){
    return 0L;
  }


  /**
   * @param row Row to check
   * @throws IllegalArgumentException Thrown if <code>row</code> is empty or null or
   * &gt; {@link HConstants#MAX_ROW_LENGTH}
   * @return <code>row</code>
   */
  static byte [] checkRow(final byte [] row) {
    return checkRow(row, 0, row == null? 0: row.length);
  }

  /**
   * @param row Row to check
   * @param offset
   * @param length
   * @throws IllegalArgumentException Thrown if <code>row</code> is empty or null or
   * &gt; {@link HConstants#MAX_ROW_LENGTH}
   * @return <code>row</code>
   */
  static byte [] checkRow(final byte [] row, final int offset, final int length) {
    if (row == null) {
      throw new IllegalArgumentException("Row buffer is null");
    }
    if (length == 0) {
      throw new IllegalArgumentException("Row length is 0");
    }
    if (length > HConstants.MAX_ROW_LENGTH) {
      throw new IllegalArgumentException("Row length " + length + " is > " +
        HConstants.MAX_ROW_LENGTH);
    }
    return row;
  }

  static void checkRow(ByteBuffer row) {
    if (row == null) {
      throw new IllegalArgumentException("Row buffer is null");
    }
    if (row.remaining() == 0) {
      throw new IllegalArgumentException("Row length is 0");
    }
    if (row.remaining() > HConstants.MAX_ROW_LENGTH) {
      throw new IllegalArgumentException("Row length " + row.remaining() + " is > " +
          HConstants.MAX_ROW_LENGTH);
    }
  }
}
