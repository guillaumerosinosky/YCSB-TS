package com.yahoo.ycsb.db;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation;
import org.joda.time.DateTime;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/**
 * Elasticsearch client for YCSB framework.
 * 
 * @author Rene Trefft
 * 
 */
public class ElasticsearchClient extends DB {

	private String ip;
	private int port;
	private String cluster;

	private boolean test = false;
	private final boolean _DEBUG = false;

	private final int SUCCESS = 0;

	private Client client;

	/**
	 * Name of type for each index
	 */
	private final String INDEX_TYPE_NAME = "type1";

	private final String TIMESTAMP_FIELD_NAME = "timestamp";
	private final String VALUE_FIELD_NAME = "value";
	private final String TAGS_FIELD_NAME = "tags";

	private final String DATE_HISTOGRAM_NAME = "dhist";

	private boolean indexRefreshed = false;

	private byte[] getMetricTemplate() throws IOException {
		return IOUtils.toByteArray(ElasticsearchClient.class.getResourceAsStream("/template.json"));
	}

	@Override
	public void init() throws DBException {

		test = Boolean.parseBoolean(getProperties().getProperty("test", "false"));

		if (!getProperties().containsKey("port") && !test) {
			throw new DBException("No port given, abort.");
		}
		port = Integer.parseInt(getProperties().getProperty("port", "9300"));

		if (!getProperties().containsKey("ip") && !test) {
			throw new DBException("No ip given, abort.");
		}
		ip = getProperties().getProperty("ip", "localhost");

		if (!getProperties().containsKey("cluster") && !test) {
			throw new DBException("No cluster name given, abort.");
		}
		cluster = getProperties().getProperty("cluster");

		if (_DEBUG) {
			System.out.println("The following properties are given: ");
			for (String element : getProperties().stringPropertyNames()) {
				System.out.println(element + ": " + getProperties().getProperty(element));
			}
		}

		if (!test) {

			Settings settings = Settings.settingsBuilder().put("client.transport.sniff", true)
					.put("cluster.name", cluster).build();
					// .put("cluster.name", "elasticsearch").build();

			// client = TransportClient.builder().settings(settings).build()
			// .addTransportAddress(new InetSocketTransportAddress(new
			// InetSocketAddress("127.0.0.1", 9300)));

			client = TransportClient.builder().settings(settings).build()
					.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(ip, port)));

			try {

				PutIndexTemplateRequestBuilder pitrb = client.admin().indices().preparePutTemplate("metric_template")
						.setSource(getMetricTemplate());
				pitrb.execute().actionGet();

			} catch (Exception e) {
				throw new DBException(e);
			}

		}

	}

	@Override
	public void cleanup() throws DBException {
		client.close();
	}

	/**
	 * Refreshes the index {@code metric}. Performed one time after the inserts
	 * to make the new records instantly available for searching
	 * (reading/scanning).
	 * 
	 * @param metric
	 */
	private void refreshIndexIfNecessary(String metric) {
		if (!indexRefreshed) {
			client.admin().indices().prepareRefresh(metric).execute().actionGet();
			indexRefreshed = true;
			// System.out.println("!!! Index Refreshed !!!");
		}
	}

	@Override
	public int read(String metric, Timestamp timestamp, HashMap<String, ArrayList<String>> tags) {

		if (metric == null || metric.isEmpty() || timestamp == null) {
			return -1;
		}

		refreshIndexIfNecessary(metric);

		SearchRequestBuilder searchRequestBuilder = client.prepareSearch(metric);

		// we assume that always one record will be retrieved (timestamp is a
		// unique field)
		searchRequestBuilder.setSize(1);

		// we only retrieve the value (from the field data cache so storing
		// the value field is not necessary)
		searchRequestBuilder.addFieldDataField(VALUE_FIELD_NAME);

		// enable this & disable code line before if stored value field should
		// be retrieved instead (storing of value field must be enabled in
		// template)
		// searchRequestBuilder.addField(VALUE_FIELD_NAME);

		BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery()
				.filter(QueryBuilders.termQuery(TIMESTAMP_FIELD_NAME, timestamp.getTime()));

		for (Map.Entry<String, ArrayList<String>> tag : tags.entrySet()) {
			boolQueryBuilder.filter(QueryBuilders.termsQuery(TAGS_FIELD_NAME + "." + tag.getKey(), tag.getValue()));
		}

		searchRequestBuilder.setQuery(boolQueryBuilder);

		if (test) {
			return SUCCESS;
		}

		try {

			SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();

			SearchHits hits = searchResponse.getHits();
			long totalHits = hits.getTotalHits();

			if (totalHits == 0) {

				System.err.println("ERROR: No value found for metric " + metric + ", timestamp " + timestamp
						+ " and tags " + tags.toString() + ".");
				return -1;

			} else if (totalHits > 1) {

				System.err.println("ERROR: Found more than one value for metric " + metric + ", timestamp " + timestamp
						+ " and tags " + tags.toString() + ". This should not happen.");

			} else {

				if (_DEBUG) {

					System.out
							.println("Found value " + hits.getAt(0).field(VALUE_FIELD_NAME).getValue() + " for metric "
									+ metric + ", timestamp " + timestamp + " and tags " + tags.toString() + ".");

				}

			}

		} catch (Exception exc) {
			exc.printStackTrace();
			return -1;
		}

		return SUCCESS;

	}

	// private String printSearchHits(SearchHits hits) {
	//
	// StringBuilder hitsStrBuilder = new StringBuilder();
	//
	// for (SearchHit hit : hits) {
	//
	// hitsStrBuilder.append(hit.getId());
	// hitsStrBuilder.append(": {");
	//
	// for (SearchHitField hitField : hit) {
	// hitsStrBuilder.append(hitField.getName());
	// hitsStrBuilder.append('=');
	// hitsStrBuilder.append(hitField.getValues().toString());
	// hitsStrBuilder.append(", ");
	// }
	//
	// if (!hit.getFields().isEmpty()) {
	// // deletes the ", " after the last field of the current record
	// hitsStrBuilder.delete(hitsStrBuilder.length() - 2,
	// hitsStrBuilder.length());
	// }
	//
	// hitsStrBuilder.append('}');
	// hitsStrBuilder.append(System.lineSeparator());
	//
	// }
	//
	// return hitsStrBuilder.toString();
	//
	// }

	@Override
	public int scan(String metric, Timestamp startTs, Timestamp endTs, HashMap<String, ArrayList<String>> tags,
			boolean avg, boolean count, boolean sum, int timeValue, TimeUnit timeUnit) {

		if (metric == null || metric.isEmpty() || startTs == null || endTs == null) {
			return -1;
		}

		refreshIndexIfNecessary(metric);

		SearchRequestBuilder searchRequestBuilder = client.prepareSearch(metric);

		// we are not interested in the records between startTs and endTs
		searchRequestBuilder.setSize(0);

		BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery()
				.filter(QueryBuilders.rangeQuery(TIMESTAMP_FIELD_NAME).gte(startTs.getTime()).lte(endTs.getTime()));

		for (Map.Entry<String, ArrayList<String>> tag : tags.entrySet()) {
			boolQueryBuilder.filter(QueryBuilders.termsQuery(TAGS_FIELD_NAME + "." + tag.getKey(), tag.getValue()));
		}

		searchRequestBuilder.setQuery(boolQueryBuilder);

		DateHistogramBuilder dateHistogramBuilder = AggregationBuilders.dateHistogram(DATE_HISTOGRAM_NAME)
				.field(TIMESTAMP_FIELD_NAME);

		if (timeUnit == TimeUnit.MILLISECONDS) {
			dateHistogramBuilder.interval(timeValue);
		} else if (timeUnit == TimeUnit.SECONDS) {
			dateHistogramBuilder.interval(DateHistogramInterval.seconds(timeValue));
		} else if (timeUnit == TimeUnit.MINUTES) {
			dateHistogramBuilder.interval(DateHistogramInterval.minutes(timeValue));
		} else if (timeUnit == TimeUnit.HOURS) {
			dateHistogramBuilder.interval(DateHistogramInterval.hours(timeValue));
		} else if (timeUnit == TimeUnit.DAYS) {
			dateHistogramBuilder.interval(DateHistogramInterval.days(timeValue));
		} else {
			System.err.println("WARNING: Not supported timeunit " + timeUnit.toString()
					+ ", converting to whole milliseconds - precision can be lost.");
			dateHistogramBuilder.interval(TimeUnit.MILLISECONDS.convert(timeValue, timeUnit));
		}

		if (avg) {
			dateHistogramBuilder.subAggregation(AggregationBuilders.avg("avg").field(VALUE_FIELD_NAME));
		} else if (count) {
			dateHistogramBuilder.subAggregation(AggregationBuilders.count("count").field(VALUE_FIELD_NAME));
		} else if (sum) {
			dateHistogramBuilder.subAggregation(AggregationBuilders.sum("sum").field(VALUE_FIELD_NAME));
		}

		searchRequestBuilder.addAggregation(dateHistogramBuilder);

		if (test) {
			return SUCCESS;
		}

		try {

			SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();

			Histogram histogram = searchResponse.getAggregations().get(DATE_HISTOGRAM_NAME);

			List<? extends Histogram.Bucket> buckets = histogram.getBuckets();

			if (buckets.isEmpty()) {
				// allowed to happen!
				return -1;
			}

			if (_DEBUG) {

				System.out.println(searchResponse.toString());

				System.out.print("Timestamp|");

				if (avg) {
					System.out.println("Avg");
				} else if (count) {
					System.out.println("Count");
				} else if (sum) {
					System.out.println("Sum");
				}

				for (Histogram.Bucket bucket : buckets) {
					System.out.println(new Timestamp(((DateTime) bucket.getKey()).getMillis()).toString() + "|"
							+ ((NumericMetricsAggregation.SingleValue) bucket.getAggregations().iterator().next())
									.getValueAsString());
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

		IndexRequestBuilder indexRequestBuilder = client.prepareIndex(metric, INDEX_TYPE_NAME);

		// enable to refresh index after each insert (and not only once after
		// all inserts)
		// indexRequestBuilder.setRefresh(true);

		try {

			XContentBuilder contentBuilder = XContentFactory.jsonBuilder().startObject();

			// nanoseconds and microseconds are not preserved (if it's really
			// necessary we could store the additional precision in a separate
			// field)
			contentBuilder.field(TIMESTAMP_FIELD_NAME, timestamp.getTime());

			contentBuilder.field(VALUE_FIELD_NAME, value);
			contentBuilder.field(TAGS_FIELD_NAME, tags);

			contentBuilder.endObject();

			indexRequestBuilder.setSource(contentBuilder);

			if (test) {
				return SUCCESS;
			}

			indexRequestBuilder.execute().actionGet();

			if (_DEBUG) {
				System.out.println("Inserted metric " + metric + ", timestamp " + timestamp + ", value " + value
						+ " and tags " + tags.toString() + ".");
			}

		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		}

		return SUCCESS;

	}

}
