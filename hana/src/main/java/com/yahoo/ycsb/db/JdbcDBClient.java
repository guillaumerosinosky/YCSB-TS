package com.yahoo.ycsb.db;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class JdbcDBClient extends DB implements JdbcDBClientConstants
{
	private static final String DEFAULT_PROP = "";
	private boolean initialized = false;
	private boolean doGroupBy = true;
	private Properties props;
	private Integer jdbcFetchSize;  Connection conn;
	private PreparedStatement insert = null;
	
	public void init() throws DBException 
	{
        if (initialized) 
        {
            System.err.println("Client connection already initialized.");
            return;
        }
        doGroupBy  = Boolean.parseBoolean(getProperties().getProperty("dogroupby", "true"));
        props = getProperties();
        String url = props.getProperty(CONNECTION_URL, DEFAULT_PROP);
        String user = props.getProperty(CONNECTION_USER, DEFAULT_PROP);
        String passwd = props.getProperty(CONNECTION_PASSWD, DEFAULT_PROP);
        String driver = props.getProperty(DRIVER_CLASS);

        String jdbcFetchSizeStr = props.getProperty(JDBC_FETCH_SIZE);
        if (jdbcFetchSizeStr != null) 
        {
            try 
            {
                this.jdbcFetchSize = Integer.parseInt(jdbcFetchSizeStr);
            }
            catch (NumberFormatException nfe) 
            {
                System.err.println("Invalid JDBC fetch size specified: " + jdbcFetchSizeStr);
                throw new DBException(nfe);
            }
        }

        String autoCommitStr = props.getProperty(JDBC_AUTO_COMMIT, Boolean.TRUE.toString());
        Boolean autoCommit = Boolean.parseBoolean(autoCommitStr);

        try 
        {
            if (driver != null) Class.forName(driver);            
            conn = DriverManager.getConnection(url, user, passwd);
            conn.setAutoCommit(autoCommit);
        }
        catch (ClassNotFoundException e) 
        {
            System.err.println("Error in initializing the JDBS driver: " + e);
            throw new DBException(e);
        }
        catch (SQLException e)
        {
            System.err.println("Error in database operation: " + e);
            throw new DBException(e);
        }
        catch (NumberFormatException e) 
        {
            System.err.println("Invalid value for fieldcount property. " + e);
            throw new DBException(e);
        }
        initialized = true;
    }

	@Override
    public void cleanup() throws DBException 
	{
		try
		{
			conn.close();
		}
		catch (SQLException e) 
		{
            System.err.println("Error in closing the connection. " + e);
            throw new DBException(e);
        }
	}
	
    private void generateTagString(HashMap<String, ArrayList<String>> tags, StringBuilder sb)
    {
        int counter=0;
        int tagCounter=0;
        if (tags.keySet().size() > 0) 
        {
            sb.append("(");
            for (String tag : tags.keySet())
            {
                if (tags.get(tag).size() > 0)
                {
                    sb.append("( ");
                    for (String tagValue : tags.get(tag)) 
                    {
                        sb.append(tag + " = " + "'" + tagValue + "' ");
                        sb.append("OR ");
                        counter++;
                    }
                    sb.delete(sb.lastIndexOf("OR"), sb.lastIndexOf("OR")+2);
                    sb.append(") ");
                    // If its the last one...
                    if (tagCounter < tags.keySet().size() -1) 
                    {
                        sb.append("AND ");
                    }
                    tagCounter++;
                }
            }
            sb.append(")");
        }
        if (counter > 0) sb.append(" AND ");        
    }

	@Override
	public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) 
	{
        if (metric == null || timestamp == null) return -1;        
        try 
        {
            PreparedStatement readStatement = createReadStatement(metric, tags);
            readStatement.setTimestamp(1, timestamp);
            ResultSet resultSet = readStatement.executeQuery();
            if (!resultSet.next()) 
            {
                resultSet.close();
                return -1;
            }
            resultSet.close();
            return SUCCESS;
        }
        catch (SQLException e) 
        {
            System.err.println("ERROR: Error in processing read of table " + metric + ": " + e);
            return -1;
        }		
	}
	
    private PreparedStatement createReadStatement(String tableName, HashMap<String, ArrayList<String>> tags) throws SQLException 
    {
        StringBuilder read = new StringBuilder("SELECT * FROM ");
        read.append(tableName);
        read.append(" WHERE ");
        generateTagString(tags, read);
        read.append(" ( ");
        read.append(TIMESTAMP_KEY);
        read.append(" = ");
        read.append("?);");
        PreparedStatement readStatement = conn.prepareStatement(read.toString());
        return readStatement;
    }

	@Override
	public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String, ArrayList<String>> tags, boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit) 
	{
        if (metric == null || startTs == null || endTs == null) return -1;
        try 
        {
            boolean ms = false;
            if (timeValue != 0) 
            {
                if (TimeUnit.MILLISECONDS.convert(timeValue, timeUnit) == 1) ms = true; 
                else System.err.println("WARNING: JDBC does not support granularity, defaulting to one bucket.");                
            }           
            PreparedStatement scanStatement = createScanStatement(metric, startTs, tags, avg, count, sum, ms);
            scanStatement.setTimestamp(1, startTs);
            scanStatement.setTimestamp(2, endTs);
            ResultSet resultSet = scanStatement.executeQuery();
            if (!resultSet.next()) 
            {
                resultSet.close();
                return -1;
            }
            resultSet.close();
            return SUCCESS;
        }
        catch (SQLException e) 
        {
            System.err.println("ERROR: Error in processing scan of table: " + metric + e);
            return -1;
        }		
	}
	
    private PreparedStatement createScanStatement(String tableName, Timestamp key, HashMap<String, ArrayList<String>> tags, boolean avg, boolean count, boolean sum, boolean ms) throws SQLException
    {
        String selectStr = "*";
        String groupByStr = "";
        if (avg) 
        {
            if (! ms) selectStr = "AVG(VALUE) as VALUE";            
            else 
            {
                selectStr = TIMESTAMP_KEY + ", AVG(VALUE) as VALUE";
                if (doGroupBy) groupByStr = "GROUP BY " + TIMESTAMP_KEY;                
            }
        }
        else if (count) 
        {
            if (! ms) selectStr = "COUNT(*) as VALUE";            
            else 
            {
                selectStr = TIMESTAMP_KEY + ", COUNT(*) as VALUE";
                if (doGroupBy) groupByStr = "GROUP BY " + TIMESTAMP_KEY;                
            }
        }
        else if (sum) 
        {
            if (! ms) selectStr = "SUM(VALUE) as VALUE";            
            else 
            {
                selectStr = TIMESTAMP_KEY + ", SUM(VALUE) as VALUE";
                if (doGroupBy) groupByStr = "GROUP BY " + TIMESTAMP_KEY;                
            }
        }
        
        StringBuilder select = new StringBuilder("SELECT " + selectStr + " FROM ");
        select.append(tableName);
        select.append(" WHERE ");
        generateTagString(tags, select);
        select.append(TIMESTAMP_KEY);
        select.append(" BETWEEN ? AND ? ");
        select.append(groupByStr + ";");
        PreparedStatement scanStatement = conn.prepareStatement(select.toString());
        if (this.jdbcFetchSize != null) scanStatement.setFetchSize(this.jdbcFetchSize);
        return scanStatement;
    }


	@Override
	public int insert(String metric, Timestamp timestamp, double value, HashMap<String, ByteIterator> tags) 
	{
        if (metric == null || timestamp == null) return -1;
        try 
        {
            int numFields = tags.size()+2; // +PK +VALUE
            if(insert == null)
            {
            	insert = createAndCacheInsertStatement(metric, tags, numFields);
            }
            PreparedStatement insertStatement = insert;
            insertStatement.setTimestamp(1, timestamp);
            insertStatement.setDouble(2, value);
            int index = 3;
            for (Map.Entry<String, ByteIterator> entry : tags.entrySet()) 
            {
                String tagvalue = entry.getValue().toString();
                insertStatement.setString(index++, tagvalue);
            }
            int result = insertStatement.executeUpdate();
            
            if (result == 1) return SUCCESS;
            else return -1;
        }
        catch (SQLException e) 
        {
            System.err.println("Error in processing insert to table: " + metric + e);
            return -1;
        }		
	}
	
    private PreparedStatement createAndCacheInsertStatement(String tableName, HashMap<String, ByteIterator> tags, int numFields) throws SQLException 
    {
        StringBuilder insert = new StringBuilder("INSERT INTO ");
        insert.append(tableName);
        insert.append("(" + TIMESTAMP_KEY + ",VALUE");
        for (Map.Entry<String, ByteIterator> entry : tags.entrySet()) 
        {
            insert.append("," + entry.getKey().toString());
        }
        insert.append(")");
        
        insert.append(" VALUES(?");
        for (int i = 1; i < numFields; i++) 
        {
            insert.append(",?");
        }
        insert.append(");");
        PreparedStatement insertStatement = conn.prepareStatement(insert.toString());
        return insertStatement;
    }
}