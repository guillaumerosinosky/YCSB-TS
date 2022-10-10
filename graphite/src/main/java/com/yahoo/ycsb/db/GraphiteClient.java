package com.yahoo.ycsb.db;

import java.io.PrintWriter;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * Graphite client for YCSB framework. Inserts records with the plaintext
 * protocol supported by Carbon. Retrieving is done using the Render API of the
 * Graphite-API.<br>
 * Restrictions:<br>
 * Tags are not supported in Graphite, so they will be ignored. Timestamps are
 * stored in second precision. Records with timestamps in the future can't be
 * stored. The function count is not supported - max will be used instead.
 *
 * @see <a href="https://graphite.readthedocs.org">Graphite Website</a>
 * @see <a href=
 *      "https://graphite.readthedocs.org/en/latest/feeding-carbon.html#the-plaintext-protocol">
 *      Graphite - The plaintext protocol</a>
 * @see <a href="https://graphite-api.readthedocs.org">Graphite-API Website</a>
 * @see <a href=
 *      "https://graphite-api.readthedocs.org/en/latest/api.html#the-render-api-render">
 *      Graphite-API - The Render API</a>
 * 
 * @author Rene Trefft
 */
public class GraphiteClient extends DB {

	private boolean test = false;
	private final boolean _DEBUG = false;

	private final int SUCCESS = 0;

	/** Inserting with Plaintext receiver of Carbon */
	private Socket plainTextSocket;
	private PrintWriter plainTextWriter;

	/** Retrieving with Render API of Graphite-API */
	private HttpClient renderApiClient;

	/** URL to Render API of Graphite-API */
	private String renderApiUrl;

	private final String RENDER_API_PATH = "/render?";

	@Override
	public void init() throws DBException {

		test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));

		if (!getProperties().containsKey("ip") && !test) {
			throw new DBException("No ip given, abort.");
		}

		if (!getProperties().containsKey("plaintextPort") && !test) {
			throw new DBException("No plaintext receiver port given, abort.");
		}

		if (!getProperties().containsKey("graphiteApiPort") && !test) {
			throw new DBException("No Graphite-API port given, abort.");
		}

		String ip = getProperties().getProperty("ip", "localhost");
		int plaintextPort = Integer.parseInt(getProperties().getProperty("plaintextPort", "2003"));
		int graphiteApiPort = Integer.parseInt(getProperties().getProperty("graphiteApiPort", "80"));

		if (_DEBUG) {
			System.out.println("The following properties are given: ");
			for (String element : getProperties().stringPropertyNames()) {
				System.out.println(element + ": " + getProperties().getProperty(element));
			}
		}

		if (!test) {

			try {
				plainTextSocket = new Socket(ip, plaintextPort);
				plainTextWriter = new PrintWriter(plainTextSocket.getOutputStream(), true);

				renderApiUrl = "http://" + ip + ':' + graphiteApiPort + RENDER_API_PATH;
				renderApiClient = HttpClients.createDefault();

			} catch (Exception e) {
				throw new DBException(e);
			}

		}

	}

	@Override
	public void cleanup() throws DBException {
		try {
			plainTextWriter.close();
			plainTextSocket.close();
		} catch (Exception e) {
			throw new DBException(e);
		}
	}

	private long getSecOfTimestamp(Timestamp timestamp) {
		return timestamp.getTime() / 1000;
	}

	@Override
	public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) {

		if (metric == null || metric.isEmpty() || timestamp == null) {
			return -1;
		}

		long timestampSec = getSecOfTimestamp(timestamp);

		StringBuilder readURL = new StringBuilder(renderApiUrl);
		readURL.append("target=");
		readURL.append(metric);

		// from is exclusive, until is inclusive for time range definition
		readURL.append("&from=");
		readURL.append(timestampSec - 1);
		readURL.append("&until=");

		readURL.append(timestampSec);
		readURL.append("&format=raw");

		HttpGet request = new HttpGet(readURL.toString());

		if (test) {
			return SUCCESS;
		}

		try {

			HttpResponse response = renderApiClient.execute(request);

			String responseStr = EntityUtils.toString(response.getEntity());

			if (_DEBUG) {
				System.out.println("Read Request: " + readURL + '\n' + "Read Response: " + responseStr);
			}

			String[] responseData = responseStr.split("\\|");

			if (responseData.length <= 1 || responseData[1].trim().equalsIgnoreCase("none")) {
				System.err
						.println("ERROR: No value found for metric " + metric + " and timestamp " + timestampSec + ".");
				return -1;
			} else {
				if (_DEBUG) {
					System.out.println("Found value " + responseData[1].trim() + " for metric " + metric
							+ " and timestamp " + timestampSec + ".");
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

		StringBuilder scanURL = new StringBuilder(renderApiUrl);
		scanURL.append("target=summarize(");
		scanURL.append(metric);
		scanURL.append(",%22");

		switch (timeUnit) {
		case DAYS:
			scanURL.append(timeValue);
			scanURL.append("day");
			break;
		case HOURS:
			scanURL.append(timeValue);
			scanURL.append("hour");
			break;
		case MINUTES:
			scanURL.append(timeValue);
			scanURL.append("minute");
			break;
		case SECONDS:
			scanURL.append(timeValue);
			scanURL.append("second");
			break;
		default:
			// time unit not supported => convert to whole seconds, precision can be lost
			scanURL.append(TimeUnit.SECONDS.convert(timeValue, timeUnit));
			scanURL.append("second");
			break;
		}

		scanURL.append("%22,%22");

		if (avg) {
			scanURL.append("avg");
		} else if (sum) {
			scanURL.append("sum");
		} else {
			// if no function or unsupported count is passed
			scanURL.append("max");

		}

		long startTimestampSec = getSecOfTimestamp(startTs);
		long endTimestampSec = getSecOfTimestamp(endTs);

		// last param of summarize function is set to true to create buckets
		// starting at the from time
		scanURL.append("%22,true)&from=");
		scanURL.append(startTimestampSec - 1);
		scanURL.append("&until=");
		scanURL.append(endTimestampSec);
		scanURL.append("&format=raw");

		HttpGet request = new HttpGet(scanURL.toString());

		if (test) {
			return SUCCESS;
		}

		try {

			HttpResponse response = renderApiClient.execute(request);
			String responseStr = EntityUtils.toString(response.getEntity());

			if (_DEBUG) {
				System.out.println("Scan Request: " + scanURL + '\n' + "Scan Response: " + responseStr);
			}

			String[] responseData = responseStr.split("\\|");

			boolean valuesFound = false;

			if (responseData.length > 1) {

				String[] values = responseData[1].trim().split(",");

				for (String value : values) {
					if (!value.equalsIgnoreCase("none")) {
						valuesFound = true;
						break;
					}
				}

			}

			if (!valuesFound) {
				// Workloads call scan with 1 millisecond intervals which will
				// be converted to 0 seconds (milliseconds are not supported).
				// => No values will be correctly returned
				// => Avoid error message
				// System.err.println("ERROR: No values found for metric " +
				// metric + ", start timestamp "
				// + startTimestampSec + ", end timestamp " + endTimestampSec +
				// ", time value " + timeValue
				// + " and time unit " + timeUnit + ".");
				return -1;
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

		long timestampSec = getSecOfTimestamp(timestamp);

		String insertRequest = metric + ' ' + value + ' ' + timestampSec;

		if (_DEBUG) {
			System.out.println("Insert Request: " + insertRequest);
		}

		// No response, so we doesn't know if new data was accepted /
		// successfully stored => SUCCESS will be always returned
		plainTextWriter.println(insertRequest);

		if (_DEBUG) {
			System.out
					.println("Inserted metric " + metric + ", timestamp " + timestampSec + " and value " + value + ".");
		}

		return SUCCESS;

	}

}
