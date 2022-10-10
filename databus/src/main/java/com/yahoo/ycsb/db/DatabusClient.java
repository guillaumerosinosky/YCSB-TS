package com.yahoo.ycsb.db;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * Databus client for YCSB-TS framework.<br>
 * Time Series data will be stored in relational tables instead of relational
 * time series tables. In time series tables filtering is just possible on the
 * columns timestamp and value.<br>
 * Restrictions:<br>
 * Timestamps are stored in millisecond precision. Functions count and sum are
 * not supported - for those avg will be used.
 * 
 * @author Rene Trefft
 */
public class DatabusClient extends DB {

	private boolean test = false;
	private final boolean _DEBUG = false;

	private final int SUCCESS = 0;

	private HttpClient httpClient;

	/** HTTP API for creating DB with table(s) */
	private URI createDbAndTablesApiUri;

	/** HTTP API for inserting data */
	private URI insertDataApiUri;

	/** HTTP API */
	private URI apiURI;

	/** HTTP context */
	private HttpClientContext httpContext;

	/**
	 * Tables (Metrics) which must be created. All current available workloads
	 * are using this metric. If new metric names will be added or existing ones
	 * modified, this array must be adapted appropriately.
	 */
	private String[] METRIC_NAMES = { "usermetric" };

	/**
	 * Tag columns which must be defined in the tables. All current available
	 * workloads are using these tag names. If new tag names will be added or
	 * existing ones modified, this array must be adapted appropriately.
	 */
	private String[] TAG_NAMES = { "TAG0", "TAG1", "TAG2" };

	/**
	 * For storing tables in.
	 */
	private String DATABASE_NAME = "ycsb_db";

	private TableType tableType;

	@Override
	public void init() throws DBException {

		test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));

		if (!getProperties().containsKey("ip") && !test) {
			throw new DBException("No ip given, abort.");
		}

		if (!getProperties().containsKey("port") && !test) {
			throw new DBException("No port given, abort.");
		}

		if (!getProperties().containsKey("user") && !test) {
			throw new DBException("No user name given, abort.");
		}

		if (!getProperties().containsKey("apiKey") && !test) {
			throw new DBException("No API key given, abort.");
		}

		if ((!getProperties().containsKey("tableType")
				|| !TableType.isFriendlyName(getProperties().getProperty("tableType"))) && !test) {
			throw new DBException("No or not invalid table type given, abort.");
		}

		String ip = getProperties().getProperty("ip", "localhost");
		// Default port in prod config: 8080
		int port = Integer.parseInt(getProperties().getProperty("port", "8080"));
		String user = getProperties().getProperty("user", "admin");
		String apiKey = getProperties().getProperty("apiKey", "adminregkey");
		tableType = TableType.getEnumOfFriendlyName(getProperties().getProperty("tableType"));

		if (_DEBUG) {
			System.out.println("The following properties are given: ");
			for (String element : getProperties().stringPropertyNames()) {
				System.out.println(element + ": " + getProperties().getProperty(element));
			}
		}

		if (!test) {

			try {

				apiURI = new URI("http", null, ip, port, "/api/", null, null);
				createDbAndTablesApiUri = new URI("http", null, ip, port, "/api/registerV1", null, null);
				insertDataApiUri = new URI("http", null, ip, port, "/api/postdataV1", null, null);

				RequestConfig globalConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build();

				HttpHost httpHost = new HttpHost(ip, port);

				CredentialsProvider credsProvider = new BasicCredentialsProvider();
				credsProvider.setCredentials(new AuthScope(httpHost), new UsernamePasswordCredentials(user, apiKey));

				httpClient = HttpClients.custom().setDefaultRequestConfig(globalConfig)
						.setDefaultCredentialsProvider(credsProvider).build();

				httpContext = HttpClientContext.create();
				AuthCache authCache = new BasicAuthCache();
				BasicScheme basicAuth = new BasicScheme();
				authCache.put(httpHost, basicAuth);
				httpContext.setAuthCache(authCache);

				createDbAndTables(DATABASE_NAME, METRIC_NAMES, TAG_NAMES);

			} catch (Exception e) {
				throw new DBException(e);
			}

		}

	}

	@Override
	public void cleanup() throws DBException {
	}

	/**
	 * 
	 * Creates database with table(s) for storing time series data.
	 * 
	 * @param database
	 *            to create tables in
	 * @param tables
	 * @param tagNames
	 *            - additional columns in the tables beside timestamp and value
	 * @throws IOException
	 * @throws JSONException
	 * @throws ClientProtocolException
	 */
	private void createDbAndTables(String database, String[] tables, String[] tagNames)
			throws ClientProtocolException, JSONException, IOException {

		JSONObject timestampColumn = new JSONObject().put("name", "time").put("dataType", "BigInteger")
				.put("isPrimaryKey", true).put("isIndex", true);
		JSONObject valueColumn = new JSONObject().put("name", "value").put("dataType", "BigDecimal");

		JSONArray columns = new JSONArray().put(timestampColumn).put(valueColumn);

		if (tableType != TableType.TIME_SERIES) {
			for (String tagName : tagNames) {
				columns.put(new JSONObject().put("name", tagName).put("dataType", "String").put("isIndex", true));
			}
		}

		JSONObject createTimeSeriesTableRequest = new JSONObject().put("datasetType", tableType.name())
				.put("schema", database).put("createschema", true).put("columns", columns);

		// for each table: add table name to POST request and send it to the
		// HTTP API
		for (String table : tables) {
			createTimeSeriesTableRequest.put("modelName", table);
			String responseStr = doPost(createDbAndTablesApiUri, createTimeSeriesTableRequest.toString());
			if (_DEBUG) {
				System.out.println("Create DB and Tables Request:\n" + createTimeSeriesTableRequest
						+ "\nCreate DB and Tables Response:\n" + responseStr);
			}
		}

	}

	/**
	 * 
	 * @param targetURI
	 *            where POST request should be sent to
	 * @param entity
	 * @return Response to the request.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	private String doPost(URI targetURI, HttpEntity entity) throws ClientProtocolException, IOException {
		HttpPost postRequest = new HttpPost(targetURI);
		postRequest.setEntity(entity);
		return execRequest(postRequest);
	}

	/**
	 * 
	 * @param targetURI
	 *            where POST request should be sent to
	 * @param str
	 *            - String that should be sent with the request
	 * @return Response to the request.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	private String doPost(URI targetURI, String str) throws ClientProtocolException, IOException {
		return doPost(targetURI, new StringEntity(str, StandardCharsets.UTF_8));
	}

	/**
	 * 
	 * @param targetURI
	 *            where GET request should be sent to
	 * @return Response to the request.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	private String doGet(URI targetURI) throws ClientProtocolException, IOException {
		HttpGet getRequest = new HttpGet(targetURI);
		return execRequest(getRequest);
	}

	/**
	 * 
	 * @param request
	 * @return
	 * @throws ParseException
	 * @throws IOException
	 */
	private String execRequest(HttpUriRequest request) throws ParseException, IOException {
		HttpResponse response = httpClient.execute(request, httpContext);
		String responseStr = EntityUtils.toString(response.getEntity());
		return responseStr;
	}

	@Override
	public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) {

		if (metric == null || metric.isEmpty() || timestamp == null) {
			return -1;
		}

		StringBuilder query = null;

		switch (tableType) {

		case RELATIONAL_TABLE:
			// insert "columnselect/value/" at the begin to select only value
			// column
			query = new StringBuilder("getdataV1/select+t+from+");
			query.append(metric);
			query.append("+as+t+where+t.time=");
			query.append(timestamp.getTime());

			for (Map.Entry<String, ArrayList<String>> tag : tags.entrySet()) {
				// if no values are stored for the current tag, we can ignore
				// the tag completely
				if (!tag.getValue().isEmpty()) {
					query.append("+and+%28");
					for (String tagValue : tag.getValue()) {
						query.append("t.");
						query.append(tag.getKey());
						query.append("=%22");
						query.append(tagValue);
						query.append("%22+or+");
					}
					// delete "+or+" after the last value of the current tag
					query.delete(query.length() - 4, query.length());
					query.append("%29");
				}
			}
			break;
		case TIME_SERIES:
		case RELATIONAL_TIME_SERIES:
			// insert "columnselect/value/" at the begin to select only value
			// column
			query = new StringBuilder("rawdataV1/");
			query.append(metric);
			query.append('/');
			query.append(timestamp.getTime());
			query.append('/');
			query.append(timestamp.getTime());
			break;
		}

		// append the query to the existing API URI
		URI readURI = apiURI.resolve(query.toString());

		if (test) {
			return SUCCESS;
		}

		try {

			String responseStr = doGet(readURI);

			if (_DEBUG) {
				System.out.println("Read URL:\n" + readURI + "\nRead Response:\n" + responseStr);
			}

			JSONObject responseObj = new JSONObject(responseStr);
			JSONArray valuesArr = (JSONArray) responseObj.get("data");

			if (valuesArr.length() == 0) {
				System.err.println("ERROR: No value found for metric " + metric + ", timestamp " + timestamp.toString()
						+ " and tags " + tags.toString() + ".");
				return -1;
			} else if (valuesArr.length() > 1) {
				System.out.println("Found more than one value for metric " + metric + ", timestamp "
						+ timestamp.toString() + " and tags " + tags.toString() + ".");
				return -1;
			} else {
				if (_DEBUG) {
					System.out.println("Found value " + valuesArr.getJSONObject(0).getDouble("value") + " for metric "
							+ metric + ", timestamp " + timestamp.toString() + " and tags " + tags.toString() + ".");
				}
			}

		} catch (Exception exc) {
			exc.printStackTrace();
			return -1;
		}

		return SUCCESS;

	}

	@Override
	public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String, ArrayList<String>> tags,
			boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit) {

		if (metric == null || metric.isEmpty() || startTs == null || endTs == null) {
			return -1;
		}

		StringBuilder query = new StringBuilder();

		// only avg is supported => avg also for count and sum
		if (avg || count || sum) {

			query.append("timeaverageV2/");

			switch (timeUnit) {

			case MILLISECONDS:
				query.append(timeValue);
				break;
			default:
				// time unit not supported => convert to whole milliseconds,
				// precision can be lost
				query.append(TimeUnit.MILLISECONDS.convert(timeValue, timeUnit));
				break;
			}

			query.append("/0");

		}

		switch (tableType) {

		case RELATIONAL_TABLE:

			query.append("getdataV1/select+t+from+");
			query.append(metric);
			query.append("+as+t+where+t.time%3E=");
			query.append(startTs.getTime());
			query.append("+and+t.time%3C=");
			query.append(endTs.getTime());

			for (Map.Entry<String, ArrayList<String>> tag : tags.entrySet()) {
				// if no values are stored for the current tag, we can ignore
				// the tag completely
				if (!tag.getValue().isEmpty()) {
					query.append("+and+%28");
					for (String tagValue : tag.getValue()) {
						query.append("t.");
						query.append(tag.getKey());
						query.append("=%22");
						query.append(tagValue);
						query.append("%22+or+");
					}
					// delete "+or+" after the last value of the current tag
					query.delete(query.length() - 4, query.length());
					query.append("%29");
				}
			}

			query.append('/');
			query.append(startTs.getTime());
			query.append('/');
			query.append(endTs.getTime());

			break;

		case TIME_SERIES:
		case RELATIONAL_TIME_SERIES:

			query.append("rawdataV1/");
			query.append(metric);
			query.append('/');
			query.append(startTs.getTime());
			query.append('/');
			query.append(endTs.getTime());

			break;
		}

		// append the query to the existing API URI
		URI readURI = apiURI.resolve(query.toString());

		if (test) {
			return SUCCESS;
		}

		try {

			String responseStr = doGet(readURI);

			if (_DEBUG) {
				System.out.println("Scan URL:\n" + readURI + "\nScan Response:\n" + responseStr);
			}

			if (responseStr.isEmpty() || ((JSONArray) (new JSONObject(responseStr)).get("data")).length() == 0) {
				// No values is possible (not an error)
				if (_DEBUG) {
					System.out.println("No value(s) found for metric " + metric + ", start timestamp "
							+ startTs.toString() + ", end timestamp " + endTs.toString() + ", avg=" + avg + ", count="
							+ count + ", sum=" + sum + ", time value " + timeValue + ", time unit " + timeUnit
							+ " and tags " + tags.toString() + ".");
				}
				return -1;

			} else {

				if (_DEBUG) {
					System.out.println("Found value(s) for metric " + metric + ", start timestamp " + startTs.toString()
							+ ", end timestamp " + endTs.toString() + ", avg=" + avg + ", count=" + count + ", sum="
							+ sum + ", time value " + timeValue + ", time unit " + timeUnit + " and tags "
							+ tags.toString() + ".");
				}

			}

		} catch (Exception exc) {
			exc.printStackTrace();
			return -1;
		}

		return SUCCESS;

	}

	@Override
	public int insert(String metric, Timestamp timestamp, double value, HashMap<String, ByteIterator> tags) {

		if (metric == null || metric.isEmpty() || timestamp == null) {
			return -1;
		}

		if (test) {
			return SUCCESS;
		}

		JSONObject dataRecord = new JSONObject().put("_tableName", metric).put("time", timestamp.getTime()).put("value",
				value);

		// tags can't be stored in a time series table - just time and value
		if (tableType != TableType.TIME_SERIES) {
			for (String tagName : TAG_NAMES) {
				// We assume that all records to insert containing tags with
				// each tag name specified above. If this is not the case in the
				// future, we can't omit the tag completely in the insert
				// request - values for each column of the table must be
				// defined. Therefore, we have store for example an empty string
				// as value for a missing tag. This approach doesn't work in
				// a relational time series table (request gives a server
				// error), so we have to find another solution in this case.
				dataRecord.put(tagName, tags.get(tagName).toString());
			}
		}

		JSONObject insertRequest = new JSONObject().put("_dataset", new JSONArray().put(dataRecord));

		if (_DEBUG) {
			System.out.println("Insert Request:\n" + insertRequest.toString());
		}

		try {

			doPost(insertDataApiUri, insertRequest.toString());

			if (_DEBUG) {
				System.out.println("Inserted metric " + metric + ", timestamp " + timestamp.getTime() + ", value "
						+ value + " and tags " + tags + ".");
			}

		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		}

		return SUCCESS;

	}

	private enum TableType {

		RELATIONAL_TABLE("rtable"), RELATIONAL_TIME_SERIES("rtstable"), TIME_SERIES("tstable");

		private final String FRIENDLY_NAME;

		private TableType(String friendlyName) {
			this.FRIENDLY_NAME = friendlyName;
		}

		public static boolean isFriendlyName(String friendlyName) {
			for (TableType type : TableType.values()) {
				if (type.getFriendlyName().equals(friendlyName)) {
					return true;
				}
			}
			return false;
		}

		public static TableType getEnumOfFriendlyName(String friendlyName) {
			TableType[] types = TableType.values();
			for (TableType type : types) {
				if (type.getFriendlyName().equals(friendlyName)) {
					return type;
				}
			}
			return null;
		}

		public String getFriendlyName() {
			return FRIENDLY_NAME;
		}

	}

}
