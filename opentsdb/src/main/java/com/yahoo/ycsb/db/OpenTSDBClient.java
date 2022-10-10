package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 19.07.15.
 */

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Need to use Json 20140107, newer versions drop an error "Unsupported major.minor version 52.0"

/**
 * OpenTSDB client for YCSB framework.
 * OpenTSDB does not support one Bucket for avg/sum/count -> using interval size as Bucket
 */
public class OpenTSDBClient extends DB {
    private final int SUCCESS = 0;
    URL urlQuery = null;
    URL urlPut = null;
    private String ip = "localhost";
    private String queryURL = "/api/query";
    private String putURL = "/api/put";
    private int port = 4242;
    private boolean _debug = false;
    private boolean filterForTags = true; // Versions above OpenTSDB 2.2 (included) can use this; Untested!
    private boolean useCount = true; // Versions above OpenTSDB 2.2 (included) have Count(), otherwise min is used
    private boolean useMs = true; // Millisecond or Second resolution
    private CloseableHttpClient client;
    private int retries = 3;
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
                throw new DBException("No port given, abort.");
            }
            port = Integer.parseInt(getProperties().getProperty("port",String.valueOf(port)));
            if (!getProperties().containsKey("ip") && ! test) {
                throw new DBException("No ip given, abort.");
            }
            ip = getProperties().getProperty("ip",ip);
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
            }
            filterForTags = Boolean.parseBoolean(getProperties().getProperty("filterForTags", "true"));
            useCount = Boolean.parseBoolean(getProperties().getProperty("useCount", "true"));
            useMs = Boolean.parseBoolean(getProperties().getProperty("useMs", "true"));
            RequestConfig requestConfig = RequestConfig.custom().build();
            if (!test) {
                client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

        try {
            urlQuery = new URL("http", ip, port, queryURL );
            if (_debug) {
                System.out.println("URL: "+urlQuery);
            }
            urlPut = new URL("http", ip, port, putURL);
            if (_debug) {
                System.out.println("URL: "+urlPut);
            }
       }
        catch (MalformedURLException e) {
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
        try {
            if (! test) {
                client.close();
            }
        }
        catch (Exception e) {
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
        JSONObject query = new JSONObject();
        query.put("start", timestampLong);
        query.put("end", timestampLong+1);
        query.put("msResolution", this.useMs);

        JSONArray queryArray = new JSONArray();
        JSONObject queryParams = new JSONObject();
        queryParams.put("metric", metric);
        queryArray.put(queryParams);
        JSONArray queryFilters = null;
        if (filterForTags) {
            queryFilters = new JSONArray();
        }
        JSONObject queryTags = null;
        if (!filterForTags) {
            queryTags = new JSONObject();
        }
        queryParams.put("aggregator", "min");
        for ( Map.Entry entry : tags.entrySet()) {
            String tagValues = "";
            for (String tagValue : (ArrayList<String>) entry.getValue()) {
                tagValues += tagValue + "|";
            }
            tagValues = tagValues.substring(0, tagValues.length() - 1);
            if (filterForTags) {
                JSONObject tagFilter = new JSONObject();
                tagFilter.put("type", "literal_or");
                tagFilter.put("tagk", entry.getKey().toString());
                tagFilter.put("groupBy", false);
                tagFilter.put("filter", tagValues);
                queryFilters.put(tagFilter);
            }
            else {
                queryTags.put(entry.getKey().toString(), tagValues);
            }
        }
        if (filterForTags) {
            queryParams.put("filters", queryFilters);
        }
        else {
            queryParams.put("tags", queryTags);
        }
        query.put("queries", queryArray);
        if (_debug) {
            System.out.println("Query String: " + query.toString());
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
        if (jsonArr.isNull(0)) {
            System.err.println("ERROR: Received empty Object for index 0 for '" + query.toString() + "'.");
            return -1;
        }
        for (int i = 0; i < jsonArr.length(); i++) {
            if (!jsonArr.getJSONObject(i).has("dps")) {
                System.err.println("ERROR: jsonArr Index " + i + " has no 'dps' key.");
            }
            else {
                JSONObject jsonObj = (JSONObject) jsonArr.getJSONObject(i).get("dps");
                if (jsonObj.keySet().size() == 0) {
                    // Allowed to happen, no error message!
                    return -1;
                }
                for (Object obj : jsonObj.keySet()) {
                    long objTS = Long.valueOf(obj.toString());
                    if (objTS == timestampLong) {
//                        if (values.containsKey(objTS)) {
//                            System.err.println("ERROR: Dict already contains value for " + objTS + ".");
//                            return -1;
//                        }
                        //                values.put(timestamp, jsonObj.getDouble(obj.toString()));
                        counter++;
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
        JSONObject query = new JSONObject();
        query.put("start", startTs.getTime());
        query.put("end", endTs.getTime());
        query.put("msResolution", this.useMs);
        String tu = "";
        if (avg || sum || count) {
            if (timeValue != 0) {
                if (timeUnit == TimeUnit.MILLISECONDS) {
                    tu = timeValue + "ms";
                }
                else if (timeUnit == TimeUnit.SECONDS) {
                    tu = timeValue + "s";
                }
                else if (timeUnit == TimeUnit.MINUTES) {
                    tu = timeValue + "m";
                }
                else if (timeUnit == TimeUnit.HOURS) {
                    tu = timeValue + "h";
                }
                else if (timeUnit == TimeUnit.DAYS) {
                    tu = timeValue + "d";
                }
                else {
                    tu = TimeUnit.DAYS.convert(timeValue, timeUnit) + "d";
                }
            }
            else {
                long timeValueInMs = endTs.getTime() - startTs.getTime();
                if (timeValueInMs > TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)) {
                    tu = TimeUnit.DAYS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS) + "d";
                }
                else if (timeValueInMs > TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)) {
                    tu = TimeUnit.HOURS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS) + "h";
                }
                else if (timeValueInMs > TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)) {
                    tu = TimeUnit.MINUTES.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS) + "m";
                }
                else if (timeValueInMs > TimeUnit.MILLISECONDS.convert(1, TimeUnit.SECONDS)) {
                    tu = TimeUnit.SECONDS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS) + "s";
                }
                else {
                    tu = TimeUnit.MILLISECONDS.convert(endTs.getTime() - startTs.getTime(), TimeUnit.MILLISECONDS) + "ms";
                }
            }
        }
        JSONArray queryArray = new JSONArray();
        JSONObject queryParams = new JSONObject();
        queryParams.put("metric", metric);
        JSONArray queryFilters = null;
        if (filterForTags) {
            queryFilters = new JSONArray();
        }
        JSONObject queryTags = null;
        if (!filterForTags) {
            queryTags = new JSONObject();
        }
        if (avg) {
            queryParams.put("aggregator", "avg");
            queryParams.put("downsample", tu+"-avg");
        }
        else if (count) {
            if (useCount) {
                queryParams.put("aggregator", "count");
                queryParams.put("downsample", tu+"-count");
            }
            else {
                queryParams.put("aggregator", "min");
                queryParams.put("downsample", tu+"-min");
            }
        }
        else if (sum) {
            queryParams.put("aggregator", "sum");
            queryParams.put("downsample", tu+"-sum");
        }
        else {
            // When scan do 1ms resolution, use min aggr.
            queryParams.put("aggregator", "min");
            queryParams.put("downsample", "1ms-min");
        }
        for ( Map.Entry entry : tags.entrySet()) {
            String tagValues = "";
            for (String tagValue : (ArrayList<String>) entry.getValue()) {
                tagValues += tagValue + "|";
            }
            tagValues = tagValues.substring(0, tagValues.length() - 1);
            if (filterForTags) {
                JSONObject tagFilter = new JSONObject();
                tagFilter.put("type", "literal_or");
                tagFilter.put("tagk", entry.getKey().toString());
                tagFilter.put("groupBy", false);
                tagFilter.put("filter", tagValues);
                queryFilters.put(tagFilter);
            }
            else {
                queryTags.put(entry.getKey().toString(), tagValues);
            }
        }
        if (filterForTags) {
            queryParams.put("filters", queryFilters);
        }
        else {
            queryParams.put("tags", queryTags);
        }
        queryArray.put(queryParams);
        query.put("queries", queryArray);
        if (_debug) {
            System.out.println("Query String: " + query.toString());
        }
        if (test) {
            return SUCCESS;
        }
        JSONArray jsonArr = runQuery(urlQuery, query.toString());
        if (jsonArr == null || jsonArr.length() == 0 ) {
//            System.err.println("ERROR: Received empty answer for '" + query.toString() + "'.");
            // is allowed
            return -1;
        }
        if (jsonArr.isNull(0)) {
            System.err.println("ERROR: Received empty Object for index 0 for '" + query.toString() + "'.");
            return -1;
        }
        for (int i = 0; i < jsonArr.length(); i++) {
            if (!jsonArr.getJSONObject(i).has("dps")) {
                System.err.println("ERROR: jsonArr Index " + i + " has no 'dps' key.");
            }
        }
//                JSONObject jsonObj = (JSONObject) jsonArr.getJSONObject(0).get("dps");
//                if (jsonObj.keySet().size() == 0) {
//                    // Allowed to happen, no error message!
//                    return -1;
//                }
//                for (Object obj :  jsonObj.keySet()) {
//                    long objLong = Long.valueOf(obj.toString());
//                    Timestamp objTS = new Timestamp(objLong);
//                    if (values.containsKey(objTS)) {
//                        System.err.println("ERROR: Dict already contains value for " + objTS + ".");
//                        return -1;
//                    }
//                    values.put(objTS, jsonObj.getDouble(obj.toString()));
//                }
//                if (_debug) {
//                    System.err.println("jsonArr: " + jsonArr);
//                }
//            }
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

        try {
            JSONObject query = new JSONObject();
            query.put("timestamp", timestamp.getTime());
            query.put("metric", metric);
            query.put("value", value);
            JSONObject queryTags = new JSONObject();
            for ( Map.Entry entry : tags.entrySet()) {
                queryTags.put(entry.getKey().toString(),entry.getValue().toString());
            }
            query.put("tags", queryTags);
            if (_debug) {
                System.out.println("Input Query String: " + query.toString());
            }
            if (test) {
                return SUCCESS;
            }
            JSONArray jsonArr = runQuery(urlPut, query.toString());
            if (_debug) {
                System.err.println("jsonArr: " + jsonArr);
            }
            if (jsonArr == null) {
                System.err.println("ERROR: Error in processing insert to metric: " + metric);
                return -1;
            }
            return SUCCESS;

        } catch (Exception e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
    }
}

