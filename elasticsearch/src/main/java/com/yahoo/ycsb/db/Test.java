package com.yahoo.ycsb.db;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.StringByteIterator;

public class Test {

	public static void main(String[] args) {

		try {

			ElasticsearchClient el = new ElasticsearchClient();
			el.init();

			HashMap<String, ByteIterator> tags = new HashMap<String, ByteIterator>();
			tags.put("tag1", new StringByteIterator("tag1Val1"));
			tags.put("tag2", new StringByteIterator("tag2Val1"));

			el.insert("abcd", Timestamp.valueOf("2015-1-1 13:10:54.324"), 100, tags);
			el.insert("abcd", Timestamp.valueOf("2015-2-1 13:10:54.324"), 200, tags);
			el.insert("abcd", Timestamp.valueOf("2015-2-2 13:10:54.324"), 300, tags);
			
			// -------------------------------

			HashMap<String, ArrayList<String>> expecTags = new HashMap<String, ArrayList<String>>();

			ArrayList<String> tagVals = new ArrayList<String>();
			tagVals.add("tag1Val1");
			tagVals.add("tag1Val2");
			tagVals.add("tag1Val3");
			ArrayList<String> tagVals2 = new ArrayList<String>();
			tagVals2.add("tag2Val1");

			expecTags.put("tag1", tagVals);
			expecTags.put("tag2", tagVals2);

			System.out.println(el.read("abcd", Timestamp.valueOf("2015-1-1 13:10:54.324"), expecTags));
		
			// reading from not existing metric / index
			System.out.println(el.read("notexist", Timestamp.valueOf("2015-1-1 13:10:54.324"), expecTags));

		System.out.println(el.scan("abcd", Timestamp.valueOf("2015-1-1 13:10:00.000"),
					Timestamp.valueOf("2015-2-4 00:00:00.000"), expecTags, false, false, true, 10, TimeUnit.DAYS));

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
