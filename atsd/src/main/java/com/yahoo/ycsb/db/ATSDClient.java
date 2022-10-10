package com.yahoo.ycsb.db;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.core.MultivaluedHashMap;

import com.axibase.tsd.client.*;
import com.axibase.tsd.model.data.series.*;
import com.axibase.tsd.model.system.TcpClientConfiguration;
import com.axibase.tsd.network.SimpleCommand;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import com.axibase.tsd.model.data.TimeFormat;
import com.axibase.tsd.model.data.command.GetSeriesQuery;
import com.axibase.tsd.model.data.command.SimpleAggregateMatcher;
import com.axibase.tsd.model.data.series.aggregate.AggregateType;
import com.axibase.tsd.model.system.ClientConfiguration;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * ATSD client for YCSB framework.
 */
public class ATSDClient extends DB {
	private final int SUCCESS = 0;

	private boolean _debug = false;
	private String ip = "localhost";
	private int httpPort = 8088;
	private int tcpPort = 8081;
	private String username = "admin";
	private String passwd = "adminadmin";
	private boolean test = false;
	private HttpClientManager httpClientManager;
	private TcpClientManager tcpClientManager;
	private DataService dataService;

	/**
	 * Initialize any state for this DB. Called once per DB instance; there is
	 * one DB instance per client thread.
	 */
	public void init() throws DBException {

		try {
			test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));
			if (!getProperties().containsKey("http_port") && !test) {
				throw new DBException("No http port given, abort.");
			}
			httpPort = Integer.parseInt(getProperties().getProperty("http_port", String.valueOf(httpPort)));

			if (!getProperties().containsKey("tcp_port") && !test) {
				throw new DBException("No tcp port given, abort.");
			}
			tcpPort = Integer.parseInt(getProperties().getProperty("tcp_port", String.valueOf(tcpPort)));

			if (!getProperties().containsKey("ip") && !test) {
				throw new DBException("No ip given, abort.");
			}
			ip = getProperties().getProperty("ip", ip);

			if (!getProperties().containsKey("username") && !test) {
				throw new DBException("No username given, abort.");
			}
			username = getProperties().getProperty("username", username);

			if (!getProperties().containsKey("passwd") && !test) {
				throw new DBException("No passwd given, abort.");
			}
			passwd = getProperties().getProperty("passwd", passwd);

			if (_debug) {
				System.out.println("The following properties are given: ");
				for (String element : getProperties().stringPropertyNames()) {
					System.out.println(element + ": " + getProperties().getProperty(element));
				}
			}

		} catch (Exception e) {
			throw new DBException(e);
		}

		httpClientManager = createHttpClientManager(ip, httpPort, username, passwd);
		dataService = new DataService(httpClientManager);
		tcpClientManager = createTcpClientManager(ip, tcpPort);
	}

	private static HttpClientManager createHttpClientManager(String ip, int httpPort, String username, String passwd) {
		ClientConfigurationFactory configurationFactory = new ClientConfigurationFactory("http", ip, httpPort, // serverPort
				"/api/v1", "/api/v1", username, passwd, 3000, // connectTimeoutMillis
				3000, // readTimeoutMillis
				600000, // pingTimeout
				false, // ignoreSSLErrors
				false, // skipStreamingControl
				false // enableGzipCompression
		);
		ClientConfiguration clientConfiguration = configurationFactory.createClientConfiguration();
		System.out.println("Connecting to ATSD: " + clientConfiguration.getMetadataUrl());
		HttpClientManager httpClientManager = new HttpClientManager(clientConfiguration);
		httpClientManager.setBorrowMaxWaitMillis(1000);

		return httpClientManager;
	}

	private static TcpClientManager createTcpClientManager(String ip, int tcpPort) {
		TcpClientConfigurationFactory tcpConfigurationFactory =
				new TcpClientConfigurationFactory(ip, tcpPort, false, 3000, 10000);
		TcpClientConfiguration tcpClientConfiguration = tcpConfigurationFactory.createClientConfiguration();
		TcpClientManager tcpClientManager = new TcpClientManager();
		tcpClientManager.setClientConfiguration(tcpClientConfiguration);
		tcpClientManager.setBorrowMaxWaitMillis(1000);

		return tcpClientManager;
	}

	public void cleanup() throws DBException {
		httpClientManager.close();
		tcpClientManager.close();
	}

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
		if (metric == null || metric.equals("")) {
			return -1;
		}
		if (timestamp == null) {
			return -1;
		}

		if (test) {
			return SUCCESS;
		}

		// Problem: You cant ask for a timestamp at TS=x, you need to give a
		// range. So: Begin: timestamp, End: timestamp + 1 ms
		// We may get more than that, but we just take the right one
		// There could also be more of them, so count
		GetSeriesQuery command = new GetSeriesQuery(metric, metric);
		command.setTags(intoMultiMap(tags));
		command.setTimeFormat(TimeFormat.MILLISECONDS);
		command.setStartTime(timestamp.getTime());
		command.setEndTime(timestamp.getTime() + 1);

		List<Series> seriesList = dataService.retrieveSeries(command);

		if (_debug) {
			System.out.println("Command: " + command.toString());
			for (Series res : seriesList) {
				System.out.println("Result: " + res.toString());
			}
		}

		if (seriesList == null || seriesList.isEmpty()) {
			System.err.println(
					"ERROR: Found no querie for metric: " + metric + " for timestamp: " + timestamp + " to read.");

			return -1;
		}
		int count = 0;
		for (Series series : seriesList) {
			for (Sample sample : series.getData()) {
				if (sample.getTimeMillis() == timestamp.getTime())
					count++;
			}
		}

		if (count == 0) {
			System.err.println("ERROR: Found no values for metric: " + metric + " for timestamp: " + timestamp + ".");
			return -1;
		} else if (count > 1) {
			System.err.println(
					"ERROR: Found more than one value for metric: " + metric + " for timestamp: " + timestamp + ".");
			return -1;
		}

		return SUCCESS;
	}

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

		if (metric == null || metric.equals("")) {
			return -1;
		}
		if (startTs == null || endTs == null) {
			return -1;
		}

		if (test) {
			return SUCCESS;
		}

		GetSeriesQuery command = new GetSeriesQuery(metric, metric);
		command.setTags(intoMultiMap(tags));
		command.setTimeFormat(TimeFormat.MILLISECONDS);
		command.setStartTime(startTs.getTime());
		command.setEndTime(endTs.getTime());

		if (avg || sum || count) {
			// AVG;SUM;COUNT
			AggregateType aggregateType = AggregateType.DETAIL;
			if (avg) {
				aggregateType = AggregateType.AVG;
			} else if (count) {
				aggregateType = AggregateType.COUNT;
			} else if (sum) {
				aggregateType = AggregateType.SUM;
			}

			IntervalUnit intervalUnit = IntervalUnit.SECOND; // smallest unit
			if (timeUnit == TimeUnit.MINUTES) {
				intervalUnit = IntervalUnit.MINUTE;
			} else if (timeUnit == TimeUnit.HOURS) {
				intervalUnit = IntervalUnit.HOUR;
			} else if (timeUnit == TimeUnit.DAYS) {
				intervalUnit = IntervalUnit.DAY;
			}

			command.setAggregateMatcher(
					new SimpleAggregateMatcher(new Interval(timeValue, intervalUnit), Interpolate.NONE, aggregateType));
		}

		List<Series> seriesList = dataService.retrieveSeries(command);

		if (_debug) {
			System.out.println("Command: " + command.toString());
			for (Series res : seriesList) {
				System.out.println("Result: " + res.toString());
			}
		}

		return SUCCESS;

	}

	private MultivaluedHashMap<String, String> intoMultiMap(HashMap<String, ArrayList<String>> tags) {
		MultivaluedHashMap<String, String> tagsMap = new MultivaluedHashMap<String, String>();

		for (Map.Entry<String, ArrayList<String>> entry : tags.entrySet()) {
			String tagKey = entry.getKey();
			for (String tagValue : entry.getValue()) {
				tagsMap.add(tagKey, tagValue);
			}
		}
		return tagsMap;
	}

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
		if (metric == null || metric.equals("")) {
			return -1;
		}
		if (timestamp == null) {
			return -1;
		}

		SimpleCommand command = new SimpleCommand(
				String.format("series e:%s m:%s=%s ms:%s %s\n",
						metric, metric, value, timestamp.getTime(), convertTags(tags)));

		tcpClientManager.send(command);

		return SUCCESS;
	}

	private String convertTags(HashMap<String, ByteIterator> tags) {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, ByteIterator> entry : tags.entrySet()) {
			builder.append(String.format("t:%s=%s ", entry.getKey(), entry.getValue().toString()));
		}

		return builder.toString();
	}
}