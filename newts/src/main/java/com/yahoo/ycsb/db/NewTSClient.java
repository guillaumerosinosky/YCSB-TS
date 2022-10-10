package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 18.10.15.
 */

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Optional;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import org.opennms.newts.api.*;
import org.opennms.newts.api.query.ResultDescriptor;
import org.opennms.newts.api.query.StandardAggregationFunctions;
import org.opennms.newts.api.search.*;
import org.opennms.newts.cassandra.CassandraSession;
import org.opennms.newts.cassandra.ContextConfigurations;
import org.opennms.newts.cassandra.search.CassandraSearcher;
import org.opennms.newts.persistence.cassandra.CassandraSampleRepository;


import java.sql.Timestamp;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NewTS client for YCSB framework.
 * No SUM or COUNT available, using MAX/MIN instead
 * Filtering for Tags and Time (together) is not possible,
 * Filtering for Tags and Aggregation is not possible
 * Filtering for Tags does not work properly, no support for SCAN/SUM/AVG/COUNT + Filtering for Tags
 * Buckets/Granularity is not possible to be "on", using 1 year size instead
 */
public class NewTSClient extends DB {
    private final int SUCCESS = 0;
    private String ip = "localhost";
    private int port = 9042;
    private String keySpace = "newts";
    private boolean _debug = false;
    private CassandraSession client;
    private SampleRepository repo;
    private CassandraSearcher searcher;
    private ContextConfigurations cc = new ContextConfigurations();
    private int heartbeatFactor = 10; // heartbeat = resolution * heartbeatfactor
    private boolean test = false;
    private boolean filterForTags = false; // NewTS can only filter for tags OR for time, tags + avg/sum/count/scan does not work

    /**
     * Initialize any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void init() throws DBException {
        try {
            test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
            if (!getProperties().containsKey("port") && !test) {
                throw new DBException("No port given, abort.");
            }
            port = Integer.parseInt(getProperties().getProperty("port", String.valueOf(port)));
            if (!getProperties().containsKey("ip") && !test) {
                throw new DBException("No ip given, abort.");
            }
            ip = getProperties().getProperty("ip", ip);
            keySpace = getProperties().getProperty("keyspace", keySpace);
            heartbeatFactor = Integer.parseInt(getProperties().getProperty("heartbeatfactor", String.valueOf(heartbeatFactor)));
            filterForTags = Boolean.parseBoolean(getProperties().getProperty("filterForTags", "false"));
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
            }
            if (!test) {
                SampleProcessorService processors = new SampleProcessorService(32);
                int ttl = 86400 * 365; // 1 Jahr TTL
                MetricRegistry registry = new MetricRegistry();
                // keypsace,ip,port,compression,user,pw
                this.client = new CassandraSession(keySpace, this.ip, this.port, "NONE", "cassandra", "cassandra");
                this.repo = new CassandraSampleRepository(this.client, ttl, registry, processors, this.cc);
                if (filterForTags) {
                    System.err.println("WARNING: FilterForTags does not work properly for NewTS!");
                    this.searcher = new CassandraSearcher(this.client, registry, this.cc);
                }
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

    }

    /**
     * Cleanup any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void cleanup() throws DBException {
        this.client.shutdown();
    }

    /**
     * Read a record from the database. Each value from the result will be stored in a HashMap
     *
     * @param metric    The name of the metric
     * @param timestamp The timestamp of the record to read.
     * @param tags     actual tags that were want to receive (can be empty)
     * @return Zero on success, a non-zero error code on error or "not found".
     */
    @Override
    public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) {
        if (metric == null || metric == "") {
            return -1;
        }
        if (timestamp == null) {
            return -1;
        }
        Resource resource = new Resource("/devices/sw1/interfaces");
        int counter = 0;
        if (test) {
            return SUCCESS;
        }

        if ( ! this.filterForTags || tags.size() < 1 ) {
            Results<Sample> samples = this.repo.select(Context.DEFAULT_CONTEXT, resource,
                    Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(timestamp.getTime())),
                    Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(timestamp.getTime())));
            for (Results.Row row : samples.getRows()) {

                if (row.getTimestamp().asMillis() == timestamp.getTime()) {
                    counter++;
                }
            }
        }
        else {
            BooleanQuery mainBq = new BooleanQuery(); // AND the smaller Querys per TAG together
            for (String tag : tags.keySet()) {
                BooleanQuery bq = new BooleanQuery(); // OR the single Querys per TAG together
                for (String tagvalue : tags.get(tag)) {
                    Query query = QueryBuilder.matchKeyAndValue(tag, tagvalue);
                    bq.add(query, Operator.OR);
                }
                mainBq.add(bq, Operator.AND);
            }
            SearchResults sr = this.searcher.search(Context.DEFAULT_CONTEXT,mainBq);
            if (! sr.isEmpty()) {
                SearchResults.Result res;
                while (sr.iterator().hasNext()) {
                    res = sr.iterator().next();
                    // Untested -> Missing: Check if timestamp is there
                }
            }
            else {
                return -1;
            }
        }

        if (counter == 0){
            System.err.println("ERROR: Found no values for metric: " + metric + " for timestamp: " + timestamp + ".");
            return -1;
        }
        else if (counter > 1){
            System.err.println("ERROR: Found more than one value for metric: " + metric + " for timestamp: " + timestamp + ".");
        }
        return SUCCESS;
    }

    /**
     * Perform a range scan for a set of records in the database. Each value from the result will be stored in a HashMap.
     *
     * @param metric  The name of the metric
     * @param startTs The timestamp of the first record to read.
     * @param endTs   The timestamp of the last record to read.
     * @param tags     actual tags that were want to receive (can be empty)
     * @param avg    do averageing
     * @param sum    do summarize
     * @param count  do count
     * @param timeValue  value for timeUnit for sum/count/avg
     * @param timeUnit  timeUnit for sum/count/avg
     * @return Zero on success, a non-zero error code on error.  See this class's description for a discussion of error codes.
     */
    @Override
    public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String,
            ArrayList<String>> tags, boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit ) {

        if (metric == null || metric == "") {
            return -1;
        }
        if (startTs == null || endTs == null) {
            return -1;
        }
        Resource resource = new Resource("/devices/sw1/interfaces");

        if (test) {
            return SUCCESS;
        }
        if ( ! this.filterForTags || tags.size() < 1 ) {
            // Duration for repo is resolution
            // Duration must be a divider of the interval
            // 1 is not allowed/possible
            if (timeValue != 0 && timeUnit.MILLISECONDS.convert(timeValue,timeUnit) < 2) {
                System.err.println("WARNING: NewTS supports only values > 2 as resolution. Defaulting to 2.");
                timeValue=2;
            }
            Duration duration = Duration.millis(TimeUnit.MILLISECONDS.convert(endTs.getTime()-startTs.getTime(), TimeUnit.MILLISECONDS)*2);
            // we must use double duration, otherwise sampleinterval wont work.
            Duration sampleInterval = Duration.millis(TimeUnit.MILLISECONDS.convert(endTs.getTime()-startTs.getTime(), TimeUnit.MILLISECONDS));
            if (timeValue != 0) {
                sampleInterval = Duration.millis(TimeUnit.MILLISECONDS.convert(timeValue, timeUnit));
                if (TimeUnit.MILLISECONDS.convert(endTs.getTime()-startTs.getTime(), TimeUnit.MILLISECONDS) % sampleInterval.asMillis() == 0) {
                    duration = Duration.millis(TimeUnit.MILLISECONDS.convert(endTs.getTime()-startTs.getTime(), TimeUnit.MILLISECONDS));
                }
                else {
                    long factor = (long) Math.ceil(duration.asMillis() / sampleInterval.asMillis());
                    duration = Duration.millis(sampleInterval.asMillis()*factor);
                }
            }
            Duration heartbeat = Duration.millis(TimeUnit.MILLISECONDS.convert(endTs.getTime()-startTs.getTime(), TimeUnit.MILLISECONDS));
            // heartbeat see https://github.com/OpenNMS/newts/wiki/RestService
            // heartbeat = max. duration for aggregations?
            // sample interval = bucketsize
            // duartion + sampleInterval see https://github.com/OpenNMS/newts/wiki/JavaAPI#measurement-selects
            // duration = complete time interval (must fit at least one sample interval in it) ... also named resolution which is totally wrong..
            if (avg || sum || count) {
                // AVG;SUM;COUNT
                ResultDescriptor descriptor = null;
                if (avg) {
                    descriptor = new ResultDescriptor(sampleInterval)
                            .datasource(metric, metric, heartbeat, StandardAggregationFunctions.AVERAGE)
                            .export(metric);
                }
                else if (sum) {
                    // There is no sum, use MAX instead
                    descriptor = new ResultDescriptor(sampleInterval)
                            .datasource(metric, metric, heartbeat, StandardAggregationFunctions.MAX)
                            .export(metric);
                }
                else if (count) {
                    // There is no count, use MIN instead
                    descriptor = new ResultDescriptor(sampleInterval)
                            .datasource(metric, metric, heartbeat, StandardAggregationFunctions.MIN)
                            .export(metric);
                }
                Results<Measurement> samples = this.repo.select(Context.DEFAULT_CONTEXT, resource,
                        Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(startTs.getTime())),
                        Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(endTs.getTime())),
                        descriptor, Optional.of(duration));

                if (samples == null || samples.getRows().size() == 0) {
                    return -1;
                }
            }
            else {
                // SCAN
                Results<Sample> samples = this.repo.select(Context.DEFAULT_CONTEXT, resource,
                        Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(startTs.getTime())),
                        Optional.of(org.opennms.newts.api.Timestamp.fromEpochMillis(endTs.getTime())));
                if (samples == null || samples.getRows().size() == 0) {
                    return -1;
                }
            }
        }
        else {
            System.err.println("WARNING: FilterForTags can't work together with SCAN/AVG/SUM/COUNT for NewTS!");
            return -1;
        }
        return SUCCESS;
    }

    /**
     * Insert a record in the database. Any tags/tagvalue pairs in the specified tags HashMap and the given value
     * will be written into the record with the specified timestamp
     *
     * @param metric    The name of the metric
     * @param timestamp The timestamp of the record to insert.
     * @param value     actual value to insert
     * @param tags      A HashMap of tag/tagvalue pairs to insert as tags
     * @return Zero on success, a non-zero error code on error.  See this class's description for a discussion of error codes.
     */
    @Override
    public int insert(String metric, Timestamp timestamp, double value, HashMap<String, ByteIterator> tags) {
        if (metric == null || metric == "") {
            return -1;
        }
        if (timestamp == null) {
            return -1;
        }
        try {
            List<Sample> samples = new ArrayList<Sample>();
            Map<String, String> tagMap = new HashMap<String, String>();
            for (Map.Entry<String, ByteIterator> tag : tags.entrySet()) {
                tagMap.put(tag.getKey(), tag.getValue().toString());
            }
            samples.add(
                    new Sample(
                            org.opennms.newts.api.Timestamp.fromEpochMillis(timestamp.getTime()),
                            new Resource("/devices/sw1/interfaces"),
                            metric,
                            MetricType.GAUGE,
                            new Gauge(value),
                            tagMap
                    )
            );
            this.repo.insert(samples);
            return SUCCESS;
        }
        catch (Exception e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
    }
}

