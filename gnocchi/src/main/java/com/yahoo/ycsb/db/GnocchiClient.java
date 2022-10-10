package com.yahoo.ycsb.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * Gnocchi client for YCSB framework. Gnocchi doesn't support tags.
 * 
 * @author Michael Zimmermann
 */
public class GnocchiClient extends DB {

	private boolean _debug = false;
	private boolean test = false;
	private final int SUCCESS = 0;

	private static final String POLICY = "policy";
	private static final String POLICY_URL = "/v1/archive_policy";
	private static final String METRIC_URL = "/v1/metric";
	private static final String METRIC_MEASURES_URL = "/v1/metric/%s/measures";

	private URL measuresURL = null;
	private UUID id;

	private String name = "usermetric";

	private String ip = "localhost";
	private int port = 8041;
	private int retries = 3;
	private CloseableHttpClient client;

	/**
	 * Initialize any state for this DB. Called once per DB instance; there is
	 * one DB instance per client thread.
	 */
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

		} catch (Exception e) {
			throw new DBException(e);
		}

		try {
			URL metricURL = new URL("http", ip, port, METRIC_URL);
			if (_debug) {
				System.out.println("METRIC_URL: " + metricURL);
			}

			id = getMetricID(metricURL);

			// if id==null create the Metric and the ArchivePolicy
			if (id == null) {

				URL policyURL = new URL("http", ip, port, POLICY_URL);
				if (_debug) {
					System.out.println("POLICY_URL: " + policyURL);
				}

				if (!createArchivePolicy(policyURL)) {
					throw new DBException("Couldn't create the ArchivePolicy.");
				}

				id = createMetric(metricURL);

				if (id == null && test == false) {
					throw new DBException("Couldn't create a Metric.");
				}
			}

			if (_debug) {
				System.out.println("ID: " + id);
			}

			measuresURL = new URL("http", ip, port, String.format(METRIC_MEASURES_URL, id));
			if (_debug) {
				System.out.println("METRIC_MEASURES_URL: " + measuresURL);
			}

		} catch (MalformedURLException e) {
			throw new DBException(e);
		}

	}

	/**
	 * Cleanup any state for this DB. Called once per DB instance; there is one
	 * DB instance per client thread.
	 */
	@Override
	public void cleanup() throws DBException {
		try {
			if (!test) {
				client.close();
			}
		} catch (Exception e) {
			throw new DBException(e);
		}
	}

	// {
	// "archive_policy_name": "low",
	// "name": "usermetric"
	// }
	private UUID createMetric(URL metricURL) {

		JSONObject metricObject = new JSONObject();
		metricObject.put("archive_policy_name", POLICY);
		metricObject.put("name", name);

		String queryString = metricObject.toString();

		if (_debug) {
			System.out.println("QueryString for Metric creation: " + queryString);
		}

		UUID uuid = doPost_CreateMetric(metricURL, queryString);

		return uuid;

	}

//	 {
//	 "aggregation_methods": [
//	 "sum",
//	 "mean",
//	 "count"
//	 ],
//	 "back_window": 0,
//	 "definition": [
//	 {
//	 "granularity": "1s",
//   "points": 1000000,
//	 }
//	 ],
//	 "name": "policy"
//	 }
	private boolean createArchivePolicy(URL policyURL) {

		JSONObject archivePolicyObject = new JSONObject();

		JSONArray aggregationMethodsArray = new JSONArray();
		aggregationMethodsArray.put("sum");
		aggregationMethodsArray.put("mean");
		aggregationMethodsArray.put("count");

		archivePolicyObject.put("aggregation_methods", aggregationMethodsArray);
		archivePolicyObject.put("back_window", 0);

		JSONArray definitionArray = new JSONArray();
		JSONObject definitionObject = new JSONObject();
		definitionObject.put("points", 1000000);
		definitionObject.put("granularity", "1s");
		definitionArray.put(definitionObject);

		archivePolicyObject.put("definition", definitionArray);

		archivePolicyObject.put("name", POLICY);

		String queryString = archivePolicyObject.toString();

		if (_debug) {
			System.out.println("QueryString for Archive Policy creation: " + queryString);
		}

		Integer statusCode = doPost(policyURL, queryString);

		if (_debug) {
			System.out.println("Returning StatusCode: " + statusCode);
		}

		if (statusCode == HttpURLConnection.HTTP_CREATED) {
			return true;
		}

		if (test) {
			return true;
		}

		return false;

	}

	private Integer doPost(URL url, String queryStr) {

		Integer statusCode;
		HttpResponse response = null;
		try {
			HttpPost postMethod = new HttpPost(url.toString());
			StringEntity requestEntity = new StringEntity(queryStr, ContentType.APPLICATION_JSON);
			postMethod.setEntity(requestEntity);
			postMethod.addHeader("X-Roles", "admin");

			int tries = retries + 1;
			while (true) {
				tries--;
				try {
					response = client.execute(postMethod);
					break;
				} catch (IOException e) {
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

			statusCode = response.getStatusLine().getStatusCode();
			EntityUtils.consumeQuietly(response.getEntity());
			postMethod.releaseConnection();

		} catch (Exception e) {
			System.err.println("ERROR: Errror while trying to query " + url.toString() + " for '" + queryStr + "'.");
			e.printStackTrace();
			if (response != null) {
				EntityUtils.consumeQuietly(response.getEntity());
			}
			return null;
		}

		return statusCode;
	}

	private UUID doPost_CreateMetric(URL url, String queryStr) {

		UUID uuid = null;
		HttpResponse response = null;
		try {
			HttpPost postMethod = new HttpPost(url.toString());
			StringEntity requestEntity = new StringEntity(queryStr, ContentType.APPLICATION_JSON);
			postMethod.setEntity(requestEntity);
			postMethod.addHeader("X-Roles", "admin");

			int tries = retries + 1;
			while (true) {
				tries--;
				try {
					response = client.execute(postMethod);
					break;
				} catch (IOException e) {
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

			if (response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_CREATED
					&& response.getEntity().getContentLength() > 0) {

				BufferedReader bis = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
				StringBuilder builder = new StringBuilder();
				String line;
				while ((line = bis.readLine()) != null) {
					builder.append(line);
				}
				JSONObject jsonObject = new JSONObject(builder.toString());
				uuid = UUID.fromString((String) jsonObject.get("id"));
			}
			EntityUtils.consumeQuietly(response.getEntity());
			postMethod.releaseConnection();

		} catch (Exception e) {
			System.err.println("ERROR: Errror while trying to query " + url.toString() + " for '" + queryStr + "'.");
			e.printStackTrace();
			if (response != null) {
				EntityUtils.consumeQuietly(response.getEntity());
			}
			return null;
		}

		return uuid;
	}

	private UUID getMetricID(URL metricURL) {

		UUID uuid = null;

		JSONArray jsonArray = doGet(metricURL);

		for (int n = 0; n < jsonArray.length(); n++) {
			JSONObject jsonObject = jsonArray.getJSONObject(n);
			uuid = UUID.fromString((String) jsonObject.get("id"));
		}

		return uuid;
	}

	private JSONArray doGet(URL url) {

		JSONArray jsonArray = new JSONArray();
		HttpResponse response = null;
		try {
			HttpGet getMethod = new HttpGet(url.toString());
			getMethod.addHeader("accept", "application/json");
			getMethod.addHeader("X-Roles", "admin");

			int tries = retries + 1;
			while (true) {
				tries--;
				try {
					response = client.execute(getMethod);
					break;
				} catch (IOException e) {
					if (tries < 1) {
						System.err.print("ERROR: Connection to " + url.toString() + " failed " + retries + "times.");
						e.printStackTrace();
						if (response != null) {
							EntityUtils.consumeQuietly(response.getEntity());
						}
						getMethod.releaseConnection();
						return null;
					}
				}
			}

			int statusCode = response.getStatusLine().getStatusCode();

			if (_debug) {
				System.out.println("Query StatusCode: " + statusCode);
			}

			if (statusCode == HttpURLConnection.HTTP_OK && response.getEntity().getContentLength() > 0) {

				BufferedReader bis = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
				StringBuilder builder = new StringBuilder();
				String line;
				while ((line = bis.readLine()) != null) {
					builder.append(line);
				}
				jsonArray = new JSONArray(builder.toString());
			}
			EntityUtils.consumeQuietly(response.getEntity());
			getMethod.releaseConnection();

		} catch (Exception e) {
			System.err.println("ERROR: Errror while trying to query " + url.toString() + ".");
			e.printStackTrace();
			if (response != null) {
				EntityUtils.consumeQuietly(response.getEntity());
			}
			return null;
		}

		return jsonArray;
	}

	// GET
	// /v1/metric/76f02203-81ce-4dae-bbaa-10de7b9b5701/measures?start=2014-10-06T14:34&stop=2014-10-06T14:34
	// HTTP/1.1
	//
	// [
	// [
	// "2014-10-06T14:34",
	// 1800.0,
	// 19.033333333333335
	// ]
	// ]
	/**
	 * Read a record from the database. Each value from the result will be
	 * stored in a HashMap
	 *
	 * @param metric
	 *            The name of the metric
	 * @param timestamp
	 *            The timestamp of the record to read.
	 * @param tags
	 *            actual tags that were want to receive (can be empty)
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

			String timestampString = timestamp.toString().replace(" ", "T");

			// start and stop can't be the same value; thus increase stop
			// timestamp with 1 ms
			String urlStr = String.format("%s?start=%s&stop=%s", measuresURL.toString(), timestampString,
					timestampString + "1");
			URL newQueryURL = new URL(urlStr);

			if (_debug) {
				System.out.println("QueryURL: " + newQueryURL.toString());
			}

			JSONArray jsonArray = doGet(newQueryURL);

			if (_debug) {
				System.out.println("Answer: " + jsonArray.toString());
			}

			if (jsonArray == null || jsonArray.length() < 1) {

				System.err.println("ERROR: Found no values for metric: " + metric + ".");
				return -1;

			} else if (jsonArray.length() > 1) {

				System.err.println("ERROR: Found more than one value for metric: " + metric + " for timestamp: "
						+ timestamp + ".");
				return -1;
			}

		} catch (MalformedURLException e) {
			System.err.println("ERROR: a malformed URL was generated.");
			return -1;
		} catch (Exception e) {
			return -1;
		}

		if (test) {
			return SUCCESS;
		}

		return SUCCESS;
	}

	// GET
	// /v1/metric/76f02203-81ce-4dae-bbaa-10de7b9b5701/measures?start=2014-10-06T14:34&stop=2014-10-06T14:34&aggregation=max
	// HTTP/1.1
	//
	// [
	// [
	// "2014-10-06T14:30:00+00:00",
	// 1800.0,
	// 19.033333333333335
	// ]
	// ]
	/**
	 * Perform a range scan for a set of records in the database. Each value
	 * from the result will be stored in a HashMap.
	 *
	 * @param metric
	 *            The name of the metric
	 * @param startTs
	 *            The timestamp of the first record to read.
	 * @param endTs
	 *            The timestamp of the last record to read.
	 * @param tags
	 *            actual tags that were want to receive (can be empty)
	 * @param avg
	 *            do averageing
	 * @param sum
	 *            do summarize
	 * @param count
	 *            do count
	 * @param timeValue
	 *            value for timeUnit for sum/count/avg
	 * @param timeUnit
	 *            timeUnit for sum/count/avg
	 * @return Zero on success, a non-zero error code on error. See this class's
	 *         description for a discussion of error codes.
	 */
	@Override
	public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String, ArrayList<String>> tags,
			boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit) {

		if (metric == null || metric == "") {
			return -1;
		}
		if (startTs == null || endTs == null) {
			return -1;
		}
		String aggregation = "";
		if (avg) {
			aggregation = "&aggregation=mean";
		} else if (count) {
			aggregation = "&aggregation=count";
		} else if (sum) {
			aggregation = "&aggregation=sum";
		}

		try {

			String urlStr = String.format("%s?start=%s&stop=%s%s", measuresURL.toString(),
					startTs.toString().replace(" ", "T"), endTs.toString().replace(" ", "T"), aggregation);
			URL newQueryURL = new URL(urlStr);

			if (_debug) {
				System.out.println("QueryURL: " + newQueryURL.toString());
			}

			JSONArray jsonArray = doGet(newQueryURL);

			if (_debug) {
				System.out.println("Answer: " + jsonArray.toString());
			}

			if (jsonArray == null || jsonArray.length() < 1) {

				System.err.println("ERROR: Found no values for metric: " + metric + ".");
				return -1;

			}

		} catch (MalformedURLException e) {
			System.err.println("ERROR: a malformed URL was generated.");
			return -1;
		} catch (Exception e) {
			return -1;
		}

		if (test) {
			return SUCCESS;
		}

		return SUCCESS;

	}

	// POST /v1/metric/76f02203-81ce-4dae-bbaa-10de7b9b5701/measures
	// HTTP/1.1
	// Content-Length: 198
	// Content-Type: application/json
	//
	// [
	// {
	// "timestamp": "2014-10-06T14:33:57",
	// "value": 43.1
	// }
	// ]
	/**
	 * Insert a record in the database. Any tags/tagvalue pairs in the specified
	 * tags HashMap and the given value will be written into the record with the
	 * specified timestamp
	 *
	 * @param metric
	 *            The name of the metric
	 * @param timestamp
	 *            The timestamp of the record to insert.
	 * @param value
	 *            actual value to insert
	 * @param tags
	 *            A HashMap of tag/tagvalue pairs to insert as tags
	 * @return Zero on success, a non-zero error code on error. See this class's
	 *         description for a discussion of error codes.
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

			JSONObject insertObject = new JSONObject();
			insertObject.put("timestamp", timestamp.toString().replace(" ", "T"));
			insertObject.put("value", value);

			JSONArray insertArray = new JSONArray();
			insertArray.put(insertObject);

			String query = insertArray.toString();

			if (_debug) {
				System.out.println("Insert measures String: " + query);
				System.out.println("Insert measures URL: " + measuresURL.toString());
			}
			if (test) {
				return SUCCESS;
			}
			Integer statusCode = doPost(measuresURL, query);

			if (_debug) {
				System.out.println("StatusCode: " + statusCode);
			}
			if (statusCode != HttpURLConnection.HTTP_ACCEPTED) {
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
