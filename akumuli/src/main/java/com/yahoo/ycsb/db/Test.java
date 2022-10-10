package com.yahoo.ycsb.db;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
//import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.StringByteIterator;

public class Test {

	public static void main(String[] args) throws DBException {

		AkumuliClient client = new AkumuliClient();
		client.init();

		HashMap<String, ByteIterator> tagsInsert = new HashMap<String, ByteIterator>();
		tagsInsert.put("tag1_Key", new StringByteIterator("tag1_Val1"));	
		HashMap<String, ByteIterator> tagsInsert2 = new HashMap<String, ByteIterator>();
		tagsInsert2.put("tag1_Key", new StringByteIterator("tag1_Val2"));

		// Insert tests
		System.out.println(client.insert("metric1", Timestamp.valueOf("2001-10-19 20:15:00"), 1.0, tagsInsert));
		System.out.println(client.insert("metric1", Timestamp.valueOf("2001-10-19 20:15:00"), 1.5, tagsInsert2));
		System.out.println(client.insert("metric1", Timestamp.valueOf("2013-01-20 00:00:00"), 2.0, tagsInsert));
		System.out.println(client.insert("metric2", Timestamp.valueOf("2016-04-03 19:56:00"), 3.0, tagsInsert));
		
		System.out.println(client.insert("metric2", Timestamp.valueOf("2016-04-04 09:30:00"), 1.5, tagsInsert2));
		System.out.println(client.insert("metric2", Timestamp.valueOf("2016-04-04 09:40:00"), 2.5, tagsInsert2));
		System.out.println(client.insert("metric2", Timestamp.valueOf("2016-04-04 09:50:00"), 3.5, tagsInsert2));
		System.out.println(client.insert("metric2", Timestamp.valueOf("2016-04-04 09:55:00"), 4.5, tagsInsert2));
		
		
		
		HashMap<String, ArrayList<String>> tagsQuery = new HashMap<String, ArrayList<String>>();
		ArrayList<String> tagValues = new ArrayList<String>();	
		tagValues.add("tag1_Val1");
		tagValues.add("tag1_Val2");	
		tagsQuery.put("tag1_Key", tagValues);

		// Read tests
		System.out.println(client.read("metric1", Timestamp.valueOf("2001-10-19 20:15:00"), tagsQuery));
		System.out.println(client.read("metric2", Timestamp.valueOf("2016-04-03 19:56:00"), tagsQuery));

		// Scan tests
		client.scan("metric2", Timestamp.valueOf("2016-04-04 09:30:00"),
				Timestamp.valueOf("2016-04-04 10:00:00"), tagsQuery, true, false, false, 15, TimeUnit.MINUTES);

	}

}
