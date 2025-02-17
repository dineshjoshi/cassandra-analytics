/*
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

package org.apache.cassandra.spark.utils.test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableMap;

import org.apache.cassandra.bridge.BigNumberConfig;
import org.apache.cassandra.bridge.CassandraBridge;
import org.apache.cassandra.bridge.CassandraVersion;
import org.apache.cassandra.bridge.RangeTombstone;
import org.apache.cassandra.spark.config.SchemaFeature;
import org.apache.cassandra.spark.config.SchemaFeatureSet;
import org.apache.cassandra.spark.data.CqlField;
import org.apache.cassandra.spark.data.CqlTable;
import org.apache.cassandra.spark.data.ReplicationFactor;
import org.apache.cassandra.spark.data.partitioner.Partitioner;
import org.apache.cassandra.spark.utils.ComparisonUtils;
import org.apache.cassandra.spark.utils.RandomUtils;
import org.apache.cassandra.spark.utils.TemporaryDirectory;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.types.StructType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Helper class to create and test various schemas
 */
public final class TestSchema
{
    @SuppressWarnings("SameParameterValue")
    public static class Builder
    {
        private String keyspace = null;
        private String table = null;
        private final List<CqlField> partitionKeys = new ArrayList<>();
        private final List<CqlField> clusteringKeys = new ArrayList<>();
        private final List<CqlField> columns = new ArrayList<>();
        private final List<CqlField.SortOrder> sortOrders = new ArrayList<>();
        private List<String> insertFields = null;
        private List<String> deleteFields;
        private int minCollectionSize = 16;
        private Integer blobSize = null;
        private boolean withCompression = true;

        public Builder withKeyspace(String keyspace)
        {
            this.keyspace = keyspace;
            return this;
        }

        public Builder withTable(String table)
        {
            this.table = table;
            return this;
        }

        public Builder withPartitionKey(String name, CqlField.CqlType type)
        {
            partitionKeys.add(new CqlField(true, false, false, name, type, 0));
            return this;
        }

        public Builder withClusteringKey(String name, CqlField.CqlType type)
        {
            clusteringKeys.add(new CqlField(false, true, false, name, type, 0));
            return this;
        }

        public Builder withStaticColumn(String name, CqlField.CqlType type)
        {
            columns.add(new CqlField(false, false, true, name, type, 0));
            return this;
        }

        public Builder withColumn(String name, CqlField.CqlType type)
        {
            columns.add(new CqlField(false, false, false, name, type, 0));
            return this;
        }

        public Builder withSortOrder(CqlField.SortOrder sortOrder)
        {
            sortOrders.add(sortOrder);
            return this;
        }

        public Builder withInsertFields(String... fields)
        {
            insertFields = Arrays.asList(fields);
            return this;
        }

        public Builder withDeleteFields(String... fields)
        {
            deleteFields = Arrays.asList(fields);
            return this;
        }

        public Builder withMinCollectionSize(int minCollectionSize)
        {
            this.minCollectionSize = minCollectionSize;
            return this;
        }

        public Builder withCompression(boolean withCompression)
        {
            this.withCompression = withCompression;
            return this;
        }

        // Override blob size
        public Builder withBlobSize(int blobSize)
        {
            this.blobSize = blobSize;
            return this;
        }

        public TestSchema build()
        {
            if (!partitionKeys.isEmpty())
            {
                return new TestSchema(
                        keyspace != null ? keyspace : "keyspace_" + UUID.randomUUID().toString().replaceAll("-", ""),
                        table != null ? table : "table_" + UUID.randomUUID().toString().replaceAll("-", ""),
                        IntStream.range(0, partitionKeys.size())
                                 .mapToObj(index -> partitionKeys.get(index).cloneWithPosition(index))
                                 .sorted()
                                 .collect(Collectors.toList()),
                        IntStream.range(0, clusteringKeys.size())
                                 .mapToObj(index -> clusteringKeys.get(index).cloneWithPosition(partitionKeys.size() + index))
                                 .sorted()
                                 .collect(Collectors.toList()),
                        IntStream.range(0, columns.size())
                                 .mapToObj(index -> columns.get(index).cloneWithPosition(partitionKeys.size() + clusteringKeys.size() + index))
                                 .sorted(Comparator.comparing(CqlField::name))
                                 .collect(Collectors.toList()),
                        sortOrders,
                        insertFields,
                        deleteFields,
                        minCollectionSize,
                        blobSize,
                        withCompression);
            }
            else
            {
                throw new IllegalArgumentException("Need at least one partition key");
            }
        }
    }

    @NotNull
    public final String keyspace;
    public final String table;
    public final String createStatement;
    public final ReplicationFactor rf = new ReplicationFactor(ReplicationFactor.ReplicationStrategy.NetworkTopologyStrategy,
                                                              ImmutableMap.of("DC1", 3));
    public final String insertStatement;
    public final String updateStatement;
    public final String deleteStatement;
    public final List<CqlField> partitionKeys;
    public final List<CqlField> clusteringKeys;
    final List<CqlField> allFields;
    public final Set<CqlField.CqlUdt> udts;
    private final Map<String, Integer> fieldPositions;
    @Nullable
    private CassandraVersion version = null;
    private final int minCollectionSize;
    private final Integer blobSize;

    public static Builder builder()
    {
        return new Builder();
    }

    public static Builder basicBuilder(CassandraBridge bridge)
    {
        return TestSchema.builder()
                         .withPartitionKey("a", bridge.aInt())
                         .withClusteringKey("b", bridge.aInt())
                         .withColumn("c", bridge.aInt());
    }

    public static TestSchema basic(CassandraBridge bridge)
    {
        return basicBuilder(bridge).build();
    }

    // CHECKSTYLE IGNORE: Constructor with many parameters
    private TestSchema(@NotNull String keyspace,
                       @NotNull String table,
                       List<CqlField> partitionKeys,
                       List<CqlField> clusteringKeys,
                       List<CqlField> columns,
                       List<CqlField.SortOrder> sortOrders,
                       @Nullable List<String> insertOverrides,
                       @Nullable List<String> deleteFields,
                       int minCollectionSize,
                       @Nullable Integer blobSize,
                       boolean withCompression)
    {
        this.keyspace = keyspace;
        this.table = table;
        this.partitionKeys = partitionKeys;
        this.clusteringKeys = clusteringKeys;
        this.minCollectionSize = minCollectionSize;
        this.blobSize = blobSize;
        this.allFields = buildAllFields(partitionKeys, clusteringKeys, columns);
        this.fieldPositions = calculateFieldPositions(allFields);
        this.createStatement = buildCreateStatement(columns, sortOrders, withCompression);
        this.insertStatement = buildInsertStatement(columns, insertOverrides);
        this.updateStatement = buildUpdateStatement();
        this.deleteStatement = buildDeleteStatement(deleteFields);
        this.udts = getUdtsFromFields();
    }

    // We take allFields as a parameter here to ensure it's been created before use
    @NotNull
    private Map<String, Integer> calculateFieldPositions(@NotNull List<CqlField> allFields)
    {
        return allFields.stream().collect(Collectors.toMap(CqlField::name, CqlField::position));
    }

    @NotNull
    private List<CqlField> buildAllFields(List<CqlField> partitionKeys,
                                          List<CqlField> clusteringKeys,
                                          List<CqlField> columns)
    {
        List<CqlField> allFields = new ArrayList<>(partitionKeys.size() + clusteringKeys.size() + columns.size());
        allFields.addAll(partitionKeys);
        allFields.addAll(clusteringKeys);
        allFields.addAll(columns);
        Collections.sort(allFields);
        return allFields;
    }

    @NotNull
    private Set<CqlField.CqlUdt> getUdtsFromFields()
    {
        return allFields.stream()
                        .map(field -> field.type().udts())
                        .flatMap(Collection::stream)
                        .collect(Collectors.toSet());
    }

    private String buildDeleteStatement(@Nullable List<String> deleteFields)
    {
        StringBuilder deleteStmtBuilder = new StringBuilder().append("DELETE FROM ")
                                                             .append(keyspace)
                                                             .append(".")
                                                             .append(table)
                                                             .append(" WHERE ");
        if (deleteFields != null)
        {
            deleteStmtBuilder.append(deleteFields.stream()
                                                 .map(override -> override + " ?")
                                                 .collect(Collectors.joining(" AND ")));
        }
        else
        {
            deleteStmtBuilder.append(allFields.stream()
                                              .map(field -> field.name() + " = ?")
                                              .collect(Collectors.joining(" AND ")));
        }
        return deleteStmtBuilder.append(";")
                                .toString();
    }

    private String buildUpdateStatement()
    {
        StringBuilder updateStmtBuilder = new StringBuilder("UPDATE ").append(keyspace)
                                                                      .append(".")
                                                                      .append(table)
                                                                      .append(" SET ");
        updateStmtBuilder.append(allFields.stream()
                                          .sorted()
                                          .filter(field -> !field.isPartitionKey() && !field.isClusteringColumn())
                                          .map(field -> field.name() + " = ?")
                                          .collect(Collectors.joining(", ")));
        updateStmtBuilder.append(" WHERE ");
        updateStmtBuilder.append(allFields.stream()
                                          .sorted()
                                          .filter(field -> field.isPartitionKey() || field.isClusteringColumn())
                                          .map(field -> field.name() + " = ?")
                                          .collect(Collectors.joining(" and ")));
        return updateStmtBuilder.append(";")
                                .toString();
    }

    private String buildInsertStatement(List<CqlField> columns, @Nullable List<String> insertOverrides)
    {
        StringBuilder insertStmtBuilder = new StringBuilder().append("INSERT INTO ")
                                                             .append(keyspace)
                                                             .append(".")
                                                             .append(table)
                                                             .append(" (");
        if (insertOverrides != null)
        {
            insertStmtBuilder.append(String.join(", ", insertOverrides))
                             .append(") VALUES (")
                             .append(insertOverrides.stream()
                                                    .map(override -> "?")
                                                    .collect(Collectors.joining(", ")));
        }
        else
        {
            insertStmtBuilder.append(allFields.stream()
                                              .sorted()
                                              .map(CqlField::name)
                                              .collect(Collectors.joining(", ")))
                             .append(") VALUES (")
                             .append(Stream.of(partitionKeys, clusteringKeys, columns)
                                           .flatMap(Collection::stream)
                                           .sorted()
                                           .map(field -> "?")
                                           .collect(Collectors.joining(", ")));
        }
        return insertStmtBuilder.append(");")
                                .toString();
    }

    private String buildCreateStatement(List<CqlField> columns,
                                        List<CqlField.SortOrder> sortOrders,
                                        boolean withCompression)
    {
        StringBuilder createStmtBuilder = new StringBuilder().append("CREATE TABLE ")
                                                             .append(keyspace)
                                                             .append(".")
                                                             .append(table)
                                                             .append(" (");
        for (CqlField field : Stream.of(partitionKeys, clusteringKeys, columns)
                                    .flatMap(Collection::stream)
                                    .sorted()
                                    .collect(Collectors.toList()))
        {
            createStmtBuilder.append(field.name())
                             .append(" ")
                             .append(field.cqlTypeName())
                             .append(field.isStaticColumn() ? " static" : "")
                             .append(", ");
        }

        createStmtBuilder.append("PRIMARY KEY((")
                         .append(partitionKeys.stream()
                                              .map(CqlField::name)
                                              .collect(Collectors.joining(", ")))
                         .append(")");

        if (!clusteringKeys.isEmpty())
        {
            createStmtBuilder.append(", ")
                             .append(clusteringKeys.stream()
                                                   .map(CqlField::name)
                                                   .collect(Collectors.joining(", ")));
        }

        createStmtBuilder.append("))");

        if (!sortOrders.isEmpty())
        {
            createStmtBuilder.append(" WITH CLUSTERING ORDER BY (");
            for (int sortOrder = 0; sortOrder < sortOrders.size(); sortOrder++)
            {
                createStmtBuilder.append(clusteringKeys.get(sortOrder).name())
                                 .append(" ")
                                 .append(sortOrders.get(sortOrder).toString());
                if (sortOrder < sortOrders.size() - 1)
                {
                    createStmtBuilder.append(", ");
                }
            }
            createStmtBuilder.append(")");
        }

        if (!withCompression)
        {
            createStmtBuilder.append(" WITH compression = {'enabled':'false'}");
        }

        return createStmtBuilder.append(";")
                                .toString();
    }

    public void setCassandraVersion(@NotNull CassandraVersion version)
    {
        this.version = version;
    }

    public CqlTable buildTable()
    {
        return new CqlTable(keyspace,
                            table,
                            createStatement,
                            rf,
                            allFields,
                            udts,
                            0);
    }

    public void writeSSTable(TemporaryDirectory directory,
                             CassandraBridge bridge,
                             Partitioner partitioner,
                             Consumer<CassandraBridge.Writer> writer)
    {
        writeSSTable(directory.path(), bridge, partitioner, writer);
    }

    public void writeSSTable(Path directory,
                             CassandraBridge bridge,
                             Partitioner partitioner,
                             Consumer<CassandraBridge.Writer> writer)
    {
        writeSSTable(directory, bridge, partitioner, false, writer);
    }

    public void writeSSTable(Path directory,
                             CassandraBridge bridge,
                             Partitioner partitioner,
                             boolean upsert,
                             Consumer<CassandraBridge.Writer> writer)
    {
        bridge.writeSSTable(partitioner,
                            keyspace,
                            table,
                            directory,
                            createStatement,
                            insertStatement,
                            updateStatement,
                            upsert,
                            udts,
                            writer);
    }

    public void writeTombstoneSSTable(Path directory,
                                      CassandraBridge bridge,
                                      Partitioner partitioner,
                                      Consumer<CassandraBridge.Writer> writer)
    {
        bridge.writeTombstoneSSTable(partitioner, directory, createStatement, deleteStatement, writer);
    }

    public static StructType toStructType(CqlTable table, boolean addLastModificationTimeColumn)
    {
        StructType structType = new StructType();
        for (CqlField field : table.fields())
        {
            structType = structType.add(field.name(), field.type().sparkSqlType(BigNumberConfig.DEFAULT));
        }
        if (addLastModificationTimeColumn)
        {
            structType = structType.add(SchemaFeatureSet.LAST_MODIFIED_TIMESTAMP.field());
        }
        // CDC jobs always add the updated_fields_indicator and is_update column
        for (SchemaFeature feature : SchemaFeatureSet.ALL_CDC_FEATURES)
        {
            structType = structType.add(feature.field());
        }
        return structType;
    }

    public TestRow[] randomRows(int numRows)
    {
        return randomRows(numRows, 0);
    }

    @SuppressWarnings("SameParameterValue")
    private TestRow[] randomRows(int numRows, int numTombstones)
    {
        TestSchema.TestRow[] testRows = new TestSchema.TestRow[numRows];
        for (int testRow = 0; testRow < testRows.length; testRow++)
        {
            testRows[testRow] = randomRow(testRow < numTombstones);
        }
        return testRows;
    }

    public TestRow randomRow()
    {
        return randomRow(false);
    }

    private TestRow randomRow(boolean tombstone)
    {
        return randomRow(field -> tombstone && field.isValueColumn());
    }

    private TestRow randomRow(Predicate<CqlField> nullifiedFields)
    {
        Object[] values = new Object[allFields.size()];
        for (CqlField field : allFields)
        {
            if (nullifiedFields.test(field))
            {
                values[field.position()] = null;
            }
            else
            {
                if (field.type().getClass().getSimpleName().equals("Blob") && blobSize != null)
                {
                    values[field.position()] = RandomUtils.randomByteBuffer(blobSize);
                }
                else
                {
                    values[field.position()] = field.type().randomValue(minCollectionSize);
                }
            }
        }
        return new TestRow(values);
    }

    public TestRow randomPartitionDelete()
    {
        return randomRow(field -> !field.isPartitionKey());
    }

    public TestRow toTestRow(InternalRow row)
    {
        if (row instanceof GenericInternalRow)
        {
            Object[] values = new Object[allFields.size()];
            for (CqlField field : allFields)
            {
                values[field.position()] = field.type().sparkSqlRowValue((GenericInternalRow) row, field.position());
            }
            return new TestRow(values);
        }
        else
        {
            throw new IllegalStateException("Can only convert GenericInternalRow");
        }
    }

    public TestRow toTestRow(Row row, Set<String> requiredColumns)
    {
        Object[] values = new Object[requiredColumns != null ? requiredColumns.size() : allFields.size()];
        int skipped = 0;
        for (CqlField field : allFields)
        {
            if (requiredColumns != null && !requiredColumns.contains(field.name()))
            {
                skipped++;
                continue;
            }
            int position = field.position() - skipped;
            values[position] = row.get(position) != null ? field.type().sparkSqlRowValue(row, position) : null;
        }
        return new TestRow(values);
    }

    @SuppressWarnings("SameParameterValue")
    public final class TestRow implements CassandraBridge.IRow
    {
        private final Object[] values;
        private boolean isTombstoned;
        private boolean isInsert;
        private List<RangeTombstone> rangeTombstones;

        private TestRow(Object[] values)
        {
            this(values, false, true);
        }

        private TestRow(Object[] values, boolean isTombstoned, boolean isInsert)
        {
            this.values = values;
            this.isTombstoned = isTombstoned;
            this.isInsert = isInsert;
        }

        public void setRangeTombstones(List<RangeTombstone> rangeTombstones)
        {
            this.rangeTombstones = rangeTombstones;
        }

        @Override
        public List<RangeTombstone> rangeTombstones()
        {
            return rangeTombstones;
        }

        public void delete()
        {
            isTombstoned = true;
        }

        public void fromUpdate()
        {
            isInsert = false;
        }

        public void fromInsert()
        {
            isInsert = true;
        }

        @Override
        public boolean isDeleted()
        {
            return isTombstoned;
        }

        @Override
        public boolean isInsert()
        {
            return isInsert;
        }

        public TestRow copy(String field, Object value)
        {
            return copy(getFieldPosition(field), value);
        }

        public TestRow copy(int position, Object value)
        {
            Object[] newValues = new Object[values.length];
            System.arraycopy(values, 0, newValues, 0, values.length);
            newValues[position] = value;
            return new TestRow(newValues);
        }

        /**
         * If a prune column filter is applied, convert expected TestRow to only include required columns
         * so we can compare with row returned by Spark
         *
         * @param columns required columns, or null if no column selection criteria
         * @return a TestRow containing only the required columns
         */
        public TestRow withColumns(@Nullable Set<String> columns)
        {
            if (columns == null)
            {
                return this;
            }
            Object[] result = new Object[columns.size()];
            int skipped = 0;
            for (CqlField field : allFields)
            {
                if (!columns.contains(field.name()))
                {
                    skipped++;
                    continue;
                }
                result[field.position() - skipped] = values[field.position()];
            }
            return new TestRow(result);
        }

        public TestRow nullifyUnsetColumn()
        {
            Object[] newValues = new Object[values.length];
            System.arraycopy(values, 0, newValues, 0, values.length);
            for (int value = 0; value < newValues.length; value++)
            {
                if (newValues[value] == CassandraBridge.UNSET_MARKER)
                {
                    newValues[value] = null;
                }
            }
            return new TestRow(newValues);
        }

        public Object[] rawValues(int start, int end)
        {
            assert start <= end && end <= values.length
                : String.format("start: %s, end: %s", version, start, end);
            Object[] result = new Object[end - start];
            System.arraycopy(values, start, result, 0, end - start);
            return result;
        }

        public Object[] allValues()
        {
            return values(0, values.length);
        }

        // Start inclusive, end exclusive
        public Object[] values(int start, int end)
        {
            // NOTE: CassandraBridge must be set before calling this class,
            //       so we can convert 4.0 Date type to LocalDate to be used in CQLSSTableWriter
            assert version != null && start <= end && end <= values.length
                : String.format("version: %s, start: %s, end: %s", version, start, end);
            Object[] result = new Object[end - start];
            for (int sourceIndex = start, destinationIndex = 0; sourceIndex < end; sourceIndex++, destinationIndex++)
            {
                result[destinationIndex] = convertForCqlWriter(getType(sourceIndex), values[sourceIndex]);
            }
            return result;
        }

        private Object convertForCqlWriter(CqlField.CqlType type, Object value)
        {
            return type.convertForCqlWriter(value, version);
        }

        public CqlField.CqlType getType(int position)
        {
            if (0 <= position && position < allFields.size())
            {
                return allFields.get(position).type();
            }
            else
            {
                throw new IllegalStateException("Unknown field at position: " + position);
            }
        }

        public boolean isNull(String field)
        {
            return get(field) == null;
        }

        public String getString(String field)
        {
            return (String) get(field);
        }

        public UUID getUUID(String field)
        {
            return (UUID) get(field);
        }

        public Long getLong(String field)
        {
            return (Long) get(field);
        }

        public Integer getInteger(String field)
        {
            return (Integer) get(field);
        }

        public Object get(String field)
        {
            return get(getFieldPosition(field));
        }

        private int getFieldPosition(String field)
        {
            return Objects.requireNonNull(fieldPositions.get(field), "Unknown field: " + field);
        }

        @Override
        public Object get(int position)
        {
            return values[position];
        }

        public boolean isTombstone()
        {
            return allFields.stream()
                            .filter(CqlField::isValueColumn)
                            .allMatch(field -> values[field.position()] == null);
        }

        public String getKey()
        {
            StringBuilder str = new StringBuilder();
            for (int key = 0; key < partitionKeys.size() + clusteringKeys.size(); key++)
            {
                CqlField.CqlType type = key < partitionKeys.size()
                        ? partitionKeys.get(key).type()
                        : clusteringKeys.get(key - partitionKeys.size()).type();
                str.append(toString(type, get(key))).append(":");
            }
            return str.toString();
        }

        private String toString(CqlField.CqlType type, Object key)
        {
            if (key instanceof BigDecimal)
            {
                return ((BigDecimal) key).setScale(8, RoundingMode.CEILING).toPlainString();
            }
            else if (key instanceof Timestamp)
            {
                return new Date(((Timestamp) key).getTime()).toString();
            }
            else if (key instanceof Object[])
            {
                return String.format("[%s]", Arrays.stream((Object[]) key)
                                                   .map(value -> toString(type, value))
                                                   .collect(Collectors.joining(", ")));
            }
            else if (key instanceof Map)
            {
                CqlField.CqlType innerType = getFrozenInnerType(type);
                if (innerType instanceof CqlField.CqlMap)
                {
                    CqlField.CqlMap mapType = (CqlField.CqlMap) innerType;
                    return ((Map<?, ?>) key).entrySet()
                            .stream()
                            .sorted((Comparator<Map.Entry<?, ?>>) (first, second) ->
                                    mapType.keyType().compare(first.getKey(), second.getKey()))
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toList())
                            .toString();
                }
                return ((Map<?, ?>) key).entrySet().stream().collect(
                        Collectors.toMap(entry -> toString(innerType, entry.getKey()),
                                         entry -> toString(innerType, entry.getValue()))).toString();
            }
            else if (key instanceof Collection)
            {
                CqlField.CqlType innerType = ((CqlField.CqlCollection) getFrozenInnerType(type)).type();
                return ((Collection<?>) key).stream()
                                            .sorted(innerType)
                                            .map(value -> toString(innerType, value))
                                            .collect(Collectors.toList()).toString();
            }
            return key != null ? key.toString() : "null";
        }

        private CqlField.CqlType getFrozenInnerType(CqlField.CqlType type)
        {
            if (type instanceof CqlField.CqlFrozen)
            {
                return getFrozenInnerType(((CqlField.CqlFrozen) type).inner());
            }
            return type;
        }

        @Override
        public String toString()
        {
            return String.format("[%s]", IntStream.range(0, values.length)
                                                  .mapToObj(index -> toString(allFields.get(index).type(), values[index]))
                                                  .collect(Collectors.joining(", ")));
        }

        public int hashCode()
        {
            return Objects.hash(values);
        }

        public boolean equals(Object other)
        {
            return other instanceof TestRow && ComparisonUtils.equals(this.values, ((TestRow) other).values);
        }
    }
}
