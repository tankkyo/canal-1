package com.alibaba.otter.canal.parse.inbound.mysql.dbsync;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.sql.Types;
import java.util.BitSet;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.filter.aviater.AviaterRegexFilter;
import com.alibaba.otter.canal.parse.exception.CanalParseException;
import com.alibaba.otter.canal.parse.inbound.BinlogParser;
import com.alibaba.otter.canal.parse.inbound.TableMeta;
import com.alibaba.otter.canal.parse.inbound.TableMeta.FieldMeta;
import com.alibaba.otter.canal.parse.inbound.mysql.dbsync.SimpleDdlParser.DdlResult;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.Header;
import com.alibaba.otter.canal.protocol.CanalEntry.Pair;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionBegin;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionEnd;
import com.alibaba.otter.canal.protocol.CanalEntry.Type;
import com.google.protobuf.ByteString;
import com.taobao.tddl.dbsync.binlog.LogEvent;
import com.taobao.tddl.dbsync.binlog.event.DeleteRowsLogEvent;
import com.taobao.tddl.dbsync.binlog.event.LogHeader;
import com.taobao.tddl.dbsync.binlog.event.QueryLogEvent;
import com.taobao.tddl.dbsync.binlog.event.RotateLogEvent;
import com.taobao.tddl.dbsync.binlog.event.RowsLogBuffer;
import com.taobao.tddl.dbsync.binlog.event.RowsLogEvent;
import com.taobao.tddl.dbsync.binlog.event.TableMapLogEvent;
import com.taobao.tddl.dbsync.binlog.event.UpdateRowsLogEvent;
import com.taobao.tddl.dbsync.binlog.event.WriteRowsLogEvent;
import com.taobao.tddl.dbsync.binlog.event.XidLogEvent;
import com.taobao.tddl.dbsync.binlog.event.TableMapLogEvent.ColumnInfo;

/**
 * 基于{@linkplain LogEvent}转化为Entry对象的处理
 * 
 * @author jianghang 2013-1-17 下午02:41:14
 * @version 1.0.0
 */
public class LogEventConvert extends AbstractCanalLifeCycle implements BinlogParser<LogEvent> {

    public static final String          ISO_8859_1          = "ISO-8859-1";
    public static final String          UTF_8               = "UTF-8";
    public static final int             TINYINT_MAX_VALUE   = 256;
    public static final int             SMALLINT_MAX_VALUE  = 65536;
    public static final int             MEDIUMINT_MAX_VALUE = 16777216;
    public static final long            INTEGER_MAX_VALUE   = 4294967296L;
    public static final BigInteger      BIGINT_MAX_VALUE    = new BigInteger("18446744073709551616");
    public static final int             version             = 1;
    public static final String          BEGIN               = "BEGIN";
    public static final String          COMMIT              = "COMMIT";
    public static final Logger          logger              = LoggerFactory.getLogger(LogEventConvert.class);

    private volatile AviaterRegexFilter nameFilter;                                                          // 运行时引用可能会有变化，比如规则发生变化时
    private TableMetaCache              tableMetaCache;
    private String                      binlogFileName      = "mysql-bin.000001";
    private Charset                     charset             = Charset.defaultCharset();

    public Entry parse(LogEvent logEvent) throws CanalParseException {
        int eventType = logEvent.getHeader().getType();
        switch (eventType) {
            case LogEvent.ROTATE_EVENT:
                binlogFileName = ((RotateLogEvent) logEvent).getFilename();
                break;
            case LogEvent.QUERY_EVENT:
                return parserQueryEvent((QueryLogEvent) logEvent);
            case LogEvent.XID_EVENT:
                return paserXidEvent((XidLogEvent) logEvent);
            case LogEvent.TABLE_MAP_EVENT:
                break;
            case LogEvent.WRITE_ROWS_EVENT:
                return parseRowsEvent((WriteRowsLogEvent) logEvent);
            case LogEvent.UPDATE_ROWS_EVENT:
                return parseRowsEvent((UpdateRowsLogEvent) logEvent);
            case LogEvent.DELETE_ROWS_EVENT:
                return parseRowsEvent((DeleteRowsLogEvent) logEvent);
            default:
                break;
        }

        return null;
    }

    public void reset() {
        // do nothing
        binlogFileName = "mysql-bin.000001";
        if (tableMetaCache != null) {
            tableMetaCache.clearTableMeta();
        }
    }

    private Entry parserQueryEvent(QueryLogEvent event) {
        String queryString = event.getQuery();
        if (StringUtils.endsWithIgnoreCase(queryString, BEGIN)) {
            TransactionBegin transactionBegin = createTransactionBegin(event.getExecTime());
            Header header = createHeader(binlogFileName, event.getHeader(), "", "");
            return createEntry(header, EntryType.TRANSACTIONBEGIN, transactionBegin.toByteString());
        } else if (StringUtils.endsWithIgnoreCase(queryString, COMMIT)) {
            TransactionEnd transactionEnd = createTransactionEnd(0L, event.getWhen()); // MyISAM可能不会有xid事件
            Header header = createHeader(binlogFileName, event.getHeader(), "", "");
            return createEntry(header, EntryType.TRANSACTIONEND, transactionEnd.toByteString());
        } else {
            // DDL语句处理
            DdlResult result = SimpleDdlParser.parse(queryString, event.getDbName());
            if (result == null) {
                logger.info(
                            "INFO ## sql = {} , position = {}:{} is not create table/alter table/drop table ddl sql,so will ignore",
                            new Object[] { queryString, binlogFileName,
                                    event.getHeader().getLogPos() - event.getHeader().getEventLen() });
                return null;
            }

            String schemaName = event.getDbName();
            String tableName = result.getTableName();
            if (tableMetaCache != null && (result.getType() == EventType.ALTER || result.getType() == EventType.ERASE)) {
                if (StringUtils.isNotEmpty(tableName)) {
                    // 如果解析到了正确的表信息，则根据全名进行清除
                    tableMetaCache.clearTableMetaWithFullName(schemaName + "." + tableName);
                } else {
                    // 如果无法解析正确的表信息，则根据schema进行清除
                    tableMetaCache.clearTableMetaWithSchemaName(schemaName);
                }
            }

            Header header = createHeader(binlogFileName, event.getHeader(), schemaName, tableName);
            RowChange.Builder rowChangeBuider = RowChange.newBuilder();
            rowChangeBuider.setIsDdl(true);
            rowChangeBuider.setSql(queryString);
            rowChangeBuider.setEventType(result.getType());
            return createEntry(header, EntryType.ROWDATA, rowChangeBuider.build().toByteString());
        }
    }

    private Entry paserXidEvent(XidLogEvent event) {
        TransactionEnd transactionEnd = createTransactionEnd(event.getXid(), event.getWhen());
        Header header = createHeader(binlogFileName, event.getHeader(), "", "");
        return createEntry(header, EntryType.TRANSACTIONEND, transactionEnd.toByteString());
    }

    private Entry parseRowsEvent(RowsLogEvent event) {
        try {
            String fullname = getSchemaNameAndTableName(event.getTable());
            if (nameFilter != null && !nameFilter.filter(fullname)) { // check name filter
                return null;
            }

            Header header = createHeader(binlogFileName, event.getHeader(), event.getTable().getDbName(),
                                         event.getTable().getTableName());
            RowChange.Builder rowChangeBuider = RowChange.newBuilder();
            rowChangeBuider.setTableId(event.getTableId());
            rowChangeBuider.setIsDdl(false);
            EventType eventType = null;
            if (LogEvent.WRITE_ROWS_EVENT == event.getHeader().getType()) {
                eventType = EventType.INSERT;
            } else if (LogEvent.UPDATE_ROWS_EVENT == event.getHeader().getType()) {
                eventType = EventType.UPDATE;
            } else if (LogEvent.DELETE_ROWS_EVENT == event.getHeader().getType()) {
                eventType = EventType.DELETE;
            } else {
                throw new CanalParseException("unsupport event type :" + event.getHeader().getType());
            }
            rowChangeBuider.setEventType(eventType);

            RowsLogBuffer buffer = event.getRowsBuf(charset.name());
            BitSet columns = event.getColumns();
            BitSet changeColumns = event.getColumns();
            TableMeta tableMeta = null;
            if (tableMetaCache != null) {// 入错存在table meta cache
                tableMeta = tableMetaCache.getTableMeta(fullname);
                if (tableMeta == null) {
                    throw new CanalParseException("not found [" + fullname + "] in db , pls check!");
                }
            }

            while (buffer.nextOneRow(columns)) {
                // 处理row记录
                RowData.Builder rowDataBuilder = RowData.newBuilder();
                if (EventType.INSERT == eventType) {
                    // insert的记录放在before字段中
                    parseOneRow(rowDataBuilder, event, buffer, columns, true, tableMeta);
                } else if (EventType.DELETE == eventType) {
                    // delete的记录放在before字段中
                    parseOneRow(rowDataBuilder, event, buffer, columns, false, tableMeta);
                } else {
                    // update需要处理before/after
                    parseOneRow(rowDataBuilder, event, buffer, columns, false, tableMeta);
                    if (!buffer.nextOneRow(changeColumns)) {
                        rowChangeBuider.addRowDatas(rowDataBuilder.build());
                        break;
                    }

                    parseOneRow(rowDataBuilder, event, buffer, event.getChangeColumns(), true, tableMeta);
                }

                rowChangeBuider.addRowDatas(rowDataBuilder.build());
            }
            return createEntry(header, EntryType.ROWDATA, rowChangeBuider.build().toByteString());
        } catch (Exception e) {
            throw new CanalParseException("parse row data failed.", e);
        }
    }

    private void parseOneRow(RowData.Builder rowDataBuilder, RowsLogEvent event, RowsLogBuffer buffer, BitSet cols,
                             boolean isAfter, TableMeta tableMeta) throws UnsupportedEncodingException {
        TableMapLogEvent map = event.getTable();
        if (map == null) {
            throw new CanalParseException("not found TableMap with tid=" + event.getTableId());
        }

        final int columnCnt = map.getColumnCnt();
        final ColumnInfo[] columnInfo = map.getColumnInfo();

        // check table fileds count，只能处理加字段
        if (tableMeta != null && columnInfo.length > tableMeta.getFileds().size()) {
            throw new CanalParseException("column size is not match for table:" + tableMeta.getFullName());
        }

        for (int i = 0; i < columnCnt; i++) {
            ColumnInfo info = columnInfo[i];
            buffer.nextValue(info.type, info.meta);

            FieldMeta fieldMeta = null;
            Column.Builder columnBuilder = Column.newBuilder();
            columnBuilder.setIndex(i);
            columnBuilder.setIsNull(false);
            columnBuilder.setUpdated(cols.get(i) && isAfter);
            if (tableMeta != null) {
                // 处理file meta
                fieldMeta = tableMeta.getFileds().get(i);
                columnBuilder.setName(fieldMeta.getColumnName());
                columnBuilder.setIsKey(fieldMeta.isKey());
            }
            if (buffer.isNull()) {
                columnBuilder.setIsNull(true);
            } else {
                final int javaType = buffer.getJavaType();
                final Serializable value = buffer.getValue();
                // 处理各种类型
                switch (javaType) {
                    case Types.INTEGER:
                    case Types.TINYINT:
                    case Types.SMALLINT:
                    case Types.BIGINT:
                        // 处理unsigned类型
                        Number number = (Number) value;
                        if (fieldMeta != null && fieldMeta.isUnsigned() && number.longValue() < 0) {
                            switch (buffer.getLength()) {
                                case 1: /* MYSQL_TYPE_TINY */
                                    columnBuilder.setValue(String.valueOf(Integer.valueOf(TINYINT_MAX_VALUE
                                                                                          + number.intValue())));

                                case 2: /* MYSQL_TYPE_SHORT */
                                    columnBuilder.setValue(String.valueOf(Integer.valueOf(SMALLINT_MAX_VALUE
                                                                                          + number.intValue())));

                                case 3: /* MYSQL_TYPE_INT24 */
                                    columnBuilder.setValue(String.valueOf(Integer.valueOf(MEDIUMINT_MAX_VALUE
                                                                                          + number.intValue())));

                                case 4: /* MYSQL_TYPE_LONG */
                                    columnBuilder.setValue(String.valueOf(Long.valueOf(INTEGER_MAX_VALUE
                                                                                       + number.longValue())));

                                case 8: /* MYSQL_TYPE_LONGLONG */
                                    columnBuilder.setValue(BIGINT_MAX_VALUE.add(BigInteger.valueOf(number.longValue())).toString());
                            }
                        } else {
                            // 对象为number类型，直接valueof即可
                            columnBuilder.setValue(String.valueOf(value));
                        }
                        break;
                    case Types.REAL: // float
                    case Types.DOUBLE: // double
                    case Types.BIT:// bit
                        // 对象为number类型，直接valueof即可
                        columnBuilder.setValue(String.valueOf(value));
                        break;
                    case Types.DECIMAL:
                        columnBuilder.setValue(((BigDecimal) value).toPlainString());
                        break;
                    case Types.TIMESTAMP:
                        String v = value.toString();
                        v = v.substring(0, v.length() - 2);
                        columnBuilder.setValue(v);
                        break;
                    case Types.TIME:
                    case Types.DATE:
                        // 需要处理year
                        columnBuilder.setValue(value.toString());
                        break;
                    case Types.BINARY:
                    case Types.VARBINARY:
                    case Types.LONGVARBINARY:
                        // byte数组，直接使用iso-8859-1保留对应编码，浪费内存
                        columnBuilder.setValue(new String((byte[]) value, ISO_8859_1));
                        break;
                    case Types.CHAR:
                    case Types.VARCHAR:
                        // 本身对象为string
                        columnBuilder.setValue(value.toString());
                        break;
                    default:
                        columnBuilder.setValue(value.toString());
                }

                if (isAfter) {
                    rowDataBuilder.addAfterColumns(columnBuilder.build());
                } else {
                    rowDataBuilder.addBeforeColumns(columnBuilder.build());
                }
            }
        }

    }

    private String getSchemaNameAndTableName(TableMapLogEvent event) {
        StringBuilder result = new StringBuilder();
        result.append(event.getDbName()).append(".").append(event.getTableName());
        return result.toString();
    }

    private Header createHeader(String binlogFile, LogHeader logHeader, String schemaName, String tableName) {
        // header会做信息冗余,方便以后做检索或者过滤
        Header.Builder headerBuilder = Header.newBuilder();
        headerBuilder.setVersion(version);
        headerBuilder.setLogfileName(binlogFile);
        headerBuilder.setLogfileOffset(logHeader.getLogPos() - logHeader.getEventLen());
        headerBuilder.setServerId(logHeader.getServerId());
        headerBuilder.setServerenCode(UTF_8);// 经过java输出后所有的编码为unicode
        headerBuilder.setExecuteTime(logHeader.getWhen() * 1000L);
        headerBuilder.setSourceType(Type.MYSQL);
        headerBuilder.setSchemaName(StringUtils.lowerCase(schemaName));
        headerBuilder.setTableName(StringUtils.lowerCase(tableName));
        headerBuilder.setEventLength(logHeader.getEventLen());
        return headerBuilder.build();
    }

    public static TransactionBegin createTransactionBegin(long executeTime) {
        TransactionBegin.Builder beginBuilder = TransactionBegin.newBuilder();
        beginBuilder.setExecuteTime(executeTime);
        return beginBuilder.build();
    }

    public static TransactionEnd createTransactionEnd(long transactionId, long executeTime) {
        TransactionEnd.Builder endBuilder = TransactionEnd.newBuilder();
        endBuilder.setTransactionId(String.valueOf(transactionId));
        endBuilder.setExecuteTime(executeTime);
        return endBuilder.build();
    }

    public static Pair createSpecialPair(String key, String value) {
        Pair.Builder pairBuilder = Pair.newBuilder();
        pairBuilder.setKey(key);
        pairBuilder.setValue(value);
        return pairBuilder.build();
    }

    public static Entry createEntry(Header header, EntryType entryType, ByteString storeValue) {
        Entry.Builder entryBuilder = Entry.newBuilder();
        entryBuilder.setHeader(header);
        entryBuilder.setEntryType(entryType);
        entryBuilder.setStoreValue(storeValue);
        return entryBuilder.build();
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void setNameFilter(AviaterRegexFilter nameFilter) {
        this.nameFilter = nameFilter;
    }

    public void setTableMetaCache(TableMetaCache tableMetaCache) {
        this.tableMetaCache = tableMetaCache;
    }

}
