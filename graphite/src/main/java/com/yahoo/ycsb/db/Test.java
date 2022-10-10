package com.yahoo.ycsb.db;

import java.sql.Timestamp;
//import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.yahoo.ycsb.DBException;

public class Test {

	public static void main(String[] args) throws DBException {
		
		GraphiteClient graphiteClient = new GraphiteClient();
		graphiteClient.init();
		
		// Insert tests	
		//System.out.println(graphiteClient.insert("metric1", Timestamp.valueOf("2001-10-18 20:15:00"), 1.0, null));
	//	System.out.println(graphiteClient.insert("metric1", Timestamp.valueOf("2013-01-19 00:00:00"), 2.0, null));
		System.out.println(graphiteClient.insert("metric2", Timestamp.valueOf("2015-08-10 21:10:05"), 3.0, null));		
		
		// Record with time stamp in future should not be inserted
		//Instant now = Instant.now();
		//Instant tenMinLater = Instant.ofEpochSecond(now.getEpochSecond()+ 600);
		//System.out.println(graphiteClient.insert("metric3", Timestamp.from(tenMinLater), 4.0, null));
		
		// waits some time, so it's guaranteed that Carbon has forwarded the new records to the TSDB
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		// Read tests		
		//System.out.println(graphiteClient.read("metric1", Timestamp.valueOf("2001-10-18 20:15:00"), null));
		System.out.println(graphiteClient.read("metric2", Timestamp.valueOf("2015-08-10 21:10:05"), null));
		
		// reading not existing records => should return -1
		//System.out.println(graphiteClient.read("metric3", Timestamp.from(tenMinLater), null));
	//	System.out.println(graphiteClient.read("metric1", Timestamp.valueOf("2016-02-14 22:00:00"), null));
		
		// Scan tests		
		//graphiteClient.scan("metric1",  Timestamp.valueOf("2016-01-17 20:15:00"), Timestamp.valueOf("2016-01-20 00:00:00"), null, false, false, true, 10, TimeUnit.MINUTES);
		
		
	}

}
