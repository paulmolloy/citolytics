package org.wikipedia.citolytics.clickstream.operators;

import com.google.common.collect.Sets;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;
import org.wikipedia.citolytics.clickstream.types.ClickStreamTranslateTuple;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;


public class ClickStreamDataSetReader implements FlatMapFunction<String, ClickStreamTranslateTuple> {
    public final static HashSet<String> filterNameSpaces = Sets.newHashSet(
            "other-wikipedia", "other-empty", "other-internal", "other-google", "other-yahoo",
            "other-bing", "other-facebook", "other-twitter", "other-other"
    );

    public final static String filterType = "link";

    @Override
    public void flatMap(String s, Collector<ClickStreamTranslateTuple> out) throws Exception {
        String[] cols = s.split(Pattern.quote("\t"));
        if (cols.length == 6) {
            // Skip if is title row or not link type
            if (cols[1].equals("prev_id") || !cols[5].equals("link")) {
                return;
            }

            // replace underscore
            String referrerName = cols[3].replace("_", " ");
            String currentName = cols[4].replace("_", " ");

            try {
                int currentId = Integer.valueOf(cols[1]);
                int clicks = cols[2].isEmpty() ? 0 : Integer.valueOf(cols[2]);

                if (filterType.equals(cols[5]) && !filterNameSpaces.contains(referrerName)) {
                    int referrerId = Integer.valueOf(cols[0]);

                    out.collect(new ClickStreamTranslateTuple(
                            referrerName,
                            referrerId,
                            0,
                            currentName,
                            currentId,
                            clicks
                    ));
//                    out.collect(new ClickStreamTuple(
//                            referrerName,
//                            referrerId,
//                            0,
//                            getOutMap(currentName, clicks),
//                            getOutMap(currentName, currentId)
//                    ));
                }

                // Impressions
//                if (clicks > 0)
//                    out.collect(new ClickStreamTuple(currentName, currentId, clicks, new HashMap<String, Integer>(), new HashMap<String, Integer>()));

            } catch (NumberFormatException e) {
                throw new Exception("Cannot read from clickstream data set. Col = " + s);
            }

        } else {
            throw new Exception("Wrong column length: " + cols.length + "; " + Arrays.toString(cols));
        }
    }

    public static HashMap<String, Integer> getOutMap(String link, int clicks_or_id) {
        HashMap<String, Integer> res = new HashMap<>();
        res.put(link, clicks_or_id);
        return res;
    }
}