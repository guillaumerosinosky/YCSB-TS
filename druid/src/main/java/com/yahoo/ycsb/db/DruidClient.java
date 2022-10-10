package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 26.10.15.
 */

import com.metamx.common.Granularity;
import com.metamx.tranquility.beam.ClusteredBeamTuning;
import com.metamx.tranquility.druid.DruidBeams;
import com.metamx.tranquility.druid.DruidDimensions;
import com.metamx.tranquility.druid.DruidLocation;
import com.metamx.tranquility.druid.DruidRollup;
import com.metamx.tranquility.typeclass.Timestamper;
import com.twitter.finagle.Service;
import com.twitter.util.Await;
import com.twitter.util.Future;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import io.druid.data.input.impl.TimestampSpec;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
//import org.apache.log4j.Level;
//import org.apache.log4j.LogManager;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.json.JSONArray;
import org.json.JSONObject;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.slf4j.impl.SimpleLogger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Druidsclient for YCSB framework.
 * Can't work with Timestamps in the past
 * even with windowPeriod and segmentGranularity of 1 Year
 * Solution: Move the workload timerange to actual time
 * Needs a REST Client for Querys and a Finagle/Tranquility Service for Ingestion (Insert)
 * Druid supports granularities of none (ms when ms is ingested), minute, fifteen_minute, thirty_minute, hour and day or all (one bucket)
 */
public class DruidClient extends DB {
    private final int SUCCESS = 0;

    private String zookeeperIP = "localhost";
    private String zookeeperPort = "2181";
    private String indexService = "overlord";
    private String firehosePattern = "druid:firehose:%s";
    private String discoveryPath = "/druid/discovery";
    private String dataSource = "usermetric";
    private String queryIP = "localhost"; // normally broker node,but historical/realtime is possible
    private String queryPort = "8090"; // normally broker node,but historical/realtime is possible
    private String queryURL = "/druid/v2/?pretty";
    URL urlQuery = null;
    private long insertStart = 0; // Workload
    private long insertEnd = 0; // Workload
    private int timeResolution = 0; // Workload
    private long realInsertStart = 0; // Druid
    private long realInsertEnd = 0; // Druid
    private Period windowPeriod = new Period().withDays(2);
    private Granularity segmentGranularity = Granularity.MONTH;
    private QueryGranularity queryGranularity = QueryGranularity.NONE; // Millisecond Querys
    private Period warmingPeriod = new Period().withMinutes(10); // does not help for first task, but spawns task for second segment 10 minutes earlier, see: https://groups.google.com/forum/#!topic/druid-user/UT5JNSZqAuk
    private int partitions = 1;
    private int replicants = 1;
    private boolean doubleSum = true; // if false do longSum, which should be faster (non floating point), see http://druid.io/docs/latest/querying/aggregations.html
    private List<String> dimensions = new ArrayList<String>();
//    private List<AggregatorFactory> aggregators;
    private Timestamper<Map<String, Object>> timestamper;
    private CuratorFramework curator;
    private CloseableHttpClient client;
    private final TimestampSpec timestampSpec = new TimestampSpec("timestamp", "auto");
    private Service<List<Map<String, Object>>, Integer> druidService;
    private boolean _debug = false;
    private boolean test = false;
    private int retries = 3;
    private String phase;
    private int tagCount;
    private String tagPrefix;
    private static final String PHASE_PROPERTY = "phase"; // See Client.java
    private static final String TAG_PREFIX_PROPERTY = "tagprefix"; // see CoreWorkload.java
    private static final String TAG_PREFIX_PROPERTY_DEFAULT = "TAG"; // see CoreWorkload.java
    private static final String TAG_COUNT_PROPERTY = "tagcount"; // see CoreWorkload.java
    private static final String TAG_COUNT_PROPERTY_DEFAULT = "3"; // see CoreWorkload.java
    private static final String METRICNAME_PROPERTY = "metric"; // see CoreWorkload.java
    private String timestampFile = "timestamps.txt";

//    private boolean filterForTags = true;

    /**
     * Initialize any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void init() throws DBException {
        try {
            if (!getProperties().containsKey(PHASE_PROPERTY)) {
                throw new DBException(String.format("No %s given, abort.",PHASE_PROPERTY));
            }
            phase = getProperties().getProperty(PHASE_PROPERTY, "");
            dataSource = getProperties().getProperty(METRICNAME_PROPERTY, dataSource);
            tagCount = Integer.valueOf(getProperties().getProperty(TAG_COUNT_PROPERTY, TAG_COUNT_PROPERTY_DEFAULT));
            tagPrefix = getProperties().getProperty(TAG_PREFIX_PROPERTY, TAG_PREFIX_PROPERTY_DEFAULT);
            dimensions.add("value");
            for (int i=0; i<tagCount; i++) {
                dimensions.add(tagPrefix+i);
            }
            timestampFile = getProperties().getProperty("timestampfile", timestampFile);
            insertStart = Long.parseLong(getProperties().getProperty("insertstart", "0"));
            if (insertStart == 0) {
                throw new DBException("insertstart must not be 0, is it not set?");
            }
            insertEnd = Long.parseLong(getProperties().getProperty("insertend", "0"));
            if (insertEnd == 0) {
                throw new DBException("insertend must not be 0, is it not set?");
            }
            timeResolution = Integer.parseInt(getProperties().getProperty("timeresolution", "0"));
            if (timeResolution == 0) {
                throw new DBException("timeresolution must not be 0, is it not set?");
            }
            test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
            doubleSum = Boolean.parseBoolean(getProperties().getProperty("doublesum", Boolean.toString(doubleSum)));
            if (!getProperties().containsKey("zookeeperip") && !test) {
                throw new DBException("No zookeeperip given, abort.");
            }
            if (!getProperties().containsKey("queryip") && !test) {
                throw new DBException("No queryip given, abort.");
            }
            partitions = Integer.parseInt(getProperties().getProperty("partitions", String.valueOf(partitions)));
            replicants = Integer.parseInt(getProperties().getProperty("replicants", String.valueOf(replicants)));
            zookeeperIP = getProperties().getProperty("zookeeperip", zookeeperIP);
            zookeeperPort = getProperties().getProperty("zookeeperport", zookeeperPort);
            queryIP = getProperties().getProperty("queryip", queryIP);
            queryPort = getProperties().getProperty("queryport", queryPort);
            indexService = getProperties().getProperty("indexservice", indexService);
            firehosePattern = getProperties().getProperty("firehosepattern", firehosePattern);
            discoveryPath = getProperties().getProperty("discoverypath", discoveryPath);
            firehosePattern = getProperties().getProperty("firehosepattern", firehosePattern);
            dataSource = getProperties().getProperty("datasource", dataSource);
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
//                System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "trace");
//                List<org.apache.log4j.Logger> loggers = Collections.<org.apache.log4j.Logger>list(LogManager.getCurrentLoggers());
//                for ( org.apache.log4j.Logger logger : loggers ) {
//                    logger.setLevel(Level.TRACE);
//                }
            }
//            else {
//                System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "error");
//                List<org.apache.log4j.Logger> loggers = Collections.<org.apache.log4j.Logger>list(LogManager.getCurrentLoggers());
//                for ( org.apache.log4j.Logger logger : loggers ) {
//                    logger.setLevel(Level.OFF);
//                }
//                System.setProperty(SimpleLogger.LEVEL_IN_BRACKETS_KEY, "false");
//                java.util.logging.Logger logger = java.util.logging.Logger.getLogger(this.getClass().getName());
//                logger.setUseParentHandlers(false);
//                java.util.logging.LogManager.getLogManager().reset();
//                java.util.logging.Logger globalLogger = java.util.logging.Logger.getLogger(java.util.logging.Logger.GLOBAL_LOGGER_NAME);
//                globalLogger.setLevel(java.util.logging.Level.OFF);
//            }
            // You don't get Logging completely off :/
            timestamper = new Timestamper<Map<String, Object>>() {
                @Override
                public DateTime timestamp(Map<String, Object> theMap)
                {
                    return new DateTime(theMap.get("timestamp"));
                }
            };
            curator = CuratorFrameworkFactory
                    .builder()
                    .connectString(String.format("%s:%s", zookeeperIP, zookeeperPort))
                    .retryPolicy(new ExponentialBackoffRetry(1000, 20, 30000))
                    .build();
            if (!test) {
                curator.start();
                druidService = DruidBeams
                        .builder(timestamper)
                        .curator(curator)
                        .discoveryPath(discoveryPath)
                        .location(
                                DruidLocation.create(
                                        indexService,
                                        firehosePattern,
                                        dataSource
                                )
                        )
                        .timestampSpec(timestampSpec)
                        .rollup(DruidRollup.create(DruidDimensions.specific(dimensions), new ArrayList<AggregatorFactory>(), queryGranularity))
                        .tuning(
                                ClusteredBeamTuning
                                        .builder()
                                        .segmentGranularity(this.segmentGranularity)
                                        .windowPeriod(this.windowPeriod)
                                        .partitions(this.replicants)
                                        .replicants(this.partitions)
                                        .warmingPeriod(this.warmingPeriod)
                                        .build()
                        )
                        .buildJavaService();
                RequestConfig requestConfig = RequestConfig.custom().build();
                if (!test) {
                    client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();
                }
            }
            urlQuery = new URL("http", queryIP, Integer.valueOf(queryPort), queryURL );
            if (_debug) {
                System.out.println("URL: " + urlQuery);
            }
            if (phase == "load") {
                // write timestamps
                String actTime = String.valueOf(System.currentTimeMillis());
                realInsertStart = Long.valueOf(actTime.substring(0, actTime.length()-3) + "000");
                realInsertEnd = realInsertStart + Math.abs(insertStart - insertEnd);
                File file = new File(timestampFile);
                if (file.isDirectory()) {
                    throw new DBException(String.format("'%s' is a directory. Please specifiy another timestamp file.",timestampFile));
                }
                if (file.exists()) {
                    file.delete();
                }
                FileOutputStream fileOut = new FileOutputStream(file);
                ObjectOutputStream oos = new ObjectOutputStream(fileOut);
                oos.writeObject(realInsertStart);
                oos.writeObject(realInsertEnd);
                oos.flush();
                oos.close();
                fileOut.flush();
                fileOut.close();
            }
            else if (phase == "run") {
                // read timestamps
                File file = new File(timestampFile);
                if (file.exists() && file.canRead() && file.isFile()){
                    FileInputStream fileIn = new FileInputStream(timestampFile);
                    ObjectInputStream ois = new ObjectInputStream(fileIn);
                    realInsertStart = (long) ois.readObject();
                    realInsertEnd = (long) ois.readObject();
                    ois.close();
                    fileIn.close();
                    file.delete();
                }
                else {
                    throw new DBException(String.format("'%s' is not existing,not readable or not a file.",timestampFile));
                }

            }
            else {
                throw new DBException(String.format("Unknown Phase: '%s'",phase));
            }
            if (_debug) {
                System.out.println(String.format("Workload Timerange: %s (%s) %s (%s)",
                        insertStart, new DateTime(insertStart),
                        insertEnd, new DateTime(insertEnd)));
                System.out.println(String.format("Druid Timerange: %s (%s) %s (%s)"
                        ,realInsertStart, new DateTime(realInsertStart),
                        realInsertEnd, new DateTime(realInsertEnd)));
            }
            if (!test && !this.druidService.isAvailable()) {
                throw new DBException("ERROR: DruidService is not available!");
            }
            // we need one insert, because this first insert takes a lot time
            // because of no worker available etcpp.
            // insert 10ms before time range, should not matter
            if (_debug) {
                System.out.println("Doing Init Insert now.");
            }
            int res = this.insert(dataSource, new Timestamp (insertStart-10),1,new HashMap<String, ByteIterator>());
            if (_debug) {
                System.out.println(String.format("Init Insert returned %s.",res));
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

    }

    private JSONArray runQuery(URL url, String queryStr) {
        JSONArray jsonArr = new JSONArray();
        HttpResponse response = null;
        try {
            HttpPost postMethod = new HttpPost(url.toString());
            StringEntity requestEntity = new StringEntity(queryStr, ContentType.APPLICATION_JSON);
            postMethod.setEntity(requestEntity);
            postMethod.addHeader("accept", "application/json");
            int tries = retries + 1;
            while (true)
            {
                tries--;
                try
                {
                    response = client.execute(postMethod);
                    break;
                }
                catch (IOException e)
                {
                    if (tries < 1) {
                        System.err.print("ERROR: Connection to " + url.toString() + " failed " + retries + "times.");
                        e.printStackTrace();
                        if (response != null) {
                            EntityUtils.consumeQuietly(response.getEntity());
                        }
                        postMethod.releaseConnection();
                        return null;
                    }
                }
            }
            if(response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_OK ||
                    response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_NO_CONTENT  ||
                    response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_MOVED_PERM){
                if (response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_MOVED_PERM) {
                    System.err.println("WARNING: Query returned 301, that means 'API call has migrated or should be forwarded to another server'");
                }
                if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_NO_CONTENT){
                    // Maybe also not HTTP_MOVED_PERM? Can't Test it right now
                    BufferedReader bis = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = bis.readLine()) != null) {
                        builder.append(line);
                    }
                    jsonArr = new JSONArray(builder.toString());
                }
                EntityUtils.consumeQuietly(response.getEntity());
                postMethod.releaseConnection();
            }
        } catch (Exception e) {
            System.err.println("ERROR: Errror while trying to query " + url.toString() + " for '" + queryStr + "'.");
            e.printStackTrace();
            if (response != null) {
                EntityUtils.consumeQuietly(response.getEntity());
            }
            return null;
        }
        return jsonArr;
    }

    /**
     * Cleanup any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void cleanup() throws DBException {
//        this.client.teardown();
        try {
            if (!test) {
                Await.result(druidService.close());
                curator.close();
                client.close();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

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
        try {
            int counter = 0;
            JSONObject query = new JSONObject();
            query.put("queryType", "select");
            query.put("dataSource", metric);
            query.put("dimensions", new JSONArray());
            query.put("metrics", new JSONArray());
            query.put("granularity", "all");
            if (tags.keySet().size() > 0) {
                JSONObject andFilter = new JSONObject();
                andFilter.put("type", "and");
                JSONArray andArray = new JSONArray();
                for (Map.Entry entry : tags.entrySet()) {
                    JSONObject orFilter = new JSONObject();
                    JSONArray orArray = new JSONArray();
                    orFilter.put("type", "or");
                    for (String tagValue : (ArrayList<String>) entry.getValue()) {
                        JSONObject selectorFilter = new JSONObject();
                        selectorFilter.put("type", "selector");
                        selectorFilter.put("dimension", entry.getKey().toString());
                        selectorFilter.put("value", tagValue);
                        orArray.put(selectorFilter);
                    }
                    orFilter.put("fields", orArray);
                    andArray.put(orFilter);
                }
                andFilter.put("fields", andArray);
                query.put("filter", andFilter);
            }
            JSONArray dateArray = new JSONArray();
            // calculate druid timestamps from workload timestamps
            long realTimestamp = this.realInsertStart + Math.abs(timestamp.getTime()-this.insertStart);
            dateArray.put(String.format("%s/%s",
                    new DateTime(realTimestamp),
                    new DateTime(realTimestamp+timeResolution)));
            query.put("intervals", dateArray);
            JSONObject pagingSpec = new JSONObject();
            pagingSpec.put("pagingIdentifiers",new JSONObject());
            pagingSpec.put("threshold",1);
            query.put("pagingSpec", pagingSpec);
            if (_debug) {
                System.out.println("Input Query String: " + query.toString());
            }
            if (test) {
                return SUCCESS;
            }
            JSONArray jsonArr = runQuery(urlQuery, query.toString());
            if (jsonArr == null || jsonArr.length() == 0 ) {
//            System.err.println("ERROR: Received empty answer for '" + query.toString() + "'.");
                // is allowed!
                return -1;
            }

            for (int i = 0; i < jsonArr.length(); i++) {
                if (!jsonArr.getJSONObject(i).has("timestamp")) {
                    System.err.println("ERROR: jsonArr Index " + i + " has no 'timestamp' key.");
                }
                else if (!jsonArr.getJSONObject(i).has("result")) {
                    System.err.println("ERROR: jsonArr Index " + i + " has no 'result' key.");
                }
                else {
                    DateTime ts = new DateTime(jsonArr.getJSONObject(i).get("timestamp"));
                    JSONObject result = (JSONObject) jsonArr.getJSONObject(i).get("result");
                    if (!result.has("events")) {
                        System.err.println("ERROR: jsonArr Index " + i + " has no 'result->events' key.");
                    }
                    else {
                        JSONArray events = result.getJSONArray("events");
                        if (events == null || events.length() == 0 ) {
                            System.err.println("ERROR: jsonArr Index " + i + " has no 'result->events'.");
                        }
                        else {
                            for (int j = 0; j < events.length(); j++) {
                                JSONObject event = events.getJSONObject(j);
                                if (event == null || event.length() == 0 || ! event.has("event")) {
                                    System.err.println("ERROR: jsonArr Index " + i + " has no 'result->events->event' at index "+j+".");
                                }
                                else {
                                    JSONObject eventEntry = event.getJSONObject("event");
                                    if (eventEntry.has("timestamp")) {
                                        DateTime ts2 = new DateTime(eventEntry.get("timestamp"));
                                        if (ts.getMillis() == ts2.getMillis() && ts.getMillis() == realTimestamp) {
                                            counter++;
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }
            if (_debug) {
                System.err.println("jsonArr: " + jsonArr);
            }
            if (counter == 0){
                System.err.println("ERROR: Found no values for metric: " + metric + " for timestamp: " + timestamp + ".");
                return -1;
            }
            else if (counter > 1){
                System.err.println("ERROR: Found more than one value for metric: " + metric + " for timestamp: " + timestamp + ".");
            }
        }
        catch (Exception e) {
            System.err.println("ERROR: Error while processing READ for metric: " + metric + e);
            e.printStackTrace();
            return -1;
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
        try {
            JSONObject query = new JSONObject();
            if (sum || avg || count) {
                query.put("queryType", "timeseries");
                JSONArray aggregatorArray = new JSONArray();
                if (sum || avg) {
                    JSONObject aggregator = new JSONObject();
                    if (this.doubleSum) {
                        aggregator.put("type", "doubleSum");
                    }
                    else {
                        aggregator.put("type", "longSum");
                    }
                    aggregator.put("name", "sumResult");
                    aggregator.put("fieldName", "value");
                    aggregatorArray.put(aggregator);
                }

                if (count || avg) {
                    JSONObject aggregator = new JSONObject();
                    aggregator.put("type", "count");
                    aggregator.put("name", "countResult");
                    aggregatorArray.put(aggregator);
                }
                query.put("aggregations", aggregatorArray);
                if (avg) {
                    JSONObject postAggregator = new JSONObject();
                    postAggregator.put("type", "arithmetic");
                    postAggregator.put("name", "averageResult");
                    postAggregator.put("fn", "/");
                    JSONArray fields = new JSONArray();
                    JSONObject field1 = new JSONObject();
                    field1.put("type","fieldAccess");
                    field1.put("name","sumResult");
                    field1.put("fieldName","sumResult");
                    fields.put(field1);
                    JSONObject field2 = new JSONObject();
                    field2.put("type","fieldAccess");
                    field2.put("name","countResult");
                    field2.put("fieldName","countResult");
                    fields.put(field2);
                    postAggregator.put("fields", fields);
                    JSONArray postAggregatorArray = new JSONArray();
                    postAggregatorArray.put(postAggregator);
                    query.put("postAggregations", postAggregatorArray);
                }
            }
            else {
                query.put("queryType", "select");
                query.put("dimensions", new JSONArray());
                query.put("metrics", new JSONArray());
                JSONObject pagingSpec = new JSONObject();
                pagingSpec.put("pagingIdentifiers",new JSONObject());
                pagingSpec.put("threshold",1);
                query.put("pagingSpec", pagingSpec);
            }
            query.put("dataSource", metric);
            if (avg || sum || count) {
                if (timeValue != 0) {
                    long granularity = TimeUnit.MILLISECONDS.convert(timeValue, timeUnit);
                    if (granularity == TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)) {
                        query.put("granularity", "minute");
                    }
                    else if (granularity == TimeUnit.MILLISECONDS.convert(15, TimeUnit.MINUTES)) {
                        query.put("granularity", "fifteen_minute");
                    }
                    else if (granularity == TimeUnit.MILLISECONDS.convert(30, TimeUnit.MINUTES)) {
                        query.put("granularity", "thirty_minute");
                    }
                    else if (granularity == TimeUnit.MILLISECONDS.convert(60, TimeUnit.MINUTES)) {
                        query.put("granularity", "hour");
                    }
                    else if (granularity == TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)) {
                        query.put("granularity", "day");
                    }
                    else if (granularity == 1) {
                        System.err.println("WARNING: Using granularity = 1 ms for Druid is not advisable and will probably lead to problems (Search for killed Java processes due to memory). See http://druid.io/docs/latest/querying/granularities.html.");
                        query.put("granularity", "none");
                    }
                    else {
                        System.err.print("WARNING: Druid only supports 1 minute, 15 minutes, 30 minutes, 1 hour, 1 day, none (= ingested resultion = ms?), all (one bucket) as granularity, using full granularity.");
                    }
                }
                else {
                    query.put("granularity", "all");
                }
            }
            else {
                // WARNING: Using granularity = 1 ms for Druid is not advisable and will probably lead to problems (Search for killed Java processes due to memory).
                // See http://druid.io/docs/latest/querying/granularities.html
                // Also a Select Query needs no "none" = 1 ms granularity, see http://druid.io/docs/latest/development/select-query.html
                // it is okay to return every existing value in one big bucket, as long as all values are delivered back
//                query.put("granularity", "none");
                query.put("granularity", "all");

            }
            if (tags.keySet().size() > 0) {
                JSONObject andFilter = new JSONObject();
                andFilter.put("type", "and");
                JSONArray andArray = new JSONArray();
                for (Map.Entry entry : tags.entrySet()) {
                    JSONObject orFilter = new JSONObject();
                    JSONArray orArray = new JSONArray();
                    orFilter.put("type", "or");
                    for (String tagValue : (ArrayList<String>) entry.getValue()) {
                        JSONObject selectorFilter = new JSONObject();
                        selectorFilter.put("type", "selector");
                        selectorFilter.put("dimension", entry.getKey().toString());
                        selectorFilter.put("value", tagValue);
                        orArray.put(selectorFilter);
                    }
                    orFilter.put("fields", orArray);
                    andArray.put(orFilter);
                }
                andFilter.put("fields", andArray);
                query.put("filter", andFilter);
            }
            JSONArray dateArray = new JSONArray();
            // calculate druid timestamps from workload timestamps
            dateArray.put(String.format("%s/%s",
                    new DateTime(this.realInsertStart+Math.abs(startTs.getTime()-this.insertStart)),
                    new DateTime(this.realInsertStart+Math.abs(endTs.getTime()-this.insertStart))));
            query.put("intervals", dateArray);
            if (_debug) {
                System.out.println("Input Query String: " + query.toString());
            }
            if (test) {
                return SUCCESS;
            }
            JSONArray jsonArr = runQuery(urlQuery, query.toString());
            if (jsonArr == null || jsonArr.length() == 0 ) {
//            System.err.println("ERROR: Received empty answer for '" + query.toString() + "'.");
                // is allowed!
                return -1;
            }

            for (int i = 0; i < jsonArr.length(); i++) {
                if (!jsonArr.getJSONObject(i).has("timestamp")) {
                    return -1;
                }
                else if (!jsonArr.getJSONObject(i).has("result")) {
                    return -1;
                }
                else {
                    DateTime ts = new DateTime(jsonArr.getJSONObject(i).get("timestamp"));
                    JSONObject result = (JSONObject) jsonArr.getJSONObject(i).get("result");
                    if (sum) {
                        if (!result.has("sumResult")) {
                            return -1;
                        }
                    }
                    else if (avg) {
                        if (!result.has("averageResult")) {
                            return -1;
                        }
                    }
                    else if (count) {
                        if (!result.has("countResult")) {
                            return -1;
                        }
                    }
                    else if (!result.has("events")) {
                        return -1;
                    }
                    else {
                        JSONArray events = result.getJSONArray("events");
                        if (events == null || events.length() == 0 ) {
                            return -1;
                        }
                    }
                }
            }
            if (_debug) {
                System.err.println("jsonArr: " + jsonArr);
            }
        }
        catch (Exception e) {
            System.err.println("ERROR: Error while processing READ for metric: " + metric + e);
            e.printStackTrace();
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
        if (metric != dataSource) {
            System.err.println(String.format("WARNING: Metric is not the same as dataSource, pleas change it! Metric: %s, dataSource: %s.",metric,this.dataSource));
        }
        Map<String, Object> data = new HashMap<String, Object>();
        // Calculate special Timestamp in special druid timerange
        // (workload timerange shifted to actual time)
        data.put("timestamp", this.realInsertStart+(timestamp.getTime()-this.insertStart));
        for (Map.Entry<String, ByteIterator> tag : tags.entrySet()) {
            data.put(tag.getKey(), tag.getValue().toString());
        }
        data.put("value", value);
        List<Map<String, Object>> druidEvents = new ArrayList<Map<String, Object>>();
        druidEvents.add(data);
        final Future<Integer> numSentFuture = druidService.apply(druidEvents);
        try {
            final Integer numSent = Await.result(numSentFuture);
            if (_debug) {
                System.out.println(String.format("Result (numSent) from Druid: %s",numSent));
            }
            if (numSent != 1) {
                return -1;
            }
        }
        catch (Exception e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
        return SUCCESS;
    }
}

