package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 20.10.15.
 */

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
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
import java.util.concurrent.TimeUnit;

// Need to use Json 20140107, newer versions drop an error "Unsupported major.minor version 52.0"

/**
 * Blueflood client for YCSB framework.
 * Blueflood has no Tags (see: https://github.com/rackerlabs/blueflood/wiki/FAQ)
 * SUM ist not available (see: https://github.com/rackerlabs/blueflood/wiki/10minuteguide#send-numeric-metrics), using MIN instead
 * Blueflood only supports the following granularities: FULL,MIN5,MIN60,MIN240,MIN1440
 * This means that we can't use one bucket over an interval, biggest bucket is Min1440. Full = Ingested Resolution
 */
public class BluefloodClient extends DB {
    private final int SUCCESS = 0;
    URL urlQuery = null;
    URL urlIngest = null;
    private String ip = "localhost";
    private String queryURL = "/v2.0/%s/views";
    private String ingestURL = "/v2.0/%s/ingest";
    private String tenantId = "usermetric";
    private int ingestPort = 19000;
    private int queryPort = 19001;
    private boolean _debug = false;
    private CloseableHttpClient client;
    private int ttl = 60*60*24*365; // 1 Year TTL
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
            ingestPort = Integer.parseInt(getProperties().getProperty("ingestPort", String.valueOf(ingestPort)));
            queryPort = Integer.parseInt(getProperties().getProperty("queryPort", String.valueOf(queryPort)));
            if (!getProperties().containsKey("ip") && ! test) {
                throw new DBException("No ip given, abort.");
            }
            ip = getProperties().getProperty("ip", ip);
            ttl = Integer.parseInt(getProperties().getProperty("ttl", String.valueOf(ttl)));
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
            }
            RequestConfig requestConfig = RequestConfig.custom().build();
            if (!test) {
                client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

        try {
            urlQuery = new URL("http", ip, queryPort, String.format(queryURL, tenantId));
            if (_debug) {
                System.out.println("URL: "+urlQuery);
            }
            urlIngest = new URL("http", ip, ingestPort, String.format(ingestURL, tenantId));
            if (_debug) {
                System.out.println("URL: "+ urlIngest);
            }
       }
        catch (MalformedURLException e) {
            throw new DBException(e);
        }
    }

    private JSONObject runQuery(URL url, String queryStr) {
        JSONObject jsonObj = new JSONObject();
        HttpResponse response = null;
        try {
            HttpRequestBase method=null;
            if (queryStr != null){
                HttpPost postMethod = new HttpPost(url.toString());
                StringEntity requestEntity = new StringEntity(queryStr, ContentType.APPLICATION_JSON);
                postMethod.setEntity(requestEntity);
                postMethod.addHeader("accept", "application/json");
                method=postMethod;
            }
            else {
                HttpGet getMethod = new HttpGet(url.toString());
                getMethod.addHeader("accept", "application/json");
                method=getMethod;
            }

            int tries = retries + 1;
            while (true)
            {
                tries--;
                try
                {
                    response = client.execute(method);
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
                        method.releaseConnection();
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
                if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_NO_CONTENT && response.getEntity().getContentLength() > 0){
                    // Maybe also not HTTP_MOVED_PERM? Can't Test it right now
                    BufferedReader bis = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = bis.readLine()) != null) {
                        builder.append(line);
                    }
                    jsonObj = new JSONObject(builder.toString());
                }
                EntityUtils.consumeQuietly(response.getEntity());
                method.releaseConnection();
           }
        } catch (Exception e) {
            System.err.println("ERROR: Errror while trying to query " + url.toString() + " for '" + queryStr + "'.");
            e.printStackTrace();
            if (response != null) {
                EntityUtils.consumeQuietly(response.getEntity());
            }
            return null;
        }
        return jsonObj;
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
        // Otherwise you get "paramter 'to' must be greater than 'from'"
        // We may get more than that, but we just take the right one
        // There could also be more of them, so count
        try {
            int counter = 0;
            long timestampLong = timestamp.getTime();
            String urlStr = String.format("%s/%s?from=%s&to=%s&resolution=FULL", urlQuery.toString(), metric, timestampLong, timestampLong+1);
            URL newQueryURL = new URL(urlStr);
            if (_debug) {
                System.out.println("QueryURL: " + newQueryURL.toString());
            }
            JSONObject jsonObj = runQuery(newQueryURL, null);
            if (_debug) {
                System.out.println("Answer: " + jsonObj);
            }
            if (jsonObj.has("values")) {
                JSONArray jsonArr = jsonObj.getJSONArray("values");
                if (jsonArr.length() > 1) {
                    System.err.println("WARNING: More than 1 value found for READ.");
                }
                if (jsonArr.length() > 0) {
                    for (int i = 0; i < jsonArr.length(); i++) {
                        if (jsonArr.getJSONObject(i).has("timestamp") && jsonArr.getJSONObject(i).getLong("timestamp") == timestampLong) {
                            counter++;
                        }
                    }
                }
            }
            if (counter == 0){
                System.err.println("ERROR: Found no values for metric: " + metric + " for timestamp: " + timestamp + ".");
                return -1;
            }
            else if (counter > 1){
                System.err.println("ERROR: Found more than one value for metric: " + metric + " for timestamp: " + timestamp + ".");
            }
        }
        catch (MalformedURLException e) {
            System.err.println("ERROR: a malformed URL was generated.");
            return -1;
        }
        catch (Exception e) {
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
        String urlAppendix="";
        long granularity = TimeUnit.MILLISECONDS.convert(timeValue, timeUnit);
        if (granularity == TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES) && ( avg || sum || count) ) {
            urlAppendix="&resolution=MIN5";
        }
        else if (granularity == TimeUnit.MILLISECONDS.convert(60, TimeUnit.MINUTES) && ( avg || sum || count) ) {
            urlAppendix="&resolution=MIN60";
        }
        else if (granularity == TimeUnit.MILLISECONDS.convert(240, TimeUnit.MINUTES) && ( avg || sum || count) ) {
            urlAppendix="&resolution=MIN240";
        }
        else if ((granularity == TimeUnit.MILLISECONDS.convert(1440, TimeUnit.MINUTES) || timeValue == 0) && (avg || sum || count)) {
            urlAppendix="&resolution=MIN1440";
        }
        else {
            if (( avg || sum || count)) {
                // Only when timeValue != 0, but needs no if here
                System.err.print("WARNING: Blueflood only supports 5, 60, 240, 1440 as granularity, using full granularity, which is the granularity that was used to ingest data.");
                urlAppendix = "&resolution=FULL";
            }
            else {
                urlAppendix="&resolution=FULL";
            }
        }
        if (avg) {
            urlAppendix+="&select=average";
        }
        else if (count) {
            urlAppendix+="&select=numPoints";
        }
        else if (sum) {
            urlAppendix+="&select=min";
        }
        try {
            int counter = 0;
            String urlStr = String.format("%s/%s?from=%s&to=%s%s", urlQuery.toString(), metric, startTs.getTime(), endTs.getTime(),urlAppendix);
            URL newQueryURL = new URL(urlStr);
            if (_debug) {
                System.out.println("QueryURL: " + newQueryURL.toString());
            }
            JSONObject jsonObj = runQuery(newQueryURL, null);
            if (_debug) {
                System.out.println("Answer: " + jsonObj);
            }
            if (jsonObj.has("values")) {
                JSONArray jsonArr = jsonObj.getJSONArray("values");
                if (jsonArr.length() < 1) {
                   return -1;
                }
            }
            else {
                return -1;
            }
            if (! jsonObj.has("metadata")) {
                return -1;
            }
            // Could check further here, but costs to much time, we expect the db to work right
            // And since we don't filter for tags (not supported), there is no possibility that we have an empty answer
        }
        catch (MalformedURLException e) {
            System.err.println("ERROR: a malformed URL was generated.");
            return -1;
        }
        catch (Exception e) {
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
            JSONObject query = new JSONObject();
            query.put("collectionTime", timestamp.getTime());
            query.put("ttlInSeconds", this.ttl);
            query.put("metricValue", value);
            query.put("metricName", metric);
            JSONArray jsonArr = new JSONArray();
            jsonArr.put(query);
            if (_debug) {
                System.out.println("Input Query String: " + query.toString());
                System.out.println("Querying URL: " + urlIngest.toString());
            }
            if (test) {
                return SUCCESS;
            }
            JSONObject jsonObj = runQuery(urlIngest, jsonArr.toString());
            if (_debug) {
                System.out.println("Answer: " + jsonObj);
            }
            if (jsonObj == null) {
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

