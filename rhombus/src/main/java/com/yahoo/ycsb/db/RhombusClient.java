package com.yahoo.ycsb.db;

/**
 * Created by Andreas Bader on 19.10.15.
 */

import com.google.common.collect.Maps;
import com.pardot.rhombus.CassandraConfiguration;
import com.pardot.rhombus.ConnectionManager;
import com.pardot.rhombus.Criteria;
import com.pardot.rhombus.ObjectMapper;
import com.pardot.rhombus.cobject.CKeyspaceDefinition;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;


/**
 * Rhombusclient for YCSB framework.
 * Creates its cassandra keyspace at init()
 * Takes more than one ip
 * No AVG,SUM -> Using COUNT instead
 * (No use of any granularity/resolution)
 * No OR/AND -> Filtering for Tags works very poor
 */
public class RhombusClient extends DB {
    private final int SUCCESS = 0;
    private String ip = "localhost";
    private String dataCenter = "datacenter1";
    private String keySpaceDefinitionFile = "/home/vagrant/files/rhombus_repl1.json";
    private boolean _debug = false;
    private ConnectionManager client;
    private ObjectMapper om;
    private String[] usedTags = {"TAG0","TAG1","TAG2"};
    private String alwaysSetTagName = "TAGALWAYSSET1";
    private boolean test = false;
    private boolean filterForTags = true;

    static String readFile(String path, Charset encoding)
            throws IOException
    {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }
    /**
     * Initialize any state for this DB.
     * Called once per DB instance; there is one DB instance per client thread.
     */
    @Override
    public void init() throws DBException {
        try {
            test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
            if (!getProperties().containsKey("ip") && !test) {
                throw new DBException("No ip given, abort.");
            }
            ip = getProperties().getProperty("ip", ip);
            keySpaceDefinitionFile = getProperties().getProperty("keySpacedefinitionfile", keySpaceDefinitionFile);
            dataCenter = getProperties().getProperty("datacenter", dataCenter);
            filterForTags = Boolean.parseBoolean(getProperties().getProperty("filterForTags", Boolean.toString(filterForTags)));
            if (_debug) {
                System.out.println("The following properties are given: ");
                for (String element : getProperties().stringPropertyNames()) {
                    System.out.println(element + ": " + getProperties().getProperty(element));
                }
            }
            if (_debug) {
                Logger root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                root.setLevel(Level.DEBUG);
            }
            else {
                Logger root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                root.setLevel(Level.OFF);
            }
            if (!test) {
                CassandraConfiguration config = new CassandraConfiguration();
                List contactPoints = new ArrayList();
                for (String splittedIp : ip.split(",")) {
                    contactPoints.add(splittedIp);
                }
                config.setContactPoints(contactPoints);
                config.setLocalDatacenter(dataCenter);
                this.client = new ConnectionManager(config);
                this.client.buildCluster();
                String json = "";
                try{
                    json = readFile(keySpaceDefinitionFile,Charset.forName("UTF-8"));
                }
                catch (Exception e){
                    throw new DBException("Can't open or read " + keySpaceDefinitionFile + ". "+e);
                }
                CKeyspaceDefinition keyspaceDefinition = CKeyspaceDefinition.fromJsonString(json);
                // false = do not rebuild if exist
                this.client.buildKeyspace(keyspaceDefinition, false);
                this.client.setDefaultKeyspace(keyspaceDefinition);
                this.om =  this.client.getObjectMapper();
                if (_debug) {
                    this.client.setLogCql(true);
                    this.om.setLogCql(true);
                }
                else {
                    this.client.setLogCql(false);
                    this.om.setLogCql(false);
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
        this.client.teardown();
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
        if ( tags != null && tags.keySet().size() > usedTags.length) {
            System.err.println("WARNING: More tags used than configured in Rhombus. Please adjust usedTags and rhombus.json! Or use only " + usedTags.length + " tags.");
        }
        try {
            int counter = 0;
            Criteria criteria = new Criteria();
            SortedMap values =  Maps.newTreeMap();
            if (this.filterForTags && tags.size() > 0) {
                for (String tag : tags.keySet()) {
                    for (String tagvalue : tags.get(tag)) {
                        values.put(tag, tagvalue);
                    }
                }
            }
            else {
                values.put(this.alwaysSetTagName, 1L);
            }
            criteria.setIndexKeys(values);
            criteria.setStartTimestamp(timestamp.getTime());
            criteria.setEndTimestamp(timestamp.getTime());
            if (test) {
                return SUCCESS;
            }
            List<Map<String, Object>> results = this.om.list(metric, criteria);
            // every result will have the right timestamp, we can't check that
            for (Map<String, Object> map : results) {
                counter++;
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
        if ( tags != null && tags.keySet().size() > usedTags.length) {
            System.err.println("WARNING: More tags used than configured in Rhombus. Please adjust usedTags and rhombus.json! Or use only "+usedTags.length+" tags.");
        }
        if (timeValue != 0) {
            System.err.println("WARNING: Rhombus does not support granularities.");
        }
        try {
            Criteria criteria = new Criteria();
            SortedMap values = Maps.newTreeMap();
            if (this.filterForTags && tags.size() > 0) {
                for (String tag : tags.keySet()) {
                    for (String tagvalue : tags.get(tag)) {
                        values.put(tag, tagvalue);
                    }
                }
            }
            else {
                values.put(this.alwaysSetTagName, 1L);
            }
            criteria.setStartTimestamp(startTs.getTime());
            criteria.setEndTimestamp(endTs.getTime());
            criteria.setIndexKeys(values);
            if (test) {
                return SUCCESS;
            }
            if (avg || sum || count ) {
                long result = this.om.count(metric, criteria);
                // Every Count result is okay, -1 is only dropped in catch
            }
            else {
                List<Map<String, Object>> results = this.om.list(metric, criteria);
                if (results == null || results.size() == 0) {
                    return -1;
                }
            }
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
            Map<String, Object> data = new HashMap();
            data.put("value", value);
            // new is needed, otherwise .remove does not work ... see http://stackoverflow.com/questions/6260113/unsupportedoperationexception-in-abstractlist-remove-when-operating-on-arrayli
            List<String> tagList = new ArrayList<String>(Arrays.asList(this.usedTags.clone()));
            for (Map.Entry<String, ByteIterator> tag : tags.entrySet()) {
                data.put(tag.getKey(), tag.getValue().toString());
                tagList.remove(tag.getKey());
            }
            for (String unusedTag : tagList) {
                data.put(unusedTag,"");
            }
            data.put(this.alwaysSetTagName,1L);
            Object id = om.insert(metric, data, timestamp.getTime());
            return SUCCESS;
        }
        catch (Exception e) {
            System.err.println("ERROR: Error in processing insert to metric: " + metric + e);
            e.printStackTrace();
            return -1;
        }
    }
}

