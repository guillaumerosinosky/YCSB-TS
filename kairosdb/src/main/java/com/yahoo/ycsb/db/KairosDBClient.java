package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 19.07.15.
 */

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import org.kairosdb.client.HttpClient;
import org.kairosdb.client.builder.*;
import org.kairosdb.client.response.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * KairosDB client for YCSB framework.
 */
public class KairosDBClient extends DB {
    private final int SUCCESS = 0;
    private HttpClient client = null;
    private String ip = "localhost";
    private int port = 8080;
    private boolean _debug = false;
    private boolean test = false;

    /**
     * Initialize any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void init() throws DBException {
        try {
            test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
            if (!getProperties().containsKey("port") && ! test) {
                throw new DBException("ERROR: No port given, abort.");
            }
            port = Integer.parseInt(getProperties().getProperty("port", String.valueOf(port)));
            if (!getProperties().containsKey("ip") && ! test) {
                throw new DBException("ERROR: No ip given, abort.");
            }
            ip = getProperties().getProperty("ip",ip);
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

        try {
            if (!test) {
                client = new HttpClient(String.format("http://%s:%s", ip, port));
            }
        }
        catch (MalformedURLException e) {
            throw new DBException(e);
        }
    }

    /**
     * Cleanup any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void cleanup() throws DBException {
        try {
            if (!test) {
                client.shutdown();
            }
        }
        catch (IOException e) {
            throw new DBException(e);
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

        // Problem: You cant ask for a timestamp at TS=x, you need to give a range. So: Begin: timestamp, End: timestamp + 1 ms
        // We may get more than that, but we just take the right one
        // There could also be more of them, so count
        int counter = 0;
        long timestampLong = timestamp.getTime();
        QueryBuilder builder = QueryBuilder.getInstance();
        QueryMetric qm = builder.setStart(timestamp)
                .setEnd(new Timestamp(timestampLong+1))
                .addMetric(metric);
        for (String tag : tags.keySet()) {
            ArrayList<String> tagnames= tags.get(tag);
            String[] tnArr = new String[tagnames.size()];
            tnArr = tagnames.toArray(tnArr);
            qm.addTag(tag, tnArr);
        }
        try {
            // No idea what that '******************* Type=number' Error means, seems to be from GSON or Apache httpclient
            // Old Debug Message in Kairos DB Client, see https://github.com/kairosdb/kairosdb-client/issues/43
            // Fixed in https://github.com/kairosdb/kairosdb-client/commit/e1b2ea9a0fef0ed67812580502d5faf4aa125e17 but no Maven version?
            // 31.10.15 -> seems to be fixed in mvn version?
            if (test) {
                return SUCCESS;
            }
            QueryResponse response = client.query(builder);
            if (response.getStatusCode() != 200) {
                return -1;
            }
            if (response.getQueries().size() == 0) {
                System.err.println("ERROR: Found no querie for metric: " + metric + " for timestamp: " + timestamp + " to read.");
                return -1;
            }
            else if  (response.getQueries().size() > 1) {
                System.err.println("ERROR: Found more than one querie for metric: " + metric + " for timestamp: " + timestamp + " to read. This should normally not happen.");
            }

            for (Queries query : response.getQueries()) {
                List<Results> resList = query.getResults();
                if (resList.size() == 0) {
//                    System.err.println("ERROR: Found no results in querie for metric: " + metric + " for timestamp: " + timestamp + " to read.");
                    return -1;
                }
//                else if  (resList.size() > 1) {
//                    System.err.println("ERROR: Found more than one results in querie for metric: " + metric + " for timestamp: " + timestamp + " to read. This should normally not happen.");
//                }
                for (Results result : resList) {
                    List<DataPoint> dataPoints = result.getDataPoints();
                    if (dataPoints.size() == 0) {
                        // Allowed to happen, no error message!
                        //System.err.println("Error: Found no dataPoints for metric: " + metric + " for timestamp: " + result.getName() + " for timestamp: " + timestamp + " to read.");
                        return -1;
                    }
                    for ( DataPoint dp : dataPoints) {
                        if ( dp.getTimestamp() == timestampLong ) {
//                            if (values.containsKey(dp.getTimestamp())) {
//                            System.err.println("ERROR: Dict already contains value for " + dp.getTimestamp() + ".");
//                            return -1;
//                        }
//                        values.put(timestamp, dp.doubleValue());
                        counter++;
                        }
                    }
                }
            }
        }
        catch (URISyntaxException e) {
            System.err.println("ERROR: Error in processing scan for metric: " + metric + " for timestamp: " + timestamp + "." + e);
            e.printStackTrace();
            return -1;
        }
        catch (IOException e) {
            System.err.println("ERROR: Error in processing scan for metric: " + metric + "  for timestamp: " + timestamp + "." + e);
            e.printStackTrace();
            return -1;
        }
//        catch (DataFormatException e) {
//            System.err.println("ERROR: Error in processing scan while converting to Double for metric: " + metric + "  for timestamp: " + timestamp + "." + e);
//            e.printStackTrace();
//            return -1;
//        }
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
        QueryBuilder builder = QueryBuilder.getInstance();
        QueryMetric qm = builder.setStart(startTs)
                .setEnd(endTs)
                .addMetric(metric);
        for (String tag : tags.keySet()) {
            ArrayList<String> tagnames= tags.get(tag);
            String[] tnArr = new String[tagnames.size()];
            tnArr = tagnames.toArray(tnArr);
            qm.addTag(tag, tnArr);
        }
        org.kairosdb.client.builder.TimeUnit tu = null;
        int newTimeValue = timeValue;
        // Can also support "weeks", "months", and "years", not used yet
        if (avg || sum || count) {
            if (timeValue == 0) {
                newTimeValue = (int) (endTs.getTime() - startTs.getTime());
                if (newTimeValue > TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)) {
                    newTimeValue = (int) TimeUnit.DAYS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS);
                    tu = org.kairosdb.client.builder.TimeUnit.valueOf(TimeUnit.DAYS.name());
                }
                else if (newTimeValue > TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)) {
                    newTimeValue = (int) TimeUnit.HOURS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS);
                    tu = org.kairosdb.client.builder.TimeUnit.valueOf(TimeUnit.HOURS.name());
                }
                else if (newTimeValue > TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)) {
                    newTimeValue = (int) TimeUnit.MINUTES.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS);
                    tu = org.kairosdb.client.builder.TimeUnit.valueOf(TimeUnit.MINUTES.name());
                }
                else if (newTimeValue > TimeUnit.MILLISECONDS.convert(1, TimeUnit.SECONDS)) {
                    newTimeValue = (int) TimeUnit.SECONDS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS);
                    tu = org.kairosdb.client.builder.TimeUnit.valueOf(TimeUnit.SECONDS.name());
                }
                else {
                    tu = org.kairosdb.client.builder.TimeUnit.valueOf(TimeUnit.MILLISECONDS.name());
                }
            }
            else {
                tu = org.kairosdb.client.builder.TimeUnit.valueOf(timeUnit.name());
            }
        }
        if (avg) {
            qm.addAggregator(AggregatorFactory.createAverageAggregator(newTimeValue, tu));
        }
        else if (count) {
            qm.addAggregator(AggregatorFactory.createCountAggregator(newTimeValue, tu));
        }
        else if (sum) {
            qm.addAggregator(AggregatorFactory.createSumAggregator(newTimeValue, tu));
        }
        try {
            if (test) {
                return SUCCESS;
            }
            QueryResponse response = client.query(builder);
            if (response.getStatusCode() != 200) {
                return -1;
            }
            if (response.getQueries().size() == 0) {
//                System.err.println("ERROR: Found no querie for metric: " + metric + " for timestamprange: " + startTs + "->" + endTs + " to scan.");
                return -1;
            }
//            else if  (response.getQueries().size() > 1) {
//                System.err.println("ERROR: Found more than one querie for metric: " + metric + " for timestamprange: " + startTs + "->" + endTs + " to scan. This should normally not happen.");
//            }
            for (Queries query : response.getQueries()) {
                List<Results> resList = query.getResults();
                if (resList.size() == 0) {
//                    System.err.println("ERROR: Found no results in querie for metric: " + metric + " for timestamprange: " + startTs + "->" + endTs + " to scan.");
                    return -1;
                }
//                else if  (resList.size() > 1) {
//                    System.err.println("ERROR: Found more than one results in querie for metric: " + metric + " for timestamprange: " + startTs + "->" + endTs + " to scan. This should normally not happen.");
//                }
                for (Results result : resList) {
                    List<DataPoint> dataPoints = result.getDataPoints();
                    if (dataPoints.size() == 0) {
                        return -1;
                    }
//                    for ( DataPoint dp : dataPoints) {
//                        if (values.containsKey(dp.getTimestamp())) {
//                            System.err.println("ERROR: Dict already contains value for " + dp.getTimestamp() + ".");
//                            return -1;
//                        }
//                        values.put(new Timestamp(dp.getTimestamp()), dp.doubleValue());
//                    }
                }
            }
        }
        catch (URISyntaxException e) {
            System.err.println("ERROR: Error in processing scan for metric: " + metric + " for timestamprange: " + startTs + "->" + endTs + "." + e);
            e.printStackTrace();
            return -1;
        }
        catch (IOException e) {
            System.err.println("ERROR: Error in processing scan for metric: " + metric + "  for timestamprange: " + startTs + "->" + endTs + "." + e);
            e.printStackTrace();
            return -1;
        }
//        catch (DataFormatException e) {
//            System.err.println("ERROR: Error in processing scan while converting to Double for metric: " + metric + "  for timestamprange: " + startTs + "->" + endTs + "." + e);
//            e.printStackTrace();
//            return -1;
//        }
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
        MetricBuilder builder = MetricBuilder.getInstance();
        Metric met = builder.addMetric(metric);

        for (Map.Entry<String, ByteIterator> entry : tags.entrySet()) {
            met.addTag(entry.getKey(), entry.getValue().toString());
        }
        met.addDataPoint(timestamp.getTime(), value);
        try {
            if (test) {
                return SUCCESS;
            }
            Response response = client.pushMetrics(builder);
            if (response.getStatusCode() != 204) {
                return -1;
            }
            else {
                return SUCCESS;
            }
        }
        catch (URISyntaxException e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
        catch (IOException e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
    }
}

