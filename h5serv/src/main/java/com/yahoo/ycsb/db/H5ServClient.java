package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 09.02.16.
 */

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.Client;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Need to use Json 20140107, newer versions drop an error "Unsupported major.minor version 52.0"

/**
 * h5serv client for YCSB framework.
 * h5serv does not support avg/sum/count
 * h5serv has not append() function or something like that
 *    -> an index must be counted to tell h5serv were to put new data
 * h5serv support strings with variable length, but these are buggy -> https://github.com/HDFGroup/h5serv/issues/77
 *    -> using a fixed length string
 * h5serv does not yet support hdf5 timestamp datatype
 *    -> using long/double
 * h5serv does not support querys with H5T_STD_U64BE or H5T_STD_U64LE due to numexpr (both are unsigned 64bit integers)
 *    -> using H5T_STD_I64LE instead (signed 64bit integer)
 */
public class H5ServClient extends DB {
    private final int SUCCESS = 0;
    URL urlDomain = null;
    URL urlDatatypes = null;
    URL urlDatasets = null;
    URL urlDatasetValue = null;
    URL urlGroups = null;
    URL urlGroupLinkDS = null; // URL for Link of Dataset
    private int tagCount;
    private String tagPrefix;
    private String phase;
    private static final String PHASE_PROPERTY = "phase"; // See Client.java
    private static final String METRICNAME_PROPERTY = "metric"; // see CoreWorkload.java
    public static final String METRICNAME_PROPERTY_DEFAULT = "usermetric"; // see CoreWorkload.java
    private static final String TAG_PREFIX_PROPERTY = "tagprefix"; // see CoreWorkload.java
    private static final String TAG_PREFIX_PROPERTY_DEFAULT = "TAG"; // see CoreWorkload.java
    private static final String TAG_COUNT_PROPERTY = "tagcount"; // see CoreWorkload.java
    private static final String TAG_COUNT_PROPERTY_DEFAULT = "3"; // see CoreWorkload.java
    private static final String TAG_VALUE_LENGTH_PROPERTY = "tagvaluelength"; // see CoreWorkload.java
    private static final String TAG_VALUE_LENGTH_PROPERTY_DEFAULT = "10"; // see CoreWorkload.java
    private String ip = "localhost";
    private String domainURL = "/";
    private String datatypesURL = "/datatypes";
    private String datasetsURL = "/datasets";
    private String groupsURL = "/groups";
    private String datatypeName = "timeseries";
    private String datasetName = "timeseriesSet";
    private int port = 5000;
    private boolean _debug = false;
    private String basedomain = "hdfgroup.org";
    private String domain = METRICNAME_PROPERTY_DEFAULT + "." + basedomain;
    private CloseableHttpClient client;
    private int retries = 3;
    private boolean test = false;
    private int stringlength = 10; // 0 = unlimited. unlimited does not work, see .https://github.com/HDFGroup/h5serv/issues/77
    private String datatypeId = "";
    int recordcount;
    int index = 0;
    List<String[]> headers;

    /**
     * Initialize any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void init() throws DBException {
        try {
            recordcount = Integer.parseInt(getProperties().getProperty(Client.RECORD_COUNT_PROPERTY, Client.DEFAULT_RECORD_COUNT));
            if (recordcount == 0)
                recordcount = Integer.MAX_VALUE;
            if (_debug) {
                System.out.println("Recordcount: "+recordcount);
            }
            if (!getProperties().containsKey(PHASE_PROPERTY)) {
                throw new DBException(String.format("No %s given, abort.",PHASE_PROPERTY));
            }
            phase = getProperties().getProperty(PHASE_PROPERTY, "");
            domain = getProperties().getProperty(METRICNAME_PROPERTY, METRICNAME_PROPERTY_DEFAULT) + "." + basedomain;
            tagCount = Integer.valueOf(getProperties().getProperty(TAG_COUNT_PROPERTY, TAG_COUNT_PROPERTY_DEFAULT));
            tagPrefix = getProperties().getProperty(TAG_PREFIX_PROPERTY, TAG_PREFIX_PROPERTY_DEFAULT);
            stringlength = Integer.valueOf(getProperties().getProperty(TAG_VALUE_LENGTH_PROPERTY, TAG_VALUE_LENGTH_PROPERTY_DEFAULT));
            test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
            if (!getProperties().containsKey("port") && ! test) {
                throw new DBException("No port given, abort.");
            }
            port = Integer.parseInt(getProperties().getProperty("port",String.valueOf(port)));
            stringlength = Integer.parseInt(getProperties().getProperty("stringlength",String.valueOf(stringlength)));
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
            RequestConfig requestConfig = RequestConfig.custom().build();
            if (!test) {
                client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();
            }
        }
        catch (Exception e) {
            throw new DBException(e);
        }

        try {
            urlDomain = new URL("http", ip, port, domainURL );
            if (_debug) {
                System.out.println("domainURL: "+urlDomain);
            }
            urlDatatypes = new URL("http", ip, port, datatypesURL );
            if (_debug) {
                System.out.println("datatypesURL: "+urlDatatypes);
            }
            urlDatasets = new URL("http", ip, port, datasetsURL );
            if (_debug) {
                System.out.println("datasetsURL: "+urlDatasets);
            }
            urlGroups = new URL("http", ip, port, groupsURL );
            if (_debug) {
                System.out.println("groupsURL: "+urlGroups);
            }
        }
        catch (MalformedURLException e) {
            throw new DBException(e);
        }
        if (_debug) {
            System.out.println("Creating new Domain in HDF5: " + domain);
        }
        headers = new ArrayList<String[]>();
        headers.add(new String[]{"host", domain});
        if (phase.equals("load")) {
            if (_debug) {
                System.out.println("Load Phase.");
            }
            // Creating domain
            JSONObject response = runQuery(urlDomain, "", headers, "put");
            if ( response == null) {
                System.out.println("ERROR: runQuery() for domain creation returned null.");
            }
            String id = response.getString("root");
            try {
                urlGroupLinkDS = new URL("http", ip, port, groupsURL + "/" + id + "/links/" + "linked_" + datasetName );
            }
            catch (MalformedURLException e) {
                e.printStackTrace();
            }
            // Creating datatype
            JSONObject query = new JSONObject();
            query.put("name", datatypeName);
            JSONObject shape = new JSONObject();
            shape.put("class", "H5S_SCALAR");
            query.put("shape", shape);
            JSONObject type = new JSONObject();
            type.put("class", "H5T_COMPOUND");
            JSONArray fields = new JSONArray();
            // YCSB_KEY
            JSONObject field = new JSONObject();
            field.put("name", "YCSB_KEY");
            JSONObject fieldtype = new JSONObject();
            // should be H5T_STD_U64BE (unsigned 64bit integer) but queries do not work then
            // queries return 500 "internal server error"
            // with "NotImplementedError: variable ``YCSB_KEY`` refers to a 64-bit unsigned integer column, not yet supported in conditions, sorry; please use regular Python selections"
            // from the sever logs
            // H5T_STD_U64LE does also not work, should be even a bit faster than H5T_STD_U64BE, See https://github.com/HDFGroup/h5serv/issues/80
            fieldtype.put("base", "H5T_STD_I64LE");
            fieldtype.put("class", "H5T_INTEGER");
            field.put("type", fieldtype);
            fields.put(field);
            // VALUE
            field = new JSONObject();
            field.put("name", "VALUE");
            fieldtype = new JSONObject();
            fieldtype.put("base", "H5T_IEEE_F64BE");
            fieldtype.put("class", "H5T_FLOAT");
            field.put("type", fieldtype);
            fields.put(field);
            // TAG0 ...TAGn
            for (int i = 0; i < tagCount; i++) {
                field = new JSONObject();
                field.put("name", tagPrefix + i);
                shape = new JSONObject();
                shape.put("class", "H5S_SIMPLE");
                shape.put("dims", new int[]{4});
                field.put("shape", shape);
                fieldtype = new JSONObject();
                fieldtype.put("class", "H5T_STRING");
                fieldtype.put("charSet", "H5T_CSET_ASCII");
                fieldtype.put("strPad", "H5T_STR_NULLTERM");
                fieldtype.put("length", 30);
                field.put("type", fieldtype);
                fields.put(field);
            }
            type.put("fields", fields);
            query.put("type", type);
            if (_debug) {
                System.out.println("Creating datatype 'timeseries' with the following query: " + query.toString());
            }
            response = runQuery(urlDatatypes, query.toString(), headers, "post");
            if (response != null) {
                if (response.has("id")) {
                    datatypeId = response.getString("id");
                }
                else {
                    System.out.println("ERROR: Response of datatype creation query has no 'id'.");
                }
            }
            if (_debug) {
                System.out.println("Datatype ID: "+ datatypeId);
            }
            // Creating dataset
            query = new JSONObject();
            query.put("shape",recordcount*2); // some space for INSERTs in RUN Phase
            query.put("type", datatypeId);
            query.put("maxdims",0);
            if (_debug) {
                System.out.println("Creating dataset with the following query: " + query.toString());
            }
            response = runQuery(urlDatasets, query.toString(), headers, "post");
            if (response != null) {
                if (response.has("id")) {
                    if (_debug) {
                        System.out.println("Creating link for " + "linked_" + datasetName + " with ID: " + response.getString("id") + " and URL: " + urlGroupLinkDS + ".");
                    }
                    try {
                        urlDatasetValue = new URL("http", ip, port, datasetsURL + "/" + response.getString("id") + "/value" );
                    }
                    catch (MalformedURLException e) {
                        System.out.println("ERROR: Response of dataset creation query 'id' could not be transformed into a URL. Value: '" + response.getString("id") + "'.");
                    }
                    // Creating link for dataset -> avoiding "anonymous dataset" (otherwise querys do not work)
                    query = new JSONObject();
                    query.put("id",response.getString("id"));
                    JSONObject response2 = runQuery(urlGroupLinkDS, query.toString() , headers, "put");
                    if (response2 == null) {
                            System.out.println("ERROR: Response of group link creation for " + "linked_" + datasetName + " query is null.");
                    }
                }
                else {
                    System.out.println("ERROR: Response of dataset creation query has no 'id'.");
                }
            }
            if (_debug) {
                System.out.println("Dataset Value URL: "+ urlDatasetValue);
            }
        }
        else {
            if (_debug) {
                System.out.println("Run Phase.");
            }
            index=recordcount; // also possible to search for the last index in HDF5
            JSONObject response = runQuery(urlDatasets, "", headers, "get");
            if (response != null) {
                if (response.has("datasets")) {
                    if (response.getJSONArray("datasets").length() > 0) {
                        if (response.getJSONArray("datasets").length() != 1) {
                            System.out.println("WARNING: Response of dataset get query has a 'datasets' array with length != 1, using first one.");
                        }
                        try {
                            urlDatasetValue = new URL("http", ip, port, datasetsURL + "/" + response.getJSONArray("datasets").get(0) + "/value");
                        }
                        catch (MalformedURLException e) {
                            System.out.println("ERROR: Response of dataset get query id of dataset could not be transformed into a URL. Value: '" + response.getJSONArray("datasets").get(0) + "'.");
                        }
                    }
                    else {
                        System.out.println("ERROR: Response of dataset get query has a 'datasets' array with length 0, this should not happen.");
                    }
                }
                else {
                    System.out.println("ERROR: Response of dataset get query has no 'datasets'.");
                }
            }
            if (_debug) {
                System.out.println("Dataset Value URL: "+ urlDatasetValue);
            }
        }
    }

    private JSONObject runQuery(URL url, String queryStr, List<String[]> headers, String queryMethod) {
        JSONObject jsonObj = new JSONObject();
        HttpResponse response = null;
        try {
            HttpEntityEnclosingRequestBase method = null;
            if (queryMethod.equals("") || queryMethod.toLowerCase().equals("post")) {
                method = new HttpPost(url.toString());
            }
            else if (queryMethod.toLowerCase().equals("put")) {
                method = new HttpPut(url.toString());
            }
            else if (queryMethod.toLowerCase().equals("get")) {
                method = new HttpGetWithEntity(url.toString());
            }
            StringEntity requestEntity = new StringEntity(queryStr, ContentType.APPLICATION_JSON);
            method.setEntity(requestEntity);
            if (!headers.isEmpty()) {
                for (String[] strArr : headers) {
                    if (strArr.length == 2) {
                        method.addHeader(strArr[0], strArr[1]);
                    }
                    else {
                        System.err.print("ERROR: Array in header list does not have length 2. Header not set.");
                    }
                }
            }
            method.addHeader("accept", "application/json");
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
                    response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_CREATED ||
                    response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_NO_CONTENT  ||
                    response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_MOVED_PERM){
                if (response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_MOVED_PERM) {
                    System.err.println("WARNING: Query returned 301, that means 'API call has migrated or should be forwarded to another server'");
                }
                if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_NO_CONTENT){
                    // Maybe also not HTTP_MOVED_PERM? Can't Test it right now
                    if (response.getEntity().getContentLength() != 0) {
                        // This if is required, as POST Value does not has an response, only 200 as HTTP Code
                        BufferedReader bis = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                        StringBuilder builder = new StringBuilder();
                        String line;
                        while ((line = bis.readLine()) != null) {
                            builder.append(line);
                        }
                        jsonObj = new JSONObject(builder.toString());
                    }

                }
                EntityUtils.consumeQuietly(response.getEntity());
                method.releaseConnection();
           }
            else {
                System.err.println("WARNING: Query returned status code "+response.getStatusLine().getStatusCode()+ " with Error: '" + response.getStatusLine().getReasonPhrase() + "'.");
                return null;
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
        int counter = 0;
        long timestampLong = timestamp.getTime();

        // every part of the query must be url encoded
        // even (,) and & must be encoded correctly
        // both, + or %20 are okay as spaces
        // ?query= must not be encoded
        StringBuilder query = new StringBuilder("(YCSB_KEY == ");
        query.append(timestampLong);
        query.append(")");
        for ( Map.Entry entry : tags.entrySet()) {
            ArrayList<String> arrList = (ArrayList<String>)  entry.getValue();
            if (arrList.size() > 1) {
                // Avoid dobule brackets
                query.append(" & (");
            }
            else {
                query.append(" & ");
            }
            for (String tagValue : arrList) {
                query.append("(" + entry.getKey().toString() + " == '" + tagValue + "') | ");
            }
            int length = query.length();
            query.delete(length-3,length);
            if (arrList.size() > 1) {
                // Avoid dobule brackets
                query.append(")");
            }
        }

        URL queryURL;
        try {
            // ?query= must not be encoded
            queryURL = new URL(urlDatasetValue.getProtocol(), urlDatasetValue.getHost(), urlDatasetValue.getPort(), urlDatasetValue.getPath() + "?query=" + URLEncoder.encode(query.toString(), "UTF-8"));
            // The URI variant is probably the better solution (technically) but does not encoded as required (e.g., does not encode (,),&)
            // Other solutions can not encode things like " == " directly
            // It must be expected that tag names or values need encoding in a non-default setup
            // so automated encoding is required
            // See http://stackoverflow.com/questions/10786042/java-url-encoding-of-query-string-parameters
            // URI variant (change queryURL to URI) -> does not work, does not encode (,),&,...:
            // //queryURL = new URI(urlDatasetValue.getProtocol(), null, urlDatasetValue.getHost(), urlDatasetValue.getPort(), urlDatasetValue.getPath(), "query=" + query.toString(), null);
        }
        catch (UnsupportedEncodingException e) {
            System.err.println("ERROR: Can not encode paramters for read query, Error: '"+ e.toString() +"'.");
            return -1;
        }
        catch (MalformedURLException e) {
            System.err.println("ERROR: Can not create URL for read query, Error: '"+ e.toString() +"'.");
            return -1;
        }
        if (_debug) {
            System.out.println("Read Query String: " + queryURL.toString());
        }
        if (test) {
            return SUCCESS;
        }
        JSONObject response = null;
        response = runQuery(queryURL, "", headers, "get");
        if (_debug) {
            System.err.println("Respone: " + response);
        }
        if (response.has("value")) {
            JSONArray jsonArr = response.getJSONArray("value");
            if (jsonArr.length() == 0) {
                // is allowed!
                return -1;
            }
            for (int i = 0; i < jsonArr.length(); i++) {
                if (jsonArr.isNull(i)) {
                    System.err.println("ERROR: jsonArr Index " + i + " is null.");
                }
                else {
                    JSONArray jsonArr2 = jsonArr.getJSONArray(0);
                    if (jsonArr2.length() == 0) {
                        // is allowed!
                        System.err.println("ERROR: jsonArr2 has length 0.");
                    }
                    if (jsonArr2.isNull(0)) {
                        System.err.println("ERROR: jsonArr2 Index 0 is null.");
                    }
                    if (jsonArr2.getLong(0) == timestampLong) {
                        counter++;
                    }

                }
            }
        }
        else {
            System.err.println("ERROR: Received response without 'value' for '" + queryURL.toString() + "' with headers '" + headers + "'.");
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
        int counter = 0;
        if (timeValue != 0) {
            if (TimeUnit.MILLISECONDS.convert(timeValue, timeUnit) != 1) {
                System.err.println("WARNING: h5serv does not support granularity, defaulting to one bucket.");
            }
        }
        // every part of the query must be url encoded
        // even (,) and & must be encoded correctly
        // both, + or %20 are okay as spaces
        // ?query= must not be encoded
        StringBuilder query = new StringBuilder("(YCSB_KEY >= ");
        query.append(startTs.getTime());
        query.append(") & (YCSB_KEY <= ");
        query.append(endTs.getTime());
        query.append(")");
        for ( Map.Entry entry : tags.entrySet()) {
            ArrayList<String> arrList = (ArrayList<String>)  entry.getValue();
            if (arrList.size() > 1) {
                // Avoid dobule brackets
                query.append(" & (");
            }
            else {
                query.append(" & ");
            }
            for (String tagValue : arrList) {
                query.append("(" + entry.getKey().toString() + " == '" + tagValue + "') | ");
            }
            int length = query.length();
            query.delete(length-3,length);
            if (arrList.size() > 1) {
                // Avoid dobule brackets
                query.append(")");
            }
        }

        URL queryURL;
        try {
            // ?query= must not be encoded
            queryURL = new URL(urlDatasetValue.getProtocol(), urlDatasetValue.getHost(), urlDatasetValue.getPort(), urlDatasetValue.getPath() + "?query=" + URLEncoder.encode(query.toString(), "UTF-8"));
            // The URI variant is probably the better solution (technically) but does not encoded as required (e.g., does not encode (,),&)
            // Other solutions can not encode things like " == " directly
            // It must be expected that tag names or values need encoding in a non-default setup
            // so automated encoding is required
            // See http://stackoverflow.com/questions/10786042/java-url-encoding-of-query-string-parameters
            // URI variant (change queryURL to URI) -> does not work, does not encode (,),&,...:
            // //queryURL = new URI(urlDatasetValue.getProtocol(), null, urlDatasetValue.getHost(), urlDatasetValue.getPort(), urlDatasetValue.getPath(), "query=" + query.toString(), null);
        }
        catch (UnsupportedEncodingException e) {
            System.err.println("ERROR: Can not encode paramters for scan/avg/sum/count query, Error: '"+ e.toString() +"'.");
            return -1;
        }
        catch (MalformedURLException e) {
            System.err.println("ERROR: Can not create URL for scan/avg/sum/count query, Error: '"+ e.toString() +"'.");
            return -1;
        }
        if (_debug) {
            System.out.println("Read Query String: " + queryURL.toString());
        }
        if (test) {
            return SUCCESS;
        }
        JSONObject response = null;
        response = runQuery(queryURL, "", headers, "get");
        if (_debug) {
            System.err.println("Respone: " + response);
        }
        if (response.has("value")) {
            JSONArray jsonArr = response.getJSONArray("value");
            if (jsonArr.length() == 0) {
                // is allowed!
                return -1;
            }
            for (int i = 0; i < jsonArr.length(); i++) {
                if (jsonArr.isNull(i) || jsonArr.getJSONArray(0).length() == 0) {
                    System.err.println("ERROR: jsonArr at Index " + i + " is null or has length null.");
                }
            }
        }
        else {
            System.err.println("ERROR: Received response without 'value' for '" + queryURL.toString() + "' with headers '" + headers + "'.");
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
        if (phase.equals("run") && index > recordcount*2) {
            System.err.println("ERROR: index is greater than (recordcount*2)-1, which is the maximum amount possible. Increase hdf5 shape, lessen INSERTs in RUN Phase or implement PUT Shape for increasing dimension (http://h5serv.readthedocs.org/en/latest/DatasetOps/PUT_DatasetShape.html).");
            return -1;
        }
        try {
            JSONObject query = new JSONObject();
            query.put("start",index);
            query.put("stop",index+1);
            JSONArray valueArr = new JSONArray();
            valueArr.put(timestamp.getTime());
            valueArr.put(value);
            for ( Map.Entry entry : tags.entrySet()) {
                valueArr.put(entry.getValue().toString());
            }
            query.put("value",valueArr);
            if (_debug) {
                System.out.println("Input Query String: " + query.toString());
            }
            if (test) {
                return SUCCESS;
            }
            JSONObject jsonObj = runQuery(urlDatasetValue, query.toString(), headers, "put");
            if (_debug) {
                System.err.println("jsonArr: " + jsonObj);
            }
            if (jsonObj == null) {
                System.err.println("ERROR: Error in processing insert to metric: " + metric + " in " + urlDatasetValue);
                return -1;
            }
            index++;
            return SUCCESS;

        } catch (Exception e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + " in " + urlDatasetValue + " "+ e);
            e.printStackTrace();
            return -1;
        }
    }
}

