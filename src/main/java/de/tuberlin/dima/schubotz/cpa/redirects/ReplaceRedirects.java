package de.tuberlin.dima.schubotz.cpa.redirects;

import de.tuberlin.dima.schubotz.cpa.types.LinkTuple;
import org.apache.flink.api.common.functions.CoGroupFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.Collector;

import java.util.Iterator;

public class ReplaceRedirects implements CoGroupFunction<WikiSimRedirectsResult2, Tuple2<String, String>, WikiSimRedirectsResult2> {
    public int replaceField = 0;
    public int hashField = 0;
    public int pageAField = 1;
    public int pageBField = 2;
    public int redirectTargetField = 1;

    public ReplaceRedirects(int replaceField) {
        this.replaceField = replaceField;
    }

    @Override
    public void coGroup(Iterable<WikiSimRedirectsResult2> a, Iterable<Tuple2<String, String>> redirect, Collector<WikiSimRedirectsResult2> out) throws Exception {
        Iterator<WikiSimRedirectsResult2> iteratorA = a.iterator();
        Iterator<Tuple2<String, String>> iteratorRedirect = redirect.iterator();

        while (iteratorA.hasNext()) {
            WikiSimRedirectsResult2 recordA = iteratorA.next();

            if (iteratorRedirect.hasNext()) {
                Tuple2<String, String> recordRedirect = iteratorRedirect.next();

                // replace page
                recordA.setField(recordRedirect.getField(redirectTargetField), replaceField);

                // check for alphabetic order
                int order = ((String) recordA.getField(pageBField)).compareTo((String) recordA.getField(pageAField));
                if (order < 0) {
                    // correct order
                    String tmp = recordA.getField(pageBField);

                    recordA.setField(recordA.getField(pageAField), pageBField);
                    recordA.setField(tmp, pageAField);
                }
                // update hash
                recordA.setField(LinkTuple.getHash((String) recordA.getField(pageAField), (String) recordA.getField(pageBField)), hashField);
            }
            out.collect(recordA);
        }
    }
}