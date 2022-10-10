package com.yahoo.ycsb.db;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import com.yahoo.ycsb.DBException;

public class Test {

	public static void main(String[] args) throws DBException {

		SerieslyClient serieslyClient = new SerieslyClient();
		serieslyClient.init();

		System.out.println("insert:");
		System.out.println("sould be 0: "
				+ serieslyClient.insert("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"), 1.0, null));
		System.out.println("sould be 0: "
				+ serieslyClient.insert("metric1", Timestamp.valueOf("2010-01-01 12:23:43.343434"), 2.0, null));
		System.out.println("sould be 0: "
				+ serieslyClient.insert("metric1", Timestamp.valueOf("2010-01-01 12:23:44.343434"), 3.0, null));

		System.out.println("read:");
		System.out.println(
				"sould be 0: " + serieslyClient.read("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"), null));
		System.out.println("souldn't be 0: "
				+ serieslyClient.read("metric1", Timestamp.valueOf("2011-11-11 12:23:42.343434"), null));

		System.out.println("scan:");
		System.out.println(
				"sould be 0: " + serieslyClient.scan("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"),
						Timestamp.valueOf("2010-01-01 12:23:44.343434"), null, true, false, false, 1, TimeUnit.HOURS));
		System.out.println(
				"sould be 0: " + serieslyClient.scan("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"),
						Timestamp.valueOf("2010-01-01 12:23:44.343434"), null, false, true, false, 1, TimeUnit.HOURS));
		System.out.println(
				"sould be 0: " + serieslyClient.scan("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"),
						Timestamp.valueOf("2010-01-01 12:23:44.343434"), null, false, false, true, 1, TimeUnit.HOURS));
		System.out.println(
				"sould be 0: " + serieslyClient.scan("metric1", Timestamp.valueOf("2010-01-01 12:23:42.343434"),
						Timestamp.valueOf("2010-01-01 12:23:44.343434"), null, false, false, false, 1, TimeUnit.HOURS));
		System.out.println(
				"souldn't be 0: " + serieslyClient.scan("metric1", Timestamp.valueOf("2011-11-11 12:23:42.343434"),
						Timestamp.valueOf("2011-11-11 12:23:44.343434"), null, false, false, false, 1, TimeUnit.HOURS));
	}

}
